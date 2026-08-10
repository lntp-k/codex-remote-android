package com.codex.remote.connection

import com.codex.remote.domain.ConnectionStatus

/**
 * Produces a capped exponential delay for consecutive automatic reconnects.
 *
 * The attempt number is zero-based: attempt 0 waits one second, attempt 1 waits two
 * seconds, and so on. A successful, stable connection should reset the caller's
 * attempt counter rather than mutating policy state here.
 */
internal class ReconnectBackoff(
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val maximumDelayMillis: Long = DEFAULT_MAXIMUM_DELAY_MILLIS,
    val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maximumDelayMillis >= initialDelayMillis) {
            "maximumDelayMillis must be at least initialDelayMillis"
        }
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    fun canRetry(attempt: Int): Boolean = attempt in 0 until maximumAttempts

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0) { "attempt must not be negative" }

        var delayMillis = initialDelayMillis
        var remainingDoublings = attempt
        while (remainingDoublings > 0 && delayMillis < maximumDelayMillis) {
            delayMillis = if (delayMillis > maximumDelayMillis / 2) {
                maximumDelayMillis
            } else {
                delayMillis * 2
            }
            remainingDoublings -= 1
        }
        return delayMillis.coerceAtMost(maximumDelayMillis)
    }

    private companion object {
        const val DEFAULT_INITIAL_DELAY_MILLIS = 1_000L
        const val DEFAULT_MAXIMUM_DELAY_MILLIS = 30_000L
        const val DEFAULT_MAXIMUM_ATTEMPTS = 10
    }
}

/**
 * Counts reconnects only when a new SSH dial actually starts.
 *
 * Network callbacks are allowed to cancel and replace pending timers without
 * consuming the retry budget. [markStarted] rejects stale timers whose expected
 * attempt no longer matches the ledger.
 */
internal class ReconnectAttemptLedger(
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) {
    var startedAttempts: Int = 0
        private set

    val maximumAttempts: Int
        get() = backoff.maximumAttempts

    fun canSchedule(): Boolean = backoff.canRetry(startedAttempts)

    fun delayMillisForNextAttempt(): Long = backoff.delayMillis(startedAttempts)

    fun markStarted(expectedAttempt: Int): Boolean {
        if (expectedAttempt != startedAttempts || !canSchedule()) return false
        startedAttempts += 1
        return true
    }

    fun reset() {
        startedAttempts = 0
    }
}

/**
 * Deduplicates default-network callbacks into reconnect-worthy transitions.
 *
 * The first available default network establishes a baseline and does not
 * reconnect an already-opening transport. Once the current network is lost,
 * availability of either that network again or a replacement is recovery.
 */
internal enum class NetworkTransition {
    INITIAL,
    UNCHANGED,
    CHANGED,
    LOST,
    RESTORED,
    STALE_LOSS,
}

internal class NetworkTransitionTracker {
    var currentNetworkId: Long? = null
        private set

    var hasAvailableNetwork: Boolean = false
        private set

    fun reset(networkId: Long?) {
        currentNetworkId = networkId
        hasAvailableNetwork = networkId != null
    }

    fun onAvailable(networkId: Long): NetworkTransition {
        if (currentNetworkId == null) {
            currentNetworkId = networkId
            hasAvailableNetwork = true
            return NetworkTransition.INITIAL
        }
        if (!hasAvailableNetwork) {
            currentNetworkId = networkId
            hasAvailableNetwork = true
            return NetworkTransition.RESTORED
        }
        if (currentNetworkId == networkId) return NetworkTransition.UNCHANGED

        currentNetworkId = networkId
        return NetworkTransition.CHANGED
    }

    fun onLost(networkId: Long): NetworkTransition {
        if (!hasAvailableNetwork || currentNetworkId != networkId) {
            return NetworkTransition.STALE_LOSS
        }

        hasAvailableNetwork = false
        return NetworkTransition.LOST
    }

    fun onUnavailable(): NetworkTransition {
        if (!hasAvailableNetwork) return NetworkTransition.STALE_LOSS
        hasAvailableNetwork = false
        return NetworkTransition.LOST
    }
}

internal enum class NetworkRecoveryAction {
    NONE,
    REQUEST_HANDOFF,
    SCHEDULE_RECONNECT,
}

internal enum class ReconnectReadiness {
    READY,
    WAITING_FOR_NETWORK,
    WAITING_FOR_DEVICE_WAKE,
}

/**
 * Prevents automatic dials while Android Doze suspends network access, and while
 * the system has reported that no default network exists. Neither wait state
 * consumes the reconnect-attempt budget.
 */
internal fun reconnectReadiness(
    isDeviceIdleMode: Boolean,
    hasObservedNetworkState: Boolean,
    hasAvailableNetwork: Boolean,
): ReconnectReadiness = when {
    isDeviceIdleMode -> ReconnectReadiness.WAITING_FOR_DEVICE_WAKE
    hasObservedNetworkState && !hasAvailableNetwork -> ReconnectReadiness.WAITING_FOR_NETWORK
    else -> ReconnectReadiness.READY
}

/**
 * Converts network callbacks into a side-effect-free recovery decision.
 *
 * In particular, the first availability callback resumes a connection that was
 * already waiting offline, while a callback never schedules over an SSH attempt
 * that is still in flight.
 */
internal fun networkRecoveryAction(
    transition: NetworkTransition,
    connectionStatus: ConnectionStatus,
    connectionAttemptActive: Boolean,
): NetworkRecoveryAction = when (transition) {
    NetworkTransition.CHANGED,
    NetworkTransition.RESTORED -> when (connectionStatus) {
        ConnectionStatus.CONNECTED -> NetworkRecoveryAction.REQUEST_HANDOFF
        ConnectionStatus.RECONNECTING,
        ConnectionStatus.ERROR,
        ConnectionStatus.DISCONNECTED -> if (connectionAttemptActive) {
            NetworkRecoveryAction.NONE
        } else {
            NetworkRecoveryAction.SCHEDULE_RECONNECT
        }
        ConnectionStatus.CONNECTING -> NetworkRecoveryAction.NONE
    }

    NetworkTransition.INITIAL -> when (connectionStatus) {
        ConnectionStatus.RECONNECTING,
        ConnectionStatus.ERROR,
        ConnectionStatus.DISCONNECTED -> if (connectionAttemptActive) {
            NetworkRecoveryAction.NONE
        } else {
            NetworkRecoveryAction.SCHEDULE_RECONNECT
        }
        ConnectionStatus.CONNECTING,
        ConnectionStatus.CONNECTED -> NetworkRecoveryAction.NONE
    }

    NetworkTransition.UNCHANGED,
    NetworkTransition.LOST,
    NetworkTransition.STALE_LOSS -> NetworkRecoveryAction.NONE
}

internal fun availableNetworkRequiresHandoff(
    attemptNetworkId: Long?,
    availableNetworkId: Long,
): Boolean = attemptNetworkId == null || attemptNetworkId != availableNetworkId

internal enum class NetworkHandoffDisposition {
    EXECUTE,
    DEFER,
}

internal fun networkHandoffDisposition(
    hasRunningTurn: Boolean,
    hasPendingApproval: Boolean,
    hasPendingRpc: Boolean,
): NetworkHandoffDisposition = if (hasRunningTurn || hasPendingApproval || hasPendingRpc) {
    NetworkHandoffDisposition.DEFER
} else {
    NetworkHandoffDisposition.EXECUTE
}
