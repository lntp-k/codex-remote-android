package com.codex.remote.session

import com.codex.remote.data.rpc.AppServerEvent
import com.codex.remote.domain.ApprovalEnqueueStatus
import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.RemoteThreadSettingsSnapshot
import com.codex.remote.domain.RemoteThreadTokenUsage
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind

internal sealed interface SessionEvent {
    val threadId: String?

    data class ItemUpsert(
        override val threadId: String?,
        val item: TimelineItem,
    ) : SessionEvent

    data class Delta(
        override val threadId: String?,
        val turnId: String?,
        val itemId: String,
        val delta: String,
        val kind: TimelineKind,
    ) : SessionEvent

    data class TurnStarted(
        override val threadId: String?,
        val turnId: String?,
    ) : SessionEvent

    data class TurnCompleted(
        override val threadId: String?,
        val turnId: String?,
    ) : SessionEvent

    data class GoalUpdated(
        override val threadId: String?,
        val goal: ThreadGoal,
    ) : SessionEvent

    data class GoalCleared(override val threadId: String?) : SessionEvent

    data class TokenUsageUpdated(
        override val threadId: String?,
        val usage: RemoteThreadTokenUsage,
    ) : SessionEvent

    data class SettingsUpdated(
        override val threadId: String?,
        val settings: RemoteThreadSettingsSnapshot,
    ) : SessionEvent

    data class ApprovalRequested(
        override val threadId: String?,
        val request: ApprovalRequest,
    ) : SessionEvent

    data class ApprovalResolved(
        override val threadId: String?,
        val requestId: RpcRequestId,
    ) : SessionEvent

    data class ContextCompacted(override val threadId: String?) : SessionEvent

    data class ThreadFailed(
        override val threadId: String?,
        val turnId: String?,
        val message: String,
        val turnIdStatus: AppServerEvent.FailureTurnIdStatus = if (turnId.isNullOrBlank()) {
            AppServerEvent.FailureTurnIdStatus.INVALID
        } else {
            AppServerEvent.FailureTurnIdStatus.EXACT
        },
    ) : SessionEvent
}

internal sealed interface SessionRouteDisposition {
    data class Applied(
        val threadId: String,
        val approvalKey: SessionApprovalKey? = null,
    ) : SessionRouteDisposition

    data class Global(val event: AppServerEvent) : SessionRouteDisposition

    data class Rejected(val rejection: SessionRejection) : SessionRouteDisposition
}

internal data class SessionRoutingResult(
    val registry: SessionRegistry,
    val disposition: SessionRouteDisposition,
)

internal object SessionEventRouter {
    fun route(
        registry: SessionRegistry,
        event: AppServerEvent,
        knownThreadIds: Set<String> = registry.sessions.keys,
    ): SessionRoutingResult = when (event) {
        is AppServerEvent.ItemUpsert -> route(
            registry,
            SessionEvent.ItemUpsert(event.threadId, event.item),
            knownThreadIds,
        )
        is AppServerEvent.AgentDelta -> route(
            registry,
            SessionEvent.Delta(event.threadId, event.turnId, event.itemId, event.delta, TimelineKind.AGENT),
            knownThreadIds,
        )
        is AppServerEvent.PlanDelta -> route(
            registry,
            SessionEvent.Delta(event.threadId, event.turnId, event.itemId, event.delta, TimelineKind.PLAN),
            knownThreadIds,
        )
        is AppServerEvent.ReasoningDelta -> route(
            registry,
            SessionEvent.Delta(event.threadId, event.turnId, event.itemId, event.delta, TimelineKind.REASONING),
            knownThreadIds,
        )
        is AppServerEvent.OutputDelta -> route(
            registry,
            SessionEvent.Delta(event.threadId, event.turnId, event.itemId, event.delta, TimelineKind.COMMAND),
            knownThreadIds,
        )
        is AppServerEvent.TurnRunning -> route(
            registry,
            if (event.running) {
                SessionEvent.TurnStarted(event.threadId, event.turnId)
            } else {
                SessionEvent.TurnCompleted(event.threadId, event.turnId)
            },
            knownThreadIds,
        )
        is AppServerEvent.Approval -> route(
            registry,
            SessionEvent.ApprovalRequested(event.threadId, event.request),
            knownThreadIds,
        )
        is AppServerEvent.ApprovalResolved -> route(
            registry,
            SessionEvent.ApprovalResolved(event.threadId, event.requestId),
            knownThreadIds,
        )
        is AppServerEvent.GoalUpdated -> route(
            registry,
            SessionEvent.GoalUpdated(event.threadId, event.goal),
            knownThreadIds,
        )
        is AppServerEvent.GoalCleared -> route(
            registry,
            SessionEvent.GoalCleared(event.threadId),
            knownThreadIds,
        )
        is AppServerEvent.TokenUsageUpdated -> route(
            registry,
            SessionEvent.TokenUsageUpdated(event.threadId, event.usage),
            knownThreadIds,
        )
        is AppServerEvent.ContextCompacted -> route(
            registry,
            SessionEvent.ContextCompacted(event.threadId),
            knownThreadIds,
        )
        is AppServerEvent.ThreadSettingsUpdated -> route(
            registry,
            SessionEvent.SettingsUpdated(event.threadId, event.settings),
            knownThreadIds,
        )
        is AppServerEvent.Failure -> if (event.threadId == null) {
            SessionRoutingResult(registry, SessionRouteDisposition.Global(event))
        } else {
            route(
                registry,
                SessionEvent.ThreadFailed(
                    threadId = event.threadId,
                    turnId = event.turnId,
                    message = event.message,
                    turnIdStatus = event.turnIdStatus,
                ),
                knownThreadIds,
            )
        }
        AppServerEvent.AccountChanged,
        is AppServerEvent.ThreadStarted,
        AppServerEvent.ThreadsChanged,
        AppServerEvent.SkillsChanged,
        is AppServerEvent.RateLimitsUpdated,
        is AppServerEvent.McpLoginCompleted,
        is AppServerEvent.LoginCompleted,
        is AppServerEvent.FatalProtocolError,
        is AppServerEvent.Warning,
        is AppServerEvent.Diagnostic,
        -> SessionRoutingResult(registry, SessionRouteDisposition.Global(event))
    }

