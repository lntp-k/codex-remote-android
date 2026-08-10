package com.codex.remote.session

internal data class SessionLoadToken(
    val threadId: String,
    val connectionGeneration: Long,
    val requestSequence: Long,
)

internal data class DraftSelectionToken(
    val connectionGeneration: Long,
    val selectionRevision: Long,
)

internal data class TurnStartToken(
    val threadId: String,
    val connectionGeneration: Long,
    val operationSequence: Long,
)

internal enum class TurnStartResponseDisposition {
    APPLY,
    ALREADY_OBSERVED,
    TERMINAL,
    SUPERSEDED,
    CONFLICT,
}

private data class TurnStartOperation(
    val connectionGeneration: Long,
    val operationSequence: Long,
    val clientUserMessageId: String? = null,
    val observedTurnId: String? = null,
    val terminal: Boolean = false,
)

private data class CompletedTurnKey(
    val threadId: String,
    val turnId: String,
)

internal class SessionRequestTracker(
    private val maxRecentCompletedTurns: Int = 128,
) {
    init {
        require(maxRecentCompletedTurns > 0)
    }

    var connectionGeneration: Long = 0
        private set

    private var requestSequence: Long = 0
    private var selectionRevision: Long = 0
    private var turnStartSequence: Long = 0
    private val latestRequestByThread = mutableMapOf<String, Long>()
    private val latestTurnStartByThread = mutableMapOf<String, TurnStartOperation>()
    private val recentCompletedTurns = linkedSetOf<CompletedTurnKey>()

    fun invalidateConnection(): Long {
        connectionGeneration += 1
        selectionRevision += 1
        latestRequestByThread.clear()
        latestTurnStartByThread.clear()
        recentCompletedTurns.clear()
        return connectionGeneration
    }

    fun advanceSelection(): Long {
        selectionRevision += 1
        return selectionRevision
    }

    fun captureDraftSelection(): DraftSelectionToken = DraftSelectionToken(
        connectionGeneration = connectionGeneration,
        selectionRevision = selectionRevision,
    )

    fun isCurrent(token: DraftSelectionToken): Boolean =
        token.connectionGeneration == connectionGeneration &&
            token.selectionRevision == selectionRevision

    fun reconcileSelection(
        previousThreadId: String?,
        previousProjectPath: String?,
        currentThreadId: String?,
        currentProjectPath: String?,
    ): Boolean {
        if (previousThreadId == currentThreadId && previousProjectPath == currentProjectPath) return false
        advanceSelection()
        return true
    }

    fun beginSessionLoad(threadId: String): SessionLoadToken {
        require(threadId.isNotBlank())
        val sequence = ++requestSequence
        latestRequestByThread[threadId] = sequence
        return SessionLoadToken(threadId, connectionGeneration, sequence)
    }

    fun isCurrent(token: SessionLoadToken): Boolean =
        token.connectionGeneration == connectionGeneration &&
            latestRequestByThread[token.threadId] == token.requestSequence

    fun isCurrentConnection(generation: Long): Boolean = generation == connectionGeneration

    fun beginTurnStart(
        threadId: String,
        clientUserMessageId: String? = null,
    ): TurnStartToken {
        require(threadId.isNotBlank())
        require(clientUserMessageId == null || clientUserMessageId.isNotBlank())
        val sequence = ++turnStartSequence
        latestTurnStartByThread[threadId] = TurnStartOperation(
            connectionGeneration = connectionGeneration,
            operationSequence = sequence,
            clientUserMessageId = clientUserMessageId,
        )
        return TurnStartToken(threadId, connectionGeneration, sequence)
    }

    fun canObserveTurnStarted(threadId: String, turnId: String): Boolean {
        if (threadId.isBlank() || turnId.isBlank()) return false
        if (CompletedTurnKey(threadId, turnId) in recentCompletedTurns) return false
        val operation = latestTurnStartByThread[threadId] ?: return true
        if (operation.connectionGeneration != connectionGeneration || operation.terminal) return true
        return operation.observedTurnId == null || operation.observedTurnId == turnId
    }

    fun observeTurnStarted(threadId: String, turnId: String): Boolean {
        if (!canObserveTurnStarted(threadId, turnId)) return false
        val operation = latestTurnStartByThread[threadId] ?: return true
        if (operation.connectionGeneration != connectionGeneration || operation.terminal) return true
        // A shared daemon broadcasts starts from every subscribed client. When this
        // operation has an origin ID, only the matching user item or RPC response
        // can establish ownership; a bare turn/started may belong to the desktop.
        if (operation.clientUserMessageId != null && operation.observedTurnId == null) return true
        if (operation.observedTurnId == null || operation.observedTurnId == turnId) {
            latestTurnStartByThread[threadId] = operation.copy(observedTurnId = turnId)
        }
        return true
    }

    fun observeUserMessage(
        threadId: String,
        turnId: String,
        clientUserMessageId: String,
    ): Boolean {
        if (threadId.isBlank() || turnId.isBlank() || clientUserMessageId.isBlank()) return false
        val operation = latestTurnStartByThread[threadId] ?: return true
        if (operation.connectionGeneration != connectionGeneration || operation.terminal) return true
        if (operation.clientUserMessageId != clientUserMessageId) return true
        if (CompletedTurnKey(threadId, turnId) in recentCompletedTurns) return false
        if (operation.observedTurnId != null && operation.observedTurnId != turnId) return false
        latestTurnStartByThread[threadId] = operation.copy(observedTurnId = turnId)
        return true
    }

    fun observeTurnCompleted(threadId: String, turnId: String) {
        if (threadId.isBlank() || turnId.isBlank()) return
        rememberCompletedTurn(threadId, turnId)
        val operation = latestTurnStartByThread[threadId] ?: return
        if (operation.connectionGeneration != connectionGeneration || operation.observedTurnId != turnId) return
        latestTurnStartByThread[threadId] = operation.copy(terminal = true)
    }

    fun resolveTurnStartResponse(
        token: TurnStartToken,
        turnId: String,
    ): TurnStartResponseDisposition {
        if (turnId.isBlank()) return TurnStartResponseDisposition.CONFLICT
        val operation = currentTurnStart(token) ?: return TurnStartResponseDisposition.SUPERSEDED
        val observedTurnId = operation.observedTurnId
        return when {
            observedTurnId == turnId && operation.terminal -> TurnStartResponseDisposition.TERMINAL
            observedTurnId == null && CompletedTurnKey(token.threadId, turnId) in recentCompletedTurns ->
                TurnStartResponseDisposition.CONFLICT
            observedTurnId == null -> {
                latestTurnStartByThread[token.threadId] = operation.copy(observedTurnId = turnId)
                TurnStartResponseDisposition.APPLY
            }
            observedTurnId != turnId -> TurnStartResponseDisposition.CONFLICT
            else -> TurnStartResponseDisposition.ALREADY_OBSERVED
        }
    }

    fun canRollbackTurnStart(token: TurnStartToken): Boolean {
        val operation = currentTurnStart(token) ?: return false
        if (operation.observedTurnId != null) return false
        latestTurnStartByThread.remove(token.threadId)
        return true
    }

    private fun currentTurnStart(token: TurnStartToken): TurnStartOperation? {
        if (token.connectionGeneration != connectionGeneration) return null
        val operation = latestTurnStartByThread[token.threadId] ?: return null
        return operation.takeIf {
            it.connectionGeneration == token.connectionGeneration &&
                it.operationSequence == token.operationSequence
        }
    }

    private fun rememberCompletedTurn(threadId: String, turnId: String) {
        val key = CompletedTurnKey(threadId, turnId)
        recentCompletedTurns.remove(key)
        recentCompletedTurns += key
        while (recentCompletedTurns.size > maxRecentCompletedTurns) {
            val oldest = recentCompletedTurns.firstOrNull() ?: break
            recentCompletedTurns.remove(oldest)
        }
    }
}
