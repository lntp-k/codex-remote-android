package com.codex.remote.data.transport

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Message-sized transport used by the app-server JSON-RPC client.
 *
 * Implementations must return exactly one JSON-RPC message at a time. A clean
 * peer close is represented by `null`; malformed or truncated transports throw
 * an [IOException] instead of being treated as a clean end of stream.
 */
internal interface AppServerMessageChannel : Closeable {
    @Throws(IOException::class)
    fun readMessage(maxChars: Int): String?

    @Throws(IOException::class)
    fun writeMessage(message: String)
}

internal class AppServerMessageTooLongException(maxChars: Int) :
    IOException("Remote app-server message exceeded the $maxChars character limit")

internal open class AppServerMessageProtocolException(message: String, cause: Throwable? = null) :
    ProtocolException(message) {
    init {
        if (cause != null) initCause(cause)
    }
}

internal class AppServerWebSocketProtocolException(message: String, cause: Throwable? = null) :
    AppServerMessageProtocolException(message, cause)

/** Newline-delimited JSON transport used by `codex app-server --listen stdio://`. */
internal class JsonLineAppServerMessageChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit = {
        runCatching { output.close() }
        runCatching { input.close() }
    },
) : AppServerMessageChannel {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    override fun readMessage(maxChars: Int): String? {
        require(maxChars > 0) { "maxChars must be positive" }
        checkOpen()

        val bytes = ByteArrayOutputStream(minOf(maxChars, INITIAL_BUFFER_BYTES))
        val maxEncodedBytes = encodedByteLimit(maxChars)
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (bytes.size() == 0) return null
                throw AppServerMessageProtocolException("Unexpected EOF while reading JSONL message")
            }
            if (next == '\n'.code) {
                var payload = bytes.toByteArray()
                if (payload.isNotEmpty() && payload.last() == '\r'.code.toByte()) {
                    payload = payload.copyOf(payload.size - 1)
                }
                val message = decodeUtf8(payload, "JSONL message")
                if (message.length > maxChars) throw AppServerMessageTooLongException(maxChars)
                return message
            }

            // One extra byte is accepted only when it is the CR in a CRLF
            // terminator. The decoded character limit remains authoritative.
            if (bytes.size() >= maxEncodedBytes && next != '\r'.code) {
                throw AppServerMessageTooLongException(maxChars)
            }
            if (bytes.size() > maxEncodedBytes) {
                throw AppServerMessageTooLongException(maxChars)
            }
            bytes.write(next)
        }
    }

    override fun writeMessage(message: String) {
        checkOpen()
        require('\n' !in message && '\r' !in message) {
            "A JSONL message must not contain a literal line break"
        }
        val payload = encodeUtf8(message, "JSONL message")
        if (payload.size > MAX_MESSAGE_BYTES) {
            throw AppServerMessageTooLongException(MAX_MESSAGE_BYTES / MAX_UTF8_BYTES_PER_CHAR)
        }
        synchronized(writeLock) {
            checkOpen()
            output.write(payload)
            output.write('\n'.code)
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeTransport()
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("App-server message channel is closed")
    }
}

/**
 * RFC 6455 client channel used by `codex app-server proxy`.
 *
 * The proxy forwards stdin/stdout byte-for-byte to the managed app-server Unix
 * socket, so this channel performs the HTTP Upgrade and WebSocket framing that
 * live on that proxied stream.
 */
