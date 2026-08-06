package com.codex.remote

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ReasoningEffortOption
import com.codex.remote.domain.RemoteCollaborationMode
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemoteServiceTier
import com.codex.remote.domain.TimelineKind
import com.codex.remote.session.SessionEvent
import com.codex.remote.session.SessionEventRouter
import com.codex.remote.session.SessionRegistry
import com.codex.remote.session.SessionRouteDisposition
import com.codex.remote.session.SessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProjectionTest {
    @Test
    fun emptyBackgroundSessionDoesNotInheritThePreviouslyProjectedModelSettings() {
        val previousModel = RemoteModel(
            id = "model-a",
            displayName = "Model A",
            description = "",
            isDefault = false,
            supportedReasoningEfforts = listOf(ReasoningEffortOption("low")),
            defaultReasoningEffort = "low",
        )
        val defaultModel = RemoteModel(
            id = "model-b",
            displayName = "Model B",
            description = "",
            isDefault = true,
            supportedReasoningEfforts = listOf(ReasoningEffortOption("high")),
            defaultReasoningEffort = "high",
            serviceTiers = listOf(RemoteServiceTier("priority", "Priority")),
            defaultServiceTier = "priority",
        )
        var registry = SessionRegistry()
        registry = registry.registerThread("thread-a").registry
        registry = registry.registerThread("thread-b").registry
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                settings = SessionSettings(
                    model = "model-a",
                    reasoningEffort = "low",
                    collaborationMode = "plan",
                ),
            )
        }.registry
        registry = registry.selectThread("thread-b").registry

        val projected = AppUiState(
            models = listOf(previousModel, defaultModel),
            selectedModel = "model-a",
            selectedReasoningEffort = "low",
            selectedCollaborationMode = "plan",
            collaborationModes = listOf(
                RemoteCollaborationMode(name = "Default", mode = "default"),
                RemoteCollaborationMode(name = "Plan", mode = "plan"),
            ),
        ).withSessionRegistry(registry)

        assertEquals("model-b", projected.selectedModel)
        assertEquals("high", projected.selectedReasoningEffort)
        assertEquals("priority", projected.selectedServiceTier)
        assertEquals("default", projected.selectedCollaborationMode)
    }

    @Test
    fun switchingProjectionRestoresCachedTimelineAndClearsUnreadOnlyForTheSelectedTask() {
        var registry = SessionRegistry()
        registry = registry.registerThread("thread-a").registry
        registry = registry.registerThread("thread-b").registry
        registry = registry.selectThread("thread-a").registry
        registry = route(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = route(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-a", "alpha", TimelineKind.AGENT),
        )
        registry = route(registry, SessionEvent.TurnStarted("thread-b", "turn-b"))
        registry = route(
            registry,
            SessionEvent.Delta("thread-b", "turn-b", "item-b", "beta", TimelineKind.AGENT),
        )

        val projectedA = AppUiState().withSessionRegistry(registry)
        assertEquals("thread-a", projectedA.selectedThreadId)
        assertEquals("alpha", projectedA.timeline.single().body)
        assertEquals(0, projectedA.sessionIndicators.getValue("thread-a").unreadCount)
        assertTrue(projectedA.sessionIndicators.getValue("thread-b").unreadCount > 0)
        assertTrue(projectedA.sessionIndicators.getValue("thread-b").isRunning)

        registry = registry.selectThread("thread-b").registry
        val projectedB = projectedA.withSessionRegistry(registry)
        assertEquals("thread-b", projectedB.selectedThreadId)
        assertEquals("beta", projectedB.timeline.single().body)
        assertEquals(0, projectedB.sessionIndicators.getValue("thread-b").unreadCount)
        assertEquals("alpha", registry.sessions.getValue("thread-a").timeline.single().body)
    }

    @Test
    fun disconnectAndMissingRemoteAuthorizationRestoreTheAtomicSafeTuple() {
        val disconnected = AppUiState(
            selectedPermissionProfile = ":danger-full-access",
            approvalPolicy = "never",
            approvalsReviewer = "auto_review",
            isBusy = true,
        ).afterDisconnect(clearActive = false)

        assertEquals(":workspace", disconnected.selectedPermissionProfile)
        assertEquals("on-request", disconnected.approvalPolicy)
        assertEquals("user", disconnected.approvalsReviewer)
        assertEquals(false, disconnected.isBusy)

        val inherited = SessionSettings(
            permissionProfile = ":danger-full-access",
            approvalPolicy = "never",
            approvalsReviewer = "user",
        )
        val reset = inherited.withRemoteAuthorization(
            permissionProfile = null,
            approvalPolicy = null,
            approvalsReviewer = null,
        )

        assertEquals(":workspace", reset.permissionProfile)
        assertEquals("on-request", reset.approvalPolicy)
        assertEquals("user", reset.approvalsReviewer)
    }

    @Test
    fun completeRemoteAuthorizationTupleRemainsTargetScoped() {
        val resumed = SessionSettings().withRemoteAuthorization(
            permissionProfile = ":danger-full-access",
            approvalPolicy = "never",
            approvalsReviewer = "user",
        )

        assertEquals(":danger-full-access", resumed.permissionProfile)
        assertEquals("never", resumed.approvalPolicy)
        assertEquals("user", resumed.approvalsReviewer)
    }

    private fun route(registry: SessionRegistry, event: SessionEvent): SessionRegistry {
        val result = SessionEventRouter.route(registry, event)
        assertTrue(result.disposition is SessionRouteDisposition.Applied)
        return result.registry
    }
}
