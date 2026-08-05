package com.codex.remote.domain

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

@Serializable
enum class AuthType { PASSWORD, PRIVATE_KEY }

@Serializable
enum class RemotePlatform { AUTO, POSIX, WINDOWS }

@Serializable
data class SavedConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val encryptedPassword: String = "",
    val encryptedPrivateKey: String = "",
    val encryptedPassphrase: String = "",
    val hostKeyFingerprint: String = "",
    val platform: RemotePlatform = RemotePlatform.AUTO,
    val lastUsedAt: Long = 0,
)

data class ConnectionDraft(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val hostKeyFingerprint: String = "",
    val clearHostKeyFingerprint: Boolean = false,
    val platform: RemotePlatform = RemotePlatform.AUTO,
)

internal enum class ConnectionDraftIssue {
    CONNECTION_NAME,
    HOST,
    PORT,
    USERNAME,
    PASSWORD,
    PRIVATE_KEY,
}

internal fun ConnectionDraft.validationIssues(existing: SavedConnection? = null): List<ConnectionDraftIssue> = buildList {
    if (name.isBlank()) add(ConnectionDraftIssue.CONNECTION_NAME)
    if (host.isBlank()) add(ConnectionDraftIssue.HOST)
    if (port.toIntOrNull()?.let { it in 1..65535 } != true) add(ConnectionDraftIssue.PORT)
    if (username.isBlank()) add(ConnectionDraftIssue.USERNAME)
    if (authType == AuthType.PASSWORD && password.isBlank() && existing?.encryptedPassword.isNullOrBlank()) {
        add(ConnectionDraftIssue.PASSWORD)
    }
    if (authType == AuthType.PRIVATE_KEY && privateKey.isBlank() && existing?.encryptedPrivateKey.isNullOrBlank()) {
        add(ConnectionDraftIssue.PRIVATE_KEY)
    }
}

data class ConnectionSecrets(
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
)

data class RemoteThread(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long,
    val status: String,
    val isPinned: Boolean = false,
)

data class RemoteProject(
    val id: String,
    val name: String,
    val path: String,
    val threads: List<RemoteThread>,
    val updatedAt: Long,
)

data class RemotePathEntry(
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
)

data class RemoteSkill(
    val name: String,
    val displayName: String,
    val description: String,
    val path: String,
    val enabled: Boolean,
    val cwds: Set<String>,
)

data class RemotePlugin(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val marketplace: String,
    val mentionPath: String,
    val enabled: Boolean,
)

enum class ComposerMentionKind { SKILL, PLUGIN }

data class ComposerMention(
    val kind: ComposerMentionKind,
    val name: String,
    val path: String,
    val token: String,
)

internal fun groupThreadsByProject(threads: List<RemoteThread>): List<RemoteProject> = threads
    .groupBy { remoteProjectIdentity(it.cwd) }
    .map { (identity, projectThreads) ->
        val sortedThreads = projectThreads.sortedWith(
            compareByDescending<RemoteThread> { it.isPinned }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        )
        val path = normalizeRemoteProjectPath(sortedThreads.firstOrNull()?.cwd.orEmpty())
        RemoteProject(
            id = identity,
            name = remoteProjectName(path),
            path = path,
            threads = sortedThreads,
            updatedAt = sortedThreads.maxOfOrNull { it.updatedAt } ?: 0,
        )
    }
    .sortedWith(compareByDescending<RemoteProject> { it.updatedAt }.thenBy { it.name.lowercase(Locale.ROOT) })

internal fun normalizeRemoteProjectPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == "/") return trimmed
    if (trimmed.length == 3 && trimmed[1] == ':' && (trimmed[2] == '\\' || trimmed[2] == '/')) {
        return trimmed
    }
    return trimmed.trimEnd('/', '\\')
}