internal class WebSocketAppServerMessageChannel private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val randomBytes: (ByteArray) -> Unit,
    private val closeTransport: () -> Unit,
) : AppServerMessageChannel {
    private val closed = AtomicBoolean(false)
    private val writeLock = ReentrantLock()

    @Volatile
    private var closeSent = false

    @Volatile
    private var closeReceived = false

    override fun readMessage(maxChars: Int): String? {
        require(maxChars > 0) { "maxChars must be positive" }
        if (closeReceived) return null
        checkOpen()

        val maxMessageBytes = encodedByteLimit(maxChars)
        var fragments: ByteArrayOutputStream? = null
        while (true) {
            val frame = try {
                readFrame(maxMessageBytes)
            } catch (error: MessageLimitExceededException) {
                failConnection(
                    CLOSE_MESSAGE_TOO_BIG,
                    error.message.orEmpty(),
                    AppServerMessageTooLongException(maxChars),
                )
            } catch (error: Utf8DecodingException) {
                failConnection(CLOSE_INVALID_PAYLOAD, error.message.orEmpty(), error)
            } catch (error: AppServerWebSocketProtocolException) {
                failConnection(CLOSE_PROTOCOL_ERROR, error.message.orEmpty(), error)
            }

            when (frame.opcode) {
                OPCODE_TEXT -> {
                    if (fragments != null) {
                        failConnection(
                            CLOSE_PROTOCOL_ERROR,
                            "Received a new text frame before the fragmented message completed",
                        )
                    }
                    if (frame.fin) return decodeAndCheckMessage(frame.payload, maxChars)
                    fragments = ByteArrayOutputStream(minOf(frame.payload.size, INITIAL_BUFFER_BYTES)).also {
                        it.write(frame.payload)
                    }
                }

                OPCODE_CONTINUATION -> {
                    val message = fragments ?: failConnection(
                        CLOSE_PROTOCOL_ERROR,
                        "Received a continuation frame without a fragmented message",
                    )
                    if (frame.payload.size > maxMessageBytes - message.size()) {
                        failConnection(
                            CLOSE_MESSAGE_TOO_BIG,
                            "Fragmented WebSocket message exceeded its byte limit",
                            MessageLimitExceededException("Fragmented WebSocket message exceeded its byte limit"),
                        )
                    }
                    message.write(frame.payload)
                    if (frame.fin) return decodeAndCheckMessage(message.toByteArray(), maxChars)
                }

                OPCODE_BINARY -> failConnection(
                    CLOSE_UNSUPPORTED_DATA,
                    "Binary WebSocket messages are not supported by the app-server protocol",
                )

                OPCODE_PING -> sendControlFrame(OPCODE_PONG, frame.payload)
                OPCODE_PONG -> Unit
                OPCODE_CLOSE -> {
                    try {
                        validateClosePayload(frame.payload)
                    } catch (error: Utf8DecodingException) {
                        failConnection(CLOSE_INVALID_PAYLOAD, error.message.orEmpty(), error)
                    } catch (error: AppServerWebSocketProtocolException) {
                        failConnection(CLOSE_PROTOCOL_ERROR, error.message.orEmpty(), error)
                    }
                    closeReceived = true
                    closeUnderlying()
                    return null
                }

                else -> failConnection(
                    CLOSE_PROTOCOL_ERROR,
                    "Received unsupported WebSocket opcode ${frame.opcode}",
                )
            }
        }
    }

    override fun writeMessage(message: String) {
        checkOpen()
        val payload = encodeUtf8(message, "WebSocket text message")
        if (payload.size > MAX_MESSAGE_BYTES) {
            throw AppServerMessageTooLongException(MAX_MESSAGE_BYTES / MAX_UTF8_BYTES_PER_CHAR)
        }
        writeLock.lock()
        try {
            checkOpen()
            if (closeSent || closeReceived) throw IOException("WebSocket closing handshake has started")
            writeFrameLocked(OPCODE_TEXT, payload)
        } finally {
            writeLock.unlock()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeSent = true
            closeTransport()
        }
    }

    private fun readFrame(maxMessageBytes: Int): Frame {
        val first = readRequiredByte("WebSocket frame header")
        val second = readRequiredByte("WebSocket frame header")
        val fin = first and FIN_BIT != 0
        val rsv = first and RSV_MASK
        val opcode = first and OPCODE_MASK
        val masked = second and MASK_BIT != 0
        val shortLength = second and PAYLOAD_LENGTH_MASK

        if (rsv != 0) throw AppServerWebSocketProtocolException("Received a frame with unsupported RSV bits")
        if (masked) throw AppServerWebSocketProtocolException("Server WebSocket frames must not be masked")
        if (opcode !in VALID_OPCODES) {
            throw AppServerWebSocketProtocolException("Received reserved WebSocket opcode $opcode")
        }
        if (opcode >= OPCODE_CLOSE && !fin) {
            throw AppServerWebSocketProtocolException("Control frames must not be fragmented")
        }

        val length = when (shortLength) {
            PAYLOAD_LENGTH_16 -> readUnsignedShort().also {
                if (it < PAYLOAD_LENGTH_16) {
                    throw AppServerWebSocketProtocolException("WebSocket frame used a non-minimal 16-bit length")
                }
            }.toLong()

            PAYLOAD_LENGTH_64 -> readUnsignedLongLength().also {
                if (it < MIN_64_BIT_PAYLOAD_LENGTH) {
                    throw AppServerWebSocketProtocolException("WebSocket frame used a non-minimal 64-bit length")
                }
            }

            else -> shortLength.toLong()
        }

        if (opcode >= OPCODE_CLOSE && length > MAX_CONTROL_PAYLOAD_BYTES) {
            throw AppServerWebSocketProtocolException("Control frame payload exceeded 125 bytes")
        }
        val frameLimit = if (opcode >= OPCODE_CLOSE) MAX_CONTROL_PAYLOAD_BYTES else {
            minOf(MAX_FRAME_PAYLOAD_BYTES, maxMessageBytes)
        }
        if (length > frameLimit) {
            throw MessageLimitExceededException("WebSocket frame payload exceeded its byte limit")
        }

        return Frame(fin, opcode, readExactly(length.toInt(), "WebSocket frame payload"))
    }

    private fun decodeAndCheckMessage(payload: ByteArray, maxChars: Int): String {
        val message = try {
            decodeUtf8(payload, "WebSocket text message")
        } catch (error: Utf8DecodingException) {
            failConnection(CLOSE_INVALID_PAYLOAD, error.message.orEmpty(), error)
        }
        if (message.length > maxChars) {
            failConnection(
                CLOSE_MESSAGE_TOO_BIG,
                "WebSocket text message exceeded the $maxChars character limit",
                AppServerMessageTooLongException(maxChars),
            )
        }
        return message
    }

    private fun validateClosePayload(payload: ByteArray) {
        if (payload.size == 1) {
            throw AppServerWebSocketProtocolException("WebSocket close payload cannot contain exactly one byte")
        }
        if (payload.size < 2) return

        val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        if (!isValidCloseCode(code)) {
            throw AppServerWebSocketProtocolException("Received invalid WebSocket close code $code")
        }
        decodeUtf8(payload.copyOfRange(2, payload.size), "WebSocket close reason")
    }

    private fun sendControlFrame(opcode: Int, payload: ByteArray) {
        writeLock.lock()
        try {
            if (closed.get() || closeSent || closeReceived) return
            writeFrameLocked(opcode, payload)
        } finally {
            writeLock.unlock()
        }
    }

    private fun writeFrameLocked(opcode: Int, payload: ByteArray) {
        if (payload.size > MAX_FRAME_PAYLOAD_BYTES) {
            throw IOException("Outgoing WebSocket frame exceeded its byte limit")
        }
        if (opcode >= OPCODE_CLOSE && payload.size > MAX_CONTROL_PAYLOAD_BYTES) {
            throw IOException("Outgoing WebSocket control frame exceeded 125 bytes")
        }

        output.write(FIN_BIT or opcode)
        when {
            payload.size < PAYLOAD_LENGTH_16 -> output.write(MASK_BIT or payload.size)
            payload.size <= 0xffff -> {
                output.write(MASK_BIT or PAYLOAD_LENGTH_16)
                output.write(payload.size ushr 8 and 0xff)
                output.write(payload.size and 0xff)
            }
            else -> {
                output.write(MASK_BIT or PAYLOAD_LENGTH_64)
                val length = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) output.write((length ushr shift and 0xff).toInt())
            }
        }

        val mask = ByteArray(4).also(randomBytes)
        output.write(mask)
        val masked = ByteArray(payload.size)
        for (index in payload.indices) {
            masked[index] = (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
        }
        output.write(masked)
        output.flush()
    }

    private fun readUnsignedShort(): Int =
        (readRequiredByte("WebSocket frame length") shl 8) or
            readRequiredByte("WebSocket frame length")

    private fun readUnsignedLongLength(): Long {
        var value = 0L
        repeat(8) { index ->
            val next = readRequiredByte("WebSocket frame length")
            if (index == 0 && next and 0x80 != 0) {
                throw AppServerWebSocketProtocolException("WebSocket payload length set the reserved high bit")
            }
            value = value shl 8 or next.toLong()
        }
        return value
    }

    private fun readRequiredByte(context: String): Int {
        val next = input.read()
        if (next == -1) throw AppServerWebSocketProtocolException("Unexpected EOF while reading $context")
        return next
    }

    private fun readExactly(length: Int, context: String): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count == -1) throw AppServerWebSocketProtocolException("Unexpected EOF while reading $context")
            if (count == 0) continue
            offset += count
        }
        return result
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("App-server WebSocket channel is closed")
    }

    private fun closeUnderlying() {
        if (closed.compareAndSet(false, true)) closeTransport()
    }

    private fun failConnection(
        @Suppress("UNUSED_PARAMETER") closeCode: Int,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        closeSent = true
        closeUnderlying()
        when (cause) {
            is AppServerMessageTooLongException -> throw cause
            is AppServerWebSocketProtocolException -> throw cause
            else -> throw AppServerWebSocketProtocolException(message.ifBlank { "Invalid WebSocket message" }, cause)
        }
    }

    private data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    internal companion object {
        private val secureRandom = SecureRandom()

        @Throws(IOException::class)
        fun open(input: InputStream, output: OutputStream): WebSocketAppServerMessageChannel =
            open(input, output, "localhost", "/", secureRandom::nextBytes) {
                runCatching { output.close() }
                runCatching { input.close() }
            }

        @Throws(IOException::class)
        internal fun open(
            input: InputStream,
            output: OutputStream,
            host: String,
            path: String,
            randomBytes: (ByteArray) -> Unit,
            closeTransport: () -> Unit,
        ): WebSocketAppServerMessageChannel {
            require(host.isNotBlank() && '\r' !in host && '\n' !in host) { "host must be a safe HTTP Host value" }
            require(path.startsWith('/') && '\r' !in path && '\n' !in path) { "path must be an absolute HTTP path" }

            val handshakeNonce = ByteArray(16).also(randomBytes)
            val key = Base64.getEncoder().encodeToString(handshakeNonce)
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            try {
                output.write(request)
                output.flush()
                validateUpgradeResponse(input, expectedAccept(key))
            } catch (error: IOException) {
                runCatching { closeTransport() }
                throw error
            } catch (error: RuntimeException) {
                runCatching { closeTransport() }
                throw AppServerWebSocketProtocolException("Invalid WebSocket upgrade response", error)
            }
            return WebSocketAppServerMessageChannel(input, output, randomBytes, closeTransport)
        }

        private fun validateUpgradeResponse(input: InputStream, expectedAccept: String) {
            var bytesRead = 0
            fun nextLine(): String {
                val line = readHttpLine(input, MAX_HTTP_LINE_BYTES)
                    ?: throw AppServerWebSocketProtocolException("Unexpected EOF during WebSocket upgrade")
                bytesRead += line.length + 2
                if (bytesRead > MAX_HTTP_HEADERS_BYTES) {
                    throw AppServerWebSocketProtocolException("WebSocket upgrade headers exceeded their byte limit")
                }
                return line
            }

            val status = nextLine()
            if (!HTTP_SWITCHING_PROTOCOLS.matches(status)) {
                throw AppServerWebSocketProtocolException("WebSocket upgrade did not return HTTP/1.1 101")
            }

            val headers = linkedMapOf<String, MutableList<String>>()
            var headerCount = 0
            while (true) {
                val line = nextLine()
                if (line.isEmpty()) break
                if (++headerCount > MAX_HTTP_HEADER_COUNT) {
                    throw AppServerWebSocketProtocolException("WebSocket upgrade returned too many headers")
                }
                if (line.firstOrNull()?.isWhitespace() == true) {
                    throw AppServerWebSocketProtocolException("Folded HTTP headers are not accepted")
                }
                val separator = line.indexOf(':')
                if (separator <= 0) throw AppServerWebSocketProtocolException("Malformed WebSocket upgrade header")
                val name = line.substring(0, separator)
                if (!HTTP_TOKEN.matches(name)) {
                    throw AppServerWebSocketProtocolException("Malformed WebSocket upgrade header name")
                }
                val value = line.substring(separator + 1).trim()
                headers.getOrPut(name.lowercase(Locale.US)) { mutableListOf() } += value
            }

            if (!headers.tokenValues("upgrade").any { it.equals("websocket", ignoreCase = true) }) {
                throw AppServerWebSocketProtocolException("WebSocket upgrade response omitted Upgrade: websocket")
            }
            if (!headers.tokenValues("connection").any { it.equals("upgrade", ignoreCase = true) }) {
                throw AppServerWebSocketProtocolException("WebSocket upgrade response omitted Connection: Upgrade")
            }
            val accepts = headers["sec-websocket-accept"].orEmpty()
            if (accepts.size != 1 || !MessageDigest.isEqual(
                    accepts.singleOrNull().orEmpty().toByteArray(StandardCharsets.US_ASCII),
                    expectedAccept.toByteArray(StandardCharsets.US_ASCII),
                )
            ) {
                throw AppServerWebSocketProtocolException("WebSocket upgrade returned an invalid Sec-WebSocket-Accept")
            }
            if (!headers["sec-websocket-extensions"].isNullOrEmpty()) {
                throw AppServerWebSocketProtocolException("Unrequested WebSocket extensions are not accepted")
            }
            if (!headers["sec-websocket-protocol"].isNullOrEmpty()) {
                throw AppServerWebSocketProtocolException("Unrequested WebSocket subprotocols are not accepted")
            }
        }

        private fun readHttpLine(input: InputStream, maxBytes: Int): String? {
            val bytes = ByteArrayOutputStream(minOf(maxBytes, 256))
            while (true) {
                val next = input.read()
                if (next == -1) {
                    if (bytes.size() == 0) return null
                    throw AppServerWebSocketProtocolException("Truncated HTTP line during WebSocket upgrade")
                }
                if (next == '\n'.code) {
                    val line = bytes.toByteArray()
                    if (line.isEmpty() || line.last() != '\r'.code.toByte()) {
                        throw AppServerWebSocketProtocolException("WebSocket upgrade headers require CRLF line endings")
                    }
                    return decodeHttpAscii(line.copyOf(line.size - 1))
                }
                if (bytes.size() >= maxBytes) {
                    throw AppServerWebSocketProtocolException("WebSocket upgrade header line exceeded its byte limit")
                }
                bytes.write(next)
            }
        }

        private fun decodeHttpAscii(bytes: ByteArray): String {
            if (bytes.any { value ->
                    val unsigned = value.toInt() and 0xff
                    unsigned == 0 || unsigned == 0x7f || unsigned in 0x01..0x08 ||
                        unsigned in 0x0b..0x1f || unsigned > 0x7f
                }
            ) {
                throw AppServerWebSocketProtocolException("WebSocket upgrade contained invalid HTTP characters")
            }
            return String(bytes, StandardCharsets.US_ASCII)
        }

        private fun Map<String, List<String>>.tokenValues(name: String): List<String> =
            get(name).orEmpty().flatMap { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }

        private fun expectedAccept(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}

private class MessageLimitExceededException(message: String) : IOException(message)
private class Utf8DecodingException(message: String, cause: Throwable) :
    AppServerMessageProtocolException(message, cause)

private fun encodedByteLimit(maxChars: Int): Int = minOf(
    MAX_MESSAGE_BYTES.toLong(),
    maxChars.toLong() * MAX_UTF8_BYTES_PER_CHAR,
).toInt()

private fun decodeUtf8(bytes: ByteArray, context: String): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: Exception) {
    throw Utf8DecodingException("$context was not valid UTF-8", error)
}

