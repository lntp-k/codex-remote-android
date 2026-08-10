package com.codex.remote.connection

import com.codex.remote.domain.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun reconnectDelayDoublesFromOneSecondAndCapsAtThirtySeconds() {
        val policy = ReconnectBackoff()

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(policy::delayMillis),
        )
    }

    @Test
    fun reconnectDelayIsOverflowSafeForAnExtremeAttemptNumber() {
        val policy = ReconnectBackoff()

        assertEquals(30_000L, policy.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun reconnectDelayRejectsNegativeAttempts() {
        val policy = ReconnectBackoff()

        assertThrows(IllegalArgumentException::class.java) {
            policy.delayMillis(-1)
        }
    }

    @Test
    fun retryBudgetStopsAfterTenConsecutiveAttempts() {
        val policy = ReconnectBackoff()

        assertTrue(policy.canRetry(0))
        assertTrue(policy.canRetry(9))
        assertFalse(policy.canRetry(10))
        assertFalse(policy.canRetry(Int.MAX_VALUE))
    }

    @Test
    fun customLargeDelaysSaturateWithoutLongOverflow() {
        val maximum = Long.MAX_VALUE - 7
        val policy = ReconnectBackoff(
            initialDelayMillis = maximum / 2 + 1,
            maximumDelayMillis = maximum,
        )

        assertEquals(maximum, policy.delayMillis(1))
        assertEquals(maximum, policy.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun canceledTimersDoNotConsumeAttemptsAndStaleTimersCannotDoubleCount() {
        val attempts = ReconnectAttemptLedger()

        assertEquals(0, attempts.startedAttempts)
        assertEquals(1_000L, attempts.delayMillisForNextAttempt())
        assertEquals(1_000L, attempts.delayMillisForNextAttempt())
        assertEquals(0, attempts.startedAttempts)

        assertTrue(attempts.markStarted(expectedAttempt = 0))
        assertFalse(attempts.markStarted(expectedAttempt = 0))
        assertEquals(1, attempts.startedAttempts)
        assertEquals(2_000L, attempts.delayMillisForNextAttempt())
    }

    @Test
    fun attemptLedgerStopsAfterTenActualStartsAndCanResetAfterStability() {
        val attempts = ReconnectAttemptLedger()

        repeat(10) { expected ->
            assertTrue(attempts.canSchedule())
            assertTrue(attempts.markStarted(expected))
        }
        assertFalse(attempts.canSchedule())
        assertFalse(attempts.markStarted(expectedAttempt = 10))

        attempts.reset()
        assertEquals(0, attempts.startedAttempts)
        assertTrue(attempts.canSchedule())
    }
}

class NetworkTransitionTrackerTest {
    @Test
    fun firstAvailableNetworkEstablishesTheBaselineAndRepeatedAvailabilityIsIgnored() {
        val tracker = NetworkTransitionTracker()

        assertFalse(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.INITIAL, tracker.onAvailable(1L))
        assertTrue(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.UNCHANGED, tracker.onAvailable(1L))
    }

    @Test
    fun changingFromOneAvailableNetworkToAnotherTriggersOnlyOnce() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)

        assertEquals(NetworkTransition.CHANGED, tracker.onAvailable(2L))
        assertEquals(NetworkTransition.UNCHANGED, tracker.onAvailable(2L))
        assertEquals(NetworkTransition.STALE_LOSS, tracker.onLost(1L))
    }

    @Test
    fun losingTheCurrentNetworkTriggersOnceAndStaleLossIsIgnored() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)

        assertEquals(NetworkTransition.STALE_LOSS, tracker.onLost(99L))
        assertEquals(NetworkTransition.LOST, tracker.onLost(1L))
        assertFalse(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.STALE_LOSS, tracker.onLost(1L))
    }

    @Test
    fun sameNetworkReturningAfterLossTriggersRecoveryOnce() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)
        tracker.onLost(1L)

        assertEquals(NetworkTransition.RESTORED, tracker.onAvailable(1L))
        assertTrue(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.UNCHANGED, tracker.onAvailable(1L))
    }

    @Test
    fun replacementNetworkAfterLossTriggersRecoveryOnceAndMakesOldLossStale() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)
        tracker.onLost(1L)

        assertEquals(NetworkTransition.RESTORED, tracker.onAvailable(2L))
        assertEquals(NetworkTransition.UNCHANGED, tracker.onAvailable(2L))
        assertEquals(NetworkTransition.STALE_LOSS, tracker.onLost(1L))
    }

    @Test
    fun lossBeforeTheFirstAvailableNetworkDoesNotCreateATransition() {
        val tracker = NetworkTransitionTracker()

        assertEquals(NetworkTransition.STALE_LOSS, tracker.onLost(1L))
        assertEquals(NetworkTransition.INITIAL, tracker.onAvailable(1L))
    }

    @Test
    fun explicitUnavailableClearsAPreviouslyKnownNetwork() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)

        assertEquals(NetworkTransition.LOST, tracker.onUnavailable())
        assertFalse(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.STALE_LOSS, tracker.onUnavailable())
        assertEquals(NetworkTransition.RESTORED, tracker.onAvailable(2L))
    }

    @Test
    fun resetAlignsAResubscribedCallbackWithTheCurrentDialNetwork() {
        val tracker = NetworkTransitionTracker()
        tracker.onAvailable(1L)

        tracker.reset(2L)

        assertEquals(2L, tracker.currentNetworkId)
        assertTrue(tracker.hasAvailableNetwork)
        assertEquals(NetworkTransition.UNCHANGED, tracker.onAvailable(2L))
    }
}