private fun remoteProjectIdentity(path: String): String {
    val normalized = normalizeRemoteProjectPath(path)
    if (normalized.isEmpty()) return "unknown"
    val windowsPath = normalized.indexOf('\\') >= 0 || (normalized.length >= 2 && normalized[1] == ':')
    val identity = normalized.replace('\\', '/')
    return if (windowsPath) identity.lowercase(Locale.ROOT) else identity
}

private fun remoteProjectName(path: String): String {
    if (path.isEmpty()) return "Unknown workspace"
    if (path == "/" || (path.length == 3 && path[1] == ':')) return path
    val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return path.substring(separator + 1).ifBlank { path }
}

data class RemoteModel(
    val id: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val hidden: Boolean = false,
    val supportedReasoningEfforts: List<ReasoningEffortOption> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val inputModalities: Set<String> = emptySet(),
    val serviceTiers: List<RemoteServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

data class ReasoningEffortOption(
    val value: String,
    val description: String = "",
)

data class RemoteServiceTier(
    val id: String,
    val name: String,
    val description: String = "",
)

data class RemoteCollaborationMode(
    val name: String,
    val mode: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
)

data class RemotePermissionProfile(
    val id: String,
    val description: String,
    val allowed: Boolean,
)

data class RemoteThreadSettingsSnapshot(
    val model: String?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    val collaborationMode: String?,
    val permissionProfile: String?,
    val approvalPolicy: String?,
    val approvalsReviewer: String?,
)

data class ComposerImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val mimeType: String,
    val dataUrl: String,
)

data class RemoteServerInfo(
    val userAgent: String,
    val codexHome: String,
    val platformFamily: String,
    val platformOs: String,
    val codexVersion: String,
)

data class RemoteAccount(
    val type: String?,
    val email: String?,
    val planType: String?,
    val requiresOpenaiAuth: Boolean,
) {
    val canRunCodex: Boolean get() = !requiresOpenaiAuth || type != null
}

data class RemoteDeviceLogin(
    val loginId: String,
    val verificationUrl: String,
    val userCode: String,
)

data class RemoteThreadSession(
    val timeline: List<TimelineItem>,
    val model: String?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    val cwd: String,
    val olderHistoryCursor: String? = null,
    val collaborationMode: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
    val permissionProfile: String? = null,
)

data class RemoteThreadHistoryPage(
    val timeline: List<TimelineItem>,
    val nextCursor: String?,
)

data class StartedRemoteThread(
    val id: String,
    val model: String?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    val cwd: String,
)

data class ForkedRemoteThread(
    val thread: RemoteThread,
    val session: RemoteThreadSession,
)

enum class ReviewTargetKind { UNCOMMITTED_CHANGES, BASE_BRANCH, CUSTOM }

data class StartedRemoteReview(
    val turnId: String,
    val threadId: String,
)

data class RemoteMcpServerStatus(
    val name: String,
    val authStatus: String,
    val toolCount: Int,
    val resourceCount: Int,
)

data class RateLimitWindowSnapshot(
    val usedPercent: Double,
    val windowDurationMinutes: Long?,
    val resetsAt: Long?,
)

data class RemoteRateLimits(
    val limitName: String?,
    val planType: String?,
    val primary: RateLimitWindowSnapshot?,
    val secondary: RateLimitWindowSnapshot?,
    val creditsBalance: String?,
    val creditsUnlimited: Boolean,
)

data class RemoteThreadTokenUsage(
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val modelContextWindow: Long?,
)

enum class ThreadGoalStatus {
    ACTIVE,
    PAUSED,
    BLOCKED,
    USAGE_LIMITED,
    BUDGET_LIMITED,
    COMPLETE,
}

