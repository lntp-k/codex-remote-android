package com.codex.remote.data.rpc

import com.codex.remote.BuildConfig
import com.codex.remote.data.ssh.ActiveSshTransport
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalContextField
import com.codex.remote.domain.ApprovalOption
import com.codex.remote.domain.ApprovalQuestion
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.ComposerMention
import com.codex.remote.domain.ComposerMentionKind
import com.codex.remote.domain.ComposerImageAttachment
import com.codex.remote.domain.FileChangeSummary
import com.codex.remote.domain.ForkedRemoteThread
import com.codex.remote.domain.RateLimitWindowSnapshot
import com.codex.remote.domain.ReasoningEffortOption
import com.codex.remote.domain.RemoteAccount
import com.codex.remote.domain.RemoteCollaborationMode
import com.codex.remote.domain.RemoteDeviceLogin
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemoteMcpServerStatus
import com.codex.remote.domain.RemotePlugin
import com.codex.remote.domain.RemotePermissionProfile
import com.codex.remote.domain.RemotePathEntry
import com.codex.remote.domain.RemoteRateLimits
import com.codex.remote.domain.RemoteServerInfo
import com.codex.remote.domain.RemoteSkill
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.RemoteThreadHistoryPage
import com.codex.remote.domain.RemoteThreadSession
import com.codex.remote.domain.RemoteThreadSettingsSnapshot
import com.codex.remote.domain.RemoteThreadTokenUsage
import com.codex.remote.domain.ReviewTargetKind
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.StartedRemoteReview
import com.codex.remote.domain.StartedRemoteThread
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.ThreadGoal
import com.codex.remote.domain.ThreadGoalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface AppServerEvent {
    enum class FailureTurnIdStatus {
        EXACT,
        LEGACY_ABSENT,
        INVALID,
    }

    data class ItemUpsert(val threadId: String?, val item: TimelineItem) : AppServerEvent
    data class AgentDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String,
        val delta: String,
    ) : AppServerEvent
    data class PlanDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String,
        val delta: String,
    ) : AppServerEvent
    data class ReasoningDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String,
        val delta: String,
    ) : AppServerEvent
    data class OutputDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String,
        val delta: String,
    ) : AppServerEvent
    data class TurnRunning(
        val threadId: String?,
        val running: Boolean,
        val turnId: String? = null,
    ) : AppServerEvent
    data class Approval(val threadId: String?, val request: ApprovalRequest) : AppServerEvent
    data class ApprovalResolved(val threadId: String, val requestId: RpcRequestId) : AppServerEvent
    data object AccountChanged : AppServerEvent
    data class ThreadStarted(
        val threadId: String,
        val thread: RemoteThread?,
    ) : AppServerEvent
    data object ThreadsChanged : AppServerEvent
    data object SkillsChanged : AppServerEvent
    data class GoalUpdated(val threadId: String, val goal: ThreadGoal) : AppServerEvent
    data class GoalCleared(val threadId: String) : AppServerEvent
    data class TokenUsageUpdated(val threadId: String, val usage: RemoteThreadTokenUsage) : AppServerEvent
    data class RateLimitsUpdated(val rateLimits: RemoteRateLimits) : AppServerEvent
    data class ContextCompacted(val threadId: String) : AppServerEvent
    data class McpLoginCompleted(val name: String, val success: Boolean, val error: String?) : AppServerEvent
    data class ThreadSettingsUpdated(
        val threadId: String,
        val settings: RemoteThreadSettingsSnapshot,
    ) : AppServerEvent
    data class LoginCompleted(val success: Boolean, val error: String?) : AppServerEvent
    data class Failure(
        val message: String,
        val threadId: String? = null,
        val turnId: String? = null,
        val turnIdStatus: FailureTurnIdStatus = if (turnId.isNullOrBlank()) {
            FailureTurnIdStatus.INVALID
        } else {
            FailureTurnIdStatus.EXACT
        },
        val kind: FailureKind = FailureKind.REMOTE,
        val hadPendingRequests: Boolean = false,
    ) : AppServerEvent
    data class FatalProtocolError(val message: String) : AppServerEvent
    data class Warning(val message: String) : AppServerEvent
    data class Diagnostic(val message: String) : AppServerEvent
}

enum class FailureKind { REMOTE, TRANSPORT }

class RpcException(message: String, val code: Int? = null) : Exception(message)

internal class AppServerLineTooLongException(maxChars: Int) :
    IOException("Remote app-server line exceeded the $maxChars character limit")

internal fun BufferedReader.readBoundedLine(maxChars: Int): String? {
    require(maxChars > 0) { "maxChars must be positive" }
    val line = StringBuilder(minOf(maxChars, 4_096))
    while (true) {
        val next = read()
        if (next == -1 || next == '\n'.code) {
            if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
            return if (next == -1 && line.isEmpty()) null else line.toString()
        }
        if (line.length >= maxChars) {
            if (line.length == maxChars && next == '\r'.code) {
                line.append(next.toChar())
                continue
            }
            throw AppServerLineTooLongException(maxChars)
        }
        line.append(next.toChar())
    }
}

private fun diagnosticPreview(value: String): String = if (value.length <= MAX_DIAGNOSTIC_CHARS) {
    value
} else {
    value.take(MAX_DIAGNOSTIC_CHARS) + "… [truncated]"
}

internal class OutstandingApprovalRequests {
    private enum class State { PENDING, RESPONDING, RESPONDED, RESOLVED_DURING_RESPONSE }
    private data class TrackedRequest(
        val threadId: String?,
        val retainedChars: Long,
        var state: State,
    )

    private val lock = Any()
    private val requests = mutableMapOf<RpcRequestId, TrackedRequest>()
    private var retainedIdChars = 0L
    private var retainedApprovalChars = 0L
    private var invalid = false

    fun reserve(
        id: RpcRequestId,
        threadId: String? = null,
        retainedChars: Long = id.displayValue.length.toLong(),
    ): Boolean = synchronized(lock) {
        val idChars = id.displayValue.length.toLong()
        val effectiveRetainedChars = maxOf(idChars, retainedChars)
        if (invalid || id in requests || requests.size >= MAX_TRACKED_APPROVAL_REQUESTS ||
            idChars > MAX_TRACKED_APPROVAL_ID_CHARS - retainedIdChars ||
            effectiveRetainedChars > MAX_TRACKED_APPROVAL_RETAINED_CHARS - retainedApprovalChars
        ) {
            invalid = true
            false
        } else {
            requests[id] = TrackedRequest(threadId, effectiveRetainedChars, State.PENDING)
            retainedIdChars += idChars
            retainedApprovalChars += effectiveRetainedChars
            true
        }
    }

    fun invalidate() = synchronized(lock) {
        invalid = true
    }

    fun resolve(id: RpcRequestId, threadId: String? = null): Boolean = synchronized(lock) {
        if (invalid) return@synchronized false
        val tracked = requests[id]
        if (tracked == null || tracked.threadId != threadId) {
            invalid = true
            return@synchronized false
        }
        when (tracked.state) {
            State.PENDING, State.RESPONDED -> {
                requests.remove(id)
                retainedIdChars -= id.displayValue.length.toLong()
                retainedApprovalChars -= tracked.retainedChars
                true
            }
            State.RESPONDING -> {
                tracked.state = State.RESOLVED_DURING_RESPONSE
                true
            }
            State.RESOLVED_DURING_RESPONSE -> {
                invalid = true
                false
            }
        }
    }

    suspend fun respondAndTrackUntilResolved(id: RpcRequestId, send: suspend () -> Unit) {
        synchronized(lock) {
            val tracked = requests[id]
            if (invalid || tracked?.state != State.PENDING) {
                invalid = true
                throw RpcException("Approval request is no longer safe to answer")
            }
            tracked.state = State.RESPONDING
        }
        try {
            send()
        } catch (error: Throwable) {
            synchronized(lock) {
                invalid = true
            }
            throw error
        }

        val delivered = synchronized(lock) {
            if (invalid) {
                false
            } else when (requests[id]?.state) {
                State.RESPONDING -> {
                    requests.getValue(id).state = State.RESPONDED
                    true
                }
                State.RESOLVED_DURING_RESPONSE -> {
                    val tracked = requests.remove(id)!!
                    retainedIdChars -= id.displayValue.length.toLong()
                    retainedApprovalChars -= tracked.retainedChars
                    true
                }
                else -> {
                    invalid = true
                    false
                }
            }
        }
        if (!delivered) {
            throw RpcException("Approval request changed state while the response was being sent")
        }
    }
}

internal fun trackedServerRequestEvent(
    requests: OutstandingApprovalRequests,
    id: RpcRequestId,
    method: String,
    params: JsonObject,
): AppServerEvent {
    val rawParams = params.toString()
    val messageChars = id.displayValue.length.toLong() + method.length.toLong() + rawParams.length.toLong()
    if (messageChars > MAX_APPROVAL_MESSAGE_CHARS) {
        requests.invalidate()
        return AppServerEvent.FatalProtocolError(
            "Remote approval request exceeded the safe size limit; disconnected without responding.",
        )
    }
    val request = CodexRpcClient.parseApprovalRequest(id, method, params, rawParams)
    if (request.kind == ApprovalKind.UNKNOWN) {
        requests.invalidate()
        return AppServerEvent.FatalProtocolError(
            "Remote sent an unsupported JSON-RPC server request; disconnected without guessing a response.",
        )
    }
    if (!requests.reserve(id, request.threadId, request.retainedCharCount)) {
        return AppServerEvent.FatalProtocolError(
            "Remote reused an outstanding approval request ID or exceeded the safe tracking limit; " +
                "disconnected without responding.",
        )
    }
    return AppServerEvent.Approval(request.threadId, request)
}

internal fun trackedServerRequestResolvedEvent(
    requests: OutstandingApprovalRequests,
    params: JsonObject,
): AppServerEvent {
    val threadId = params.strictNonBlankString("threadId")
    val requestId = params["requestId"]?.let(CodexRpcClient::parseRequestId)
    if (threadId == null || requestId == null || !requests.resolve(requestId, threadId)) {
        requests.invalidate()
        return AppServerEvent.FatalProtocolError(
            "Remote sent an invalid or unexpected approval resolution; disconnected without responding.",
        )
    }
    return AppServerEvent.ApprovalResolved(threadId, requestId)
}

