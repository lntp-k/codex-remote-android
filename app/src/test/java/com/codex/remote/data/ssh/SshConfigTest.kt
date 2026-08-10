package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigTest {
    @Test
    fun configuresReplyCheckedProtocolKeepAliveForDeadRouteDetection() {
        val ssh = SSHClient(androidCompatibleSshConfig())

        try {
            configureProtocolKeepAlive(ssh)

            assertEquals(KeepAliveProvider.KEEP_ALIVE, androidCompatibleSshConfig().keepAliveProvider)
            assertTrue(ssh.connection.keepAlive is KeepAliveRunner)
            assertEquals(15, ssh.connection.keepAlive.keepAliveInterval)
            assertEquals(3, (ssh.connection.keepAlive as KeepAliveRunner).maxAliveCount)
        } finally {
            runCatching { ssh.close() }
        }
    }

    @Test
    fun excludesCurve25519ThatAndroidCannotInstantiate() {
        val names = androidCompatibleSshConfig().keyExchangeFactories.map { it.name }

        assertFalse(names.any { it.contains("curve25519", ignoreCase = true) })
        assertTrue(names.any { it.contains("ecdh", ignoreCase = true) || it.contains("group14", ignoreCase = true) })
    }

    @Test
    fun posixCommandsUseTheRemoteLoginShell() {
        assertEquals(
            "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'",
            codexVersionCommand(RemotePlatform.POSIX),
        )
        assertTrue(daemonVersionCommand(RemotePlatform.POSIX).contains("-lc 'codex app-server daemon version'"))
        assertTrue(daemonStartCommand(RemotePlatform.POSIX).contains("-lc 'codex app-server daemon start'"))
        assertTrue(
            appServerCommand(RemotePlatform.POSIX, AppServerConnectionMode.SHARED_DAEMON)
                .contains("-lc 'exec codex app-server proxy'"),
        )
        assertTrue(
            appServerCommand(RemotePlatform.POSIX, AppServerConnectionMode.ISOLATED_STDIO)
                .contains("-lc 'exec codex app-server --listen stdio://'"),
        )
    }

    @Test
    fun windowsCommandAllowsTheRemotePowerShellProfile() {
        val isolated = appServerCommand(RemotePlatform.WINDOWS, AppServerConnectionMode.ISOLATED_STDIO)
        val shared = appServerCommand(RemotePlatform.WINDOWS, AppServerConnectionMode.SHARED_DAEMON)

        assertTrue(isolated.contains("powershell.exe"))
        assertFalse(isolated.contains("-NoProfile"))
        assertTrue(isolated.contains("codex app-server --listen stdio://"))
        assertTrue(shared.contains("codex app-server proxy"))
        assertFalse(shared.contains("-NoProfile"))
    }

    @Test
    fun daemonProbeRequiresAValidRunningStatusObject() {
        val status = parseSharedDaemonStatus(
            """{"status":"running","cliVersion":"0.145.0","appServerVersion":"0.145.0"}""",
        )

        assertTrue(status?.running == true)
        assertEquals("0.145.0", status?.cliVersion)
        assertEquals("0.145.0", status?.appServerVersion)
        assertTrue(status?.isCompatibleWith("0.145.0") == true)
        assertFalse(status?.isCompatibleWith("0.144.0") == true)
        assertFalse(parseSharedDaemonStatus("""{"status":"running"}""")?.isCompatibleWith("0.145.0") == true)
        assertEquals(
            "0.145.0",
            servingCodexVersion(AppServerConnectionMode.SHARED_DAEMON, "0.146.0", status),
        )
        assertEquals(
            "0.146.0",
            servingCodexVersion(AppServerConnectionMode.ISOLATED_STDIO, "0.146.0", status),
        )
        assertTrue(daemonReportsRunning("""{"status":"running","socketPath":"/tmp/codex.sock"}"""))
        assertTrue(daemonReportsRunning("warning\n  {\"status\": \"running\"}\n"))
        assertFalse(daemonReportsRunning("""{"status":"stopped"}"""))
        assertFalse(daemonReportsRunning("not json\n{\"status\":"))
    }
}