data class ThreadGoal(
    val threadId: String,
    val objective: String,
    val status: ThreadGoalStatus,
    val tokenBudget: Long?,
    val tokensUsed: Long,
    val timeUsedSeconds: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class TimelineKind {
    USER,
    AGENT,
    REASONING,
    PLAN,
    COMMAND,
    FILE_CHANGE,
    TOOL,
    REVIEW,
    COMPACTION,
    ERROR,
}

data class TimelineItem(
    val id: String,
    val kind: TimelineKind,
    val title: String = "",
    val body: String = "",
    val status: String = "",
    val expanded: Boolean = false,
    val fileChanges: List<FileChangeSummary> = emptyList(),
    val isGoal: Boolean = false,
    val turnId: String? = null,
    val fileChangesComplete: Boolean = true,
)

data class FileChangeSummary(
    val path: String,
    val kind: String,
    val diff: String,
    val movePath: String? = null,
)

data class ApprovalFileItemKey(
    val threadId: String,
    val turnId: String,
    val itemId: String,
)

internal const val FILE_CHANGE_PREVIEW_MAX_FILES = 200
internal const val FILE_CHANGE_PREVIEW_MAX_LINES = 200
internal const val FILE_CHANGE_PREVIEW_MAX_CHARS = 32_768
internal const val FILE_CHANGE_TARGET_MAX_CHARS = 4_096
internal const val FILE_CHANGE_TARGETS_MAX_CHARS = 32_768

internal data class BoundedFileChangePreview(
    val path: String,
    val movePath: String?,
    val kind: String,
    val diff: String,
    val targetTruncated: Boolean,
    val diffTruncated: Boolean,
)

internal data class AggregateFileChangePreview(
    val files: List<BoundedFileChangePreview>,
    val hiddenTargetCount: Int,
    val renderedLineCount: Int,
    val renderedCharCount: Int,
    val renderedTargetCharCount: Int,
) {
    val targetsTruncated: Boolean get() = hiddenTargetCount > 0
    val targetTruncated: Boolean get() = files.any { it.targetTruncated }
    val diffTruncated: Boolean get() = files.any { it.diffTruncated }
    val fullyReviewable: Boolean get() = !targetsTruncated && !targetTruncated && !diffTruncated
}

private data class BoundedDiffPreview(
    val text: String,
    val lineCount: Int,
    val truncated: Boolean,
)

internal fun aggregateFileChangePreview(
    changes: List<FileChangeSummary>,
): AggregateFileChangePreview {
    val visibleFileCount = minOf(changes.size, FILE_CHANGE_PREVIEW_MAX_FILES)
    val files = ArrayList<BoundedFileChangePreview>(visibleFileCount)
    var remainingLines = FILE_CHANGE_PREVIEW_MAX_LINES
    var remainingChars = FILE_CHANGE_PREVIEW_MAX_CHARS
    var remainingTargetChars = FILE_CHANGE_TARGETS_MAX_CHARS
    var renderedLineCount = 0
    var renderedCharCount = 0
    var renderedTargetCharCount = 0

    for (index in 0 until visibleFileCount) {
        val change = changes[index]
        val diff = boundedDiffPreview(change.diff, remainingLines, remainingChars)
        var fileTargetChars = minOf(FILE_CHANGE_TARGET_MAX_CHARS, remainingTargetChars)
        val path = boundedTextPreview(change.path, fileTargetChars)
        fileTargetChars -= path.text.length
        remainingTargetChars -= path.text.length
        renderedTargetCharCount += path.text.length
        val movePath = change.movePath?.let { value ->
            boundedTextPreview(value, fileTargetChars).also { preview ->
                remainingTargetChars -= preview.text.length
                renderedTargetCharCount += preview.text.length
            }
        }
        files += BoundedFileChangePreview(
            path = path.text,
            movePath = movePath?.text,
            kind = change.kind,
            diff = diff.text,
            targetTruncated = path.truncated || movePath?.truncated == true,
            diffTruncated = diff.truncated,
        )
        remainingLines -= diff.lineCount
        remainingChars -= diff.text.length
        renderedLineCount += diff.lineCount
        renderedCharCount += diff.text.length
    }

    return AggregateFileChangePreview(
        files = files,
        hiddenTargetCount = changes.size - visibleFileCount,
        renderedLineCount = renderedLineCount,
        renderedCharCount = renderedCharCount,
        renderedTargetCharCount = renderedTargetCharCount,
    )
}

internal fun TimelineItem.fileApprovalSnapshotOrNull(): TimelineItem? {
    val exactTurnId = turnId?.takeIf(String::isNotBlank) ?: return null
    if (
        id.isBlank() || kind != TimelineKind.FILE_CHANGE || status != "inProgress" ||
        !fileChangesComplete || fileChanges.isEmpty() ||
        !aggregateFileChangePreview(fileChanges).fullyReviewable
    ) {
        return null
    }
    return TimelineItem(
        id = id,
        kind = TimelineKind.FILE_CHANGE,
        status = "inProgress",
        fileChanges = fileChanges.map { it.copy() },
        turnId = exactTurnId,
        fileChangesComplete = true,
    )
}

internal val TimelineItem.approvalFileSnapshotRetainedCharCount: Long
    get() {
        var retainedChars = 0L
        fun retain(value: String?) {
            retainedChars = retainedChars.saturatingAddRetainedChars(value)
        }
        retain(id)
        retain(title)
        retain(body)
        retain(status)
        retain(turnId)
        fileChanges.forEach { change ->
            retain(change.path)
            retain(change.kind)
            retain(change.diff)
            retain(change.movePath)
        }
        return retainedChars
    }

private data class BoundedTextPreview(
    val text: String,
    val truncated: Boolean,
)

private fun boundedTextPreview(text: String, maxChars: Int): BoundedTextPreview {
    var endExclusive = minOf(text.length, maxChars.coerceAtLeast(0))
    if (
        endExclusive in 1 until text.length &&
        text[endExclusive - 1].isHighSurrogate() && text[endExclusive].isLowSurrogate()
    ) {
        endExclusive -= 1
    }
    return BoundedTextPreview(
        text = text.substring(0, endExclusive),
        truncated = endExclusive < text.length,
    )
}

private fun boundedDiffPreview(
    diff: String,
    maxLines: Int,
    maxChars: Int,
): BoundedDiffPreview {
    if (diff.isEmpty()) return BoundedDiffPreview("", 0, false)
    if (maxLines <= 0 || maxChars <= 0) return BoundedDiffPreview("", 0, true)

    var endExclusive = 0
    var lineCount = 1
    while (endExclusive < diff.length && endExclusive < maxChars) {
        when (val current = diff[endExclusive]) {
            '\n' -> {
                if (lineCount >= maxLines) break
                lineCount += 1
                endExclusive += 1
            }
            '\r' -> {
                if (lineCount >= maxLines) break
                lineCount += 1
                endExclusive += 1
                if (
                    endExclusive < diff.length && endExclusive < maxChars &&
                    diff[endExclusive] == '\n'
                ) {
                    endExclusive += 1
                }
            }
            else -> {
                val charWidth = if (
                    current.isHighSurrogate() && endExclusive + 1 < diff.length &&
                    diff[endExclusive + 1].isLowSurrogate()
                ) {
                    2
                } else {
                    1
                }
                if (endExclusive + charWidth > maxChars) break
                endExclusive += charWidth
            }
        }
    }

    return BoundedDiffPreview(
        text = diff.substring(0, endExclusive),
        lineCount = if (endExclusive == 0) 0 else lineCount,
        truncated = endExclusive < diff.length,
    )
}

enum class ApprovalKind { COMMAND, FILE_CHANGE, PERMISSION, USER_INPUT, UNKNOWN }

enum class PermissionMode { ASK, AUTO_REVIEW, FULL_ACCESS, READ_ONLY }

data class ApprovalQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isOther: Boolean = false,
    val options: List<ApprovalOption> = emptyList(),
)

