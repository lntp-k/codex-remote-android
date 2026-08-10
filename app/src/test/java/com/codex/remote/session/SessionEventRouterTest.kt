package com.codex.remote.session

import com.codex.remote.data.rpc.AppServerEvent
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventRouterTest {
    @Test
    fun appServerDeltaRetainsItsExactTurnIdentity() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))

        val routed = SessionEventRouter.route(
            registry,
            AppServerEvent.AgentDelta("thread-a", "turn-a", "item-a", "hello"),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals("turn-a", routed.registry.sessions.getValue("thread-a").timeline.single().turnId)
    }

    @Test
    fun interleavedDeltasStayWithTheirExactThreadsAndTurns() {
        var registry = registryWith("thread-a", "thread-b")
        registry = registry.selectThread("thread-a").requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-b", "turn-b"))

        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-a", "A1", TimelineKind.AGENT),
        )
        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-b", "turn-b", "item-b", "B1", TimelineKind.AGENT),
        )
        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-a", "A2", TimelineKind.AGENT),
        )

        assertEquals("A1A2", registry.sessions.getValue("thread-a").timeline.single().body)
        assertEquals("B1", registry.sessions.getValue("thread-b").timeline.single().body)
        assertEquals("turn-a", registry.sessions.getValue("thread-a").timeline.single().turnId)
        assertEquals("turn-b", registry.sessions.getValue("thread-b").timeline.single().turnId)
        assertEquals("thread-a", registry.selectedThreadId)
        assertEquals(0, registry.sessions.getValue("thread-a").unreadCount)
        assertTrue(registry.sessions.getValue("thread-b").unreadCount > 0)
    }

    @Test
    fun desktopUserMessageWithTheSameBodyDoesNotReplaceThePhoneOptimisticItem() {
        var registry = registryWith("thread-a")
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                timeline = listOf(
                    TimelineItem(
                        id = "local-phone",
                        kind = TimelineKind.USER,
                        body = "same text",
                    ),
                ),
            )
        }.requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-desktop"))

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ItemUpsert(
                "thread-a",
                TimelineItem(
                    id = "desktop-item",
                    kind = TimelineKind.USER,
                    body = "same text",
                    turnId = "turn-desktop",
                    clientId = "desktop-client",
                ),
            ),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals(
            listOf("local-phone", "desktop-item"),
            routed.registry.sessions.getValue("thread-a").timeline.map(TimelineItem::id),
        )
    }

    @Test
    fun matchingClientIdReplacesOnlyTheExactPhoneOptimisticItem() {
        var registry = registryWith("thread-a")
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                timeline = listOf(
                    TimelineItem(id = "local-other", kind = TimelineKind.USER, body = "same text"),
                    TimelineItem(id = "local-phone", kind = TimelineKind.USER, body = "same text"),
                ),
            )
        }.requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-phone"))

        val canonical = TimelineItem(
            id = "canonical-phone",
            kind = TimelineKind.USER,
            body = "same text",
            turnId = "turn-phone",
            clientId = "local-phone",
        )
        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ItemUpsert("thread-a", canonical),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals(
            listOf("local-other", "canonical-phone"),
            routed.registry.sessions.getValue("thread-a").timeline.map(TimelineItem::id),
        )
    }

    @Test
    fun missingClientIdNeverClaimsAPhoneOptimisticItemByBody() {
        var registry = registryWith("thread-a")
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                timeline = listOf(
                    TimelineItem(id = "local-phone", kind = TimelineKind.USER, body = "same text"),
                ),
            )
        }.requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-desktop"))

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ItemUpsert(
                "thread-a",
                TimelineItem(
                    id = "desktop-item",
                    kind = TimelineKind.USER,
                    body = "same text",
                    turnId = "turn-desktop",
                ),
            ),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals(
            listOf("local-phone", "desktop-item"),
            routed.registry.sessions.getValue("thread-a").timeline.map(TimelineItem::id),
        )
    }

    @Test
    fun canonicalReplayRemovesAnAlreadyCoexistingMatchingPlaceholder() {
        val canonical = TimelineItem(
            id = "canonical-phone",
            kind = TimelineKind.USER,
            body = "phone text",
            turnId = "turn-phone",
            clientId = "local-phone",
        )
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-phone"))
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                timeline = listOf(
                    canonical,
                    TimelineItem(id = "local-phone", kind = TimelineKind.USER, body = "phone text"),
                ),
            )
        }.requireApplied()

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ItemUpsert("thread-a", canonical.copy(status = "completed")),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals(listOf("canonical-phone"), routed.registry.sessions.getValue("thread-a").timeline.map(TimelineItem::id))
        assertEquals("completed", routed.registry.sessions.getValue("thread-a").timeline.single().status)
    }

    @Test
    fun missingAndUnknownThreadIdsRejectWithoutChangingCachedSessions() {
        val original = registryWith("thread-a").selectThread("thread-a").requireApplied()

        val missing = SessionEventRouter.route(
            original,
            SessionEvent.Delta(null, "turn-a", "item-a", "ignored", TimelineKind.AGENT),
        )
        assertRejected(missing, SessionDiagnosticCode.MISSING_THREAD_ID)
        assertEquals(original.sessions, missing.registry.sessions)
        assertEquals(original.selectedThreadId, missing.registry.selectedThreadId)
        assertEquals(SessionDiagnosticCode.MISSING_THREAD_ID, missing.registry.diagnostics.last().code)

        val unknown = SessionEventRouter.route(
            missing.registry,
            SessionEvent.TurnStarted("thread-unknown", "turn-unknown"),
        )
        assertRejected(unknown, SessionDiagnosticCode.UNKNOWN_THREAD_ID)
        assertEquals(missing.registry.sessions, unknown.registry.sessions)
        assertEquals(original.selectedThreadId, unknown.registry.selectedThreadId)
        assertFalse("thread-unknown" in unknown.registry.sessions)
        assertEquals(SessionDiagnosticCode.UNKNOWN_THREAD_ID, unknown.registry.diagnostics.last().code)
    }

    @Test
    fun staleTurnCompletionCannotFinishTheActiveTurnOrItsItems() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-current"))
        registry = registry.updateSession("thread-a", markUnread = false) { session ->
            session.copy(
                timeline = listOf(
                    TimelineItem(
                        id = "old-item",
                        kind = TimelineKind.AGENT,
                        body = "old",
                        status = "inProgress",
                        turnId = "turn-old",
                    ),
                    TimelineItem(
                        id = "current-item",
                        kind = TimelineKind.AGENT,
                        body = "current",
                        status = "inProgress",
                        turnId = "turn-current",
                    ),
                ),
            )
        }.requireApplied()
        val beforeStaleCompletion = registry.sessions.getValue("thread-a")

        val stale = SessionEventRouter.route(
            registry,
            SessionEvent.TurnCompleted("thread-a", "turn-old"),
        )
        val staleRejection = assertRejected(stale, SessionDiagnosticCode.STALE_TURN)
        assertFalse(staleRejection.disconnectRecommended)
        val afterStaleCompletion = stale.registry.sessions.getValue("thread-a")
        assertTrue(afterStaleCompletion.isTurnRunning)
        assertEquals("turn-current", afterStaleCompletion.activeTurnId)
        assertEquals(beforeStaleCompletion.timeline, afterStaleCompletion.timeline)

        val completed = SessionEventRouter.route(
            stale.registry,
            SessionEvent.TurnCompleted("thread-a", "turn-current"),
        )
        assertTrue(completed.disposition is SessionRouteDisposition.Applied)
        val session = completed.registry.sessions.getValue("thread-a")
        assertFalse(session.isTurnRunning)
        assertEquals(null, session.activeTurnId)
        assertEquals("inProgress", session.timeline.first { it.id == "old-item" }.status)
        assertEquals("completed", session.timeline.first { it.id == "current-item" }.status)
    }

    @Test
    fun staleTurnFailureCannotFinishTheNewActiveTurn() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-b"))
        val beforeFailure = registry.sessions.getValue("thread-a")

        val stale = SessionEventRouter.route(
            registry,
            AppServerEvent.Failure(
                message = "late failure from turn A",
                threadId = "thread-a",
                turnId = "turn-a",
            ),
        )

        assertRejected(stale, SessionDiagnosticCode.STALE_TURN)
        val afterFailure = stale.registry.sessions.getValue("thread-a")
        assertTrue(afterFailure.isTurnRunning)
        assertEquals("turn-b", afterFailure.activeTurnId)
        assertEquals(beforeFailure.timeline, afterFailure.timeline)
    }

    @Test
    fun malformedFailureTurnIdNeverFallsBackButTrulyAbsentLegacyIdCan() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-pending", "thread-a", "turn-a"),
            ),
        )

        listOf<String?>(null, "", "   ").forEach { malformedTurnId ->
            val malformed = SessionEventRouter.route(
                registry,
                AppServerEvent.Failure(
                    message = "malformed failure identity",
                    threadId = "thread-a",
                    turnId = malformedTurnId,
                    turnIdStatus = AppServerEvent.FailureTurnIdStatus.INVALID,
                ),
            )

            val rejection = assertRejected(malformed, SessionDiagnosticCode.MISSING_TURN_ID)
            assertTrue(rejection.disconnectRecommended)
            val unchanged = malformed.registry.sessions.getValue("thread-a")
            assertTrue(unchanged.isTurnRunning)
            assertEquals("turn-a", unchanged.activeTurnId)
            assertEquals(1, unchanged.approvalQueue.entries.size)
        }

        val legacy = SessionEventRouter.route(
            registry,
            AppServerEvent.Failure(
                message = "legacy failure without a turn id",
                threadId = "thread-a",
                turnId = null,
                turnIdStatus = AppServerEvent.FailureTurnIdStatus.LEGACY_ABSENT,
            ),
        )

        assertTrue(legacy.disposition is SessionRouteDisposition.Applied)
        val failed = legacy.registry.sessions.getValue("thread-a")
        assertFalse(failed.isTurnRunning)
        assertEquals(SessionStreamStatus.FAILED, failed.streamStatus)
        assertTrue(failed.approvalQueue.entries.isEmpty())
    }

    @Test
    fun conflictingTurnStartRejectsWithDisconnectRecommendation() {
        var registry = registryWith("thread-a")
        registry = registry.expectTurn("thread-a", "turn-a").requireApplied()

        val conflictBeforeStart = SessionEventRouter.route(
            registry,
            SessionEvent.TurnStarted("thread-a", "turn-b"),
        )
        val expectedConflict = assertRejected(conflictBeforeStart, SessionDiagnosticCode.STALE_TURN)
        assertTrue(expectedConflict.disconnectRecommended)
        val afterExpectedConflict = conflictBeforeStart.registry.sessions.getValue("thread-a")
        assertTrue(afterExpectedConflict.isTurnRunning)
        assertEquals(null, afterExpectedConflict.activeTurnId)
        assertEquals("turn-a", afterExpectedConflict.expectedTurnId)

        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        val conflictAfterStart = SessionEventRouter.route(
            registry,
            SessionEvent.TurnStarted("thread-a", "turn-b"),
        )
        val activeConflict = assertRejected(conflictAfterStart, SessionDiagnosticCode.STALE_TURN)
        assertTrue(activeConflict.disconnectRecommended)
        val afterActiveConflict = conflictAfterStart.registry.sessions.getValue("thread-a")
        assertTrue(afterActiveConflict.isTurnRunning)
        assertEquals("turn-a", afterActiveConflict.activeTurnId)
        assertEquals("turn-a", afterActiveConflict.expectedTurnId)
    }

    @Test
    fun turnCompletionDropsPendingApprovalsButPreservesRespondingResolutionTracking() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))

        val pending = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-pending", "thread-a", "turn-a"),
            ),
        )
        val pendingKey = requireNotNull(
            (pending.disposition as SessionRouteDisposition.Applied).approvalKey,
        )
        val responding = SessionEventRouter.route(
            pending.registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-responding", "thread-a", "turn-a"),
            ),
        )
        val respondingKey = requireNotNull(
            (responding.disposition as SessionRouteDisposition.Applied).approvalKey,
        )
        registry = responding.registry.markApprovalResponding(respondingKey).requireApplied()

        val completed = SessionEventRouter.route(
            registry,
            SessionEvent.TurnCompleted("thread-a", "turn-a"),
        )

        assertTrue(completed.disposition is SessionRouteDisposition.Applied)
        assertEquals(null, completed.registry.approvalFor(pendingKey))
        assertTrue(completed.registry.approvalFor(respondingKey)?.responding == true)
        assertEquals(
            null,
            completed.registry.sessions.getValue("thread-a")
                .approvalQueue.requestForResponse(respondingKey.queueKey),
        )
        assertFalse(pendingKey in completed.registry.approvalArrivalOrder)
        assertTrue(respondingKey in completed.registry.approvalArrivalOrder)

        val lateApproval = SessionEventRouter.route(
            completed.registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-late", "thread-a", "turn-a"),
            ),
        )
        val rejection = assertRejected(lateApproval, SessionDiagnosticCode.STALE_TURN)
        assertTrue(rejection.disconnectRecommended)
        assertEquals(null, lateApproval.registry.approvalFor(pendingKey))
        assertTrue(lateApproval.registry.approvalFor(respondingKey)?.responding == true)

        val resolved = SessionEventRouter.route(
            lateApproval.registry,
            SessionEvent.ApprovalResolved("thread-a", RpcRequestId.Text("request-responding")),
        )
        assertTrue(resolved.disposition is SessionRouteDisposition.Applied)
        assertEquals(null, resolved.registry.approvalFor(respondingKey))
        assertTrue(resolved.registry.approvalArrivalOrder.isEmpty())
    }

    @Test
    fun turnFailureDropsEveryPendingApprovalForTheTerminalTurn() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-pending", "thread-a", "turn-a"),
            ),
        )

        val failed = SessionEventRouter.route(
            registry,
            SessionEvent.ThreadFailed("thread-a", "turn-a", "remote failure"),
        )

        assertTrue(failed.disposition is SessionRouteDisposition.Applied)
        val session = failed.registry.sessions.getValue("thread-a")
        assertFalse(session.isTurnRunning)
        assertEquals(SessionStreamStatus.FAILED, session.streamStatus)
        assertTrue(session.approvalQueue.entries.isEmpty())
        assertTrue(session.approvalQueue.respondingKeys.isEmpty())
        assertTrue(failed.registry.approvalArrivalOrder.isEmpty())
        assertEquals(null, failed.registry.currentApproval)
    }

    @Test
    fun approvalCallbacksRequireTheOriginalThreadAndQueueKey() {
        var registry = registryWith("thread-a", "thread-b")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-b", "turn-b"))

        val approvalA = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-a", "thread-a", "turn-a"),
            ),
        )
        val keyA = requireNotNull(
            (approvalA.disposition as SessionRouteDisposition.Applied).approvalKey,
        )
        registry = approvalA.registry

        val approvalB = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-b",
                approval("request-b", "thread-b", "turn-b"),
            ),
        )
        val keyB = requireNotNull(
            (approvalB.disposition as SessionRouteDisposition.Applied).approvalKey,
        )
        assertNotEquals(keyA, keyB)
        registry = approvalB.registry
        assertEquals(
            listOf(keyA.queueKey, keyB.queueKey),
            registry.visibleApprovalQueue(ApprovalQueue()).entries.map { it.key },
        )

        val wrongWireResolution = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalResolved("thread-b", RpcRequestId.Text("request-a")),
        )
        val wrongResolution = assertRejected(
            wrongWireResolution,
            SessionDiagnosticCode.OWNERSHIP_MISMATCH,
        )
        assertTrue(wrongResolution.disconnectRecommended)
        assertEquals(1, wrongWireResolution.registry.sessions.getValue("thread-a").approvalQueue.entries.size)
        assertEquals(1, wrongWireResolution.registry.sessions.getValue("thread-b").approvalQueue.entries.size)

        val forgedOwnership = wrongWireResolution.registry.markApprovalResponding(
            SessionApprovalKey("thread-b", keyA.queueKey),
        )
        assertFalse(forgedOwnership.applied)
        assertEquals(SessionDiagnosticCode.OWNERSHIP_MISMATCH, forgedOwnership.rejection?.diagnostic?.code)
        assertTrue(forgedOwnership.registry.sessions.getValue("thread-b").approvalQueue.respondingKeys.isEmpty())

        val responding = forgedOwnership.registry.markApprovalResponding(keyA).requireApplied()
        assertTrue(responding.approvalFor(keyA)?.responding == true)
        assertTrue(responding.approvalFor(keyB)?.responding == false)

        val exactResolution = SessionEventRouter.route(
            responding,
            SessionEvent.ApprovalResolved("thread-a", RpcRequestId.Text("request-a")),
        )
        assertTrue(exactResolution.disposition is SessionRouteDisposition.Applied)
        assertEquals(null, exactResolution.registry.approvalFor(keyA))
        assertNotNull(exactResolution.registry.approvalFor(keyB))
        assertEquals(keyB, exactResolution.registry.currentApproval?.key)
    }

    @Test
    fun approvalWithoutAnActiveTurnIsRejectedFailClosed() {
        val registry = registryWith("thread-a")

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested(
                "thread-a",
                approval("request-a", "thread-a", "turn-a"),
            ),
        )

        val rejection = assertRejected(routed, SessionDiagnosticCode.STALE_TURN)
        assertTrue(rejection.disconnectRecommended)
        assertTrue(routed.registry.sessions.getValue("thread-a").approvalQueue.entries.isEmpty())
    }

    @Test
    fun approvalWithoutATurnIdIsRejectedFailClosed() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))

        listOf<String?>(null, "", "   ").forEachIndexed { index, turnId ->
            val routed = SessionEventRouter.route(
                registry,
                SessionEvent.ApprovalRequested(
                    "thread-a",
                    approval("request-$index", "thread-a", turnId),
                ),
            )

            val rejection = assertRejected(routed, SessionDiagnosticCode.MISSING_TURN_ID)
            assertTrue(rejection.disconnectRecommended)
            assertTrue(routed.registry.sessions.getValue("thread-a").approvalQueue.entries.isEmpty())
        }
    }

    @Test
    fun legacyApprovalWithoutTurnIdBindsOnlyToTheSoleActiveTurn() {
        var registry = registryWith("thread-a")
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        val legacy = approval("legacy-request", "thread-a", null).copy(
            rawMethod = "execCommandApproval",
        )

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested("thread-a", legacy),
        )

        assertTrue(routed.disposition is SessionRouteDisposition.Applied)
        assertEquals(
            "turn-a",
            routed.registry.sessions.getValue("thread-a").approvalQueue.entries.single().request.turnId,
        )
    }

    @Test
    fun legacyApprovalWithoutTurnIdAndActiveTurnIsRejectedFailClosed() {
        val registry = registryWith("thread-a")
        val legacy = approval("legacy-request", "thread-a", null).copy(
            rawMethod = "applyPatchApproval",
        )

        val routed = SessionEventRouter.route(
            registry,
            SessionEvent.ApprovalRequested("thread-a", legacy),
        )

        val rejection = assertRejected(routed, SessionDiagnosticCode.MISSING_TURN_ID)
        assertTrue(rejection.disconnectRecommended)
    }

    @Test
    fun cachedSelectionRestoresEachSessionsIndependentTimeline() {
        var registry = registryWith("thread-a", "thread-b")
        registry = registry.selectThread("thread-a").requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-a", "turn-a"))
        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-a", "turn-a", "item-a", "alpha", TimelineKind.AGENT),
        )
        registry = registry.selectThread("thread-b").requireApplied()
        registry = routeApplied(registry, SessionEvent.TurnStarted("thread-b", "turn-b"))
        registry = routeApplied(
            registry,
            SessionEvent.Delta("thread-b", "turn-b", "item-b", "beta", TimelineKind.AGENT),
        )
        registry = registry.selectThread("thread-a").requireApplied()

        assertEquals("thread-a", registry.selectedSession?.threadId)
        assertEquals("alpha", registry.selectedSession?.timeline?.single()?.body)
        assertEquals("beta", registry.sessions.getValue("thread-b").timeline.single().body)
        assertEquals("turn-a", registry.selectedSession?.activeTurnId)
        assertEquals("turn-b", registry.sessions.getValue("thread-b").activeTurnId)
        assertEquals(0, registry.selectedSession?.unreadCount)
    }

    private fun approval(
        requestId: String,
        threadId: String,
        turnId: String?,
    ): ApprovalRequest = ApprovalRequest(
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

    private fun registryWith(vararg threadIds: String): SessionRegistry {
        var registry = SessionRegistry()
        threadIds.forEach { threadId ->
            registry = registry.registerThread(threadId).requireApplied()
        }
        return registry
    }

    private fun routeApplied(registry: SessionRegistry, event: SessionEvent): SessionRegistry {
        val routed = SessionEventRouter.route(registry, event)
        assertTrue("Expected $event to apply but was ${routed.disposition}", routed.disposition is SessionRouteDisposition.Applied)
        return routed.registry
    }

    private fun assertRejected(
        result: SessionRoutingResult,
        expectedCode: SessionDiagnosticCode,
    ): SessionRejection {
        assertTrue(
            "Expected rejection $expectedCode but was ${result.disposition}",
            result.disposition is SessionRouteDisposition.Rejected,
        )
        val rejection = (result.disposition as SessionRouteDisposition.Rejected).rejection
        assertEquals(expectedCode, rejection.diagnostic.code)
        return rejection
    }

    private fun SessionRegistryMutation.requireApplied(): SessionRegistry {
        assertTrue("Expected registry mutation to apply but was $rejection", applied)
        return registry
    }
}
