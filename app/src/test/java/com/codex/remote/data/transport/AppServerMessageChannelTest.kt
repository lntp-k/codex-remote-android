package com.codex.remote.data.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppServerMessageChannelTest {
    @Test
    fun jsonLineChannelReadsCrLfAndWritesOneFlushedLine() {
        val output = ByteArrayOutputStream()
        val channel = JsonLineAppServerMessageChannel(
            ByteArrayInputStream("first\r\nsecond\n".toByteArray()),
            output,
            closeTransport = {},
        )

        assertEquals("first", channel.readMessage(5))
        assertEquals("second", channel.readMessage(6))
        assertNull(channel.readMessage(6))

        channel.writeMessage("{\"id\":1}")
        assertEquals("{\"id\":1}\n", output.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun jsonLineChannelRejectsInvalidUtf8AndOversizedMessages() {
        val invalidUtf8 = JsonLineAppServerMessageChannel(
            ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte())),
            ByteArrayOutputStream(),
            closeTransport = {},
        )
        val invalidError = runCatching { invalidUtf8.readMessage(8) }.exceptionOrNull()
        assertTrue(invalidError is AppServerMessageProtocolException)

        val tooLong = JsonLineAppServerMessageChannel(
            ByteArrayInputStream("abcde\n".toByteArray()),
            ByteArrayOutputStream(),
            closeTransport = {},
        )
        val lengthError = runCatching { tooLong.readMessage(4) }.exceptionOrNull()
        assertTrue(lengthError is AppServerMessageTooLongException)
    }

    @Test
    fun jsonLineChannelRejectsValidJsonAtEofWithoutLineDelimiter() {
        val channel = JsonLineAppServerMessageChannel(
            ByteArrayInputStream("{\"id\":1,\"result\":{}}".toByteArray()),
            ByteArrayOutputStream(),
            closeTransport = {},
        )

        val error = runCatching { channel.readMessage(64) }.exceptionOrNull()

        assertTrue(error is AppServerMessageProtocolException)
        assertTrue(error?.message?.contains("Unexpected EOF") == true)
    }

    @Test
    fun websocketUpgradeIsValidatedAndTextFrameIsRead() {
        val fixture = fixture(serverFrame(fin = true, opcode = 1, payload = "hello".toByteArray()))

        assertEquals("hello", fixture.channel.readMessage(5))
        val request = fixture.handshakeRequest()
        assertTrue(request.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
        assertFalse(request.contains("Origin:", ignoreCase = true))
    }

    @Test
    fun websocketClientTextFramesAreMasked() {
        val fixture = fixture()

        fixture.channel.writeMessage("hello")

        val frame = fixture.clientFrames().single()
        assertEquals(1, frame.opcode)
        assertTrue(frame.masked)
        assertArrayEquals("hello".toByteArray(), frame.payload)
    }

    @Test
    fun websocketReassemblesFragmentsAndAnswersPingWithMaskedPong() {
        val fixture = fixture(
            serverFrame(fin = false, opcode = 1, payload = "hel".toByteArray()),
            serverFrame(fin = true, opcode = 9, payload = "?".toByteArray()),
            serverFrame(fin = true, opcode = 0, payload = "lo".toByteArray()),
        )

        assertEquals("hello", fixture.channel.readMessage(5))

        val pong = fixture.clientFrames().single()
        assertEquals(10, pong.opcode)
        assertTrue(pong.masked)
        assertArrayEquals("?".toByteArray(), pong.payload)
    }

    @Test
    fun websocketRejectsMaskedServerFramesAndFailsClosed() {
        var closed = false
        val fixture = fixture(
            serverFrame(fin = true, opcode = 1, payload = "bad".toByteArray(), masked = true),
            closeTransport = { closed = true },
        )

        val error = runCatching { fixture.channel.readMessage(10) }.exceptionOrNull()

        assertTrue(error is AppServerWebSocketProtocolException)
        assertTrue(closed)
        assertTrue(fixture.clientFrames().isEmpty())
    }

    @Test
    fun websocketRejectsNonMinimalLengthsAndInvalidUtf8() {
        val nonMinimal = fixture(byteArrayOf(0x81.toByte(), 126, 0, 1, 'a'.code.toByte()))
        assertTrue(
            runCatching { nonMinimal.channel.readMessage(10) }.exceptionOrNull() is
                AppServerWebSocketProtocolException,
        )

        val invalidUtf8 = fixture(
            serverFrame(fin = true, opcode = 1, payload = byteArrayOf(0xc3.toByte(), 0x28)),
        )
        assertTrue(
            runCatching { invalidUtf8.channel.readMessage(10) }.exceptionOrNull() is
                AppServerWebSocketProtocolException,
        )
    }

    @Test
    fun websocketEnforcesDecodedCharacterLimit() {
        val fixture = fixture(serverFrame(fin = true, opcode = 1, payload = "abcde".toByteArray()))

        val error = runCatching { fixture.channel.readMessage(4) }.exceptionOrNull()

        assertTrue(error is AppServerMessageTooLongException)
        assertTrue(fixture.clientFrames().isEmpty())
    }

    @Test
    fun websocketEchoesValidCloseAndReturnsCleanEndOfStream() {
        var closed = false
        val closePayload = byteArrayOf(0x03, 0xe8.toByte()) + "bye".toByteArray()
        val fixture = fixture(
            serverFrame(fin = true, opcode = 8, payload = closePayload),
            closeTransport = { closed = true },
        )

        assertNull(fixture.channel.readMessage(16))
        assertTrue(closed)
        assertTrue(fixture.clientFrames().isEmpty())
    }

    @Test
    fun websocketUpgradeRejectsIncorrectAcceptAndClosesTransport() {
        val output = ByteArrayOutputStream()
        var closed = false
        val response = upgradeResponse("incorrect").toByteArray(StandardCharsets.US_ASCII)

        val error = runCatching {
            WebSocketAppServerMessageChannel.open(
                input = ByteArrayInputStream(response),
                output = output,
                host = "localhost",
                path = "/",
                randomBytes = fixedRandom,
                closeTransport = { closed = true },
            )
        }.exceptionOrNull()

        assertTrue(error is AppServerWebSocketProtocolException)
        assertTrue(closed)
    }

    @Test
    fun websocketRejectsFragmentedControlFrames() {
        val fixture = fixture(serverFrame(fin = false, opcode = 9, payload = byteArrayOf(1)))

        val error = runCatching { fixture.channel.readMessage(10) }.exceptionOrNull()

        assertTrue(error is AppServerWebSocketProtocolException)
        assertTrue(fixture.clientFrames().isEmpty())
    }

    @Test
    fun websocketCloseDoesNotWaitForABlockedFrameWriter() {
        val input = ByteArrayInputStream(upgradeResponse(EXPECTED_ACCEPT).toByteArray(StandardCharsets.US_ASCII))
        val output = BlockingFrameOutput()
        val channel = WebSocketAppServerMessageChannel.open(
            input = input,
            output = output,
            host = "localhost",
            path = "/",
            randomBytes = fixedRandom,
            closeTransport = output::closeTransport,
        )
        output.blockFrames = true
        val writerFinished = CountDownLatch(1)
        val writer = Thread {
            runCatching { channel.writeMessage("blocked") }
            writerFinished.countDown()
        }.also(Thread::start)
        assertTrue(output.frameWriteStarted.await(1, TimeUnit.SECONDS))

        val closeFinished = CountDownLatch(1)
        Thread {
            channel.close()
            closeFinished.countDown()
        }.start()

        assertTrue("close must not wait for the writer lock", closeFinished.await(1, TimeUnit.SECONDS))
        assertTrue(output.closed)
        assertTrue(writerFinished.await(1, TimeUnit.SECONDS))
        writer.join(1_000)
    }

    private fun fixture(
        vararg frames: ByteArray,
        closeTransport: () -> Unit = {},
    ): WebSocketFixture {
        val response = upgradeResponse(EXPECTED_ACCEPT).toByteArray(StandardCharsets.US_ASCII)
        val input = ByteArrayInputStream(response + frames.fold(ByteArray(0), ByteArray::plus))
        val output = ByteArrayOutputStream()
        val channel = WebSocketAppServerMessageChannel.open(
            input = input,
            output = output,
            host = "localhost",
            path = "/",
            randomBytes = fixedRandom,
            closeTransport = closeTransport,
        )
        return WebSocketFixture(channel, output)
    }

    private data class WebSocketFixture(
        val channel: WebSocketAppServerMessageChannel,
        val output: ByteArrayOutputStream,
    ) {
        fun handshakeRequest(): String =
            output.toByteArray().copyOfRange(0, frameOffset()).toString(StandardCharsets.US_ASCII)

        fun clientFrames(): List<DecodedClientFrame> {
            val bytes = output.toByteArray()
            var offset = frameOffset()
            val result = mutableListOf<DecodedClientFrame>()
            while (offset < bytes.size) {
                val first = bytes[offset++].toInt() and 0xff
                val second = bytes[offset++].toInt() and 0xff
                val masked = second and 0x80 != 0
                var length = second and 0x7f
                if (length == 126) {
                    length = ((bytes[offset++].toInt() and 0xff) shl 8) or
                        (bytes[offset++].toInt() and 0xff)
                } else if (length == 127) {
                    var longLength = 0L
                    repeat(8) { longLength = longLength shl 8 or (bytes[offset++].toLong() and 0xff) }
                    if (longLength > Int.MAX_VALUE) fail("Test frame was unexpectedly large")
                    length = longLength.toInt()
                }
                val mask = if (masked) bytes.copyOfRange(offset, offset + 4).also { offset += 4 } else ByteArray(4)
                val payload = bytes.copyOfRange(offset, offset + length)
                offset += length
                if (masked) {
                    for (index in payload.indices) {
                        payload[index] = (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
                    }
                }
                result += DecodedClientFrame(first and 0x0f, masked, payload)
            }
            return result
        }

        private fun frameOffset(): Int {
            val bytes = output.toByteArray()
            val marker = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            for (index in 0..bytes.size - marker.size) {
                if (bytes.copyOfRange(index, index + marker.size).contentEquals(marker)) return index + marker.size
            }
            fail("Handshake request terminator was missing")
            return -1
        }
    }

    private data class DecodedClientFrame(
        val opcode: Int,
        val masked: Boolean,
        val payload: ByteArray,
    )

    private class BlockingFrameOutput : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        val frameWriteStarted = CountDownLatch(1)
        private val releaseFrame = CountDownLatch(1)

        @Volatile
        var blockFrames: Boolean = false

        @Volatile
        var closed: Boolean = false

        override fun write(value: Int) {
            beforeWrite()
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            beforeWrite()
            delegate.write(bytes, offset, length)
        }

        fun closeTransport() {
            closed = true
            releaseFrame.countDown()
        }

        private fun beforeWrite() {
            if (!blockFrames) return
            frameWriteStarted.countDown()
            releaseFrame.await(2, TimeUnit.SECONDS)
            if (closed) throw IOException("transport closed")
        }
    }

    companion object {
        private val NONCE = ByteArray(16) { it.toByte() }
        private val KEY = Base64.getEncoder().encodeToString(NONCE)
        private val EXPECTED_ACCEPT = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((KEY + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)),
        )
        private val fixedRandom: (ByteArray) -> Unit = { destination ->
            when (destination.size) {
                16 -> NONCE.copyInto(destination)
                4 -> byteArrayOf(1, 2, 3, 4).copyInto(destination)
                else -> error("Unexpected random byte request: ${destination.size}")
            }
        }

        private fun upgradeResponse(accept: String): String =
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "uPgRaDe: WebSocket\r\n" +
                "Connection: keep-alive, Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n" +
                "\r\n"

        private fun serverFrame(
            fin: Boolean,
            opcode: Int,
            payload: ByteArray,
            masked: Boolean = false,
        ): ByteArray {
            require(payload.size < 126)
            val output = ByteArrayOutputStream()
            output.write((if (fin) 0x80 else 0) or opcode)
            output.write((if (masked) 0x80 else 0) or payload.size)
            if (masked) {
                val mask = byteArrayOf(5, 6, 7, 8)
                output.write(mask)
                payload.forEachIndexed { index, byte ->
                    output.write(byte.toInt() xor mask[index and 3].toInt())
                }
            } else {
                output.write(payload)
            }
            return output.toByteArray()
        }
    }
}