class CodexRpcClient(
    private val transport: ActiveSshTransport,
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val requestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<RpcRequestId, CompletableDeferred<JsonObject>>()
    private val writeMutex = Mutex()
    private val outstandingApprovalRequests = OutstandingApprovalRequests()
    private val protocolTerminationStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<AppServerEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<AppServerEvent> = _events
    private var readerJob: Job? = null

    internal fun hasPendingRequests(): Boolean = pending.isNotEmpty()

    suspend fun initialize(): RemoteServerInfo {
        readerJob = scope.launch { readLoop() }
        scope.launch { stderrLoop() }
        val result = request(
            "initialize",
            initializeParams(),
        )
        notify("initialized", buildJsonObject {})
        return RemoteServerInfo(
            userAgent = result.string("userAgent").orEmpty(),
            codexHome = result.string("codexHome").orEmpty(),
            platformFamily = result.string("platformFamily") ?: transport.remotePlatform.name.lowercase(),
            platformOs = result.string("platformOs") ?: transport.remotePlatform.name.lowercase(),
            codexVersion = transport.codexVersion,
        )
    }

    suspend fun listThreads(): List<RemoteThread> = listThreads(archived = false)

    suspend fun listArchivedThreads(): List<RemoteThread> = listThreads(archived = true)

    private suspend fun listThreads(archived: Boolean): List<RemoteThread> = collectAllThreadPages { cursor ->
        val result = request("thread/list", threadListParams(cursor, archived))
        ThreadPage(
            threads = result.array("data").mapNotNull(::parseThread),
            nextCursor = result.string("nextCursor"),
        )
    }

    suspend fun listModels(): List<RemoteModel> = collectAllModelPages { cursor ->
        val result = request("model/list", buildJsonObject {
            put("limit", MODEL_PAGE_SIZE)
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        })
        ModelPage(
            models = result.array("data").mapNotNull(::parseModel),
            nextCursor = result.string("nextCursor"),
        )
    }

    suspend fun readRemoteDirectory(path: String): List<RemotePathEntry> {
        val result = request("fs/readDirectory", buildJsonObject { put("path", path) })
        return result.array("entries").mapNotNull { element ->
            val entry = element.asObject() ?: return@mapNotNull null
            val name = entry.string("fileName") ?: return@mapNotNull null
            RemotePathEntry(
                name = name,
                isDirectory = entry.boolean("isDirectory"),
                isFile = entry.boolean("isFile"),
            )
        }
    }

    suspend fun listCollaborationModes(): List<RemoteCollaborationMode> {
        val result = request("collaborationMode/list")
        return result.array("data").mapNotNull(::parseCollaborationMode)
    }

    suspend fun listPermissionProfiles(cwd: String?): List<RemotePermissionProfile> {
        val profiles = mutableListOf<RemotePermissionProfile>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = request("permissionProfile/list", buildJsonObject {
                put("limit", PERMISSION_PROFILE_PAGE_SIZE)
                cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
                cursor?.let { put("cursor", it) }
            })
            profiles += result.array("data").mapNotNull(::parsePermissionProfile)
            cursor = result.string("nextCursor")?.takeIf(String::isNotBlank)
            if (cursor != null && !seenCursors.add(cursor)) {
                throw RpcException("permissionProfile/list returned a repeated nextCursor")
            }
        } while (cursor != null)
        return profiles.distinctBy { it.id }
    }

    suspend fun listSkills(cwds: List<String>, forceReload: Boolean = false): List<RemoteSkill> {
        val result = request("skills/list", skillsListParams(cwds, forceReload))
        return result.array("data")
            .flatMap { entryElement ->
                val entry = entryElement.asObject() ?: return@flatMap emptyList()
                val cwd = entry.string("cwd").orEmpty()
                entry.array("skills").mapNotNull { parseSkill(it, cwd) }
            }
            .groupBy { "${it.name}\u0000${it.path}" }
            .values
            .map { matches -> matches.first().copy(cwds = matches.flatMapTo(linkedSetOf()) { it.cwds }) }
            .sortedWith(compareBy<RemoteSkill> { it.displayName.lowercase() }.thenBy { it.name })
    }

    suspend fun listInstalledPlugins(cwds: List<String>): List<RemotePlugin> {
        val result = request("plugin/installed", pluginInstalledParams(cwds))
        return result.array("marketplaces")
            .flatMap { marketplaceElement ->
                val marketplace = marketplaceElement.asObject() ?: return@flatMap emptyList()
                val marketplaceName = marketplace.string("name").orEmpty()
                marketplace.array("plugins").mapNotNull { parsePlugin(it, marketplaceName) }
            }
            .filter { it.enabled }
            .distinctBy { it.id }
            .sortedWith(compareBy<RemotePlugin> { it.displayName.lowercase() }.thenBy { it.id })
    }

    suspend fun readAccount(): RemoteAccount {
        val result = request("account/read", buildJsonObject { put("refreshToken", false) })
        return parseAccount(result)
    }

    suspend fun startDeviceLogin(): RemoteDeviceLogin {
        val result = request("account/login/start", buildJsonObject { put("type", "chatgptDeviceCode") })
        return RemoteDeviceLogin(
            loginId = result.strictNonBlankString("loginId")
                ?: throw RpcException("Remote did not return a login ID"),
            verificationUrl = result.string("verificationUrl")
                ?: throw RpcException("Remote did not return a device sign-in URL"),
            userCode = result.string("userCode") ?: throw RpcException("Remote did not return a device code"),
        )
    }

    suspend fun cancelLogin(loginId: String) {
        request("account/login/cancel", buildJsonObject { put("loginId", loginId) })
    }

    suspend fun renameThread(threadId: String, name: String) {
        request("thread/name/set", threadSetNameParams(threadId, name))
    }

    suspend fun archiveThread(threadId: String) {
        request("thread/archive", threadArchiveParams(threadId))
    }

    suspend fun unarchiveThread(threadId: String): RemoteThread {
        val result = request("thread/unarchive", threadMutationParams(threadId))
        return result["thread"]?.let(::parseThread)
            ?: throw RpcException("thread/unarchive did not return thread")
    }

    suspend fun deleteThread(threadId: String) {
        request("thread/delete", threadMutationParams(threadId))
    }

    suspend fun setThreadPinned(threadId: String, isPinned: Boolean): RemoteThread {
        val result = request("thread/metadata/update", buildJsonObject {
            put("threadId", threadId)
            put("isPinned", isPinned)
        })
        return result["thread"]?.let(::parseThread)
            ?: throw RpcException("thread/metadata/update did not return thread")
    }

    suspend fun compactThread(threadId: String) {
        request("thread/compact/start", buildJsonObject { put("threadId", threadId) })
    }

    suspend fun getThreadGoal(threadId: String): ThreadGoal? {
        val result = request("thread/goal/get", threadGoalGetParams(threadId))
        return parseThreadGoal(result["goal"] ?: JsonNull)
    }

    suspend fun setThreadGoal(
        threadId: String,
        objective: String? = null,
        status: ThreadGoalStatus? = null,
        tokenBudget: Long? = null,
    ): ThreadGoal {
        val result = request(
            "thread/goal/set",
            threadGoalSetParams(threadId, objective, status, tokenBudget),
        )
        return parseThreadGoal(result["goal"] ?: JsonNull)
            ?: throw RpcException("thread/goal/set did not return goal")
    }

    suspend fun clearThreadGoal(threadId: String): Boolean {
        val result = request("thread/goal/clear", threadGoalClearParams(threadId))
        return result.boolean("cleared")
    }

    suspend fun forkThread(
        threadId: String,
        cwd: String,
        model: String?,
        serviceTier: String?,
        approvalPolicy: String,
        approvalsReviewer: String,
        permissionProfile: String?,
    ): ForkedRemoteThread {
        val result = request(
            "thread/fork",
            threadForkParams(
                threadId,
                cwd,
                model,
                serviceTier,
                approvalPolicy,
                approvalsReviewer,
                permissionProfile,
            ),
        )
        val threadElement = result["thread"] ?: throw RpcException("thread/fork did not return thread")
        val thread = parseThread(threadElement) ?: throw RpcException("thread/fork returned an invalid thread")
        val threadObject = threadElement.asObject()
        return ForkedRemoteThread(
            thread = thread,
            session = RemoteThreadSession(
                timeline = parseTurnsTimeline(
                    threadObject?.array("turns").orEmpty(),
                    descending = false,
                ),
                model = result.string("model") ?: model,
                reasoningEffort = result.string("reasoningEffort"),
                serviceTier = result.string("serviceTier") ?: serviceTier,
                cwd = result.string("cwd") ?: thread.cwd,
                collaborationMode = result.obj("collaborationMode")?.string("mode"),
                approvalPolicy = result.string("approvalPolicy") ?: approvalPolicy,
                approvalsReviewer = result.string("approvalsReviewer") ?: approvalsReviewer,
                permissionProfile = result.obj("activePermissionProfile")?.strictNonBlankString("id")
                    ?: permissionProfile,
            ),
        )
    }

    suspend fun startReview(
        threadId: String,
        targetKind: ReviewTargetKind,
        targetValue: String = "",
    ): StartedRemoteReview {
        val result = request("review/start", reviewStartParams(threadId, targetKind, targetValue))
        return StartedRemoteReview(
            turnId = result.obj("turn")?.strictNonBlankString("id")
                ?: throw RpcException("review/start did not return turn.id"),
            threadId = result.strictNonBlankString("reviewThreadId") ?: threadId,
        )
    }

    suspend fun listMcpServerStatuses(threadId: String?): List<RemoteMcpServerStatus> {
        val servers = mutableListOf<RemoteMcpServerStatus>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = request("mcpServerStatus/list", buildJsonObject {
                put("limit", MCP_STATUS_PAGE_SIZE)
                put("detail", "toolsAndAuthOnly")
                threadId?.let { put("threadId", it) }
                cursor?.let { put("cursor", it) }
            })
            servers += result.array("data").mapNotNull(::parseMcpServerStatus)
            cursor = result.string("nextCursor")?.takeIf(String::isNotBlank)
            if (cursor != null && !seenCursors.add(cursor)) {
                throw RpcException("mcpServerStatus/list returned a duplicate nextCursor")
            }
        } while (cursor != null)
        return servers.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    suspend fun startMcpOauthLogin(name: String, threadId: String?): String {
        val result = request("mcpServer/oauth/login", buildJsonObject {
            put("name", name)
            threadId?.let { put("threadId", it) }
        })
        return result.string("authorizationUrl")
            ?: throw RpcException("mcpServer/oauth/login did not return authorizationUrl")
    }

    suspend fun reloadMcpServers() {
        request("config/mcpServer/reload")
    }

    suspend fun submitFeedback(classification: String, reason: String, threadId: String?): String {
        val result = request("feedback/upload", feedbackUploadParams(classification, reason, threadId))
        return result.strictNonBlankString("threadId").orEmpty()
    }

    suspend fun readRateLimits(): RemoteRateLimits {
        val result = request("account/rateLimits/read")
        return parseRateLimits(result["rateLimits"] ?: JsonNull)
            ?: throw RpcException("account/rateLimits/read did not return rateLimits")
    }

    suspend fun remotePathExists(path: String): Boolean = try {
        request("fs/getMetadata", buildJsonObject { put("path", path) })
        true
    } catch (error: RpcException) {
        val message = error.message.orEmpty()
        if (message.contains("ENOENT", true) || message.contains("No such file", true) ||
            message.contains("cannot find", true) || message.contains("not found", true)
        ) {
            false
        } else {
            throw error
        }
    }

    suspend fun startThread(
        cwd: String,
        model: String?,
        serviceTier: String?,
        approvalPolicy: String,
        approvalsReviewer: String,
        permissionProfile: String?,
    ): StartedRemoteThread {
        val result = request(
            "thread/start",
            threadStartParams(cwd, model, serviceTier, approvalPolicy, approvalsReviewer, permissionProfile),
        )
        return StartedRemoteThread(
            id = result.obj("thread")?.strictNonBlankString("id")
                ?: throw RpcException("thread/start did not return thread.id"),
            model = result.string("model") ?: model,
            reasoningEffort = result.string("reasoningEffort"),
            serviceTier = result.string("serviceTier") ?: serviceTier,
            cwd = result.string("cwd") ?: cwd,
        )
    }

    suspend fun resumeThread(threadId: String, cwd: String): RemoteThreadSession = try {
        resumeThreadPaginated(threadId, cwd)
    } catch (error: RpcException) {
        if (!error.isHistoryPaginationUnavailable()) throw error
        resumeThreadLegacy(threadId, cwd)
    }

    private suspend fun resumeThreadPaginated(threadId: String, cwd: String): RemoteThreadSession {
        val result = request("thread/resume", threadResumeParams(threadId, cwd, paginated = true))
        val thread = result.obj("thread")
        val initialPage = result.obj("initialTurnsPage")
        if (initialPage != null) {
            val turns = initialPage.array("data")
            return sessionFromResume(
                result = result,
                thread = thread,
                fallbackCwd = cwd,
                turns = turns,
                timeline = parseTurnsTimeline(turns),
                olderHistoryCursor = selectOlderHistoryCursor(
                    initialPageCursor = initialPage.string("nextCursor"),
                    turnsBackwardsCursor = result.string("turnsBackwardsCursor"),
                ),
            )
        }

        // Older servers may ignore the experimental fields. Read their legacy history
        // without issuing a second resume against an already-loaded thread.
        val inlineTurns = thread?.array("turns").orEmpty()
        val legacyThread = if (inlineTurns.isNotEmpty()) {
            thread
        } else {
            request("thread/read", buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            }).obj("thread") ?: thread
        }
        val legacyTurns = legacyThread?.array("turns").orEmpty()
        return sessionFromResume(
            result = result,
            thread = legacyThread,
            fallbackCwd = cwd,
            turns = legacyTurns,
            timeline = parseTurnsTimeline(legacyTurns, descending = false),
        )
    }

    private suspend fun resumeThreadLegacy(threadId: String, cwd: String): RemoteThreadSession {
        val result = request("thread/resume", threadResumeParams(threadId, cwd, paginated = false))
        val thread = result.obj("thread")
        val turns = thread?.array("turns").orEmpty()
        return sessionFromResume(
            result = result,
            thread = thread,
            fallbackCwd = cwd,
            turns = turns,
            timeline = parseTurnsTimeline(turns, descending = false),
        )
    }

    private fun sessionFromResume(
        result: JsonObject,
        thread: JsonObject?,
        fallbackCwd: String,
        turns: List<JsonElement>,
        timeline: List<TimelineItem>,
        olderHistoryCursor: String? = null,
    ) = RemoteThreadSession(
        timeline = timeline,
        model = result.string("model"),
        reasoningEffort = result.string("reasoningEffort"),
        serviceTier = result.string("serviceTier"),
        cwd = result.string("cwd") ?: thread?.string("cwd") ?: fallbackCwd,
        olderHistoryCursor = olderHistoryCursor,
        collaborationMode = result.obj("collaborationMode")?.string("mode"),
        approvalPolicy = result.string("approvalPolicy"),
        approvalsReviewer = result.string("approvalsReviewer"),
        permissionProfile = result.obj("activePermissionProfile")?.strictNonBlankString("id"),
        activeTurnId = parseActiveTurnId(turns),
    )

    suspend fun loadOlderThreadHistory(threadId: String, cursor: String): RemoteThreadHistoryPage {
        val result = request("thread/turns/list", threadTurnsListParams(threadId, cursor))
        return RemoteThreadHistoryPage(
            timeline = parseTurnsTimeline(result.array("data")),
            nextCursor = checkedNextHistoryCursor(
                returnedCursor = result.string("nextCursor"),
                consumedCursors = setOf(cursor),
            ),
        )
    }

    suspend fun startTurn(
        threadId: String,
        text: String,
        cwd: String,
        model: String?,
        reasoningEffort: String?,
        serviceTier: String?,
        approvalPolicy: String,
        approvalsReviewer: String,
        permissionProfile: String?,
        collaborationMode: RemoteCollaborationMode?,
        mentions: List<ComposerMention> = emptyList(),
        attachments: List<ComposerImageAttachment> = emptyList(),
    ): String? {
        val result = request(
            "turn/start",
            turnStartParams(
                threadId,
                text,
                cwd,
                model,
                reasoningEffort,
                serviceTier,
                approvalPolicy,
                approvalsReviewer,
                permissionProfile,
                collaborationMode,
                mentions,
                attachments,
            ),
        )
        return startedTurnId(result)
    }

    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        mentions: List<ComposerMention> = emptyList(),
        attachments: List<ComposerImageAttachment> = emptyList(),
    ) {
        request(
            "turn/steer",
            turnSteerParams(threadId, expectedTurnId, text, mentions, attachments),
        )
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        request("turn/interrupt", buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
        })
    }

    suspend fun respondToApproval(
        request: ApprovalRequest,
        decision: String,
        answers: Map<String, List<String>> = emptyMap(),
    ) {
        if (!request.supportsDecision(decision)) {
            throw RpcException("Approval decision is not available for this request")
        }
        if (decision.startsWith("accept") && !request.canApprove(emptyList(), request.threadId)) {
            throw RpcException("Approval request has incomplete security context")
        }
        if (request.kind == ApprovalKind.USER_INPUT && decision == "accept" && !request.canSubmitAnswers(answers)) {
            throw RpcException("User input response is incomplete or does not match the rendered choices")
        }
        try {
            outstandingApprovalRequests.respondAndTrackUntilResolved(request.requestId) {
                respond(request.requestId, approvalResponseParams(request, decision, answers))
            }
        } catch (error: Throwable) {
            transport.abort()
            throw error
        }
    }

    suspend fun request(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        if (protocolTerminationStarted.get()) throw RpcException("Remote connection is closed")
        val id = RpcRequestId.Number(requestId.getAndIncrement())
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            if (protocolTerminationStarted.get()) throw RpcException("Remote connection is closed")
            send(buildJsonObject {
                put("method", method)
                put("id", id.value)
                put("params", params)
            })
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String, params: JsonObject) = send(buildJsonObject {
        put("method", method)
        put("params", params)
    })

    private suspend fun respond(id: RpcRequestId, result: JsonObject) = send(responseEnvelope(id, result))

    private suspend fun send(message: JsonObject) = writeMutex.withLock {
        if (protocolTerminationStarted.get()) throw RpcException("Remote connection is closed")
        withContext(Dispatchers.IO) {
            transport.writer.write(json.encodeToString(JsonObject.serializer(), message))
            transport.writer.newLine()
            transport.writer.flush()
        }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = try {
                    transport.reader.readBoundedLine(MAX_APP_SERVER_LINE_CHARS)
                } catch (_: AppServerLineTooLongException) {
                    terminateForProtocolError(
                        AppServerEvent.FatalProtocolError(
                            "Remote app-server message exceeded the safe size limit; disconnected.",
                        ),
                    )
                    return
                } ?: break
                if (line.isBlank()) continue
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (message == null) {
                    _events.emit(
                        AppServerEvent.Diagnostic(
                            "Could not parse app-server output: ${diagnosticPreview(line)}",
                        ),
                    )
                    continue
                }
                val idElement = message["id"]
                val id = if (idElement == null) {
                    null
                } else {
                    try {
                        requireRequestId(idElement)
                    } catch (error: RpcException) {
                        terminateForProtocolError(
                            AppServerEvent.FatalProtocolError(
                                error.message ?: "Invalid app-server JSON-RPC request id",
                            ),
                        )
                        return
                    }
                }
                if (id != null && (message.containsKey("result") || message.containsKey("error"))) {
                    val deferred = pending.remove(id)
                    val error = message.obj("error")
                    if (error != null) {
                        deferred?.completeExceptionally(
                            RpcException(
                                error.string("message") ?: "RPC request failed",
                                error["code"]?.jsonPrimitive?.longOrNull?.toInt(),
                            ),
                        )
                    } else {
                        deferred?.complete(message.obj("result") ?: buildJsonObject {})
                    }
                    continue
                }
                val method = message.string("method") ?: continue
                val params = message.obj("params") ?: buildJsonObject {}
                if (id != null) {
                    if (!handleServerRequest(id, method, params)) return
                } else {
                    handleNotification(method, params)
                }
            }
            if (!protocolTerminationStarted.get()) {
                val hadPendingRequests = pending.isNotEmpty()
                outstandingApprovalRequests.invalidate()
                transport.abort()
                _events.emit(
                    AppServerEvent.Failure(
                        message = "Remote app-server disconnected",
                        kind = FailureKind.TRANSPORT,
                        hadPendingRequests = hadPendingRequests,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!protocolTerminationStarted.get()) {
                val hadPendingRequests = pending.isNotEmpty()
                outstandingApprovalRequests.invalidate()
                transport.abort()
                _events.emit(appServerReaderFailureEvent(error, hadPendingRequests))
            }
        } finally {
            val error = RpcException("Remote connection closed")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }

    private suspend fun stderrLoop() {
        try {
            while (true) {
                val line = transport.errorReader.readBoundedLine(MAX_APP_SERVER_LINE_CHARS) ?: break
                if (line.isNotBlank()) _events.emit(AppServerEvent.Diagnostic(diagnosticPreview(line)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppServerLineTooLongException) {
            terminateForProtocolError(
                AppServerEvent.FatalProtocolError(
                    "Remote app-server diagnostic exceeded the safe size limit; disconnected.",
                ),
            )
        } catch (_: Throwable) {
            // The stdout reader owns connection-level failure reporting.
        }
    }

    private suspend fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "item/started", "item/completed" -> params.obj("item")?.let(::parseTimelineItem)?.let { item ->
                val threadId = params.strictNonBlankString("threadId") ?: return@let
                val turnId = params.strictNonBlankString("turnId") ?: return@let
                val status = if (method == "item/started") {
                    "inProgress"
                } else {
                    item.status.takeIf { it in setOf("completed", "failed", "declined") } ?: "completed"
                }
                _events.emit(
                    AppServerEvent.ItemUpsert(
                        threadId,
                        item.copy(status = status, turnId = turnId),
                    ),
                )
            }
            "serverRequest/resolved" -> {
                when (val event = trackedServerRequestResolvedEvent(outstandingApprovalRequests, params)) {
                    is AppServerEvent.FatalProtocolError -> terminateForProtocolError(event)
                    else -> _events.emit(event)
                }
            }
            "item/agentMessage/delta" -> _events.emit(
                AppServerEvent.AgentDelta(
                    params.strictNonBlankString("threadId"),
                    params.strictNonBlankString("turnId"),
                    params.strictString("itemId").orEmpty(),
                    params.strictString("delta").orEmpty(),
                ),
            )
            "item/plan/delta" -> _events.emit(
                AppServerEvent.PlanDelta(
                    params.strictNonBlankString("threadId"),
                    params.strictNonBlankString("turnId"),
                    params.strictString("itemId").orEmpty(),
                    params.strictString("delta").orEmpty(),
                ),
            )
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> _events.emit(
                AppServerEvent.ReasoningDelta(
                    params.strictNonBlankString("threadId"),
                    params.strictNonBlankString("turnId"),
                    params.strictString("itemId").orEmpty(),
                    params.strictString("delta").orEmpty(),
                ),
            )
            "item/commandExecution/outputDelta" -> _events.emit(
                AppServerEvent.OutputDelta(
                    params.strictNonBlankString("threadId"),
                    params.strictNonBlankString("turnId"),
                    params.strictString("itemId").orEmpty(),
                    params.strictString("delta").orEmpty(),
                ),
            )
            "turn/diff/updated" -> Unit
            "turn/started" -> _events.emit(turnRunningEvent(params, running = true))
            "turn/completed" -> {
                val event = turnRunningEvent(params, running = false)
                turnCompletedFailureEvent(params)?.let { _events.emit(it) }
                _events.emit(event)
            }
            "account/updated" -> _events.emit(AppServerEvent.AccountChanged)
            "thread/started" -> _events.emit(threadStartedEvent(params))
            "thread/status/changed", "thread/archived", "thread/deleted", "thread/closed",
            "thread/name/updated", "thread/unarchived" ->
                _events.emit(AppServerEvent.ThreadsChanged)
            "skills/changed" -> _events.emit(AppServerEvent.SkillsChanged)
            "thread/goal/updated" -> {
                val goal = params["goal"]?.let(::parseThreadGoal) ?: return
                val parameterThreadId = params.strictNonBlankString("threadId")
                if (parameterThreadId != null && parameterThreadId != goal.threadId) return
                val threadId = parameterThreadId ?: goal.threadId
                _events.emit(AppServerEvent.GoalUpdated(threadId, goal))
            }
            "thread/goal/cleared" -> params.strictNonBlankString("threadId")?.let { threadId ->
                _events.emit(AppServerEvent.GoalCleared(threadId))
            }
            "thread/tokenUsage/updated" -> {
                val usage = params["tokenUsage"]?.let(::parseThreadTokenUsage) ?: return
                params.strictNonBlankString("threadId")?.let { threadId ->
                    _events.emit(AppServerEvent.TokenUsageUpdated(threadId, usage))
                }
            }
            "account/rateLimits/updated" -> {
                params["rateLimits"]?.let(::parseRateLimits)?.let { rateLimits ->
                    _events.emit(AppServerEvent.RateLimitsUpdated(rateLimits))
                }
            }
            "thread/compacted" -> params.strictNonBlankString("threadId")?.let { threadId ->
                _events.emit(AppServerEvent.ContextCompacted(threadId))
            }
            "mcpServer/oauthLogin/completed" -> _events.emit(
                AppServerEvent.McpLoginCompleted(
                    name = params.string("name").orEmpty(),
                    success = params.boolean("success"),
                    error = params.string("error"),
                ),
            )
            "thread/settings/updated" -> {
                val threadId = params.strictNonBlankString("threadId") ?: return
                val settings = params.obj("threadSettings") ?: return
                _events.emit(
                    AppServerEvent.ThreadSettingsUpdated(
                        threadId = threadId,
                        settings = RemoteThreadSettingsSnapshot(
                            model = settings.string("model"),
                            reasoningEffort = settings.string("effort"),
                            serviceTier = settings.string("serviceTier"),
                            collaborationMode = settings.obj("collaborationMode")?.string("mode"),
                            permissionProfile = settings.obj("activePermissionProfile")
                                ?.strictNonBlankString("id"),
                            approvalPolicy = settings.string("approvalPolicy"),
                            approvalsReviewer = settings.string("approvalsReviewer"),
                        ),
                    ),
                )
            }
            "account/login/completed" -> _events.emit(
                AppServerEvent.LoginCompleted(
                    success = params.boolean("success"),
                    error = params.string("error"),
                ),
            )
            "error" -> {
                _events.emit(genericFailureEvent(params))
            }
            "warning", "guardianWarning", "deprecationNotice", "configWarning" -> {
                val message = params.string("message") ?: params.obj("warning")?.string("message")
                if (!message.isNullOrBlank()) _events.emit(AppServerEvent.Warning(message))
            }
        }
    }

    private suspend fun handleServerRequest(id: RpcRequestId, method: String, params: JsonObject): Boolean {
        return when (val event = trackedServerRequestEvent(outstandingApprovalRequests, id, method, params)) {
            is AppServerEvent.FatalProtocolError -> {
                terminateForProtocolError(event)
                false
            }
            else -> {
                _events.emit(event)
                true
            }
        }
    }

    private suspend fun terminateForProtocolError(event: AppServerEvent.FatalProtocolError) {
        if (!protocolTerminationStarted.compareAndSet(false, true)) return
        outstandingApprovalRequests.invalidate()
        transport.abort()
        _events.emit(event)
    }

    companion object {
        internal fun turnRunningEvent(params: JsonObject, running: Boolean) =
            AppServerEvent.TurnRunning(
                threadId = params.strictNonBlankString("threadId"),
                running = running,
                turnId = params.obj("turn")?.strictNonBlankString("id"),
            )

        internal fun turnCompletedFailureEvent(params: JsonObject): AppServerEvent.Failure? {
            val message = params.obj("turn")?.obj("error")?.string("message")
                ?.takeIf(String::isNotBlank)
                ?: return null
            val lifecycle = turnRunningEvent(params, running = false)
            return AppServerEvent.Failure(
                message = message,
                threadId = lifecycle.threadId,
                turnId = lifecycle.turnId,
                turnIdStatus = if (lifecycle.turnId == null) {
                    AppServerEvent.FailureTurnIdStatus.INVALID
                } else {
                    AppServerEvent.FailureTurnIdStatus.EXACT
                },
            )
        }

        internal fun genericFailureEvent(params: JsonObject): AppServerEvent.Failure {
            val turnId = params.strictNonBlankString("turnId")
            val turnIdStatus = when {
                turnId != null -> AppServerEvent.FailureTurnIdStatus.EXACT
                "turnId" !in params -> AppServerEvent.FailureTurnIdStatus.LEGACY_ABSENT
                else -> AppServerEvent.FailureTurnIdStatus.INVALID
            }
            return AppServerEvent.Failure(
                message = params.obj("error")?.string("message") ?: "Codex turn failed",
                threadId = params.strictNonBlankString("threadId"),
                turnId = turnId,
                turnIdStatus = turnIdStatus,
            )
        }

        internal fun threadStartedEvent(params: JsonObject): AppServerEvent {
            val threadObject = params.obj("thread")
            val nestedThreadId = threadObject?.strictNonBlankString("id")
            val parameterThreadId = params.strictNonBlankString("threadId")

            if (nestedThreadId != null && parameterThreadId != null && nestedThreadId != parameterThreadId) {
                return AppServerEvent.ThreadsChanged
            }

            val threadId = nestedThreadId ?: parameterThreadId ?: return AppServerEvent.ThreadsChanged
            val thread = threadObject
                ?.takeIf { nestedThreadId == threadId }
                ?.let(::parseThread)
                ?.takeIf { it.id == threadId }
            return AppServerEvent.ThreadStarted(threadId, thread)
        }

        internal fun startedTurnId(result: JsonObject): String? =
            result.obj("turn")?.strictNonBlankString("id")
                ?: result.strictNonBlankString("turnId")

        internal fun parseRequestId(element: JsonElement): RpcRequestId? {
            val primitive = element as? JsonPrimitive ?: return null
            return if (primitive.isString) {
                RpcRequestId.Text(primitive.content)
            } else {
                primitive.longOrNull?.let { RpcRequestId.Number(it) }
            }
        }

        internal fun requireRequestId(element: JsonElement): RpcRequestId =
            parseRequestId(element) ?: throw RpcException("Invalid app-server JSON-RPC request id")

        internal fun responseEnvelope(id: RpcRequestId, result: JsonObject): JsonObject = buildJsonObject {
            when (id) {
                is RpcRequestId.Text -> put("id", id.value)
                is RpcRequestId.Number -> put("id", id.value)
            }
            put("result", result)
        }

        internal fun parseApprovalRequest(
            id: RpcRequestId,
            method: String,
            params: JsonObject,
            rawParams: String = params.toString(),
        ): ApprovalRequest = when (method) {
            "item/commandExecution/requestApproval", "execCommandApproval" ->
                parseCommandApprovalRequest(id, method, params, rawParams)
            "item/fileChange/requestApproval", "applyPatchApproval" ->
                parseFileApprovalRequest(id, method, params, rawParams)
            "item/permissions/requestApproval" -> parsePermissionApprovalRequest(id, method, params, rawParams)
            "item/tool/requestUserInput" -> parseUserInputRequest(id, method, params, rawParams)
            else -> ApprovalRequest(
                requestId = id,
                kind = ApprovalKind.UNKNOWN,
                title = "Remote request",
                detail = method,
                rawMethod = method,
                rawParams = rawParams,
                threadId = params.strictNonBlankString("threadId")
                    ?: params.strictNonBlankString("conversationId"),
                context = listOf(ApprovalContextField("Unrecognized request", rawParams)),
                availableDecisions = listOf("decline"),
                securityContextComplete = false,
            )
        }

        internal fun approvalResponseParams(
            request: ApprovalRequest,
            decision: String,
            answers: Map<String, List<String>> = emptyMap(),
        ): JsonObject = when (request.kind) {
            ApprovalKind.USER_INPUT -> buildJsonObject {
                if (!request.canSubmitAnswers(answers)) {
                    throw RpcException("User input response is incomplete or does not match the rendered choices")
                }
                put("answers", buildJsonObject {
                    request.questions.forEach { question ->
                        val values = answers[question.id].orEmpty()
                        if (values.isEmpty()) return@forEach
                        put(question.id, buildJsonObject {
                            put("answers", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                })
            }
            ApprovalKind.PERMISSION -> buildJsonObject {
                val original = runCatching {
                    APPROVAL_JSON.parseToJsonElement(request.rawParams).jsonObject
                }.getOrNull()
                val grantsRequestedScope = decision == "accept" || decision == "acceptForSession"
                put(
                    "permissions",
                    if (grantsRequestedScope) original?.obj("permissions") ?: buildJsonObject {}
                    else buildJsonObject {},
                )
                put("scope", if (decision == "acceptForSession") "session" else "turn")
            }
            else -> buildJsonObject {
                put("decision", approvalDecisionElement(request.rawMethod, decision))
            }
        }

        internal fun initializeParams(): JsonObject = buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "codex_remote_android")
                put("title", "Codex Remote for Android")
                put("version", BuildConfig.VERSION_NAME)
            })
            put("capabilities", buildJsonObject {
                put("experimentalApi", true)
                put("requestAttestation", false)
            })
        }

        internal fun threadResumeParams(
            threadId: String,
            cwd: String,
            paginated: Boolean,
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            if (cwd.isNotBlank()) put("cwd", cwd)
            if (paginated) {
                put("excludeTurns", true)
                put("initialTurnsPage", buildJsonObject {
                    put("limit", THREAD_HISTORY_PAGE_SIZE)
                    put("sortDirection", "desc")
                    put("itemsView", "full")
                })
            }
        }

        internal fun threadTurnsListParams(threadId: String, cursor: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("cursor", cursor)
            put("limit", THREAD_HISTORY_PAGE_SIZE)
            put("sortDirection", "desc")
            put("itemsView", "full")
        }

        internal fun parseTurnsTimeline(
            turns: List<JsonElement>,
            descending: Boolean = true,
        ): List<TimelineItem> = (if (descending) turns.asReversed() else turns)
            .flatMap { turnElement ->
                val turn = turnElement.asObject() ?: return@flatMap emptyList()
                val turnId = turn.strictNonBlankString("id")
                turn.array("items").mapNotNull { itemElement ->
                    parseTimelineItem(itemElement)?.copy(turnId = turnId)
                }
            }

        internal fun parseActiveTurnId(turns: List<JsonElement>): String? {
            val activeTurnIds = mutableListOf<String>()
            turns.forEach { turnElement ->
                val turn = turnElement.asObject() ?: return@forEach
                val status = turn.strictString("status") ?: return@forEach
                if (status !in ACTIVE_TURN_STATUSES) return@forEach
                val turnId = turn.strictNonBlankString("id") ?: return null
                activeTurnIds += turnId
            }
            return activeTurnIds.singleOrNull()
        }

        internal fun checkedNextHistoryCursor(
            returnedCursor: String?,
            consumedCursors: Set<String>,
        ): String? {
            val cursor = returnedCursor?.takeIf(String::isNotBlank) ?: return null
            if (cursor in consumedCursors) {
                throw RpcException("thread/turns/list returned a duplicate nextCursor")
            }
            return cursor
        }

        internal fun threadListParams(cursor: String?, archived: Boolean = false): JsonObject = buildJsonObject {
            put("limit", THREAD_PAGE_SIZE)
            put("sortKey", "updated_at")
            put("sortDirection", "desc")
            put("archived", archived)
            // Desktop passes an empty filter so app-server includes every interactive source.
            put("sourceKinds", buildJsonArray {})
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        }

        internal fun threadStartParams(
            cwd: String,
            model: String?,
            serviceTier: String? = null,
            approvalPolicy: String,
            approvalsReviewer: String = "user",
            permissionProfile: String? = null,
        ): JsonObject = buildJsonObject {
            put("cwd", cwd)
            put("approvalPolicy", approvalPolicy)
            put("approvalsReviewer", approvalsReviewer)
            if (permissionProfile.isNullOrBlank()) {
                put("sandbox", if (approvalPolicy == "never") "danger-full-access" else "workspace-write")
            }
            else put("permissions", permissionProfile)
            if (!model.isNullOrBlank()) put("model", model)
            if (!serviceTier.isNullOrBlank()) put("serviceTier", serviceTier)
        }

        internal fun threadSetNameParams(threadId: String, name: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("name", name)
        }

        internal fun threadArchiveParams(threadId: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
        }

        internal fun threadMutationParams(threadId: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
        }

        internal fun feedbackUploadParams(
            classification: String,
            reason: String,
            threadId: String?,
        ): JsonObject = buildJsonObject {
            put("classification", classification)
            reason.trim().takeIf(String::isNotEmpty)?.let { put("reason", it) }
            threadId?.let { put("threadId", it) }
            put("includeLogs", true)
            put("tags", buildJsonObject { put("client", "codex_remote_android") })
        }

        internal fun threadGoalGetParams(threadId: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
        }

        internal fun threadGoalSetParams(
            threadId: String,
            objective: String? = null,
            status: ThreadGoalStatus? = null,
            tokenBudget: Long? = null,
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            objective?.let { put("objective", it) }
            status?.let { put("status", it.wireValue()) }
            tokenBudget?.let { put("tokenBudget", it) }
        }

        internal fun threadGoalClearParams(threadId: String): JsonObject = buildJsonObject {
            put("threadId", threadId)
        }

        internal fun threadForkParams(
            threadId: String,
            cwd: String,
            model: String?,
            serviceTier: String? = null,
            approvalPolicy: String,
            approvalsReviewer: String = "user",
            permissionProfile: String? = null,
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            if (cwd.isNotBlank()) put("cwd", cwd)
            if (!model.isNullOrBlank()) put("model", model)
            if (!serviceTier.isNullOrBlank()) put("serviceTier", serviceTier)
            put("approvalPolicy", approvalPolicy)
            put("approvalsReviewer", approvalsReviewer)
            if (permissionProfile.isNullOrBlank()) {
                put("sandbox", if (approvalPolicy == "never") "danger-full-access" else "workspace-write")
            }
            else put("permissions", permissionProfile)
            put("ephemeral", false)
        }

        internal fun reviewStartParams(
            threadId: String,
            targetKind: ReviewTargetKind,
            targetValue: String,
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("delivery", "inline")
            put("target", buildJsonObject {
                when (targetKind) {
                    ReviewTargetKind.UNCOMMITTED_CHANGES -> put("type", "uncommittedChanges")
                    ReviewTargetKind.BASE_BRANCH -> {
                        put("type", "baseBranch")
                        put("branch", targetValue.trim())
                    }
                    ReviewTargetKind.CUSTOM -> {
                        put("type", "custom")
                        put("instructions", targetValue.trim())
                    }
                }
            })
        }

        internal fun skillsListParams(cwds: List<String>, forceReload: Boolean): JsonObject = buildJsonObject {
            if (cwds.isNotEmpty()) {
                put("cwds", buildJsonArray { cwds.distinct().forEach { add(JsonPrimitive(it)) } })
            }
            if (forceReload) put("forceReload", true)
        }

        internal fun pluginInstalledParams(cwds: List<String>): JsonObject = buildJsonObject {
            if (cwds.isNotEmpty()) {
                put("cwds", buildJsonArray { cwds.distinct().forEach { add(JsonPrimitive(it)) } })
            }
        }

        internal fun turnStartParams(
            threadId: String,
            text: String,
            cwd: String,
            model: String?,
            reasoningEffort: String?,
            serviceTier: String? = null,
            approvalPolicy: String,
            approvalsReviewer: String = "user",
            permissionProfile: String? = null,
            collaborationMode: RemoteCollaborationMode? = null,
            mentions: List<ComposerMention>,
            attachments: List<ComposerImageAttachment> = emptyList(),
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            if (cwd.isNotBlank()) put("cwd", cwd)
            put("approvalPolicy", approvalPolicy)
            put("approvalsReviewer", approvalsReviewer)
            if (!permissionProfile.isNullOrBlank()) {
                put("permissions", permissionProfile)
            } else if (approvalPolicy == "never") {
                put("sandboxPolicy", buildJsonObject { put("type", "dangerFullAccess") })
            }
            if (!model.isNullOrBlank()) put("model", model)
            if (!reasoningEffort.isNullOrBlank()) put("effort", reasoningEffort)
            if (!serviceTier.isNullOrBlank()) put("serviceTier", serviceTier)
            if (collaborationMode != null && !model.isNullOrBlank()) {
                put("collaborationMode", collaborationModeParam(collaborationMode, model, reasoningEffort))
            }
            put("input", userInputs(text, mentions, attachments))
        }

        internal fun turnSteerParams(
            threadId: String,
            expectedTurnId: String,
            text: String,
            mentions: List<ComposerMention>,
            attachments: List<ComposerImageAttachment>,
        ): JsonObject = buildJsonObject {
            put("threadId", threadId)
            put("expectedTurnId", expectedTurnId)
            put("input", userInputs(text, mentions, attachments))
        }

        private fun userInputs(
            text: String,
            mentions: List<ComposerMention>,
            attachments: List<ComposerImageAttachment>,
        ): JsonArray = buildJsonArray {
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    put("text_elements", buildJsonArray {})
                })
            }
            attachments.distinctBy { it.id }.forEach { attachment ->
                add(buildJsonObject {
                    put("type", "image")
                    put("url", attachment.dataUrl)
                })
            }
            mentions.distinctBy { "${it.kind}:${it.path}" }.forEach { mention ->
                add(buildJsonObject {
                    put("type", if (mention.kind == ComposerMentionKind.SKILL) "skill" else "mention")
                    put("name", mention.name)
                    put("path", mention.path)
                })
            }
        }

        private fun collaborationModeParam(
            collaborationMode: RemoteCollaborationMode,
            model: String,
            reasoningEffort: String?,
        ): JsonObject = buildJsonObject {
            put("mode", collaborationMode.mode)
            put("settings", buildJsonObject {
                put("model", collaborationMode.model ?: model)
                val effort = collaborationMode.reasoningEffort ?: reasoningEffort
                if (effort == null) put("reasoning_effort", JsonNull) else put("reasoning_effort", effort)
                put("developer_instructions", JsonNull)
            })
        }

        internal fun parseModel(element: JsonElement): RemoteModel? {
            val model = element.asObject() ?: return null
            val id = model.string("model") ?: model.string("id") ?: return null
            val serviceTiers = model.array("serviceTiers").mapNotNull { tierElement ->
                val tier = tierElement.asObject() ?: return@mapNotNull null
                val tierId = tier.string("id") ?: return@mapNotNull null
                com.codex.remote.domain.RemoteServiceTier(
                    id = tierId,
                    name = tier.string("name") ?: tierId,
                    description = tier.string("description").orEmpty(),
                )
            }.ifEmpty {
                model.array("additionalSpeedTiers").mapNotNull { tier ->
                    tier.jsonPrimitive.contentOrNull?.let {
                        com.codex.remote.domain.RemoteServiceTier(it, it, "")
                    }
                }
            }
            return RemoteModel(
                id = id,
                displayName = model.string("displayName") ?: id,
                description = model.string("description").orEmpty(),
                isDefault = model.boolean("isDefault"),
                hidden = model.boolean("hidden"),
                supportedReasoningEfforts = model.array("supportedReasoningEfforts").mapNotNull { option ->
                    val item = option.asObject() ?: return@mapNotNull null
                    val value = item.string("reasoningEffort") ?: return@mapNotNull null
                    ReasoningEffortOption(value = value, description = item.string("description").orEmpty())
                },
                defaultReasoningEffort = model.string("defaultReasoningEffort"),
                inputModalities = model.array("inputModalities")
                    .mapNotNullTo(linkedSetOf()) { it.jsonPrimitive.contentOrNull },
                serviceTiers = serviceTiers,
                defaultServiceTier = model.string("defaultServiceTier"),
            )
        }

        internal fun parseCollaborationMode(element: JsonElement): RemoteCollaborationMode? {
            val mode = element.asObject() ?: return null
            val wireMode = mode.string("mode") ?: return null
            return RemoteCollaborationMode(
                name = mode.string("name") ?: wireMode,
                mode = wireMode,
                model = mode.string("model"),
                reasoningEffort = mode.string("reasoning_effort") ?: mode.string("reasoningEffort"),
            )
        }

        internal fun parsePermissionProfile(element: JsonElement): RemotePermissionProfile? {
            val profile = element.asObject() ?: return null
            val id = profile.strictNonBlankString("id") ?: return null
            return RemotePermissionProfile(
                id = id,
                description = profile.string("description").orEmpty(),
                allowed = profile.booleanOrDefault("allowed", true),
            )
        }

        internal fun parseAccount(result: JsonObject): RemoteAccount {
            val account = result.obj("account")
            return RemoteAccount(
                type = account?.string("type"),
                email = account?.string("email"),
                planType = account?.string("planType"),
                requiresOpenaiAuth = result.boolean("requiresOpenaiAuth"),
            )
        }

        internal fun parseThreadGoal(element: JsonElement): ThreadGoal? {
            val goal = element.asObject() ?: return null
            val threadId = goal.strictNonBlankString("threadId") ?: return null
            val objective = goal.string("objective") ?: return null
            val status = goal.string("status")?.toThreadGoalStatus() ?: return null
            return ThreadGoal(
                threadId = threadId,
                objective = objective,
                status = status,
                tokenBudget = goal["tokenBudget"]?.jsonPrimitive?.longOrNull,
                tokensUsed = goal["tokensUsed"]?.jsonPrimitive?.longOrNull ?: 0,
                timeUsedSeconds = goal["timeUsedSeconds"]?.jsonPrimitive?.longOrNull ?: 0,
                createdAt = goal["createdAt"]?.jsonPrimitive?.longOrNull ?: 0,
                updatedAt = goal["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }

        internal fun parseMcpServerStatus(element: JsonElement): RemoteMcpServerStatus? {
            val status = element.asObject() ?: return null
            val name = status.string("name") ?: return null
            return RemoteMcpServerStatus(
                name = name,
                authStatus = status.string("authStatus").orEmpty(),
                toolCount = status.obj("tools")?.size ?: 0,
                resourceCount = status.array("resources").size + status.array("resourceTemplates").size,
            )
        }

        internal fun parseRateLimits(element: JsonElement): RemoteRateLimits? {
            val limits = element.asObject() ?: return null
            val credits = limits.obj("credits")
            return RemoteRateLimits(
                limitName = limits.string("limitName"),
                planType = limits.string("planType"),
                primary = limits["primary"]?.let(::parseRateLimitWindow),
                secondary = limits["secondary"]?.let(::parseRateLimitWindow),
                creditsBalance = credits?.string("balance"),
                creditsUnlimited = credits?.boolean("unlimited") ?: false,
            )
        }

        internal fun parseThreadTokenUsage(element: JsonElement): RemoteThreadTokenUsage? {
            val usage = element.asObject() ?: return null
            val total = usage.obj("total") ?: return null
            return RemoteThreadTokenUsage(
                totalTokens = total["totalTokens"]?.jsonPrimitive?.longOrNull ?: 0,
                inputTokens = total["inputTokens"]?.jsonPrimitive?.longOrNull ?: 0,
                outputTokens = total["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0,
                modelContextWindow = usage["modelContextWindow"]?.jsonPrimitive?.longOrNull,
            )
        }

        private fun parseRateLimitWindow(element: JsonElement): RateLimitWindowSnapshot? {
            val window = element.asObject() ?: return null
            return RateLimitWindowSnapshot(
                usedPercent = window["usedPercent"]?.jsonPrimitive?.doubleOrNull ?: return null,
                windowDurationMinutes = window["windowDurationMins"]?.jsonPrimitive?.longOrNull,
                resetsAt = window["resetsAt"]?.jsonPrimitive?.longOrNull,
            )
        }

        internal fun parseSkill(element: JsonElement, cwd: String): RemoteSkill? {
            val skill = element.asObject() ?: return null
            val name = skill.string("name") ?: return null
            val path = skill.string("path") ?: return null
            val interfaceData = skill.obj("interface")
            return RemoteSkill(
                name = name,
                displayName = interfaceData?.string("displayName") ?: name,
                description = interfaceData?.string("shortDescription")
                    ?: skill.string("shortDescription")
                    ?: skill.string("description").orEmpty(),
                path = path,
                enabled = skill.booleanOrDefault("enabled", true),
                cwds = setOf(cwd),
            )
        }

        internal fun parsePlugin(element: JsonElement, marketplace: String): RemotePlugin? {
            val plugin = element.asObject() ?: return null
            val id = plugin.string("id") ?: return null
            val name = plugin.string("name") ?: id.substringBefore('@')
            val interfaceData = plugin.obj("interface")
            val available = plugin.string("availability")?.equals("DISABLED_BY_ADMIN", ignoreCase = true) != true
            return RemotePlugin(
                id = id,
                name = name,
                displayName = interfaceData?.string("displayName") ?: name,
                description = interfaceData?.string("shortDescription").orEmpty(),
                marketplace = marketplace,
                mentionPath = "plugin://$id",
                enabled = plugin.booleanOrDefault("installed", true) &&
                    plugin.booleanOrDefault("enabled", true) && available,
            )
        }

        internal fun parseTimelineItem(element: JsonElement): TimelineItem? {
            val item = element.asObject() ?: return null
            val type = item.strictString("type")?.takeIf(String::isNotBlank) ?: return null
            val id = item.strictString("id")?.takeIf(String::isNotBlank) ?: return null
            return when (type) {
                "userMessage" -> TimelineItem(
                    id,
                    TimelineKind.USER,
                    body = item.array("content").mapNotNull { contentElement ->
                        val content = contentElement.asObject() ?: return@mapNotNull null
                        when (content.string("type")) {
                            "text" -> content.string("text")
                            "image", "localImage" -> "[Image]"
                            "audio", "localAudio" -> "[Audio]"
                            "skill", "mention" -> content.string("name")?.let { "\$$it" }
                            else -> null
                        }
                    }.joinToString("\n"),
                    isGoal = item.boolean("goal"),
                )
                "agentMessage" -> TimelineItem(id, TimelineKind.AGENT, body = item.string("text").orEmpty())
                "reasoning" -> TimelineItem(
                    id,
                    TimelineKind.REASONING,
                    title = "Reasoning",
                    body = (item.array("summary").ifEmpty { item.array("content") })
                        .joinToString("\n") { it.jsonPrimitive.contentOrNull.orEmpty() },
                )
                "plan" -> TimelineItem(id, TimelineKind.PLAN, title = "Plan", body = item.string("text").orEmpty())
                "commandExecution" -> TimelineItem(
                    id,
                    TimelineKind.COMMAND,
                    title = item.string("command").orEmpty(),
                    body = item.string("aggregatedOutput").orEmpty(),
                    status = item.string("status").orEmpty(),
                )
                "fileChange" -> {
                    val changeElements = item["changes"] as? JsonArray
                    val changes = changeElements.orEmpty().mapNotNull(::parseTimelineFileChange)
                    TimelineItem(
                        id,
                        TimelineKind.FILE_CHANGE,
                        title = changes.joinToString(", ") { it.path },
                        status = item.string("status").orEmpty(),
                        fileChanges = changes,
                        fileChangesComplete = changeElements != null && changes.size == changeElements.size,
                    )
                }
                "mcpToolCall", "dynamicToolCall", "collabAgentToolCall" -> TimelineItem(
                    id,
                    TimelineKind.TOOL,
                    title = item.string("tool") ?: type,
                    body = item["arguments"]?.toString()
                        ?: item.string("prompt")
                        ?: item["result"]?.toString().orEmpty(),
                    status = item.string("status").orEmpty(),
                )
                "subAgentActivity" -> TimelineItem(
                    id,
                    TimelineKind.TOOL,
                    title = "Sub-agent ${item.string("kind").orEmpty()}",
                    body = item.string("agentPath").orEmpty(),
                )
                "webSearch" -> TimelineItem(
                    id,
                    TimelineKind.TOOL,
                    title = "Web search",
                    body = item.string("query").orEmpty(),
                )
                "imageView" -> TimelineItem(
                    id,
                    TimelineKind.TOOL,
                    title = "Viewed image",
                    body = item.string("path").orEmpty(),
                )
                "imageGeneration" -> TimelineItem(
                    id,
                    TimelineKind.TOOL,
                    title = "Image generation",
                    body = item.string("savedPath") ?: item.string("revisedPrompt").orEmpty(),
                    status = item.string("status").orEmpty(),
                )
                "enteredReviewMode", "exitedReviewMode" -> TimelineItem(
                    id,
                    TimelineKind.REVIEW,
                    title = if (type == "enteredReviewMode") "Code review started" else "Code review completed",
                    body = item.string("review").orEmpty(),
                )
                "contextCompaction" -> TimelineItem(
                    id,
                    TimelineKind.COMPACTION,
                    title = "Context compacted",
                )
                "hookPrompt" -> TimelineItem(id, TimelineKind.TOOL, title = "Hook", body = "Prompt context added")
                "sleep" -> TimelineItem(id, TimelineKind.TOOL, title = "Wait", body = "Codex waited before continuing")
                else -> null
            }
        }

        internal fun parseThread(element: JsonElement): RemoteThread? {
            val item = element.asObject() ?: return null
            val id = item.strictNonBlankString("id") ?: return null
            return RemoteThread(
                id = id,
                title = item.string("name") ?: item.string("preview")?.take(80).orEmpty().ifBlank { "New task" },
                cwd = item.string("cwd").orEmpty(),
                updatedAt = item["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
                isPinned = item.boolean("isPinned"),
                status = when (val status = item["status"]) {
                    is JsonPrimitive -> status.contentOrNull.orEmpty()
                    is JsonObject -> status.string("type") ?: status.keys.firstOrNull().orEmpty()
                    else -> ""
                },
            )
        }
    }

    internal fun beginClose() {
        protocolTerminationStarted.set(true)
        outstandingApprovalRequests.invalidate()
        readerJob?.cancel()
        scope.cancel()
        val error = RpcException("Remote connection closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        beginClose()
        transport.close()
    }
}

internal fun appServerReaderFailureEvent(
    error: Throwable,
    hadPendingRequests: Boolean = false,
): AppServerEvent {
    val isTransportInterruption = generateSequence(error) { it.cause }.any { it is IOException }
    return if (isTransportInterruption) {
        AppServerEvent.Failure(
            message = error.message ?: "SSH data stream was interrupted",
            kind = FailureKind.TRANSPORT,
            hadPendingRequests = hadPendingRequests,
        )
    } else {
        AppServerEvent.FatalProtocolError(
            "Remote app-server reader failed safely; disconnected without automatic retry.",
        )
    }
}

private val APPROVAL_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

internal const val MAX_APP_SERVER_LINE_CHARS = 8 * 1024 * 1024
internal const val MAX_APPROVAL_MESSAGE_CHARS = 512L * 1024L
internal const val MAX_APPROVAL_QUESTIONS = 3
internal const val MAX_APPROVAL_OPTIONS_PER_QUESTION = 32
internal const val MAX_TRACKED_APPROVAL_REQUESTS = 256
internal const val MAX_TRACKED_APPROVAL_ID_CHARS = 1L * 1024L * 1024L
internal const val MAX_TRACKED_APPROVAL_RETAINED_CHARS = 4L * 1024L * 1024L
private const val MAX_DIAGNOSTIC_CHARS = 4_096

private val NEW_COMMAND_APPROVAL_KEYS = setOf(
    "additionalPermissions",
    "approvalId",
    "availableDecisions",
    "command",
    "commandActions",
    "cwd",
    "environmentId",
    "itemId",
    "networkApprovalContext",
    "proposedExecpolicyAmendment",
    "proposedNetworkPolicyAmendments",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
)

private val LEGACY_COMMAND_APPROVAL_KEYS = setOf(
    "approvalId",
    "callId",
    "command",
    "conversationId",
    "cwd",
    "parsedCmd",
    "reason",
)

private val NEW_FILE_APPROVAL_KEYS = setOf(
    "grantRoot",
    "itemId",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
)

private val LEGACY_FILE_APPROVAL_KEYS = setOf(
    "callId",
    "conversationId",
    "fileChanges",
    "grantRoot",
    "reason",
)

private val PERMISSION_APPROVAL_KEYS = setOf(
    "cwd",
    "environmentId",
    "itemId",
    "permissions",
    "reason",
    "startedAtMs",
    "threadId",
    "turnId",
)

private val USER_INPUT_REQUEST_KEYS = setOf(
    "autoResolutionMs",
    "isBlocking",
    "itemId",
    "questions",
    "threadId",
    "turnId",
)

private val SUPPORTED_APPROVAL_DECISIONS = setOf("accept", "acceptForSession", "decline", "cancel")

private fun parseCommandApprovalRequest(
    id: RpcRequestId,
    method: String,
    params: JsonObject,
    rawParams: String,
): ApprovalRequest {
    val legacy = method == "execCommandApproval"
    val allowedKeys = if (legacy) LEGACY_COMMAND_APPROVAL_KEYS else NEW_COMMAND_APPROVAL_KEYS
    val unknown = params.unknownFields(allowedKeys)
    val threadId = params.strictNonBlankString(if (legacy) "conversationId" else "threadId")
    val turnId = params.strictNonBlankString("turnId")
    val itemId = params.strictString(if (legacy) "callId" else "itemId")
    val cwd = params.strictString("cwd")
    val command = params["command"].approvalDisplayValue()
    val requiredContextPresent = if (legacy) {
        !threadId.isNullOrBlank() && !itemId.isNullOrBlank() && !cwd.isNullOrBlank() &&
            params["command"].isStringArray() && params["parsedCmd"].isValidLegacyParsedCommands()
    } else {
        !threadId.isNullOrBlank() && !turnId.isNullOrBlank() && !itemId.isNullOrBlank() &&
            params["startedAtMs"].isJsonLong() && !params.strictString("command").isNullOrBlank() &&
            !cwd.isNullOrBlank()
    }
    val permissionsValid = params["additionalPermissions"].isNullOrValidPermissionProfile()
    val availableDecisionsValid = params["availableDecisions"].isNullOrValidAvailableDecisions()
    val commandActionsValid = legacy || params["commandActions"].isNullOrValidCommandActions()
    val networkContextValid = legacy || params["networkApprovalContext"].isNullOrValidNetworkApprovalContext()
    val execPolicyValid = legacy || params["proposedExecpolicyAmendment"].isNullOrStringArray()
    val networkPolicyValid = legacy || params["proposedNetworkPolicyAmendments"].isNullOrValidNetworkPolicyAmendments()
    val securityContextComplete = requiredContextPresent && permissionsValid &&
        availableDecisionsValid && commandActionsValid && networkContextValid && execPolicyValid &&
        networkPolicyValid && params.hasValidOptionalStrings(
            if (legacy) setOf("approvalId", "reason")
            else setOf("approvalId", "command", "cwd", "environmentId", "reason"),
        ) && unknown == null
    val context = buildList {
        add(ApprovalContextField("Working directory", cwd ?: "Not provided"))
        addContext("Reason", params.string("reason"))
        addContext("Thread", threadId)
        addContext("Turn", turnId)
        addContext("Item", itemId)
        addContext("Approval callback", params.string("approvalId"))
        addContext("Started at (ms)", (params["startedAtMs"] as? JsonPrimitive)?.longOrNull?.toString())
        addContext("Environment", params.string("environmentId"))
        addJsonContext("Parsed command actions", params[if (legacy) "parsedCmd" else "commandActions"])
        addJsonContext("Available decisions", params["availableDecisions"])
        addJsonContext("Additional permissions", params["additionalPermissions"])
        addJsonContext("Network request", params["networkApprovalContext"])
        addJsonContext("Exec policy amendment", params["proposedExecpolicyAmendment"])
        addJsonContext("Network policy amendments", params["proposedNetworkPolicyAmendments"])
        addJsonContext("Unrecognized request data", unknown)
        if (!securityContextComplete) {
            add(ApprovalContextField("Validation warning", APPROVAL_VALIDATION_WARNING))
        }
    }
    return ApprovalRequest(
        requestId = id,
        kind = ApprovalKind.COMMAND,
        title = "Allow command execution?",
        detail = command ?: params.string("reason") ?: "Remote Codex requests command execution",
        rawMethod = method,
        rawParams = rawParams,
        threadId = threadId,
        turnId = turnId,
        itemId = itemId,
        approvalId = params.strictString("approvalId"),
        startedAtMs = (params["startedAtMs"] as? JsonPrimitive)?.longOrNull,
        cwd = cwd,
        context = context,
        availableDecisions = params.approvalDecisions(ApprovalKind.COMMAND, securityContextComplete),
        securityContextComplete = securityContextComplete,
    )
}

private fun parseFileApprovalRequest(
    id: RpcRequestId,
    method: String,
    params: JsonObject,
    rawParams: String,
): ApprovalRequest {
    val legacy = method == "applyPatchApproval"
    val allowedKeys = if (legacy) LEGACY_FILE_APPROVAL_KEYS else NEW_FILE_APPROVAL_KEYS
    val unknown = params.unknownFields(allowedKeys)
    val threadId = params.strictNonBlankString(if (legacy) "conversationId" else "threadId")
    val turnId = params.strictNonBlankString("turnId")
    val itemId = params.strictString(if (legacy) "callId" else "itemId")
    val (fileChanges, fileChangesValid) = if (legacy) params.parseLegacyFileChanges() else emptyList<FileChangeSummary>() to true
    val requiredContextPresent = if (legacy) {
        !threadId.isNullOrBlank() && !itemId.isNullOrBlank() && params["fileChanges"] is JsonObject
    } else {
        !threadId.isNullOrBlank() && !turnId.isNullOrBlank() && !itemId.isNullOrBlank() &&
            params["startedAtMs"].isJsonLong()
    }
    val securityContextComplete = requiredContextPresent && fileChangesValid &&
        params.hasValidOptionalStrings(setOf("grantRoot", "reason")) && unknown == null
    val context = buildList {
        addContext("Reason", params.string("reason"))
        addContext("Session write root", params.string("grantRoot"))
        addContext("Thread", threadId)
        addContext("Turn", turnId)
        addContext("Item", itemId)
        addContext("Started at (ms)", (params["startedAtMs"] as? JsonPrimitive)?.longOrNull?.toString())
        addJsonContext("Unrecognized request data", unknown)
        if (!securityContextComplete) {
            add(ApprovalContextField("Validation warning", APPROVAL_VALIDATION_WARNING))
        }
    }
    return ApprovalRequest(
        requestId = id,
        kind = ApprovalKind.FILE_CHANGE,
        title = "Allow file changes?",
        detail = params.string("reason") ?: params.string("grantRoot") ?: "Remote Codex requests write access to the project",
        rawMethod = method,
        rawParams = rawParams,
        threadId = threadId,
        turnId = turnId,
        itemId = itemId,
        startedAtMs = (params["startedAtMs"] as? JsonPrimitive)?.longOrNull,
        context = context,
        fileChanges = fileChanges,
        availableDecisions = params.approvalDecisions(ApprovalKind.FILE_CHANGE, securityContextComplete),
        securityContextComplete = securityContextComplete,
    )
}

private fun parsePermissionApprovalRequest(
    id: RpcRequestId,
    method: String,
    params: JsonObject,
    rawParams: String,
): ApprovalRequest {
    val unknown = params.unknownFields(PERMISSION_APPROVAL_KEYS)
    val threadId = params.strictNonBlankString("threadId")
    val turnId = params.strictNonBlankString("turnId")
    val itemId = params.strictString("itemId")
    val cwd = params.strictString("cwd")
    val permissions = params["permissions"]
    val securityContextComplete = !threadId.isNullOrBlank() && !turnId.isNullOrBlank() &&
        !itemId.isNullOrBlank() && !cwd.isNullOrBlank() &&
        params["startedAtMs"].isJsonLong() &&
        permissions is JsonObject && permissions.isValidPermissionProfile() &&
        params.hasValidOptionalStrings(setOf("environmentId", "reason")) && unknown == null
    val context = buildList {
        addContext("Working directory", cwd)
        addContext("Reason", params.string("reason"))
        addContext("Thread", threadId)
        addContext("Turn", turnId)
        addContext("Item", itemId)
        addContext("Started at (ms)", (params["startedAtMs"] as? JsonPrimitive)?.longOrNull?.toString())
        addContext("Environment", params.string("environmentId"))
        addJsonContext("Requested permissions", permissions)
        addJsonContext("Unrecognized request data", unknown)
        if (!securityContextComplete) {
            add(ApprovalContextField("Validation warning", APPROVAL_VALIDATION_WARNING))
        }
    }
    return ApprovalRequest(
        requestId = id,
        kind = ApprovalKind.PERMISSION,
        title = "Allow additional permissions?",
        detail = params.string("reason") ?: "Remote Codex requests additional file or network permissions",
        rawMethod = method,
        rawParams = rawParams,
        threadId = threadId,
        turnId = turnId,
        itemId = itemId,
        startedAtMs = (params["startedAtMs"] as? JsonPrimitive)?.longOrNull,
        cwd = cwd,
        context = context,
        availableDecisions = params.approvalDecisions(ApprovalKind.PERMISSION, securityContextComplete),
        securityContextComplete = securityContextComplete,
    )
}

private fun parseUserInputRequest(
    id: RpcRequestId,
    method: String,
    params: JsonObject,
    rawParams: String,
): ApprovalRequest {
    val unknown = params.unknownFields(USER_INPUT_REQUEST_KEYS)
    val questionElements = params["questions"] as? JsonArray
    val boundedQuestionElements = questionElements?.takeIf { it.size in 1..MAX_APPROVAL_QUESTIONS }
    val questions = boundedQuestionElements.orEmpty().mapNotNull(::parseApprovalQuestion)
    val isBlockingValid = (params["isBlocking"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.toBooleanStrictOrNull() != null
    val autoResolution = params["autoResolutionMs"]
    val autoResolutionValid = autoResolution == null || autoResolution is JsonNull ||
        autoResolution.isJsonLong() && autoResolution.jsonPrimitive.longOrNull!! >= 0L
    val securityContextComplete = boundedQuestionElements != null && questions.isNotEmpty() &&
        questions.size == boundedQuestionElements.size &&
        questions.map(ApprovalQuestion::id).distinct().size == questions.size &&
        isBlockingValid && autoResolutionValid && unknown == null &&
        params.strictNonBlankString("threadId") != null &&
        params.strictNonBlankString("turnId") != null && !params.strictString("itemId").isNullOrBlank()
    return ApprovalRequest(
        requestId = id,
        kind = ApprovalKind.USER_INPUT,
        title = questions.firstOrNull()?.header?.ifBlank { null } ?: "Codex needs your input",
        detail = questions.firstOrNull()?.question ?: "Enter a reply",
        rawMethod = method,
        rawParams = rawParams,
        questions = questions,
        threadId = params.strictNonBlankString("threadId"),
        turnId = params.strictNonBlankString("turnId"),
        itemId = params.strictString("itemId"),
        availableDecisions = if (securityContextComplete) listOf("accept") else emptyList(),
        securityContextComplete = securityContextComplete,
    )
}

private fun approvalDecisionElement(method: String, decision: String): JsonElement {
    if (method != "execCommandApproval" && method != "applyPatchApproval") return JsonPrimitive(decision)
    return when (decision) {
        "accept" -> JsonPrimitive("approved")
        "acceptForSession" -> JsonPrimitive("approved_for_session")
        "decline" -> buildJsonObject {
            put("denied", buildJsonObject { put("rejection", "Denied by user") })
        }
        "cancel" -> JsonPrimitive("abort")
        else -> JsonPrimitive(decision)
    }
}

private fun JsonObject.approvalDecisions(
    kind: ApprovalKind,
    securityContextComplete: Boolean,
): List<String> {
    val advertised = this["availableDecisions"]
    val decisions = if (advertised == null || advertised is JsonNull) {
        com.codex.remote.domain.defaultApprovalDecisions(kind)
    } else {
        (advertised as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
            .filter { it in SUPPORTED_APPROVAL_DECISIONS }
            .distinct()
    }
    return if (securityContextComplete) decisions else decisions.filterNot { it.startsWith("accept") }
}

private fun JsonObject.parseLegacyFileChanges(): Pair<List<FileChangeSummary>, Boolean> {
    val changes = obj("fileChanges") ?: return emptyList<FileChangeSummary>() to false
    if (changes.isEmpty()) return emptyList<FileChangeSummary>() to false
    val parsed = mutableListOf<FileChangeSummary>()
    for ((path, element) in changes) {
        if (path.isBlank()) return emptyList<FileChangeSummary>() to false
        val change = element.asObject() ?: return emptyList<FileChangeSummary>() to false
        val kind = change.strictString("type") ?: return emptyList<FileChangeSummary>() to false
        val (diff, movePath) = when (kind) {
            "update" -> {
                if (change.keys.any { it !in setOf("type", "unified_diff", "move_path") }) {
                    return emptyList<FileChangeSummary>() to false
                }
                val diff = change.strictString("unified_diff")
                    ?: return emptyList<FileChangeSummary>() to false
                val rawMovePath = change["move_path"]
                val movePath = when (rawMovePath) {
                    null, JsonNull -> null
                    is JsonPrimitive -> rawMovePath.takeIf(JsonPrimitive::isString)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return emptyList<FileChangeSummary>() to false
                    else -> return emptyList<FileChangeSummary>() to false
                }
                diff to movePath
            }
            "add", "delete" -> {
                if (change.keys != setOf("type", "content")) {
                    return emptyList<FileChangeSummary>() to false
                }
                val diff = change.strictString("content")
                    ?: return emptyList<FileChangeSummary>() to false
                diff to null
            }
            else -> return emptyList<FileChangeSummary>() to false
        }
        parsed += FileChangeSummary(path = path, kind = kind, diff = diff, movePath = movePath)
    }
    return parsed to true
}

private fun parseTimelineFileChange(element: JsonElement): FileChangeSummary? {
    val change = element.asObject() ?: return null
    if (change.keys != setOf("diff", "kind", "path")) return null
    val path = change.strictString("path")?.takeIf(String::isNotBlank) ?: return null
    val diff = change.strictString("diff") ?: return null
    val kind = change.obj("kind") ?: return null
    val kindType = kind.strictString("type") ?: return null
    val movePath = when (kindType) {
        "add", "delete" -> {
            if (kind.keys != setOf("type")) return null
            null
        }
        "update" -> {
            if (kind.keys.any { it !in setOf("type", "move_path") }) return null
            val rawMovePath = kind["move_path"]
            when (rawMovePath) {
                null, JsonNull -> null
                is JsonPrimitive -> rawMovePath.takeIf(JsonPrimitive::isString)?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                else -> return null
            }
        }
        else -> return null
    }
    return FileChangeSummary(path = path, kind = kindType, diff = diff, movePath = movePath)
}

private fun JsonElement?.isStringArray(): Boolean =
    this is JsonArray && all { it is JsonPrimitive && it.isString }

private fun JsonElement?.isNullOrStringArray(): Boolean =
    this == null || this is JsonNull || isStringArray()

private fun JsonElement?.isNullOrValidCommandActions(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> all { element ->
        val command = element.asObject() ?: return@all false
        val type = command.strictString("type") ?: return@all false
        if (command.strictString("command") == null) return@all false
        when (type) {
            "read" -> command.keys == setOf("command", "name", "path", "type") &&
                command.strictString("name") != null && command.strictString("path") != null
            "listFiles" -> command.keys.all { it in setOf("command", "path", "type") } &&
                command.hasValidOptionalStrings(setOf("path"))
            "search" -> command.keys.all { it in setOf("command", "path", "query", "type") } &&
                command.hasValidOptionalStrings(setOf("path", "query"))
            "unknown" -> command.keys == setOf("command", "type")
            else -> false
        }
    }
    else -> false
}

private fun JsonElement?.isNullOrValidNetworkApprovalContext(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonObject -> keys == setOf("host", "protocol") &&
        !strictString("host").isNullOrBlank() &&
        strictString("protocol") in setOf("http", "https", "socks5Tcp", "socks5Udp")
    else -> false
}

private fun JsonElement?.isNullOrValidNetworkPolicyAmendments(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> all { element ->
        val amendment = element.asObject() ?: return@all false
        amendment.keys == setOf("action", "host") &&
            amendment.strictString("action") in setOf("allow", "deny") &&
            !amendment.strictString("host").isNullOrBlank()
    }
    else -> false
}

private fun JsonElement?.isValidLegacyParsedCommands(): Boolean {
    val commands = this as? JsonArray ?: return false
    return commands.all { element ->
        val command = element.asObject() ?: return@all false
        val type = command.strictString("type") ?: return@all false
        if (command.strictString("cmd") == null) return@all false
        when (type) {
            "read" -> command.keys == setOf("cmd", "name", "path", "type") &&
                command.strictString("name") != null && command.strictString("path") != null
            "list_files" -> command.keys.all { it in setOf("cmd", "path", "type") } &&
                command.hasValidOptionalStrings(setOf("path"))
            "search" -> command.keys.all { it in setOf("cmd", "path", "query", "type") } &&
                command.hasValidOptionalStrings(setOf("path", "query"))
            "unknown" -> command.keys == setOf("cmd", "type")
            else -> false
        }
    }
}

private fun JsonElement?.isNullOrValidAvailableDecisions(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> all(JsonElement::isValidAvailableDecision)
    else -> false
}

private fun JsonElement.isValidAvailableDecision(): Boolean = when (this) {
    is JsonPrimitive -> isString && contentOrNull in SUPPORTED_APPROVAL_DECISIONS
    is JsonObject -> when {
        keys == setOf("acceptWithExecpolicyAmendment") -> {
            val wrapper = obj("acceptWithExecpolicyAmendment")
            val amendment = wrapper?.get("execpolicy_amendment")
            wrapper?.keys == setOf("execpolicy_amendment") && amendment.isStringArray()
        }
        keys == setOf("applyNetworkPolicyAmendment") -> {
            val wrapper = obj("applyNetworkPolicyAmendment")
            val amendment = wrapper?.obj("network_policy_amendment")
            wrapper?.keys == setOf("network_policy_amendment") &&
                amendment?.keys == setOf("action", "host") &&
                amendment.strictString("action") in setOf("allow", "deny") &&
                amendment.strictString("host") != null
        }
        else -> false
    }
    else -> false
}

private fun parseApprovalQuestion(element: JsonElement): ApprovalQuestion? {
    val question = element.asObject() ?: return null
    if (question.keys.any { it !in setOf("header", "id", "isOther", "isSecret", "options", "question") }) {
        return null
    }
    val id = question.strictString("id")?.takeIf(String::isNotBlank) ?: return null
    val header = question.strictString("header") ?: return null
    val prompt = question.strictString("question")?.takeIf(String::isNotBlank) ?: return null
    val isOther = question.strictBoolean("isOther") ?: return null
    val isSecret = question.strictBoolean("isSecret") ?: return null
    if (isSecret) return null
    if (!question.containsKey("options")) return null
    val optionsElement = question["options"]
    val options = when (optionsElement) {
        null, JsonNull -> emptyList()
        is JsonArray -> {
            if (optionsElement.size > MAX_APPROVAL_OPTIONS_PER_QUESTION) return null
            optionsElement.mapNotNull { optionElement ->
                val option = optionElement.asObject() ?: return null
                if (option.keys != setOf("description", "label")) return null
                ApprovalOption(
                    description = option.strictString("description") ?: return null,
                    label = option.strictString("label")?.takeIf(String::isNotBlank) ?: return null,
                )
            }
        }
        else -> return null
    }
    if (options.map(ApprovalOption::label).distinct().size != options.size) return null
    return ApprovalQuestion(id = id, header = header, question = prompt, isOther = isOther, options = options)
}

private fun JsonObject.strictBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
    ?.takeUnless(JsonPrimitive::isString)
    ?.contentOrNull
    ?.toBooleanStrictOrNull()

private fun JsonObject.unknownFields(allowedKeys: Set<String>): JsonObject? {
    val values = entries.filter { it.key !in allowedKeys }.associate { it.toPair() }
    return if (values.isEmpty()) null else JsonObject(values)
}

private fun JsonElement?.approvalDisplayValue(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> toString()
    else -> toString()
}

private fun MutableList<ApprovalContextField>.addContext(label: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { add(ApprovalContextField(label, it)) }
}

private fun MutableList<ApprovalContextField>.addJsonContext(label: String, value: JsonElement?) {
    if (value != null && value !is JsonNull) add(ApprovalContextField(label, value.toString()))
}

private fun JsonElement?.isNullOrValidPermissionProfile(): Boolean =
    this == null || this is JsonNull || (this as? JsonObject)?.isValidPermissionProfile() == true

private fun JsonObject.isValidPermissionProfile(): Boolean {
    if (keys.any { it !in setOf("fileSystem", "network") }) return false
    val fileSystem = this["fileSystem"]
    if (fileSystem != null && fileSystem !is JsonNull &&
        (fileSystem as? JsonObject)?.isValidFileSystemPermissions() != true
    ) return false
    val network = this["network"]
    if (network != null && network !is JsonNull &&
        (network as? JsonObject)?.isValidNetworkPermissions() != true
    ) return false
    return true
}

private fun JsonObject.isValidFileSystemPermissions(): Boolean {
    if (keys.any { it !in setOf("entries", "globScanMaxDepth", "read", "write") }) return false
    val entries = this["entries"]
    if (entries != null && entries !is JsonNull) {
        val array = entries as? JsonArray ?: return false
        if (array.any { (it as? JsonObject)?.isValidFileSystemEntry() != true }) return false
    }
    for (key in listOf("read", "write")) {
        val value = this[key]
        if (value != null && value !is JsonNull) {
            val array = value as? JsonArray ?: return false
            if (array.any { it !is JsonPrimitive || !it.isString }) return false
        }
    }
    val maxDepth = this["globScanMaxDepth"]
    if (maxDepth != null && maxDepth !is JsonNull &&
        (maxDepth as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull?.let { it in 1..UInt.MAX_VALUE.toLong() } != true
    ) return false
    return true
}

private fun JsonObject.isValidNetworkPermissions(): Boolean {
    if (keys.any { it != "enabled" }) return false
    val enabled = this["enabled"] ?: return true
    if (enabled is JsonNull) return true
    return enabled is JsonPrimitive && !enabled.isString &&
        enabled.contentOrNull?.toBooleanStrictOrNull() != null
}

private fun JsonObject.isValidFileSystemEntry(): Boolean {
    if (keys != setOf("access", "path")) return false
    if (strictString("access") !in setOf("read", "write", "deny")) return false
    return (this["path"] as? JsonObject)?.isValidFileSystemPath() == true
}

private fun JsonObject.isValidFileSystemPath(): Boolean = when (strictString("type")) {
    "path" -> keys == setOf("type", "path") && !strictString("path").isNullOrBlank()
    "glob_pattern" -> keys == setOf("type", "pattern") && !strictString("pattern").isNullOrBlank()
    "special" -> keys == setOf("type", "value") &&
        (this["value"] as? JsonObject)?.isValidSpecialFileSystemPath() == true
    else -> false
}

private fun JsonObject.isValidSpecialFileSystemPath(): Boolean = when (strictString("kind")) {
    "root", "minimal", "tmpdir", "slash_tmp" -> keys == setOf("kind")
    "project_roots" -> keys.all { it in setOf("kind", "subpath") } &&
        (this["subpath"] == null || this["subpath"] is JsonNull || strictString("subpath") != null)
    "unknown" -> keys.all { it in setOf("kind", "path", "subpath") } && !strictString("path").isNullOrBlank() &&
        (this["subpath"] == null || this["subpath"] is JsonNull || strictString("subpath") != null)
    else -> false
}

private const val APPROVAL_VALIDATION_WARNING =
    "Request contains missing, malformed, or unrecognized security fields. Only denial is allowed."

internal data class ThreadPage(
    val threads: List<RemoteThread>,
    val nextCursor: String?,
)

internal data class ModelPage(
    val models: List<RemoteModel>,
    val nextCursor: String?,
)

internal suspend fun collectAllThreadPages(
    loadPage: suspend (cursor: String?) -> ThreadPage,
): List<RemoteThread> {
    val threadsById = linkedMapOf<String, RemoteThread>()
    val usedCursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = loadPage(cursor)
        page.threads.forEach { thread ->
            val current = threadsById[thread.id]
            if (current == null || thread.updatedAt >= current.updatedAt) threadsById[thread.id] = thread
        }
        cursor = page.nextCursor?.takeIf(String::isNotBlank)
        if (cursor != null && !usedCursors.add(cursor)) {
            throw RpcException("thread/list returned a duplicate nextCursor")
        }
    } while (cursor != null)
    return threadsById.values.sortedWith(compareByDescending<RemoteThread> { it.updatedAt }.thenBy { it.id })
}

internal suspend fun collectAllModelPages(
    loadPage: suspend (cursor: String?) -> ModelPage,
): List<RemoteModel> {
    val modelsById = linkedMapOf<String, RemoteModel>()
    val usedCursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = loadPage(cursor)
        page.models.forEach { model -> modelsById[model.id] = model }
        cursor = page.nextCursor?.takeIf(String::isNotBlank)
        if (cursor != null && !usedCursors.add(cursor)) {
            throw RpcException("model/list returned a duplicate nextCursor")
        }
    } while (cursor != null)
    return modelsById.values.toList()
}

private const val THREAD_PAGE_SIZE = 100
private const val MODEL_PAGE_SIZE = 100
private const val MCP_STATUS_PAGE_SIZE = 100
private const val PERMISSION_PROFILE_PAGE_SIZE = 100
internal const val THREAD_HISTORY_PAGE_SIZE = 5
private val ACTIVE_TURN_STATUSES = setOf("inProgress", "running", "started")

internal fun selectOlderHistoryCursor(
    initialPageCursor: String?,
    turnsBackwardsCursor: String?,
): String? = initialPageCursor?.takeIf(String::isNotBlank)
    ?: turnsBackwardsCursor?.takeIf(String::isNotBlank)

private fun RpcException.isHistoryPaginationUnavailable(): Boolean {
    if (code == -32601 || code == -32602) return true
    return message.orEmpty().let { message ->
        message.contains("unknown field", ignoreCase = true) ||
            message.contains("invalid params", ignoreCase = true) ||
            message.contains("initialTurnsPage", ignoreCase = true) ||
            message.contains("excludeTurns", ignoreCase = true) ||
            message.contains("experimental", ignoreCase = true) &&
            message.contains("not supported", ignoreCase = true)
    }
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
private fun JsonObject.strictNonBlankString(key: String): String? =
    strictString(key)?.takeIf(String::isNotBlank)
private fun JsonObject.hasValidOptionalStrings(keys: Set<String>): Boolean = keys.all { key ->
    val value = this[key]
    value == null || value is JsonNull || value is JsonPrimitive && value.isString
}
private fun JsonElement?.isJsonLong(): Boolean =
    this is JsonPrimitive && !isString && longOrNull != null
private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

private fun ThreadGoalStatus.wireValue(): String = when (this) {
    ThreadGoalStatus.ACTIVE -> "active"
    ThreadGoalStatus.PAUSED -> "paused"
    ThreadGoalStatus.BLOCKED -> "blocked"
    ThreadGoalStatus.USAGE_LIMITED -> "usageLimited"
    ThreadGoalStatus.BUDGET_LIMITED -> "budgetLimited"
    ThreadGoalStatus.COMPLETE -> "complete"
}

private fun String.toThreadGoalStatus(): ThreadGoalStatus? = when (lowercase().replace("_", "").replace("-", "")) {
    "active" -> ThreadGoalStatus.ACTIVE
    "paused" -> ThreadGoalStatus.PAUSED
    "blocked" -> ThreadGoalStatus.BLOCKED
    "usagelimited" -> ThreadGoalStatus.USAGE_LIMITED
    "budgetlimited" -> ThreadGoalStatus.BUDGET_LIMITED
    "complete" -> ThreadGoalStatus.COMPLETE
    else -> null
}