data class ApprovalOption(
    val label: String,
    val description: String,
)

sealed interface RpcRequestId {
    val displayValue: String

    data class Text(val value: String) : RpcRequestId {
        override val displayValue: String = value
    }

    data class Number(val value: Long) : RpcRequestId {
        override val displayValue: String = value.toString()
    }
}

data class ApprovalContextField(
    val label: String,
    val value: String,
)

data class ApprovalRequest(
    val requestId: RpcRequestId,
    val kind: ApprovalKind,
    val title: String,
    val detail: String,
    val rawMethod: String,
    val rawParams: String = "{}",
    val questions: List<ApprovalQuestion> = emptyList(),
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val approvalId: String? = null,
    val startedAtMs: Long? = null,
    val cwd: String? = null,
    val context: List<ApprovalContextField> = emptyList(),
    val fileChanges: List<FileChangeSummary> = emptyList(),
    val availableDecisions: List<String> = defaultApprovalDecisions(kind),
    val securityContextComplete: Boolean = true,
) {
    internal val retainedCharCount: Long = calculateRetainedCharCount()

    fun bindFileChangesSnapshot(
        timeline: List<TimelineItem>,
        selectedThreadId: String?,
    ): ApprovalRequest = bindFileChangesSnapshot(
        threadId = selectedThreadId,
        item = timeline.firstOrNull { item ->
            item.id == itemId && item.turnId == turnId && item.kind == TimelineKind.FILE_CHANGE
        },
    )

    fun bindFileChangesSnapshot(
        threadId: String?,
        item: TimelineItem?,
    ): ApprovalRequest {
        if (kind != ApprovalKind.FILE_CHANGE) return this
        if (rawMethod == "applyPatchApproval") return this
        val targetItemId = itemId
        val targetTurnId = turnId
        val matchingItem = if (
            this.threadId != null && threadId == this.threadId &&
            targetItemId != null && targetTurnId != null
        ) {
            item?.takeIf { candidate ->
                candidate.id == targetItemId && candidate.turnId == targetTurnId &&
                    candidate.kind == TimelineKind.FILE_CHANGE
            }
        } else {
            null
        }
        val safeSnapshot = matchingItem?.fileApprovalSnapshotOrNull()
        if (fileChanges.isEmpty()) {
            return if (safeSnapshot != null) {
                copy(fileChanges = safeSnapshot.fileChanges.map { it.copy() })
            } else {
                this
            }
        }
        if (matchingItem == null) return this
        val changedWhilePending = safeSnapshot == null || safeSnapshot.fileChanges != fileChanges
        return if (changedWhilePending) withoutFileAcceptance() else this
    }

    private fun withoutFileAcceptance(): ApprovalRequest = copy(
        securityContextComplete = false,
        availableDecisions = availableDecisions.filterNot { it.startsWith("accept") },
    )

    fun resolvedFileChanges(
        @Suppress("UNUSED_PARAMETER") timeline: List<TimelineItem>,
        @Suppress("UNUSED_PARAMETER") selectedThreadId: String? = threadId,
    ): List<FileChangeSummary> = fileChanges

    fun canApprove(
        @Suppress("UNUSED_PARAMETER") timeline: List<TimelineItem>,
        @Suppress("UNUSED_PARAMETER") selectedThreadId: String? = threadId,
    ): Boolean = securityContextComplete && (
        kind != ApprovalKind.FILE_CHANGE ||
            (fileChanges.isNotEmpty() && aggregateFileChangePreview(fileChanges).fullyReviewable)
        )

    fun supportsDecision(decision: String): Boolean = decision in availableDecisions

    fun canSubmitAnswers(answers: Map<String, List<String>>): Boolean {
        if (kind != ApprovalKind.USER_INPUT || questions.isEmpty()) return false
        if (answers.keys != questions.mapTo(linkedSetOf(), ApprovalQuestion::id)) return false
        return questions.all { question ->
            val answer = answers[question.id]?.singleOrNull()?.takeIf(String::isNotBlank) ?: return@all false
            question.options.isEmpty() || question.isOther || question.options.any { it.label == answer }
        }
    }

    private fun calculateRetainedCharCount(): Long {
        var retainedChars = 0L
        fun retain(value: String?) {
            retainedChars = retainedChars.saturatingAddRetainedChars(value)
        }
        retain(requestId.displayValue)
        retain(title)
        retain(detail)
        retain(rawMethod)
        retain(rawParams)
        retain(threadId)
        retain(turnId)
        retain(itemId)
        retain(approvalId)
        retain(cwd)
        questions.forEach { question ->
            retain(question.id)
            retain(question.header)
            retain(question.question)
            question.options.forEach { option ->
                retain(option.label)
                retain(option.description)
            }
        }
        context.forEach { field ->
            retain(field.label)
            retain(field.value)
        }
        fileChanges.forEach { change ->
            retain(change.path)
            retain(change.kind)
            retain(change.diff)
            retain(change.movePath)
        }
        availableDecisions.forEach(::retain)
        return retainedChars
    }
}

