package com.codex.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRequestTrackerTest {
    @Test
    fun laterLoadForTheSameThreadSupersedesAnOlderResponse() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val firstA = tracker.beginSessionLoad("thread-a")
        val threadB = tracker.beginSessionLoad("thread-b")
        val secondA = tracker.beginSessionLoad("thread-a")

        assertFalse(tracker.isCurrent(firstA))
        assertTrue(tracker.isCurrent(threadB))
        assertTrue(tracker.isCurrent(secondA))
    }

    @Test
    fun reconnectInvalidatesEveryOutstandingSessionLoad() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val request = tracker.beginSessionLoad("thread-a")

        tracker.invalidateConnection()

        assertFalse(tracker.isCurrent(request))
        assertFalse(tracker.isCurrentConnection(request.connectionGeneration))
    }

    @Test
    fun draftSelectionTokenIsInvalidatedByNavigationOrReconnect() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val originalDraft = tracker.captureDraftSelection()

        assertTrue(tracker.isCurrent(originalDraft))

        tracker.advanceSelection()
        assertFalse(tracker.isCurrent(originalDraft))

        val replacementDraft = tracker.captureDraftSelection()
        assertTrue(tracker.isCurrent(replacementDraft))

        tracker.invalidateConnection()
        assertFalse(tracker.isCurrent(replacementDraft))
    }

    @Test
    fun refreshChangingOnlyTheDraftProjectInvalidatesThePendingSelection() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val draft = tracker.captureDraftSelection()

        assertTrue(
            tracker.reconcileSelection(
                previousThreadId = null,
                previousProjectPath = "/workspace/a",
                currentThreadId = null,
                currentProjectPath = "/workspace/b",
            ),
        )
        assertFalse(tracker.isCurrent(draft))
        assertFalse(
            tracker.reconcileSelection(
                previousThreadId = null,
                previousProjectPath = "/workspace/b",
                currentThreadId = null,
                currentProjectPath = "/workspace/b",
            ),
        )
    }

    @Test
    fun completedNotificationBeforeResponseCannotReopenTheTurn() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val start = tracker.beginTurnStart("thread-a")

        tracker.observeTurnStarted("thread-a", "turn-a")
        tracker.observeTurnCompleted("thread-a", "turn-a")

        assertEquals(
            TurnStartResponseDisposition.TERMINAL,
            tracker.resolveTurnStartResponse(start, "turn-a"),
        )
        assertFalse(tracker.canRollbackTurnStart(start))
    }

    @Test
    fun notificationBeforeResponseIsAlreadyOwnedInsteadOfAppliedTwice() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val start = tracker.beginTurnStart("thread-a")

        tracker.observeTurnStarted("thread-a", "turn-a")

        assertEquals(
            TurnStartResponseDisposition.ALREADY_OBSERVED,
            tracker.resolveTurnStartResponse(start, "turn-a"),
        )
        assertFalse(tracker.canRollbackTurnStart(start))
    }

    @Test
    fun conflictingNotificationAndResponseTurnIdsFailClassification() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val start = tracker.beginTurnStart("thread-a")

        assertTrue(tracker.canObserveTurnStarted("thread-a", "turn-notification"))
        assertTrue(tracker.observeTurnStarted("thread-a", "turn-notification"))

        assertEquals(
            TurnStartResponseDisposition.CONFLICT,
            tracker.resolveTurnStartResponse(start, "turn-response"),
        )
        assertFalse(tracker.canRollbackTurnStart(start))
    }

    @Test
    fun completedTurnCannotBeReboundToANewerStartOperation() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val completed = tracker.beginTurnStart("thread-a")
        tracker.observeTurnStarted("thread-a", "turn-completed")
        tracker.observeTurnCompleted("thread-a", "turn-completed")
        assertEquals(
            TurnStartResponseDisposition.TERMINAL,
            tracker.resolveTurnStartResponse(completed, "turn-completed"),
        )

        val newer = tracker.beginTurnStart("thread-a")

        assertFalse(tracker.canObserveTurnStarted("thread-a", "turn-completed"))
        assertFalse(tracker.observeTurnStarted("thread-a", "turn-completed"))
        assertEquals(
            TurnStartResponseDisposition.CONFLICT,
            tracker.resolveTurnStartResponse(newer, "turn-completed"),
        )
        assertEquals(
            TurnStartResponseDisposition.APPLY,
            tracker.resolveTurnStartResponse(newer, "turn-new"),
        )
    }

    @Test
    fun completedTurnMemoryIsBoundedAndClearedOnReconnect() {
        val tracker = SessionRequestTracker(maxRecentCompletedTurns = 2)
        tracker.invalidateConnection()
        tracker.observeTurnCompleted("thread-a", "turn-a")
        tracker.observeTurnCompleted("thread-a", "turn-b")
        tracker.observeTurnCompleted("thread-a", "turn-c")

        assertTrue(tracker.canObserveTurnStarted("thread-a", "turn-a"))
        assertFalse(tracker.canObserveTurnStarted("thread-a", "turn-b"))
        assertFalse(tracker.canObserveTurnStarted("thread-a", "turn-c"))

        tracker.invalidateConnection()
        assertTrue(tracker.canObserveTurnStarted("thread-a", "turn-b"))
        assertTrue(tracker.canObserveTurnStarted("thread-a", "turn-c"))
    }

    @Test
    fun newerStartOwnsSuccessAndFailureRollbackForTheThread() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val older = tracker.beginTurnStart("thread-a")
        val newer = tracker.beginTurnStart("thread-a")

        assertEquals(
            TurnStartResponseDisposition.SUPERSEDED,
            tracker.resolveTurnStartResponse(older, "turn-a"),
        )
        assertFalse(tracker.canRollbackTurnStart(older))
        assertEquals(
            TurnStartResponseDisposition.APPLY,
            tracker.resolveTurnStartResponse(newer, "turn-b"),
        )
    }

    @Test
    fun onlyAnUnobservedCurrentStartMayRollBackOptimisticState() {
        val tracker = SessionRequestTracker()
        tracker.invalidateConnection()
        val start = tracker.beginTurnStart("thread-a")

        assertTrue(tracker.canRollbackTurnStart(start))
        assertFalse(tracker.canRollbackTurnStart(start))
        assertEquals(
            TurnStartResponseDisposition.SUPERSEDED,
            tracker.resolveTurnStartResponse(start, "turn-a"),
        )
    }
}
