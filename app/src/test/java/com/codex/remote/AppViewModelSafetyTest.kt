package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.ReasoningEffortOption
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemoteServiceTier
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.session.SessionDiagnosticCode
import com.codex.remote.session.SessionLimits
import com.codex.remote.session.SessionRegistry
import com.codex.remote.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelSafetyTest {
    @Test
    fun uncachedSessionDefaultsComeFromTheRemoteCatalogInsteadOfThePreviousTask() {
        val state = AppUiState(
            models = listOf(
                RemoteModel(
                    id = "model-previous",
                    displayName = "Previous",
                    description = "",
                    isDefault = false,
                    supportedReasoningEfforts = listOf(ReasoningEffortOption("low")),
                    defaultReasoningEffort = "low",
                ),
                RemoteModel(
                    id = "model-default",
                    displayName = "Default",
                    description = "",
                    isDefault = true,
                    supportedReasoningEfforts = listOf(ReasoningEffortOption("high")),
                    defaultReasoningEffort = "high",
                    serviceTiers = listOf(RemoteServiceTier("priority", "Priority")),
                    defaultServiceTier = "priority",
                ),
            ),
            selectedModel = "model-previous",
            selectedReasoningEffort = "low",
        )

        val defaults = state.defaultSessionSettings()

        assertEquals("model-default", defaults.model)
        assertEquals("high", defaults.reasoningEffort)
        assertEquals("priority", defaults.serviceTier)
        assertEquals(":workspace", defaults.permissionProfile)
        assertEquals("on-request", defaults.approvalPolicy)
        assertEquals("user", defaults.approvalsReviewer)
    }

    @Test
    fun remoteActiveStatusBlocksArchivingEvenWhenTheThreadIsNotCached() {
        assertTrue(shouldBlockArchive(remoteThread(status = "active"), session = null))
        assertTrue(shouldBlockArchive(remoteThread(status = "inProgress"), session = null))
        assertFalse(shouldBlockArchive(remoteThread(status = "idle"), session = null))
    }

    @Test
    fun approvalResponseRequiresItsExactLiveOwnerTurn() {
        val request = approval(turnId = "turn-a")

        assertTrue(
            approvalBelongsToLiveTurn(
                owner = SessionState(
                    threadId = "thread-a",
                    isTurnRunning = true,
                    activeTurnId = "turn-a",
                    expectedTurnId = "turn-a",
                ),
                request = request,
            ),
        )
        assertFalse(
            approvalBelongsToLiveTurn(
                owner = SessionState(
                    threadId = "thread-a",
                    isTurnRunning = false,
                    activeTurnId = null,
                    expectedTurnId = null,
                ),
                request = request,
            ),
        )
        assertFalse(
            approvalBelongsToLiveTurn(
                owner = SessionState(
                    threadId = "thread-a",
                    isTurnRunning = true,
                    activeTurnId = "turn-b",
                    expectedTurnId = "turn-b",
                ),
                request = request,
            ),
        )
    }

    @Test
    fun exactSelectionContextRejectsAProjectOrThreadChange() {
        val captured = AppUiState(
            selectedThreadId = null,
            selectedProjectPath = "/workspace/a",
        ).selectionContext()

        assertTrue(
            AppUiState(
                selectedThreadId = null,
                selectedProjectPath = "/workspace/a",
            ).matchesSelection(captured),
        )
        assertFalse(
            AppUiState(
                selectedThreadId = null,
                selectedProjectPath = "/workspace/b",
            ).matchesSelection(captured),
        )
        assertFalse(
            AppUiState(
                selectedThreadId = "thread-a",
                selectedProjectPath = "/workspace/a",
            ).matchesSelection(captured),
        )
    }

    @Test
    fun resumedTurnIdUsesOnlyAuthoritativeOrCachedLiveIdentity() {
        assertEquals(
            "turn-remote",
            resolveResumedTurnId(
                remoteActiveTurnId = "turn-remote",
                cachedIsRunning = true,
                cachedActiveTurnId = "turn-cached",
                cachedExpectedTurnId = "turn-expected",
            ),
        )
        assertEquals(
            "turn-cached",
            resolveResumedTurnId(
                remoteActiveTurnId = null,
                cachedIsRunning = true,
                cachedActiveTurnId = "turn-cached",
                cachedExpectedTurnId = "turn-expected",
            ),
        )
        assertEquals(
            "turn-expected",
            resolveResumedTurnId(
                remoteActiveTurnId = null,
                cachedIsRunning = true,
                cachedActiveTurnId = null,
                cachedExpectedTurnId = "turn-expected",
            ),
        )
        assertNull(
            resolveResumedTurnId(
                remoteActiveTurnId = null,
                cachedIsRunning = false,
                cachedActiveTurnId = "turn-stale",
                cachedExpectedTurnId = "turn-stale",
            ),
        )
    }

    @Test
    fun rejectedHistoryMutationCanRestoreRegistryAndProjectedLoadingState() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxHistoryCursors = 2,
                maxAggregateHistoryCursors = 2,
            ),
        )
        registry = registry.registerThread("thread-a").registry
        registry = registry.selectThread("thread-a").registry
        val loading = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                olderHistoryCursor = "cursor-next",
                hasOlderHistory = true,
                isOlderHistoryLoading = true,
                consumedHistoryCursors = setOf("cursor-used"),
            )
        }
        assertTrue(loading.applied)
        registry = loading.registry

        val rejected = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(consumedHistoryCursors = setOf("cursor-used", "cursor-next"))
        }
        assertFalse(rejected.applied)
        assertEquals(
            SessionDiagnosticCode.HISTORY_CURSOR_CAPACITY,
            rejected.rejection?.diagnostic?.code,
        )
        assertTrue(rejected.registry.sessions.getValue("thread-a").isOlderHistoryLoading)

        val restored = rejected.registry.clearOlderHistoryLoading("thread-a")
        assertTrue(restored.applied)
        assertFalse(restored.registry.sessions.getValue("thread-a").isOlderHistoryLoading)
        assertFalse(
            AppUiState(isOlderHistoryLoading = true)
                .withSessionRegistry(restored.registry)
                .isOlderHistoryLoading,
        )
    }

    private fun remoteThread(status: String) = RemoteThread(
        id = "thread-a",
        title = "Task A",
        cwd = "/workspace/a",
        updatedAt = 1L,
        status = status,
    )

    private fun approval(turnId: String?) = ApprovalRequest(
        requestId = RpcRequestId.Text("approval-a"),
        kind = ApprovalKind.COMMAND,
        title = "Run command",
        detail = "",
        rawMethod = "item/commandExecution/requestApproval",
        threadId = "thread-a",
        turnId = turnId,
    )
}