private fun Long.saturatingAddRetainedChars(value: String?): Long {
    val additionalChars = value?.length?.toLong() ?: return this
    return if (this > Long.MAX_VALUE - additionalChars) Long.MAX_VALUE else this + additionalChars
}

fun defaultApprovalDecisions(kind: ApprovalKind): List<String> = when (kind) {
    ApprovalKind.COMMAND,
    ApprovalKind.FILE_CHANGE,
    ApprovalKind.PERMISSION,
    -> listOf("accept", "acceptForSession", "decline")
    ApprovalKind.USER_INPUT -> listOf("accept")
    ApprovalKind.UNKNOWN -> listOf("decline")
}

data class ApprovalQueueKey(
    val queueInstanceId: String,
    val sequence: Long,
    val requestId: RpcRequestId,
)

data class QueuedApproval(
    val key: ApprovalQueueKey,
    val request: ApprovalRequest,
)

enum class ApprovalEnqueueStatus { ENQUEUED, DUPLICATE_ACTIVE_ID, CAPACITY_EXCEEDED }

data class ApprovalEnqueueResult(
    val queue: ApprovalQueue,
    val status: ApprovalEnqueueStatus,
)

data class ApprovalQueue(
    val entries: List<QueuedApproval> = emptyList(),
    val respondingKeys: Set<ApprovalQueueKey> = emptySet(),
    val queueInstanceId: String = UUID.randomUUID().toString(),
    val nextSequence: Long = 1,
) {
    val requests: List<ApprovalRequest>
        get() = entries.map(QueuedApproval::request)

    val currentEntry: QueuedApproval?
        get() = entries.firstOrNull()

    val current: ApprovalRequest?
        get() = currentEntry?.request

    fun enqueue(request: ApprovalRequest): ApprovalQueue = enqueueResult(request).queue

    fun enqueueResult(request: ApprovalRequest): ApprovalEnqueueResult {
        if (entries.any { it.request.requestId == request.requestId }) {
            return ApprovalEnqueueResult(this, ApprovalEnqueueStatus.DUPLICATE_ACTIVE_ID)
        }
        if (entries.size >= MAX_PENDING_REQUESTS) {
            return ApprovalEnqueueResult(this, ApprovalEnqueueStatus.CAPACITY_EXCEEDED)
        }
        val retainedChars = retainedCharsWithinLimit()
        if (retainedChars == null || request.retainedCharCount > MAX_RETAINED_CHARS - retainedChars) {
            return ApprovalEnqueueResult(this, ApprovalEnqueueStatus.CAPACITY_EXCEEDED)
        }
        val key = ApprovalQueueKey(queueInstanceId, nextSequence, request.requestId)
        return ApprovalEnqueueResult(
            copy(
                entries = entries + QueuedApproval(key, request),
                nextSequence = nextSequence + 1,
            ),
            ApprovalEnqueueStatus.ENQUEUED,
        )
    }

    fun requestForResponse(key: ApprovalQueueKey): ApprovalRequest? = entries
        .firstOrNull { it.key == key }
        ?.request
        ?.takeUnless { key in respondingKeys }

    fun markResponding(key: ApprovalQueueKey): ApprovalQueue {
        if (requestForResponse(key) == null) return this
        return copy(respondingKeys = respondingKeys + key)
    }

    fun complete(key: ApprovalQueueKey): ApprovalQueue {
        if (entries.none { it.key == key }) return this
        return copy(
            entries = entries.filterNot { it.key == key },
            respondingKeys = respondingKeys - key,
        )
    }

    fun complete(requestId: RpcRequestId): ApprovalQueue {
        val entry = entries.firstOrNull { it.request.requestId == requestId } ?: return this
        return complete(entry.key)
    }

    fun bindFileChangeSnapshots(
        timeline: List<TimelineItem>,
        selectedThreadId: String?,
    ): ApprovalQueue = bindWithinRetainedBudget { request ->
        request.bindFileChangesSnapshot(timeline, selectedThreadId)
    }

    fun bindFileChangeSnapshot(
        threadId: String,
        item: TimelineItem,
    ): ApprovalQueue = bindWithinRetainedBudget { request ->
        request.bindFileChangesSnapshot(threadId, item)
    }

    private inline fun bindWithinRetainedBudget(
        transform: (ApprovalRequest) -> ApprovalRequest,
    ): ApprovalQueue {
        var retainedChars = retainedCharsWithinLimit() ?: return this
        var changed = false
        val boundEntries = entries.map { entry ->
            val original = entry.request
            val candidate = transform(original)
            if (candidate === original) {
                entry
            } else {
                val retainedWithoutOriginal = retainedChars - original.retainedCharCount
                if (candidate.retainedCharCount <= MAX_RETAINED_CHARS - retainedWithoutOriginal) {
                    retainedChars = retainedWithoutOriginal + candidate.retainedCharCount
                    changed = true
                    entry.copy(request = candidate)
                } else {
                    entry
                }
            }
        }
        return if (changed) copy(entries = boundEntries) else this
    }

    private fun retainedCharsWithinLimit(): Long? {
        var retainedChars = 0L
        entries.forEach { entry ->
            val requestChars = entry.request.retainedCharCount
            if (requestChars > MAX_RETAINED_CHARS - retainedChars) return null
            retainedChars += requestChars
        }
        return retainedChars
    }

    companion object {
        const val MAX_PENDING_REQUESTS = 64
        const val MAX_RETAINED_CHARS = 4L * 1024L * 1024L
    }
}

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class AppUiState(
    val savedConnections: List<SavedConnection> = emptyList(),
    val activeConnection: SavedConnection? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionMessage: String = "",
    val threads: List<RemoteThread> = emptyList(),
    val archivedThreads: List<RemoteThread> = emptyList(),
    val isArchivedThreadsLoading: Boolean = false,
    val archivedThreadsError: String? = null,
    val projects: List<RemoteProject> = emptyList(),
    val skills: List<RemoteSkill> = emptyList(),
    val plugins: List<RemotePlugin> = emptyList(),
    val remoteDirectoryPath: String? = null,
    val remoteDirectoryEntries: List<RemotePathEntry> = emptyList(),
    val isRemoteDirectoryLoading: Boolean = false,
    val remoteDirectoryError: String? = null,
    val isComposerCatalogLoading: Boolean = false,
    val composerCatalogError: String? = null,
    val selectedProjectPath: String? = null,
    val selectedThreadId: String? = null,
    val threadGoal: ThreadGoal? = null,
    val isGoalLoading: Boolean = false,
    val goalError: String? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val olderHistoryCursor: String? = null,
    val hasOlderHistory: Boolean = false,
    val isOlderHistoryLoading: Boolean = false,
    val olderHistoryError: String? = null,
    val consumedHistoryCursors: Set<String> = emptySet(),
    val models: List<RemoteModel> = emptyList(),
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    val selectedServiceTier: String? = null,
    val collaborationModes: List<RemoteCollaborationMode> = emptyList(),
    val selectedCollaborationMode: String = "default",
    val permissionProfiles: List<RemotePermissionProfile> = emptyList(),
    val selectedPermissionProfile: String? = null,
    val approvalsReviewer: String = "user",
    val remoteServer: RemoteServerInfo? = null,
    val remoteAccount: RemoteAccount? = null,
    val remoteDeviceLogin: RemoteDeviceLogin? = null,
    val isLoginStarting: Boolean = false,
    val mcpServers: List<RemoteMcpServerStatus> = emptyList(),
    val isMcpStatusLoading: Boolean = false,
    val mcpStatusError: String? = null,
    val isMcpLoginStarting: Boolean = false,
    val mcpAuthorizationUrl: String? = null,
    val isFeedbackSubmitting: Boolean = false,
    val feedbackError: String? = null,
    val rateLimits: RemoteRateLimits? = null,
    val threadTokenUsage: RemoteThreadTokenUsage? = null,
    val isStatusLoading: Boolean = false,
    val statusError: String? = null,
    val approvalPolicy: String = "on-request",
    val isTurnRunning: Boolean = false,
    val activeTurnId: String? = null,
    val approvalQueue: ApprovalQueue = ApprovalQueue(),
    val approvalFileItems: Map<ApprovalFileItemKey, TimelineItem> = emptyMap(),
    val pendingHostKeyFingerprint: String? = null,
    val isRestoringLastConnection: Boolean = true,
    val showConnections: Boolean = false,
    val showConnectionEditor: Boolean = false,
    val editingConnection: SavedConnection? = null,
    val isBusy: Boolean = false,
    val notice: String? = null,
)

