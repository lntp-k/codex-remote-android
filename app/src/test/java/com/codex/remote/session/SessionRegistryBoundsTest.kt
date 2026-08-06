package com.codex.remote.session

import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.ThreadGoalStatus
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryBoundsTest {
    @Test
    fun leastRecentlyUsedUnprotectedSessionIsEvictedWithoutLosingSelection() {
        var registry = SessionRegistry(limits = SessionLimits(maxSessions = 2))
        registry = registry.registerThread("thread-a").requireApplied()
        registry = registry.registerThread("thread-b").requireApplied()
        registry = registry.selectThread("thread-b").requireApplied()

        val added = registry.registerThread("thread-c")

        assertTrue(added.applied)
        assertEquals(setOf("thread-b", "thread-c"), added.registry.sessions.keys)
        assertEquals("thread-b", added.registry.selectedThreadId)
        assertFalse("thread-a" in added.registry.sessions)
    }

    @Test
    fun selectedRunningApprovalAndActiveGoalSessionsFailClosedAtCapacity() {
        var registry = SessionRegistry(limits = SessionLimits(maxSessions = 4))
        listOf("thread-selected", "thread-running", "thread-approval", "thread-goal")
            .forEach { threadId -> registry = registry.registerThread(threadId).requireApplied() }
        registry = registry.selectThread("thread-selected").requireApplied()
        registry = registry.expectTurn("thread-running", "turn-running").requireApplied()
        registry = registry.expectTurn("thread-approval", "turn-approval").requireApplied()
        registry = routeApplied(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-approval",
                approval("request-approval", "thread-approval", "turn-approval"),
            ),
        )
        registry = registry.markApprovalResponding(requireNotNull(registry.currentApproval).key).requireApplied()
        registry = routeApplied(
            registry,
            SessionEvent.TurnCompleted("thread-approval", "turn-approval"),
        )
        registry = registry.updateSession("thread-goal") {
            it.copy(goal = activeGoal("thread-goal"))
        }.requireApplied()
        val sessionsBefore = registry.sessions

        val refused = registry.registerThread("thread-new")

        assertFalse(refused.applied)
        assertEquals(SessionDiagnosticCode.SESSION_CAPACITY, refused.rejection?.diagnostic?.code)
        assertEquals(sessionsBefore, refused.registry.sessions)
        assertEquals("thread-selected", refused.registry.selectedThreadId)
        assertFalse("thread-new" in refused.registry.sessions)
    }

    @Test
    fun globalDiagnosticsKeepOnlyTheNewestBoundedMessages() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxDiagnostics = 2,
                maxDiagnosticChars = 12,
            ),
        )

        repeat(3) { index ->
            val routed = SessionEventRouter.route(
                registry,
                SessionEvent.TurnStarted("unknown-thread-$index-with-a-long-name", "turn-$index"),
            )
            assertTrue(routed.disposition is SessionRouteDisposition.Rejected)
            registry = routed.registry
        }

        assertEquals(2, registry.diagnostics.size)
        assertTrue(registry.diagnostics.all { it.code == SessionDiagnosticCode.UNKNOWN_THREAD_ID })
        assertTrue(registry.diagnostics.all { it.message.length <= 12 })
        assertTrue(registry.diagnostics[0].sequence < registry.diagnostics[1].sequence)
    }

    @Test
    fun timelineItemCapacityRejectsTheOverflowWithoutAppendingIt() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxTimelineItems = 1,
                maxTimelineChars = 1_024,
            ),
        )
        registry = registry.registerThread("thread-a").requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-1", "one", TimelineKind.AGENT),
        )
        val timelineBefore = registry.sessions.getValue("thread-a").timeline

        val overflow = SessionEventRouter.route(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-2", "two", TimelineKind.AGENT),
        )

        assertTrue(overflow.disposition is SessionRouteDisposition.Rejected)
        val rejection = (overflow.disposition as SessionRouteDisposition.Rejected).rejection
        assertEquals(SessionDiagnosticCode.TIMELINE_CAPACITY, rejection.diagnostic.code)
        assertTrue(rejection.disconnectRecommended)
        assertEquals(timelineBefore, overflow.registry.sessions.getValue("thread-a").timeline)
        assertTrue(overflow.registry.sessions.getValue("thread-a").isTurnRunning)
    }

    @Test
    fun timelineCharacterCapacityRejectsAnOversizedDelta() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxTimelineItems = 4,
                maxTimelineChars = 24,
            ),
        )
        registry = registry.registerThread("thread-a").requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))

        val overflow = SessionEventRouter.route(
            registry,
            SessionEvent.Delta(
                threadId = "thread-a",
                turnId = "turn-a",
                itemId = "item-1",
                delta = "x".repeat(64),
                kind = TimelineKind.AGENT,
            ),
        )

        assertTrue(overflow.disposition is SessionRouteDisposition.Rejected)
        val rejection = (overflow.disposition as SessionRouteDisposition.Rejected).rejection
        assertEquals(SessionDiagnosticCode.TIMELINE_CAPACITY, rejection.diagnostic.code)
        assertTrue(rejection.disconnectRecommended)
        assertTrue(overflow.registry.sessions.getValue("thread-a").timeline.isEmpty())
    }

    @Test
    fun aggregateTimelineItemBudgetEvictsTheLeastRecentlyUsedEligibleSession() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 4,
                maxTimelineItems = 4,
                maxTimelineChars = 100,
                maxAggregateRetainedChars = 1_000,
                maxAggregateTimelineItems = 2,
                maxAggregateHistoryCursors = 10,
            ),
        )
        registry = registry.registerThread("a").requireApplied()
        registry = registry.registerThread("b").requireApplied()
        registry = registry.registerThread("c").requireApplied()
        registry = registry.updateSession("a") {
            it.copy(timeline = listOf(timelineItem("a", 8)))
        }.requireApplied()
        registry = registry.updateSession("b") {
            it.copy(timeline = listOf(timelineItem("b", 8)))
        }.requireApplied()

        val filled = registry.updateSession("c") {
            it.copy(timeline = listOf(timelineItem("c", 8)))
        }

        assertTrue(filled.applied)
        assertEquals(setOf("b", "c"), filled.registry.sessions.keys)
        assertEquals(2L, filled.registry.sessions.values.retainedStateUsage().timelineItems)
    }

    @Test
    fun aggregateRetainedCharactersIncludeTimelineCursorsAndRemoteThreadMetadataOnRegister() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 3,
                maxTimelineItems = 4,
                maxTimelineChars = 100,
                maxAggregateRetainedChars = 28,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 10,
                maxHistoryCursors = 4,
                maxHistoryCursorChars = 10,
            ),
        )
        registry = registry.registerThread(remoteThread("a", "aa", cwd = "c", status = "s")).requireApplied()
        registry = registry.selectThread("a").requireApplied()
        registry = registry.updateSession("a") {
            it.copy(
                timeline = listOf(timelineItem("x", 4)),
                olderHistoryCursor = "1234",
                hasOlderHistory = true,
            )
        }.requireApplied()

        val rejected = registry.registerThread(
            remoteThread("b", "z".repeat(10), cwd = "c", status = "s"),
        )

        assertFalse(rejected.applied)
        assertEquals(SessionDiagnosticCode.RETAINED_STATE_CAPACITY, rejected.rejection?.diagnostic?.code)
        assertEquals(setOf("a"), rejected.registry.sessions.keys)
        assertEquals(15L, rejected.registry.sessions.values.retainedStateUsage().retainedChars)
    }

    @Test
    fun newRegistrationCommitsAggregateEvictionOnlyAfterTheCandidateFits() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 3,
                maxAggregateRetainedChars = 8,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 10,
            ),
        )
        registry = registry.registerThread(remoteThread("a", "aaa", cwd = "c", status = "s")).requireApplied()
        registry = registry.registerThread("b").requireApplied()
        registry = registry.selectThread("b").requireApplied()

        val added = registry.registerThread("c")

        assertTrue(added.applied)
        assertEquals(setOf("b", "c"), added.registry.sessions.keys)
        assertEquals("b", added.registry.selectedThreadId)
        assertFalse("a" in added.registry.sessions)
        assertTrue(added.registry.sessions.values.retainedStateUsage().retainedChars <= 8)
    }

    @Test
    fun newRegistrationRollsBackPlannedAggregateEvictionWhenTheCandidateCannotFit() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 2,
                maxAggregateRetainedChars = 5,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 10,
            ),
        )
        registry = registry.registerThread("a").requireApplied()
        registry = registry.registerThread("b").requireApplied()
        registry = registry.selectThread("b").requireApplied()
        val sessionsBefore = registry.sessions

        val rejected = registry.registerThread(
            remoteThread("c", "z".repeat(10), cwd = "c", status = "s"),
        )

        assertFalse(rejected.applied)
        assertEquals(SessionDiagnosticCode.RETAINED_STATE_CAPACITY, rejected.rejection?.diagnostic?.code)
        assertEquals(sessionsBefore, rejected.registry.sessions)
        assertEquals("b", rejected.registry.selectedThreadId)
    }

    @Test
    fun existingRegistrationRollsBackPlannedAggregateEvictionAndLruTouchOnRejection() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 3,
                maxAggregateRetainedChars = 5,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 10,
            ),
        )
        registry = registry.registerThread("a").requireApplied()
        registry = registry.registerThread("b").requireApplied()
        val sessionsBefore = registry.sessions
        val touchedBefore = registry.sessions.getValue("b").lastTouchedSequence

        val rejected = registry.registerThread(
            remoteThread("b", "z".repeat(10), cwd = "c", status = "s"),
        )

        assertFalse(rejected.applied)
        assertEquals(SessionDiagnosticCode.RETAINED_STATE_CAPACITY, rejected.rejection?.diagnostic?.code)
        assertEquals(sessionsBefore, rejected.registry.sessions)
        assertEquals(touchedBefore, rejected.registry.sessions.getValue("b").lastTouchedSequence)
    }

    @Test
    fun aggregateHistoryCursorCountEvictsTheLeastRecentlyUsedEligibleSession() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxAggregateRetainedChars = 1_000,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 2,
                maxHistoryCursors = 2,
                maxHistoryCursorChars = 10,
            ),
        )
        registry = registry.registerThread("a").requireApplied()
        registry = registry.updateSession("a") {
            it.copy(consumedHistoryCursors = linkedSetOf("1", "2"))
        }.requireApplied()
        registry = registry.registerThread("b").requireApplied()

        val filled = registry.updateSession("b") {
            it.copy(olderHistoryCursor = "3", hasOlderHistory = true)
        }

        assertTrue(filled.applied)
        assertEquals(setOf("b"), filled.registry.sessions.keys)
        assertEquals(1L, filled.registry.sessions.values.retainedStateUsage().historyCursors)
    }

    @Test
    fun directSessionUpdateRejectsOversizedTimelineWithoutRetainingIt() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxTimelineItems = 1,
                maxTimelineChars = 12,
                maxAggregateRetainedChars = 10_000,
            ),
        )
        registry = registry.registerThread("thread-a").requireApplied()
        registry = registry.expectTurn("thread-a", "turn-a").requireApplied()
        registry = routeApplied(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-a", "thread-a", "turn-a"),
            ),
        )
        val approvalBefore = requireNotNull(registry.currentApproval).key

        val rejected = registry.updateSession("thread-a") {
            it.copy(timeline = listOf(timelineItem("item", 32)))
        }

        assertFalse(rejected.applied)
        assertEquals(SessionDiagnosticCode.TIMELINE_CAPACITY, rejected.rejection?.diagnostic?.code)
        assertTrue(rejected.rejection?.disconnectRecommended == true)
        assertTrue(rejected.registry.sessions.getValue("thread-a").timeline.isEmpty())
        assertEquals(approvalBefore, rejected.registry.currentApproval?.key)
    }

    @Test
    fun directSessionUpdateRejectsTooManyOrOversizedHistoryCursors() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxHistoryCursors = 2,
                maxHistoryCursorChars = 4,
            ),
        )
        registry = registry.registerThread("thread-a").requireApplied()

        val tooMany = registry.updateSession("thread-a") {
            it.copy(consumedHistoryCursors = linkedSetOf("a", "b", "c"))
        }
        assertFalse(tooMany.applied)
        assertEquals(SessionDiagnosticCode.HISTORY_CURSOR_CAPACITY, tooMany.rejection?.diagnostic?.code)
        assertTrue(tooMany.rejection?.disconnectRecommended == true)
        assertTrue(tooMany.registry.sessions.getValue("thread-a").consumedHistoryCursors.isEmpty())

        val tooLong = tooMany.registry.updateSession("thread-a") {
            it.copy(olderHistoryCursor = "abcde", hasOlderHistory = true)
        }
        assertFalse(tooLong.applied)
        assertEquals(SessionDiagnosticCode.HISTORY_CURSOR_CAPACITY, tooLong.rejection?.diagnostic?.code)
        assertTrue(tooLong.rejection?.disconnectRecommended == true)
        assertEquals(null, tooLong.registry.sessions.getValue("thread-a").olderHistoryCursor)
    }

    @Test
    fun aggregateEvictionPreservesSelectedRunningApprovalAndActiveGoalSessions() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxSessions = 5,
                maxTimelineItems = 4,
                maxTimelineChars = 100,
                maxAggregateRetainedChars = 10_000,
                maxAggregateTimelineItems = 4,
                maxAggregateHistoryCursors = 10,
            ),
        )
        listOf("thread-selected", "thread-running", "thread-approval", "thread-goal", "thread-target")
            .forEach { threadId ->
                registry = registry.registerThread(threadId).requireApplied()
            }
        registry = registry.selectThread("thread-selected").requireApplied()
        registry = registry.expectTurn("thread-running", "turn-running").requireApplied()
        registry = registry.expectTurn("thread-approval", "turn-approval").requireApplied()
        registry = routeApplied(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-approval",
                approval("request-approval", "thread-approval", "turn-approval"),
            ),
        )
        registry = registry.markApprovalResponding(requireNotNull(registry.currentApproval).key).requireApplied()
        registry = routeApplied(
            registry,
            SessionEvent.TurnCompleted("thread-approval", "turn-approval"),
        )
        registry = registry.updateSession("thread-goal") {
            it.copy(goal = activeGoal("thread-goal"))
        }.requireApplied()
        listOf("thread-selected", "thread-running", "thread-approval", "thread-goal")
            .forEachIndexed { index, threadId ->
                registry = registry.updateSession(threadId) {
                    it.copy(timeline = listOf(timelineItem(('a'.code + index).toChar().toString(), 7)))
                }.requireApplied()
            }

        val filled = registry.updateSession("thread-target") {
            it.copy(timeline = listOf(timelineItem("e", 7)))
        }

        assertFalse(filled.applied)
        assertEquals(SessionDiagnosticCode.RETAINED_STATE_CAPACITY, filled.rejection?.diagnostic?.code)
        assertTrue("thread-selected" in filled.registry.sessions)
        assertTrue("thread-running" in filled.registry.sessions)
        assertTrue("thread-approval" in filled.registry.sessions)
        assertTrue("thread-target" in filled.registry.sessions)
        assertTrue("thread-goal" in filled.registry.sessions)
        assertTrue(filled.registry.sessions.getValue("thread-target").timeline.isEmpty())
        assertTrue(filled.registry.sessions.getValue("thread-running").isTurnRunning)
        assertTrue(filled.registry.sessions.getValue("thread-approval").approvalQueue.entries.isNotEmpty())
    }

    @Test
    fun activeGoalSessionIsProtectedAtSessionAdmissionCapacity() {
        var registry = SessionRegistry(limits = SessionLimits(maxSessions = 1))
        registry = registry.registerThread("thread-goal").requireApplied()
        registry = registry.updateSession("thread-goal") {
            it.copy(goal = activeGoal("thread-goal"))
        }.requireApplied()

        val refused = registry.registerThread("thread-new")

        assertFalse(refused.applied)
        assertEquals(SessionDiagnosticCode.SESSION_CAPACITY, refused.rejection?.diagnostic?.code)
        assertEquals(setOf("thread-goal"), refused.registry.sessions.keys)
    }

    @Test
    fun metadataRefreshUpdatesTheThreadWithoutTouchingLruOrder() {
        var registry = SessionRegistry(limits = SessionLimits(maxSessions = 2))
        registry = registry.registerThread("thread-a").requireApplied()
        registry = registry.registerThread("thread-b").requireApplied()
        registry = registry.selectThread("thread-a").requireApplied().clearSelection()
        val touchedBefore = registry.sessions.getValue("thread-b").lastTouchedSequence
        val nextSequenceBefore = registry.nextSequence

        registry = registry.refreshThreadMetadata(remoteThread("thread-b", "Refreshed title")).requireApplied()

        assertEquals("Refreshed title", registry.sessions.getValue("thread-b").thread?.title)
        assertEquals(touchedBefore, registry.sessions.getValue("thread-b").lastTouchedSequence)
        assertEquals(nextSequenceBefore, registry.nextSequence)

        val added = registry.registerThread("thread-c")
        assertTrue(added.applied)
        assertEquals(setOf("thread-a", "thread-c"), added.registry.sessions.keys)
        assertFalse("thread-b" in added.registry.sessions)
    }

    @Test
    fun metadataRefreshRollsBackPlannedAggregateEvictionAndLruTouchOnRejection() {
        var registry = SessionRegistry(
            limits = SessionLimits(
                maxAggregateRetainedChars = 5,
                maxAggregateTimelineItems = 10,
                maxAggregateHistoryCursors = 10,
            ),
        )
        registry = registry.registerThread("a").requireApplied()
        registry = registry.registerThread("b").requireApplied()
        val sessionsBefore = registry.sessions
        val touchedBefore = registry.sessions.getValue("b").lastTouchedSequence

        val rejected = registry.refreshThreadMetadata(
            remoteThread("b", "z".repeat(20), cwd = "c", status = "s"),
        )

        assertFalse(rejected.applied)
        assertEquals(SessionDiagnosticCode.RETAINED_STATE_CAPACITY, rejected.rejection?.diagnostic?.code)
        assertEquals(sessionsBefore, rejected.registry.sessions)
        assertEquals(touchedBefore, rejected.registry.sessions.getValue("b").lastTouchedSequence)
        assertTrue(rejected.registry.sessions.getValue("b").diagnostics.isEmpty())
        assertTrue(rejected.registry.sessions.values.retainedStateUsage().retainedChars <= 5)
    }

    @Test
    fun retainedCharacterCountIncludesGoalSettingsDiagnosticsAndApprovalPayloads() {
        val base = SessionState(threadId = "t")
        val goal = activeGoal("t").copy(objective = "go")
        val settings = SessionSettings(
            model = "m",
            reasoningEffort = "r",
            serviceTier = "s",
            collaborationMode = "c",
            permissionProfile = "p",
            approvalPolicy = "a",
            approvalsReviewer = "v",
        )
        val diagnostic = SessionDiagnostic(
            code = SessionDiagnosticCode.THREAD_FAILURE,
            message = "diag",
            threadId = "t",
        )
        val request = approval("r", "t", "u")
        val populated = base.copy(
            olderHistoryError = "error",
            goal = goal,
            settings = settings,
            activeTurnId = "active",
            expectedTurnId = "expected",
            approvalQueue = base.approvalQueue.enqueue(request),
            diagnostics = listOf(diagnostic),
        )
        val settingsChars = listOfNotNull(
            settings.model,
            settings.reasoningEffort,
            settings.serviceTier,
            settings.collaborationMode,
            settings.permissionProfile,
            settings.approvalPolicy,
            settings.approvalsReviewer,
        ).sumOf { it.length }.toLong()
        val expected = base.retainedCharacterCount()
            .saturatingAdd("error".length.toLong())
            .saturatingAdd(goal.threadId.length.toLong())
            .saturatingAdd(goal.objective.length.toLong())
            .saturatingAdd(settingsChars)
            .saturatingAdd("active".length.toLong())
            .saturatingAdd("expected".length.toLong())
            .saturatingAdd(request.retainedCharCount)
            .saturatingAdd(diagnostic.message.length.toLong())
            .saturatingAdd(diagnostic.threadId.orEmpty().length.toLong())

        assertEquals(expected, populated.retainedCharacterCount())
        assertEquals(expected, populated.retainedStateUsage().retainedChars)
    }

    @Test
    fun retainedStateArithmeticSaturatesEveryAggregateDimension() {
        val saturated = SessionRetainedState(
            retainedChars = Long.MAX_VALUE - 1,
            timelineItems = Long.MAX_VALUE - 1,
            historyCursors = Long.MAX_VALUE - 1,
        ).saturatingAdd(
            SessionRetainedState(
                retainedChars = 2,
                timelineItems = 2,
                historyCursors = 2,
            ),
        )

        assertEquals(Long.MAX_VALUE, saturated.retainedChars)
        assertEquals(Long.MAX_VALUE, saturated.timelineItems)
        assertEquals(Long.MAX_VALUE, saturated.historyCursors)
    }

    private fun routeApplied(registry: SessionRegistry, event: SessionEvent): SessionRegistry {
        val routed = SessionEventRouter.route(registry, event)
        assertTrue("Expected $event to apply but was ${routed.disposition}", routed.disposition is SessionRouteDisposition.Applied)
        return routed.registry
    }

    private fun timelineItem(id: String, bodyChars: Int): TimelineItem = TimelineItem(
        id = id,
        kind = TimelineKind.AGENT,
        body = "x".repeat(bodyChars),
    )

    private fun approval(requestId: String, threadId: String, turnId: String): ApprovalRequest = ApprovalRequest(
        requestId = RpcRequestId.Text(requestId),
        kind = ApprovalKind.COMMAND,
        title = "Allow command?",
        detail = "echo test",
        rawMethod = "item/commandExecution/requestApproval",
        threadId = threadId,
        turnId = turnId,
        itemId = "item-$requestId",
        cwd = "/workspace",
        securityContextComplete = true,
    )

    private fun activeGoal(threadId: String): ThreadGoal = ThreadGoal(
        threadId = threadId,
        objective = "Keep working",
        status = ThreadGoalStatus.ACTIVE,
        tokenBudget = null,
        tokensUsed = 0,
        timeUsedSeconds = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun remoteThread(
        id: String,
        title: String,
        cwd: String = "/workspace",
        status: String = "idle",
    ): RemoteThread = RemoteThread(
        id = id,
        title = title,
        cwd = cwd,
        updatedAt = 1,
        status = status,
    )

    private fun SessionRegistryMutation.requireApplied(): SessionRegistry {
        assertTrue("Expected registry mutation to apply but was $rejection", applied)
        return registry
    }
}
