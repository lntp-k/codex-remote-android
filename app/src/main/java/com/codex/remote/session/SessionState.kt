package com.codex.remote.session

import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalQueueKey
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RemoteThreadSettingsSnapshot
import com.codex.remote.domain.RemoteThreadTokenUsage
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.ThreadGoalStatus
import com.codex.remote.domain.TimelineItem

internal data class SessionLimits(
    val maxSessions: Int = 32,
    val maxDiagnostics: Int = 32,
    val maxDiagnosticChars: Int = 4_096,
    val maxTimelineItems: Int = 512,
    val maxTimelineChars: Long = 4L * 1024L * 1024L,
    val maxAggregateRetainedChars: Long = 16L * 1024L * 1024L,
    val maxAggregateTimelineItems: Long = 2_048,
    val maxAggregateHistoryCursors: Long = 512,
    val maxHistoryCursors: Int = 128,
    val maxHistoryCursorChars: Int = 2_048,
) {
    init {
        require(maxSessions > 0)
        require(maxDiagnostics > 0)
        require(maxDiagnosticChars > 0)
        require(maxTimelineItems > 0)
        require(maxTimelineChars > 0)
        require(maxAggregateRetainedChars > 0)
        require(maxAggregateTimelineItems > 0)
        require(maxAggregateHistoryCursors > 0)
        require(maxHistoryCursors > 0)
        require(maxHistoryCursorChars > 0)
    }
}

internal enum class SessionStreamStatus {
    IDLE,
    RUNNING,
    FAILED,
}

internal data class SessionSettings(
    val model: String? = null,
    val reasoningEffort: String? = null,
    val serviceTier: String? = null,
    val collaborationMode: String? = null,
    val permissionProfile: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
) {
    fun merge(snapshot: RemoteThreadSettingsSnapshot): SessionSettings = copy(
        model = snapshot.model ?: model,
        reasoningEffort = snapshot.reasoningEffort ?: reasoningEffort,
        serviceTier = snapshot.serviceTier,
        collaborationMode = snapshot.collaborationMode ?: collaborationMode,
        permissionProfile = snapshot.permissionProfile,
        approvalPolicy = snapshot.approvalPolicy ?: approvalPolicy,
        approvalsReviewer = snapshot.approvalsReviewer ?: approvalsReviewer,
    )
}

internal enum class SessionDiagnosticCode {
    MISSING_THREAD_ID,
    UNKNOWN_THREAD_ID,
    MISSING_TURN_ID,
    STALE_TURN,
    OWNERSHIP_MISMATCH,
    DUPLICATE_APPROVAL_ID,
    APPROVAL_CAPACITY,
    SESSION_CAPACITY,
    TIMELINE_CAPACITY,
    HISTORY_CURSOR_CAPACITY,
    RETAINED_STATE_CAPACITY,
    CONTEXT_COMPACTED,
    THREAD_FAILURE,
}

internal data class SessionDiagnostic(
    val code: SessionDiagnosticCode,
    val message: String,
    val threadId: String? = null,
    val sequence: Long = 0,
)

internal data class SessionApprovalKey(
    val threadId: String,
    val queueKey: ApprovalQueueKey,
)

internal data class OwnedApproval(
    val key: SessionApprovalKey,
    val request: ApprovalRequest,
    val responding: Boolean,
)

internal data class SessionState(
    val threadId: String,
    val thread: RemoteThread? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val olderHistoryCursor: String? = null,
    val hasOlderHistory: Boolean = false,
    val isOlderHistoryLoading: Boolean = false,
    val olderHistoryError: String? = null,
    val consumedHistoryCursors: Set<String> = emptySet(),
    val goal: ThreadGoal? = null,
    val tokenUsage: RemoteThreadTokenUsage? = null,
    val settings: SessionSettings = SessionSettings(),
    val isTurnRunning: Boolean = false,
    val activeTurnId: String? = null,
    val expectedTurnId: String? = null,
    val approvalQueue: ApprovalQueue = ApprovalQueue(),
    val streamStatus: SessionStreamStatus = SessionStreamStatus.IDLE,
    val unreadCount: Int = 0,
    val diagnostics: List<SessionDiagnostic> = emptyList(),
    val revision: Long = 0,
    val lastTouchedSequence: Long = 0,
) {
    init {
        require(threadId.isNotBlank())
    }

    val hasProtectedWork: Boolean
        get() = isTurnRunning || approvalQueue.entries.isNotEmpty() ||
            goal?.status?.let { it != ThreadGoalStatus.COMPLETE } == true
}

