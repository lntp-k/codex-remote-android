package com.codex.remote.data.ssh

import android.content.Context
import com.codex.remote.data.transport.AppServerMessageChannel
import com.codex.remote.data.transport.JsonLineAppServerMessageChannel
import com.codex.remote.data.transport.WebSocketAppServerMessageChannel
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.ConnectionSecrets
import com.codex.remote.domain.RemotePlatform
import com.codex.remote.domain.SavedConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HostKeyChangedException(
    expected: String,
    actual: String,
) : SecurityException("SSH host key changed. Saved: $expected; received: $actual.")

class UnknownHostKeyException(val fingerprint: String) :
    SecurityException("Confirm the SSH host fingerprint before connecting: $fingerprint")

class RemoteCodexUnavailableException(message: String) : IllegalStateException(message)

internal enum class AppServerConnectionMode {
    SHARED_DAEMON,
    ISOLATED_STDIO,
}

internal data class SharedDaemonStatus(
    val running: Boolean,
    val cliVersion: String?,
    val appServerVersion: String?,
)

internal class TransportAbortPlan(
    private val blockOutbound: List<() -> Unit>,
    private val cleanupStreams: List<() -> Unit>,
) {
    fun run() {
        blockOutbound.forEach { action -> runCatching(action) }
        cleanupStreams.forEach { action -> runCatching(action) }
    }
}

internal class TransportCloseGate {
    private val started = AtomicBoolean(false)

    fun run(block: () -> Unit) {
        if (started.compareAndSet(false, true)) block()
    }
}

