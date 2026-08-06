package com.codex.remote.session

import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalQueueKey
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RpcRequestId

internal data class SessionRejection(
    val diagnostic: SessionDiagnostic,
    val disconnectRecommended: Boolean = false,
)

internal data class SessionRegistryMutation(
    val registry: SessionRegistry,
    val rejection: SessionRejection? = null,
) {
    val applied: Boolean get() = rejection == null
}

/**
 * Immutable registry snapshot with centralized aggregate checks for retained [SessionState] changes.
 *
 * The generated [copy] method is an intentional structural bypass because this type is internal.
 * Production code must route retained session mutations through [registerThread],
 * [refreshThreadMetadata], or [updateSession], which validate and commit atomically. [reject]
 * is the sole diagnostic-retention path and trims or drops diagnostics against the same budget.
 */
internal data class SessionRegistry(
    val limits: SessionLimits = SessionLimits(),
    val sessions: Map<String, SessionState> = emptyMap(),
    val selectedThreadId: String? = null,
    val approvalArrivalOrder: List<SessionApprovalKey> = emptyList(),
    val diagnostics: List<SessionDiagnostic> = emptyList(),
    val nextSequence: Long = 1,
) {
    val selectedSession: SessionState?
        get() = selectedThreadId?.let(sessions::get)

    val currentApproval: OwnedApproval?
        get() = approvalArrivalOrder.firstNotNullOfOrNull(::approvalFor)

    fun approvalFor(key: SessionApprovalKey): OwnedApproval? {
        val session = sessions[key.threadId] ?: return null
        val entry = session.approvalQueue.entries.firstOrNull { it.key == key.queueKey } ?: return null
        return OwnedApproval(
            key = key,
            request = entry.request,
            responding = key.queueKey in session.approvalQueue.respondingKeys,
        )
    }

    fun approvalFor(queueKey: ApprovalQueueKey): OwnedApproval? = approvalArrivalOrder
        .asSequence()
        .filter { it.queueKey == queueKey }
        .mapNotNull(::approvalFor)
        .firstOrNull()

    fun visibleApprovalQueue(previous: ApprovalQueue): ApprovalQueue {
        val entries = approvalArrivalOrder.mapNotNull { key ->
            sessions[key.threadId]
                ?.approvalQueue
                ?.entries
                ?.firstOrNull { it.key == key.queueKey }
        }
        val respondingKeys = entries
            .asSequence()
            .map { it.key }
            .filterTo(linkedSetOf()) { key ->
                sessions.values.any { key in it.approvalQueue.respondingKeys }
            }
        return ApprovalQueue(
            entries = entries,
            respondingKeys = respondingKeys,
            queueInstanceId = previous.queueInstanceId,
            nextSequence = previous.nextSequence,
        )
    }

    fun registerThread(thread: RemoteThread): SessionRegistryMutation = registerThread(thread.id, thread)

    fun refreshThreadMetadata(thread: RemoteThread): SessionRegistryMutation {
        if (thread.id.isBlank()) {
            return reject(
                code = SessionDiagnosticCode.MISSING_THREAD_ID,
                message = "Cannot refresh session metadata without an exact thread ID",
            )
        }
        val current = sessions[thread.id]
            ?: return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Cannot refresh metadata for uncached thread ${thread.id}",
                threadId = thread.id,
            )
        return applyRetainedSessionMutation(
            threadId = thread.id,
            transformed = current.copy(thread = thread),
            markUnread = false,
            touchLru = false,
            incrementRevision = current.thread != thread,
            disconnectOnCapacity = false,
        )
    }

    fun registerThread(threadId: String, thread: RemoteThread? = null): SessionRegistryMutation {
        if (threadId.isBlank()) {
            return reject(
                code = SessionDiagnosticCode.MISSING_THREAD_ID,
                message = "Cannot register a session without an exact thread ID",
            )
        }
        val current = sessions[threadId]
        if (current != null) {
            return applyRetainedSessionMutation(
                threadId = threadId,
                transformed = current.copy(thread = thread ?: current.thread),
                markUnread = false,
                touchLru = true,
                incrementRevision = false,
                disconnectOnCapacity = false,
            )
        }

        return applyRetainedSessionMutation(
            threadId = threadId,
            transformed = SessionState(threadId = threadId, thread = thread),
            markUnread = false,
            touchLru = true,
            incrementRevision = false,
            disconnectOnCapacity = false,
        )
    }

    fun selectThread(thread: RemoteThread): SessionRegistryMutation {
        val registered = registerThread(thread)
        if (!registered.applied) return registered
        return registered.registry.selectCachedThread(thread.id)
    }

    fun selectThread(
        threadId: String,
        knownThreadIds: Set<String> = sessions.keys,
    ): SessionRegistryMutation {
        if (threadId.isBlank()) {
            return reject(
                code = SessionDiagnosticCode.MISSING_THREAD_ID,
                message = "Cannot select a session without an exact thread ID",
            )
        }
        val ready = if (threadId in sessions) {
            SessionRegistryMutation(this)
        } else if (threadId in knownThreadIds) {
            registerThread(threadId)
        } else {
            return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Refused to select unknown thread $threadId",
                threadId = threadId,
            )
        }
        if (!ready.applied) return ready
        return ready.registry.selectCachedThread(threadId)
    }

    fun clearSelection(): SessionRegistry = copy(selectedThreadId = null)

    fun expectTurn(threadId: String, turnId: String): SessionRegistryMutation {
        if (turnId.isBlank()) {
            return reject(
                code = SessionDiagnosticCode.MISSING_TURN_ID,
                message = "Cannot bind a pending turn without an exact turn ID",
                threadId = threadId.takeIf(String::isNotBlank),
            )
        }
        val session = sessions[threadId]
            ?: return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Cannot bind a pending turn to unknown thread $threadId",
                threadId = threadId,
            )
        val ownedTurnIds = listOfNotNull(session.expectedTurnId, session.activeTurnId).toSet()
        if (ownedTurnIds.any { it != turnId }) {
            return reject(
                code = SessionDiagnosticCode.STALE_TURN,
                message = "Cannot replace the turn owned by thread $threadId with $turnId",
                threadId = threadId,
            )
        }
        return updateSession(threadId, markUnread = false) {
            it.copy(
                isTurnRunning = true,
                expectedTurnId = turnId,
                streamStatus = SessionStreamStatus.RUNNING,
            )
        }
    }

    fun ensureSessionForEvent(
        threadId: String,
        knownThreadIds: Set<String> = sessions.keys,
    ): SessionRegistryMutation {
        if (threadId in sessions) return SessionRegistryMutation(this)
        if (threadId !in knownThreadIds) {
            return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Refused event for unknown thread $threadId",
                threadId = threadId,
            )
        }
        return registerThread(threadId)
    }

    fun updateSession(
        threadId: String,
        markUnread: Boolean = true,
        transform: (SessionState) -> SessionState,
    ): SessionRegistryMutation {
        val current = sessions[threadId]
            ?: return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Refused mutation for uncached thread $threadId",
                threadId = threadId,
            )
        val transformed = transform(current)
        require(transformed.threadId == threadId) { "Session transforms cannot change thread ownership" }
        return applyRetainedSessionMutation(
            threadId = threadId,
            transformed = transformed,
            markUnread = markUnread,
            touchLru = true,
            incrementRevision = true,
            disconnectOnCapacity = true,
        )
    }

    fun appendApprovalOrder(key: SessionApprovalKey): SessionRegistry = if (key in approvalArrivalOrder) {
        this
    } else {
        copy(approvalArrivalOrder = approvalArrivalOrder + key)
    }

    fun markApprovalResponding(key: SessionApprovalKey): SessionRegistryMutation {
        val owned = approvalFor(key)
            ?: return reject(
                code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                message = "Approval callback did not match its original thread and queue key",
                threadId = key.threadId,
            )
        if (owned.responding) {
            return reject(
                code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                message = "Approval is already being answered",
                threadId = key.threadId,
            )
        }
        return updateSession(key.threadId, markUnread = false) { session ->
            session.copy(approvalQueue = session.approvalQueue.markResponding(key.queueKey))
        }
    }

    fun completeApproval(key: SessionApprovalKey): SessionRegistryMutation {
        if (approvalFor(key) == null) {
            return reject(
                code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                message = "Approval completion did not match its original thread and queue key",
                threadId = key.threadId,
            )
        }
        val updated = updateSession(key.threadId, markUnread = false) { session ->
            session.copy(approvalQueue = session.approvalQueue.complete(key.queueKey))
        }
        if (!updated.applied) return updated
        return updated.copy(
            registry = updated.registry.copy(
                approvalArrivalOrder = updated.registry.approvalArrivalOrder.filterNot { it == key },
            ),
        )
    }

    fun completeApproval(threadId: String, requestId: RpcRequestId): SessionRegistryMutation {
        val session = sessions[threadId]
            ?: return reject(
                code = SessionDiagnosticCode.UNKNOWN_THREAD_ID,
                message = "Approval resolution referenced unknown thread $threadId",
                threadId = threadId,
                disconnectRecommended = true,
            )
        val entry = session.approvalQueue.entries.firstOrNull { it.request.requestId == requestId }
            ?: return reject(
                code = SessionDiagnosticCode.OWNERSHIP_MISMATCH,
                message = "Approval resolution did not match request ${requestId.displayValue} in thread $threadId",
                threadId = threadId,
                disconnectRecommended = true,
            )
        return completeApproval(SessionApprovalKey(threadId, entry.key))
    }

    fun reject(
        code: SessionDiagnosticCode,
        message: String,
        threadId: String? = null,
        disconnectRecommended: Boolean = false,
        touchSession: Boolean = true,
    ): SessionRegistryMutation {
        val sequence = nextSequence
        val diagnostic = SessionDiagnostic(
            code = code,
            message = message.bounded(limits.maxDiagnosticChars),
            threadId = threadId,
            sequence = sequence,
        )
        val cached = threadId?.let(sessions::get)
        val updated = if (cached == null) {
            copy(
                diagnostics = (diagnostics + diagnostic).takeLast(limits.maxDiagnostics),
                nextSequence = sequence + 1,
            )
        } else {
            var retainedDiagnostics = (cached.diagnostics + diagnostic).takeLast(limits.maxDiagnostics)
            var candidate = cached.copy(
                diagnostics = retainedDiagnostics,
                revision = cached.revision + 1,
                lastTouchedSequence = if (touchSession) sequence else cached.lastTouchedSequence,
            )
            while (
                !retainedStateUsageReplacing(threadId, candidate).isWithin(limits) &&
                retainedDiagnostics.isNotEmpty()
            ) {
                retainedDiagnostics = retainedDiagnostics.drop(1)
                candidate = candidate.copy(diagnostics = retainedDiagnostics)
            }
            val retainedNewDiagnostic = retainedDiagnostics.any { it.sequence == diagnostic.sequence }
            val stored = if (retainedNewDiagnostic) {
                candidate
            } else if (touchSession) {
                cached.copy(
                    revision = cached.revision + 1,
                    lastTouchedSequence = sequence,
                )
            } else {
                cached
            }
            copy(
                sessions = sessions.replacing(threadId, stored),
                nextSequence = sequence + 1,
            )
        }
        return SessionRegistryMutation(
            registry = updated,
            rejection = SessionRejection(diagnostic, disconnectRecommended),
        )
    }

    private fun selectCachedThread(threadId: String): SessionRegistryMutation {
        val current = sessions.getValue(threadId)
        val sequence = nextSequence
        val selected = current.copy(unreadCount = 0, lastTouchedSequence = sequence)
        return SessionRegistryMutation(
            copy(
                sessions = sessions.replacing(threadId, selected),
                selectedThreadId = threadId,
                nextSequence = sequence + 1,
            ),
        )
    }

    private fun applyRetainedSessionMutation(
        threadId: String,
        transformed: SessionState,
        markUnread: Boolean,
        touchLru: Boolean,
        incrementRevision: Boolean,
        disconnectOnCapacity: Boolean,
    ): SessionRegistryMutation {
        require(transformed.threadId == threadId) { "Session transforms cannot change thread ownership" }
        if (transformed.timeline.size > limits.maxTimelineItems ||
            transformed.timeline.retainedCharacterCount() > limits.maxTimelineChars
        ) {
            return reject(
                code = SessionDiagnosticCode.TIMELINE_CAPACITY,
                message = "Session timeline exceeded its safe retention limit",
                threadId = threadId,
                disconnectRecommended = disconnectOnCapacity,
                touchSession = false,
            )
        }
        if (!transformed.historyCursorsWithin(limits)) {
            return reject(
                code = SessionDiagnosticCode.HISTORY_CURSOR_CAPACITY,
                message = "Session history cursors exceeded their safe retention limit",
                threadId = threadId,
                disconnectRecommended = disconnectOnCapacity,
                touchSession = false,
            )
        }

        val current = sessions[threadId]
        val sequence = nextSequence
        val stored = transformed.copy(
            unreadCount = if (markUnread && current != null && selectedThreadId != threadId) {
                current.unreadCount.saturatingIncrement(99)
            } else {
                transformed.unreadCount
            },
            revision = if (incrementRevision && current != null) {
                current.revision + 1
            } else {
                transformed.revision
            },
            lastTouchedSequence = if (touchLru) sequence else transformed.lastTouchedSequence,
        )

        var planned = this
        if (current == null) {
            planned = planned.makeRoomForSession()
                ?: return reject(
                    code = SessionDiagnosticCode.SESSION_CAPACITY,
                    message = "All cached sessions are selected or have protected work; refused to evict one",
                    touchSession = false,
                )
        }
        planned = planned.makeRoomForRetainedState(threadId, stored)
            ?: return reject(
                code = SessionDiagnosticCode.RETAINED_STATE_CAPACITY,
                message = "Cached sessions exceeded the aggregate safe retention limit",
                threadId = threadId,
                disconnectRecommended = disconnectOnCapacity,
                touchSession = false,
            )

        return SessionRegistryMutation(
            planned.copy(
                sessions = planned.sessions.replacing(threadId, stored),
                nextSequence = if (touchLru) sequence + 1 else sequence,
            ),
        )
    }

    private fun makeRoomForSession(): SessionRegistry? {
        var candidateRegistry = this
        while (candidateRegistry.sessions.size >= limits.maxSessions) {
            val candidate = candidateRegistry.evictionCandidates().firstOrNull()
                ?: return null
            candidateRegistry = candidateRegistry.evictSession(candidate.threadId)
        }
        return candidateRegistry
    }

    private fun makeRoomForRetainedState(
        threadId: String,
        updated: SessionState,
    ): SessionRegistry? {
        var candidateRegistry = this
        while (!candidateRegistry.retainedStateUsageReplacing(threadId, updated).isWithin(limits)) {
            val candidate = candidateRegistry
                .evictionCandidates(excludingThreadId = threadId)
                .firstOrNull()
                ?: return null
            candidateRegistry = candidateRegistry.evictSession(candidate.threadId)
        }
        return candidateRegistry
    }

    private fun evictionCandidates(excludingThreadId: String? = null): List<SessionState> = sessions.values
        .asSequence()
        .filter { it.threadId != excludingThreadId }
        .filter { it.threadId != selectedThreadId }
        .filterNot { it.hasProtectedWork }
        .sortedWith(compareBy<SessionState> { it.lastTouchedSequence }.thenBy { it.threadId })
        .toList()

    private fun evictSession(threadId: String): SessionRegistry = copy(
        sessions = sessions - threadId,
        approvalArrivalOrder = approvalArrivalOrder.filterNot { it.threadId == threadId },
    )

    private fun retainedStateUsageReplacing(
        threadId: String,
        updated: SessionState,
    ): SessionRetainedState = sessions.replacing(threadId, updated).values.retainedStateUsage()
}

private fun <V> Map<String, V>.replacing(key: String, value: V): Map<String, V> =
    LinkedHashMap(this).also { it[key] = value }

private fun Int.saturatingIncrement(maximum: Int): Int = if (this >= maximum) maximum else this + 1

private fun String.bounded(maxChars: Int): String = if (length <= maxChars) this else take(maxChars - 1) + "…"

private fun SessionRetainedState.isWithin(limits: SessionLimits): Boolean =
    retainedChars <= limits.maxAggregateRetainedChars &&
        timelineItems <= limits.maxAggregateTimelineItems &&
        historyCursors <= limits.maxAggregateHistoryCursors

private fun SessionState.historyCursorsWithin(limits: SessionLimits): Boolean {
    val cursorCount = consumedHistoryCursors.size.toLong().saturatingAdd(
        if (olderHistoryCursor == null) 0L else 1L,
    )
    if (cursorCount > limits.maxHistoryCursors.toLong()) return false
    if (olderHistoryCursor?.length?.let { it > limits.maxHistoryCursorChars } == true) return false
    return consumedHistoryCursors.none { it.length > limits.maxHistoryCursorChars }
}