    fun route(
        registry: SessionRegistry,
        event: SessionEvent,
        knownThreadIds: Set<String> = registry.sessions.keys,
    ): SessionRoutingResult {
        val threadId = event.threadId?.takeIf(String::isNotBlank)
            ?: return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.MISSING_THREAD_ID,
                    message = "Refused thread-scoped event without an exact thread ID",
                    disconnectRecommended = event.requiresApprovalOwnership(),
                ),
            )
        val ready = registry.ensureSessionForEvent(threadId, knownThreadIds)
        if (!ready.applied) {
            val rejection = ready.rejection!!
            return rejected(
                if (event.requiresApprovalOwnership() && !rejection.disconnectRecommended) {
                    ready.copy(rejection = rejection.copy(disconnectRecommended = true))
                } else {
                    ready
                },
            )
        }
        val currentRegistry = ready.registry
        val session = currentRegistry.sessions.getValue(threadId)
        return when (event) {
            is SessionEvent.ItemUpsert -> applyItemUpsert(currentRegistry, session, event)
            is SessionEvent.Delta -> applyDelta(currentRegistry, session, event)
            is SessionEvent.TurnStarted -> applyTurnStarted(currentRegistry, session, event)
            is SessionEvent.TurnCompleted -> applyTurnCompleted(currentRegistry, session, event)
            is SessionEvent.GoalUpdated -> applyGoalUpdated(currentRegistry, session, event)
            is SessionEvent.GoalCleared -> applied(
                currentRegistry.updateSession(threadId) { it.copy(goal = null) },
                threadId,
            )
            is SessionEvent.TokenUsageUpdated -> applied(
                currentRegistry.updateSession(threadId) { it.copy(tokenUsage = event.usage) },
                threadId,
            )
            is SessionEvent.SettingsUpdated -> applied(
                currentRegistry.updateSession(threadId) {
                    it.copy(settings = it.settings.merge(event.settings))
                },
                threadId,
            )
            is SessionEvent.ApprovalRequested -> applyApproval(currentRegistry, session, event)
            is SessionEvent.ApprovalResolved -> applied(
                currentRegistry.completeApproval(threadId, event.requestId),
                threadId,
            )
            is SessionEvent.ContextCompacted -> applyContextCompacted(currentRegistry, session)
            is SessionEvent.ThreadFailed -> applyThreadFailure(currentRegistry, session, event)
        }
    }

    private fun applyItemUpsert(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.ItemUpsert,
    ): SessionRoutingResult {
        val turnId = event.item.turnId?.takeIf(String::isNotBlank)
            ?: return missingTurn(registry, session.threadId, "Timeline item ${event.item.id}")
        if (event.item.id.isBlank()) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                    message = "Refused timeline item without an exact item ID",
                    threadId = session.threadId,
                ),
            )
        }
        if (!session.acceptsTurn(turnId)) {
            return staleTurn(registry, session, turnId, "timeline item")
        }
        val index = session.timeline.indexOfFirst { existing ->
            existing.id == event.item.id && existing.turnId == turnId
        }
        val localUserIndex = if (event.item.kind == TimelineKind.USER) {
            session.timeline.indexOfLast { existing ->
                existing.id.startsWith("local-") &&
                    existing.kind == TimelineKind.USER &&
                    existing.body == event.item.body
            }
        } else {
            -1
        }
        val timeline = if (index < 0 && localUserIndex >= 0) {
            session.timeline.toMutableList().also { it[localUserIndex] = event.item }
        } else if (index < 0) {
            session.timeline + event.item
        } else {
            session.timeline.toMutableList().also { it[index] = event.item }
        }
        return applyTimeline(registry, session, timeline) { updated ->
            updated.copy(
                timeline = timeline,
                approvalQueue = updated.approvalQueue.bindFileChangeSnapshot(session.threadId, event.item),
            )
        }
    }

    private fun applyDelta(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.Delta,
    ): SessionRoutingResult {
        val turnId = event.turnId?.takeIf(String::isNotBlank)
            ?: return missingTurn(registry, session.threadId, "Delta for item ${event.itemId}")
        if (event.itemId.isBlank()) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                    message = "Refused delta without an exact item ID",
                    threadId = session.threadId,
                ),
            )
        }
        if (!session.acceptsTurn(turnId)) {
            return staleTurn(registry, session, turnId, "delta")
        }
        val index = session.timeline.indexOfFirst { it.id == event.itemId && it.turnId == turnId }
        val timeline = if (index < 0) {
            session.timeline + TimelineItem(
                id = event.itemId,
                kind = event.kind,
                body = event.delta,
                status = "inProgress",
                turnId = turnId,
            )
        } else {
            session.timeline.toMutableList().also { items ->
                val current = items[index]
                items[index] = current.copy(body = current.body + event.delta)
            }
        }
        return applyTimeline(registry, session, timeline) { it.copy(timeline = timeline) }
    }

    private fun applyTurnStarted(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.TurnStarted,
    ): SessionRoutingResult {
        val turnId = event.turnId?.takeIf(String::isNotBlank)
            ?: return missingTurn(registry, session.threadId, "Turn start")
        val ownedTurnIds = listOfNotNull(session.expectedTurnId, session.activeTurnId).toSet()
        if (ownedTurnIds.any { it != turnId }) {
            return staleTurn(
                registry,
                session,
                turnId,
                "turn start",
                disconnectRecommended = true,
            )
        }
        return applied(
            registry.updateSession(session.threadId) {
                it.copy(
                    isTurnRunning = true,
                    activeTurnId = turnId,
                    expectedTurnId = turnId,
                    streamStatus = SessionStreamStatus.RUNNING,
                )
            },
            session.threadId,
        )
    }

    private fun applyTurnCompleted(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.TurnCompleted,
    ): SessionRoutingResult {
        val turnId = event.turnId?.takeIf(String::isNotBlank)
            ?: return missingTurn(registry, session.threadId, "Turn completion")
        if (!session.acceptsTurn(turnId)) {
            return staleTurn(registry, session, turnId, "turn completion")
        }
        return applied(
            registry.updateTerminalSession(session) {
                it.copy(
                    timeline = it.timeline.completeRunningItems(turnId, "completed"),
                    isTurnRunning = false,
                    activeTurnId = null,
                    expectedTurnId = null,
                    streamStatus = SessionStreamStatus.IDLE,
                )
            },
            session.threadId,
        )
    }

    private fun applyGoalUpdated(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.GoalUpdated,
    ): SessionRoutingResult {
        if (event.goal.threadId != session.threadId) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                    message = "Goal ownership did not match thread ${session.threadId}",
                    threadId = session.threadId,
                ),
            )
        }
        return applied(
            registry.updateSession(session.threadId) { it.copy(goal = event.goal) },
            session.threadId,
        )
    }

    private fun applyApproval(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.ApprovalRequested,
    ): SessionRoutingResult {
        val request = event.request
        if (request.threadId != session.threadId || request.requestId.displayValue.isBlank()) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                    message = "Approval request ownership did not match thread ${session.threadId}",
                    threadId = session.threadId,
                    disconnectRecommended = true,
                ),
            )
        }
        val exactRequestTurnId = request.turnId?.takeIf(String::isNotBlank)
        val requestTurnId = exactRequestTurnId ?: if (
            request.rawMethod in LEGACY_APPROVAL_METHODS && session.isTurnRunning
        ) {
            session.currentTurnIds().singleOrNull()
        } else {
            null
        }
        if (requestTurnId == null) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.MISSING_TURN_ID,
                    message = "Approval request could not be bound to one exact active turn",
                    threadId = session.threadId,
                    disconnectRecommended = true,
                ),
            )
        }
        if (!session.acceptsTurn(requestTurnId)) {
            return staleTurn(registry, session, requestTurnId, "approval", disconnectRecommended = true)
        }
        val ownedRequest = if (exactRequestTurnId == null) request.copy(turnId = requestTurnId) else request
        val bound = ownedRequest.bindFileChangesSnapshot(session.timeline, session.threadId)
        val enqueue = session.approvalQueue.enqueueResult(bound)
        if (enqueue.status != ApprovalEnqueueStatus.ENQUEUED) {
            val code = if (enqueue.status == ApprovalEnqueueStatus.DUPLICATE_ACTIVE_ID) {
                SessionDiagnosticCode.DUPLICATE_APPROVAL_ID
            } else {
                SessionDiagnosticCode.APPROVAL_CAPACITY
            }
            return rejected(
                registry.reject(
                    code = code,
                    message = "Approval queue rejected request ${request.requestId.displayValue}",
                    threadId = session.threadId,
                    disconnectRecommended = true,
                ),
            )
        }
        val key = SessionApprovalKey(session.threadId, enqueue.queue.entries.last().key)
        val updated = registry.updateSession(session.threadId) {
            it.copy(approvalQueue = enqueue.queue)
        }
        if (!updated.applied) return rejected(updated)
        return SessionRoutingResult(
            registry = updated.registry.appendApprovalOrder(key),
            disposition = SessionRouteDisposition.Applied(session.threadId, key),
        )
    }

    private fun applyContextCompacted(
        registry: SessionRegistry,
        session: SessionState,
    ): SessionRoutingResult {
        val marker = TimelineItem(
            id = "compaction-${registry.nextSequence}",
            kind = TimelineKind.COMPACTION,
            title = "Context compacted",
        )
        val timeline = session.timeline + marker
        val diagnostic = SessionDiagnostic(
            code = SessionDiagnosticCode.CONTEXT_COMPACTED,
            message = "Task context compacted",
            threadId = session.threadId,
            sequence = registry.nextSequence,
        )
        return applyTimeline(registry, session, timeline) {
            it.copy(
                timeline = timeline,
                diagnostics = (it.diagnostics + diagnostic).takeLast(registry.limits.maxDiagnostics),
            )
        }
    }

    private fun applyThreadFailure(
        registry: SessionRegistry,
        session: SessionState,
        event: SessionEvent.ThreadFailed,
    ): SessionRoutingResult {
        val turnId = when (event.turnIdStatus) {
            AppServerEvent.FailureTurnIdStatus.EXACT ->
                event.turnId?.takeIf(String::isNotBlank)
                    ?: return missingTurn(
                        registry,
                        session.threadId,
                        "Thread failure",
                        disconnectRecommended = true,
                    )
            AppServerEvent.FailureTurnIdStatus.LEGACY_ABSENT ->
                session.currentTurnIds().singleOrNull()
                    ?: return missingTurn(registry, session.threadId, "Legacy thread failure")
            AppServerEvent.FailureTurnIdStatus.INVALID ->
                return missingTurn(
                    registry,
                    session.threadId,
                    "Thread failure with an invalid turn ID",
                    disconnectRecommended = true,
                )
        }
        if (!session.acceptsTurn(turnId)) {
            return staleTurn(registry, session, turnId, "thread failure")
        }
        val diagnostic = SessionDiagnostic(
            code = SessionDiagnosticCode.THREAD_FAILURE,
            message = event.message.bounded(registry.limits.maxDiagnosticChars),
            threadId = session.threadId,
            sequence = registry.nextSequence,
        )
        return applied(
            registry.updateTerminalSession(session) {
                it.copy(
                    timeline = it.timeline.completeRunningItems(turnId, "failed"),
                    isTurnRunning = false,
                    activeTurnId = null,
                    expectedTurnId = null,
                    streamStatus = SessionStreamStatus.FAILED,
                    diagnostics = (it.diagnostics + diagnostic).takeLast(registry.limits.maxDiagnostics),
                )
            },
            session.threadId,
        )
    }

    private fun applyTimeline(
        registry: SessionRegistry,
        session: SessionState,
        timeline: List<TimelineItem>,
        transform: (SessionState) -> SessionState,
    ): SessionRoutingResult {
        if (timeline.size > registry.limits.maxTimelineItems ||
            timeline.retainedCharacterCount() > registry.limits.maxTimelineChars
        ) {
            return rejected(
                registry.reject(
                    code = SessionDiagnosticCode.TIMELINE_CAPACITY,
                    message = "Session timeline exceeded its safe retention limit",
                    threadId = session.threadId,
                    disconnectRecommended = true,
                ),
            )
        }
        return applied(
            registry.updateSession(session.threadId) { transform(it) },
            session.threadId,
        )
    }

    private fun missingTurn(
        registry: SessionRegistry,
        threadId: String,
        context: String,
        disconnectRecommended: Boolean = false,
    ): SessionRoutingResult = rejected(
        registry.reject(
            code = SessionDiagnosticCode.MISSING_TURN_ID,
            message = "$context did not include an exact turn ID",
            threadId = threadId,
            disconnectRecommended = disconnectRecommended,
        ),
    )

    private fun staleTurn(
        registry: SessionRegistry,
        session: SessionState,
        receivedTurnId: String,
        context: String,
        disconnectRecommended: Boolean = false,
    ): SessionRoutingResult = rejected(
        registry.reject(
            code = SessionDiagnosticCode.STALE_TURN,
            message = "Refused $context for stale turn $receivedTurnId; active turn is " +
                session.currentTurnIds().joinToString().ifBlank { "unknown" },
            threadId = session.threadId,
            disconnectRecommended = disconnectRecommended,
        ),
    )

    private fun applied(
        mutation: SessionRegistryMutation,
        threadId: String,
    ): SessionRoutingResult = if (mutation.applied) {
        SessionRoutingResult(mutation.registry, SessionRouteDisposition.Applied(threadId))
    } else {
        rejected(mutation)
    }

    private fun rejected(mutation: SessionRegistryMutation): SessionRoutingResult = SessionRoutingResult(
        mutation.registry,
        SessionRouteDisposition.Rejected(requireNotNull(mutation.rejection)),
    )
}