class NetworkRecoveryActionTest {
    @Test
    fun firstAvailabilityResumesAConnectionThatStartedOffline() {
        assertEquals(
            NetworkRecoveryAction.SCHEDULE_RECONNECT,
            networkRecoveryAction(
                transition = NetworkTransition.INITIAL,
                connectionStatus = ConnectionStatus.RECONNECTING,
                connectionAttemptActive = false,
            ),
        )
    }

    @Test
    fun initialAvailabilityAloneDoesNotReplaceAConnectionWithAMatchingBaseline() {
        assertEquals(
            NetworkRecoveryAction.NONE,
            networkRecoveryAction(
                transition = NetworkTransition.INITIAL,
                connectionStatus = ConnectionStatus.CONNECTED,
                connectionAttemptActive = false,
            ),
        )
    }

    @Test
    fun availabilityOnlyRequiresHandoffWhenTheDialBaselineIsUnknownOrDifferent() {
        assertFalse(availableNetworkRequiresHandoff(7L, 7L))
        assertTrue(availableNetworkRequiresHandoff(null, 7L))
        assertTrue(availableNetworkRequiresHandoff(6L, 7L))
    }

    @Test
    fun changedNetworkDoesNotScheduleOverAnActiveReconnectAttempt() {
        assertEquals(
            NetworkRecoveryAction.NONE,
            networkRecoveryAction(
                transition = NetworkTransition.CHANGED,
                connectionStatus = ConnectionStatus.RECONNECTING,
                connectionAttemptActive = true,
            ),
        )
    }

    @Test
    fun changedNetworkRequestsAFreshTransportForAConnectedSession() {
        assertEquals(
            NetworkRecoveryAction.REQUEST_HANDOFF,
            networkRecoveryAction(
                transition = NetworkTransition.CHANGED,
                connectionStatus = ConnectionStatus.CONNECTED,
                connectionAttemptActive = false,
            ),
        )
    }

    @Test
    fun networkHandoffExecutesOnlyWhenTurnsAndApprovalsAreIdle() {
        assertEquals(
            NetworkHandoffDisposition.EXECUTE,
            networkHandoffDisposition(
                hasRunningTurn = false,
                hasPendingApproval = false,
                hasPendingRpc = false,
            ),
        )
        assertEquals(
            NetworkHandoffDisposition.DEFER,
            networkHandoffDisposition(
                hasRunningTurn = true,
                hasPendingApproval = false,
                hasPendingRpc = false,
            ),
        )
        assertEquals(
            NetworkHandoffDisposition.DEFER,
            networkHandoffDisposition(
                hasRunningTurn = false,
                hasPendingApproval = true,
                hasPendingRpc = false,
            ),
        )
        assertEquals(
            NetworkHandoffDisposition.DEFER,
            networkHandoffDisposition(
                hasRunningTurn = false,
                hasPendingApproval = false,
                hasPendingRpc = true,
            ),
        )
    }

    @Test
    fun unchangedAndLostCallbacksDoNotStartDuplicateRecovery() {
        listOf(NetworkTransition.UNCHANGED, NetworkTransition.LOST, NetworkTransition.STALE_LOSS)
            .forEach { transition ->
                assertEquals(
                    NetworkRecoveryAction.NONE,
                    networkRecoveryAction(
                        transition = transition,
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionAttemptActive = false,
                    ),
                )
            }
    }
}

class ReconnectReadinessTest {
    @Test
    fun dozeWaitsWithoutDialingEvenWhenANetworkHandleStillExists() {
        assertEquals(
            ReconnectReadiness.WAITING_FOR_DEVICE_WAKE,
            reconnectReadiness(
                isDeviceIdleMode = true,
                hasObservedNetworkState = true,
                hasAvailableNetwork = true,
            ),
        )
    }

    @Test
    fun anAuthoritativeMissingDefaultNetworkWaitsWithoutDialing() {
        assertEquals(
            ReconnectReadiness.WAITING_FOR_NETWORK,
            reconnectReadiness(
                isDeviceIdleMode = false,
                hasObservedNetworkState = true,
                hasAvailableNetwork = false,
            ),
        )
    }

    @Test
    fun reconnectIsReadyBeforeTheFirstNetworkSnapshotOrWithAnAvailableNetwork() {
        assertEquals(
            ReconnectReadiness.READY,
            reconnectReadiness(
                isDeviceIdleMode = false,
                hasObservedNetworkState = false,
                hasAvailableNetwork = false,
            ),
        )
        assertEquals(
            ReconnectReadiness.READY,
            reconnectReadiness(
                isDeviceIdleMode = false,
                hasObservedNetworkState = true,
                hasAvailableNetwork = true,
            ),
        )
    }
}
