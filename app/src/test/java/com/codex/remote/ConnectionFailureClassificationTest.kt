package com.codex.remote

import com.codex.remote.data.rpc.RpcException
import com.codex.remote.data.rpc.AppServerEvent
import com.codex.remote.data.rpc.FailureKind
import com.codex.remote.data.rpc.appServerReaderFailureEvent
import com.codex.remote.data.ssh.HostKeyChangedException
import com.codex.remote.data.ssh.RemoteCodexUnavailableException
import com.codex.remote.data.ssh.UnknownHostKeyException
import java.io.IOException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFailureClassificationTest {
    @Test
    fun temporaryTransportFailuresCanReconnect() {
        assertTrue(IOException("connection reset").isRetryableConnectionFailure())
        assertTrue(RpcException("Remote connection closed").isRetryableConnectionFailure())
        assertTrue(IllegalStateException("No route to host").isRetryableConnectionFailure())
    }

    @Test
    fun hostIdentityAndRemoteSetupFailuresStaySuspended() {
        assertFalse(UnknownHostKeyException("SHA256:test").isRetryableConnectionFailure())
        assertFalse(HostKeyChangedException("SHA256:old", "SHA256:new").isRetryableConnectionFailure())
        assertFalse(RemoteCodexUnavailableException("codex missing").isRetryableConnectionFailure())
        assertFalse(SecurityException("blocked").isRetryableConnectionFailure())
    }

    @Test
    fun authenticationAndUnknownProtocolFailuresDoNotLoop() {
        assertFalse(IllegalStateException("Authentication failed").isRetryableConnectionFailure())
        assertFalse(IllegalStateException("unsupported protocol message").isRetryableConnectionFailure())
        assertFalse(
            TransportException(DisconnectReason.KEY_EXCHANGE_FAILED).isRetryableConnectionFailure(),
        )
        assertFalse(
            TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE).isRetryableConnectionFailure(),
        )
        assertTrue(
            TransportException(DisconnectReason.CONNECTION_LOST).isRetryableConnectionFailure(),
        )
    }

    @Test
    fun readerBugsFailClosedWhileIoInterruptionsRemainReconnectable() {
        val transport = appServerReaderFailureEvent(
            IOException("socket closed"),
            hadPendingRequests = true,
        )
        assertTrue(
            transport is AppServerEvent.Failure &&
                transport.kind == FailureKind.TRANSPORT &&
                transport.hadPendingRequests,
        )

        val localBug = appServerReaderFailureEvent(IllegalStateException("unexpected parser state"))
        assertTrue(localBug is AppServerEvent.FatalProtocolError)
    }
}