private val LEGACY_APPROVAL_METHODS = setOf("execCommandApproval", "applyPatchApproval")

private fun SessionRegistry.updateTerminalSession(
    session: SessionState,
    transform: (SessionState) -> SessionState,
): SessionRegistryMutation {
    val approvalQueue = session.approvalQueue.retainRespondingOnly()
    val retainedKeys = approvalQueue.entries.mapTo(hashSetOf()) { it.key }
    val updated = updateSession(session.threadId) { current ->
        transform(current).copy(approvalQueue = approvalQueue)
    }
    if (!updated.applied) return updated
    return updated.copy(
        registry = updated.registry.copy(
            approvalArrivalOrder = updated.registry.approvalArrivalOrder.filter { key ->
                key.threadId != session.threadId || key.queueKey in retainedKeys
            },
        ),
    )
}

private fun ApprovalQueue.retainRespondingOnly(): ApprovalQueue {
    val retainedEntries = entries.filter { it.key in respondingKeys }
    val retainedKeys = retainedEntries.mapTo(linkedSetOf()) { it.key }
    return copy(
        entries = retainedEntries,
        respondingKeys = retainedKeys,
    )
}

private fun SessionEvent.requiresApprovalOwnership(): Boolean =
    this is SessionEvent.ApprovalRequested || this is SessionEvent.ApprovalResolved

private fun SessionState.currentTurnIds(): Set<String> =
    listOfNotNull(expectedTurnId, activeTurnId).filter(String::isNotBlank).toSet()

private fun SessionState.acceptsTurn(turnId: String): Boolean =
    isTurnRunning && currentTurnIds().isNotEmpty() && currentTurnIds().all { it == turnId }

private fun List<TimelineItem>.completeRunningItems(turnId: String, terminalStatus: String): List<TimelineItem> =
    map { item ->
        if (item.turnId == turnId && item.status in setOf("inProgress", "running", "started")) {
            item.copy(status = terminalStatus)
        } else {
            item
        }
    }

private fun String.bounded(maxChars: Int): String = if (length <= maxChars) this else take(maxChars - 1) + "…"