internal data class SessionRetainedState(
    val retainedChars: Long = 0,
    val timelineItems: Long = 0,
    val historyCursors: Long = 0,
) {
    fun saturatingAdd(other: SessionRetainedState): SessionRetainedState = SessionRetainedState(
        retainedChars = retainedChars.saturatingAdd(other.retainedChars),
        timelineItems = timelineItems.saturatingAdd(other.timelineItems),
        historyCursors = historyCursors.saturatingAdd(other.historyCursors),
    )
}

internal fun Iterable<SessionState>.retainedStateUsage(): SessionRetainedState =
    fold(SessionRetainedState()) { total, session ->
        total.saturatingAdd(session.retainedStateUsage())
    }

internal fun SessionState.retainedStateUsage(): SessionRetainedState {
    var historyCursorCount = 0L
    if (olderHistoryCursor != null) {
        historyCursorCount = historyCursorCount.saturatingAdd(1L)
    }
    consumedHistoryCursors.forEach {
        historyCursorCount = historyCursorCount.saturatingAdd(1L)
    }

    return SessionRetainedState(
        retainedChars = retainedCharacterCount(),
        timelineItems = timeline.size.toLong(),
        historyCursors = historyCursorCount,
    )
}

internal fun SessionState.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(threadId)
    retained = retained.saturatingAdd(thread?.retainedCharacterCount() ?: 0L)
    retained = retained.saturatingAdd(timeline.retainedCharacterCount())
    retained = retained.saturatingAdd(olderHistoryCursor)
    consumedHistoryCursors.forEach { cursor -> retained = retained.saturatingAdd(cursor) }
    retained = retained.saturatingAdd(olderHistoryError)
    retained = retained.saturatingAdd(goal?.retainedCharacterCount() ?: 0L)
    retained = retained.saturatingAdd(settings.retainedCharacterCount())
    retained = retained.saturatingAdd(activeTurnId)
    retained = retained.saturatingAdd(expectedTurnId)
    retained = retained.saturatingAdd(approvalQueue.retainedCharacterCount())
    diagnostics.forEach { diagnostic ->
        retained = retained.saturatingAdd(diagnostic.retainedCharacterCount())
    }
    return retained
}

internal fun List<TimelineItem>.retainedCharacterCount(): Long = fold(0L) { total, item ->
    total.saturatingAdd(item.retainedCharacterCount())
}

internal fun TimelineItem.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(id)
    retained = retained.saturatingAdd(title)
    retained = retained.saturatingAdd(body)
    retained = retained.saturatingAdd(status)
    retained = retained.saturatingAdd(turnId)
    fileChanges.forEach { change ->
        retained = retained.saturatingAdd(change.path)
        retained = retained.saturatingAdd(change.kind)
        retained = retained.saturatingAdd(change.diff)
        retained = retained.saturatingAdd(change.movePath)
    }
    return retained
}

private fun RemoteThread.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(id)
    retained = retained.saturatingAdd(title)
    retained = retained.saturatingAdd(cwd)
    retained = retained.saturatingAdd(status)
    return retained
}

private fun ThreadGoal.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(threadId)
    retained = retained.saturatingAdd(objective)
    return retained
}

private fun SessionSettings.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(model)
    retained = retained.saturatingAdd(reasoningEffort)
    retained = retained.saturatingAdd(serviceTier)
    retained = retained.saturatingAdd(collaborationMode)
    retained = retained.saturatingAdd(permissionProfile)
    retained = retained.saturatingAdd(approvalPolicy)
    retained = retained.saturatingAdd(approvalsReviewer)
    return retained
}

// Queue keys reuse the queue/request ID objects; count each peer-controlled request payload once.
private fun ApprovalQueue.retainedCharacterCount(): Long = entries.fold(0L) { total, entry ->
    total.saturatingAdd(entry.request.retainedCharCount)
}

private fun SessionDiagnostic.retainedCharacterCount(): Long {
    var retained = 0L
    retained = retained.saturatingAdd(message)
    retained = retained.saturatingAdd(threadId)
    return retained
}

private fun Long.saturatingAdd(value: String?): Long {
    val chars = value?.length?.toLong() ?: return this
    return saturatingAdd(chars)
}

internal fun Long.saturatingAdd(value: Long): Long =
    if (value < 0L || this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