class ActiveSshTransport internal constructor(
    private val ssh: SSHClient,
    private val session: Session,
    private val command: Session.Command,
    private val messageChannel: AppServerMessageChannel,
    val fingerprint: String,
    val remotePlatform: RemotePlatform,
    val codexVersion: String,
    val sharedDaemon: Boolean,
) : Closeable {
    private val outbound = command.outputStream
    private val errorInbound = command.errorStream
    private val closeGate = TransportCloseGate()

    val errorReader: BufferedReader = BufferedReader(InputStreamReader(errorInbound, Charsets.UTF_8))

    fun readMessage(maxChars: Int): String? = messageChannel.readMessage(maxChars)

    fun writeMessage(message: String) = messageChannel.writeMessage(message)

    private val abortPlan = TransportAbortPlan(
        blockOutbound = listOf(
            { outbound.close() },
            { command.close() },
            { session.close() },
            { ssh.disconnect() },
            { ssh.close() },
        ),
        cleanupStreams = listOf(
            { messageChannel.close() },
            { errorReader.close() },
        ),
    )

    fun abort() {
        closeGate.run(abortPlan::run)
    }

    override fun close() {
        closeGate.run {
            runCatching { messageChannel.close() }
            runCatching { command.close() }
            runCatching { session.close() }
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }
}

class SshAppServerTransportFactory(private val context: Context) {
    suspend fun open(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
    ): ActiveSshTransport = withContext(Dispatchers.IO) {
        var observedFingerprint = ""
        val ssh = authenticatedClient(connection, secrets) { observedFingerprint = it }
        try {
            val remotePlatform = resolvePlatform(ssh, connection.platform)
            val codexVersion = readCodexVersion(ssh, remotePlatform)
            val sharedDaemonStatus = try {
                ensureSharedDaemon(ssh, remotePlatform, codexVersion)
            } catch (_: Exception) {
                null
            }
            val opened = openAppServer(ssh, remotePlatform, sharedDaemonStatus != null)
            val servingCodexVersion = servingCodexVersion(opened.mode, codexVersion, sharedDaemonStatus)
            ssh.timeout = 0
            ActiveSshTransport(
                ssh = ssh,
                session = opened.session,
                command = opened.command,
                messageChannel = opened.messageChannel,
                fingerprint = observedFingerprint,
                remotePlatform = remotePlatform,
                codexVersion = servingCodexVersion,
                sharedDaemon = opened.mode == AppServerConnectionMode.SHARED_DAEMON,
            )
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            throw error
        }
    }

    private fun authenticatedClient(
        connection: SavedConnection,
        secrets: ConnectionSecrets,
        onFingerprint: (String) -> Unit,
    ): SSHClient {
        val ssh = SSHClient(androidCompatibleSshConfig())
        var unknownFingerprint: String? = null
        var changedFingerprint: String? = null
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val actual = sha256Fingerprint(key)
                onFingerprint(actual)
                val expected = connection.hostKeyFingerprint
                if (expected.isBlank()) {
                    unknownFingerprint = actual
                    return false
                }
                if (expected != actual) {
                    changedFingerprint = actual
                    return false
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        try {
            ssh.connect(connection.host, connection.port)
            when (connection.authType) {
                AuthType.PASSWORD -> ssh.authPassword(connection.username, secrets.password)
                AuthType.PRIVATE_KEY -> authenticatePrivateKey(ssh, connection.username, secrets)
            }
            configureProtocolKeepAlive(ssh)
            return ssh
        } catch (error: Throwable) {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            unknownFingerprint?.let { throw UnknownHostKeyException(it) }
            changedFingerprint?.let { throw HostKeyChangedException(connection.hostKeyFingerprint, it) }
            throw error
        }
    }

    private fun authenticatePrivateKey(
        ssh: SSHClient,
        username: String,
        secrets: ConnectionSecrets,
    ) {
        val keyFile = File.createTempFile("codex_remote_", ".key", context.cacheDir)
        try {
            keyFile.writeText(secrets.privateKey, Charsets.UTF_8)
            val provider = if (secrets.passphrase.isBlank()) {
                ssh.loadKeys(keyFile.absolutePath)
            } else {
                ssh.loadKeys(keyFile.absolutePath, secrets.passphrase.toCharArray())
            }
            ssh.authPublickey(username, provider)
        } finally {
            keyFile.writeText("")
            keyFile.delete()
        }
    }

    private fun sha256Fingerprint(key: PublicKey): String {
        val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun resolvePlatform(ssh: SSHClient, configured: RemotePlatform): RemotePlatform {
        if (configured != RemotePlatform.AUTO) return configured
        val probe = runCommand(ssh, "printf '__CODEX_POSIX__'")
        return if (probe.exitStatus == 0 && probe.stdout.contains("__CODEX_POSIX__")) {
            RemotePlatform.POSIX
        } else {
            RemotePlatform.WINDOWS
        }
    }

    private fun readCodexVersion(ssh: SSHClient, platform: RemotePlatform): String {
        val probe = runCommand(ssh, codexVersionCommand(platform))
        val version = probe.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("codex-cli ") || it.startsWith("codex ") }
        if (probe.exitStatus != 0 || version == null) {
            val detail = probe.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                ?: probe.stdout.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "codex --version returned no version"
            throw RemoteCodexUnavailableException(
                "No usable Codex CLI was found in the remote login shell. Run codex --version on the remote host and complete installation first. $detail",
            )
        }
        return version.substringAfter(' ').trim()
    }

    private fun ensureSharedDaemon(
        ssh: SSHClient,
        platform: RemotePlatform,
        expectedCodexVersion: String,
    ): SharedDaemonStatus? {
        val current = runCommand(ssh, daemonVersionCommand(platform))
        val currentStatus = current.stdout.takeIf { current.exitStatus == 0 }
            ?.let(::parseSharedDaemonStatus)
        if (currentStatus?.running == true) {
            return currentStatus.takeIf { it.isCompatibleWith(expectedCodexVersion) }
        }

        val started = runCommand(ssh, daemonStartCommand(platform))
        if (started.exitStatus != 0) return null
        val afterStart = runCommand(ssh, daemonVersionCommand(platform))
        val startedStatus = afterStart.stdout.takeIf { afterStart.exitStatus == 0 }
            ?.let(::parseSharedDaemonStatus)
        return startedStatus?.takeIf { it.running && it.isCompatibleWith(expectedCodexVersion) }
    }

    private fun openAppServer(
        ssh: SSHClient,
        platform: RemotePlatform,
        preferSharedDaemon: Boolean,
    ): OpenedAppServer {
        if (!preferSharedDaemon) return openAppServer(ssh, platform, AppServerConnectionMode.ISOLATED_STDIO)
        return try {
            openAppServer(ssh, platform, AppServerConnectionMode.SHARED_DAEMON)
        } catch (sharedError: Exception) {
            try {
                openAppServer(ssh, platform, AppServerConnectionMode.ISOLATED_STDIO)
            } catch (isolatedError: Throwable) {
                isolatedError.addSuppressed(sharedError)
                throw isolatedError
            }
        }
    }

    private fun openAppServer(
        ssh: SSHClient,
        platform: RemotePlatform,
        mode: AppServerConnectionMode,
    ): OpenedAppServer {
        val session = ssh.startSession()
        try {
            val command = session.exec(appServerCommand(platform, mode))
            try {
                val messageChannel = when (mode) {
                    AppServerConnectionMode.SHARED_DAEMON -> WebSocketAppServerMessageChannel.open(
                        command.inputStream,
                        command.outputStream,
                    )
                    AppServerConnectionMode.ISOLATED_STDIO -> JsonLineAppServerMessageChannel(
                        command.inputStream,
                        command.outputStream,
                    )
                }
                return OpenedAppServer(session, command, messageChannel, mode)
            } catch (error: Throwable) {
                runCatching { command.close() }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    private fun runCommand(ssh: SSHClient, commandLine: String): ProbeResult {
        val session = ssh.startSession()
        return try {
            val command = session.exec(commandLine)
            try {
                command.join(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (command.isOpen) {
                    throw RemoteCodexUnavailableException("Remote Codex preflight timed out")
                }
                ProbeResult(
                    exitStatus = command.exitStatus ?: -1,
                    stdout = command.inputStream.bufferedReader(Charsets.UTF_8).readText(),
                    stderr = command.errorStream.bufferedReader(Charsets.UTF_8).readText(),
                )
            } finally {
                runCatching { command.close() }
            }
        } finally {
            runCatching { session.close() }
        }
    }

    private data class ProbeResult(
        val exitStatus: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class OpenedAppServer(
        val session: Session,
        val command: Session.Command,
        val messageChannel: AppServerMessageChannel,
        val mode: AppServerConnectionMode,
    )

    companion object {
        private const val PROBE_TIMEOUT_SECONDS = 15L
    }
}

internal fun codexVersionCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex --version }\""
}

internal fun daemonVersionCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc 'codex app-server daemon version'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server daemon version }\""
}

internal fun daemonStartCommand(platform: RemotePlatform): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> "exec \"\${SHELL:-/bin/sh}\" -lc 'codex app-server daemon start'"
    RemotePlatform.WINDOWS ->
        "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server daemon start }\""
}

internal fun daemonReportsRunning(stdout: String): Boolean = stdout.lineSequence()
    .mapNotNull(::parseSharedDaemonStatus)
    .any(SharedDaemonStatus::running)

internal fun parseSharedDaemonStatus(stdout: String): SharedDaemonStatus? = stdout.lineSequence()
    .map(String::trim)
    .filter { it.startsWith('{') && it.endsWith('}') }
    .mapNotNull { line ->
        runCatching {
            val status = Json.parseToJsonElement(line).jsonObject
            val state = status["status"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            SharedDaemonStatus(
                running = state == "running",
                cliVersion = status["cliVersion"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                appServerVersion = status["appServerVersion"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }
    .firstOrNull()

internal fun SharedDaemonStatus.isCompatibleWith(expectedCodexVersion: String): Boolean =
    running && expectedCodexVersion.isNotBlank() && appServerVersion == expectedCodexVersion

internal fun servingCodexVersion(
    mode: AppServerConnectionMode,
    loginCliVersion: String,
    daemonStatus: SharedDaemonStatus?,
): String = if (mode == AppServerConnectionMode.SHARED_DAEMON) {
    daemonStatus?.appServerVersion ?: loginCliVersion
} else {
    loginCliVersion
}

internal fun appServerCommand(
    platform: RemotePlatform,
    mode: AppServerConnectionMode = AppServerConnectionMode.ISOLATED_STDIO,
): String = when (platform) {
    RemotePlatform.AUTO -> error("AUTO platform must be resolved before building a Codex command")
    RemotePlatform.POSIX -> when (mode) {
        AppServerConnectionMode.SHARED_DAEMON ->
            "exec \"\${SHELL:-/bin/sh}\" -lc 'exec codex app-server proxy'"
        AppServerConnectionMode.ISOLATED_STDIO ->
            "exec \"\${SHELL:-/bin/sh}\" -lc 'exec codex app-server --listen stdio://'"
    }
    RemotePlatform.WINDOWS -> when (mode) {
        AppServerConnectionMode.SHARED_DAEMON ->
            "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server proxy }\""
        AppServerConnectionMode.ISOLATED_STDIO ->
            "powershell.exe -NoLogo -NonInteractive -Command \"& { codex app-server --listen stdio:// }\""
    }
}

internal fun androidCompatibleSshConfig() = DefaultSecurityProviderConfig().apply {
    keyExchangeFactories = keyExchangeFactories.filterNot {
        it.name.contains("curve25519", ignoreCase = true)
    }
    keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
}

internal fun configureProtocolKeepAlive(ssh: SSHClient) {
    ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
    (ssh.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEP_ALIVE_MAX_UNANSWERED
}

private const val KEEP_ALIVE_INTERVAL_SECONDS = 15
private const val KEEP_ALIVE_MAX_UNANSWERED = 3