private fun encodeUtf8(value: String, context: String): ByteArray = try {
    val encoded = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    ByteArray(encoded.remaining()).also(encoded::get)
} catch (error: Exception) {
    throw IOException("$context was not valid Unicode", error)
}

private fun isValidCloseCode(code: Int): Boolean =
    code in 3000..4999 || code in setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014)

private const val INITIAL_BUFFER_BYTES = 4_096
private const val MAX_UTF8_BYTES_PER_CHAR = 4
private const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
private const val MAX_FRAME_PAYLOAD_BYTES = MAX_MESSAGE_BYTES
private const val MAX_CONTROL_PAYLOAD_BYTES = 125
private const val MAX_HTTP_LINE_BYTES = 8 * 1024
private const val MAX_HTTP_HEADERS_BYTES = 32 * 1024
private const val MAX_HTTP_HEADER_COUNT = 100

private const val FIN_BIT = 0x80
private const val RSV_MASK = 0x70
private const val MASK_BIT = 0x80
private const val OPCODE_MASK = 0x0f
private const val PAYLOAD_LENGTH_MASK = 0x7f
private const val PAYLOAD_LENGTH_16 = 126
private const val PAYLOAD_LENGTH_64 = 127
private const val MIN_64_BIT_PAYLOAD_LENGTH = 65_536L

private const val OPCODE_CONTINUATION = 0x0
private const val OPCODE_TEXT = 0x1
private const val OPCODE_BINARY = 0x2
private const val OPCODE_CLOSE = 0x8
private const val OPCODE_PING = 0x9
private const val OPCODE_PONG = 0xa
private val VALID_OPCODES = setOf(
    OPCODE_CONTINUATION,
    OPCODE_TEXT,
    OPCODE_BINARY,
    OPCODE_CLOSE,
    OPCODE_PING,
    OPCODE_PONG,
)

private const val CLOSE_PROTOCOL_ERROR = 1002
private const val CLOSE_UNSUPPORTED_DATA = 1003
private const val CLOSE_INVALID_PAYLOAD = 1007
private const val CLOSE_MESSAGE_TOO_BIG = 1009

private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private val HTTP_SWITCHING_PROTOCOLS = Regex("HTTP/1\\.1[ \\t]+101(?:[ \\t]+[^\\r\\n]*)?")
private val HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
