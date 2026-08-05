package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerFailureStateTest {
    @Test
    fun globalFailureAfterBootstrapBecomesVisibleAndDropsPendingWork() {
        val queued = ApprovalQueue().enqueue(
            ApprovalRequest(
                requestId = RpcRequestId.Text("approval-1"),
                kind = ApprovalKind.COMMAND,
                title = "Run command",
                detail = "git status",
                rawMethod = "commandExecution/requestApproval",
            ),
        )
        val state = AppUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            connectionMessage = "1 project · 2 tasks",
            selectedProjectPath = "/srv/app",
            selectedThreadId = "thread-1",
            timeline = listOf(
                TimelineItem("command-1", TimelineKind.COMMAND, status = "inProgress"),
            ),
            isTurnRunning = true,
            activeTurnId = "turn-1",
            approvalQueue = queued.markResponding(queued.currentEntry!!.key),
            showConnections = false,
        )

        val failed = state.afterGlobalAppServerFailure("SSH connection was interrupted")

        assertEquals(ConnectionStatus.ERROR, failed.connectionStatus)
        assertEquals("SSH connection was interrupted", failed.connectionMessage)
        assertEquals("SSH connection was interrupted", failed.notice)
        assertTrue(failed.showConnections)
        assertNull(failed.selectedThreadId)
        assertFalse(failed.isTurnRunning)
        assertNull(failed.activeTurnId)
        assertTrue(failed.timeline.isEmpty())
        assertTrue(failed.approvalQueue.requests.isEmpty())
        assertTrue(failed.approvalQueue.respondingKeys.isEmpty())
    }
}