internal fun AppUiState.withThreadRenamed(threadId: String, name: String): AppUiState {
    val renamed = threads.map { thread ->
        if (thread.id == threadId) thread.copy(title = name) else thread
    }
    return copy(threads = renamed, projects = groupThreadsByProject(renamed))
}

internal fun AppUiState.withThreadArchived(threadId: String): AppUiState {
    val remaining = threads.filterNot { it.id == threadId }
    val remainingProjects = groupThreadsByProject(remaining)
    val archivedSelectedThread = selectedThreadId == threadId
    val nextProjectPath = selectedProjectPath
        ?.takeIf { path -> remainingProjects.any { it.path == path } }
        ?: remainingProjects.firstOrNull()?.path
    return copy(
        threads = remaining,
        projects = remainingProjects,
        selectedProjectPath = nextProjectPath,
        selectedThreadId = selectedThreadId.takeUnless { archivedSelectedThread },
        threadGoal = if (archivedSelectedThread) null else threadGoal,
        isGoalLoading = if (archivedSelectedThread) false else isGoalLoading,
        goalError = if (archivedSelectedThread) null else goalError,
        threadTokenUsage = if (archivedSelectedThread) null else threadTokenUsage,
        timeline = if (archivedSelectedThread) emptyList() else timeline,
        olderHistoryCursor = if (archivedSelectedThread) null else olderHistoryCursor,
        hasOlderHistory = if (archivedSelectedThread) false else hasOlderHistory,
        isOlderHistoryLoading = if (archivedSelectedThread) false else isOlderHistoryLoading,
        olderHistoryError = if (archivedSelectedThread) null else olderHistoryError,
        consumedHistoryCursors = if (archivedSelectedThread) emptySet() else consumedHistoryCursors,
    )
}

internal fun mergeTimelineHistory(
    older: List<TimelineItem>,
    newer: List<TimelineItem>,
): List<TimelineItem> {
    val newerById = newer.associateBy { it.turnId to it.id }
    val seen = mutableSetOf<Pair<String?, String>>()
    return buildList(older.size + newer.size) {
        older.forEach { item ->
            val identity = item.turnId to item.id
            if (seen.add(identity)) add(newerById[identity] ?: item)
        }
        newer.forEach { item ->
            if (seen.add(item.turnId to item.id)) add(item)
        }
    }
}
