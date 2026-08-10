package com.codex.remote.session

import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ResumeEventEpoch(val value: Long)

internal enum class ResumeEventOfferDisposition {
    APPLIED,
    BUFFERED,
    OVERFLOW,
}

internal enum class ResumeEventDrainDisposition {
    DRAINED,
    STALE,
    OVERFLOW,
}

/**
 * Keeps inbound events behind a resume snapshot without ever holding [applyMutex] while the
 * resume RPC is awaiting its response.
 *
 * The small JVM monitor protects queue bookkeeping only. Event application is serialized by
 * [applyMutex], but inbound events bypass that mutex while a resume is buffering or draining.
 */
internal class ResumeEventCoordinator<Event>(
    private val maxBufferedEvents: Int,
    private val maxBufferedWeight: Long = Long.MAX_VALUE,
    private val measureEvent: (Event) -> Long = { 1L },
) {
    init {
        require(maxBufferedEvents > 0) { "maxBufferedEvents must be positive" }
        require(maxBufferedWeight > 0L) { "maxBufferedWeight must be positive" }
    }

    private enum class Mode {
        LIVE,
        BUFFERING,
        DRAINING,
        OVERFLOW,
    }

    private enum class QueueDisposition {
        LIVE,
        BUFFERED,
        OVERFLOW,
    }

    private sealed interface NextEvent<out Event> {
        data class Value<Event>(val event: Event) : NextEvent<Event>
        data object Complete : NextEvent<Nothing>
        data object Stale : NextEvent<Nothing>
        data object Overflow : NextEvent<Nothing>
    }

    private data class BufferedEvent<Event>(
        val event: Event,
        val weight: Long,
    )

    private val stateLock = Any()
    private val applyMutex = Mutex()
    private val bufferedEvents = ArrayDeque<BufferedEvent<Event>>()
    private var bufferedWeight = 0L
    private var mode = Mode.LIVE
    private var epochSequence = 0L
    private var activeEpoch = 0L

    suspend fun beginResume(): ResumeEventEpoch = applyMutex.withLock {
        synchronized(stateLock) {
            epochSequence += 1L
            activeEpoch = epochSequence
            if (mode != Mode.OVERFLOW) mode = Mode.BUFFERING
            ResumeEventEpoch(activeEpoch)
        }
    }

    /**
     * Immediately invalidates the outstanding resume owner while retaining its queued events.
     * The returned epoch owns a snapshot-free drain; a late response for the old epoch is stale.
     */
    fun abandonResume(): ResumeEventEpoch? = synchronized(stateLock) {
        if (mode == Mode.LIVE) return@synchronized null
        epochSequence += 1L
        activeEpoch = epochSequence
        if (mode != Mode.OVERFLOW) mode = Mode.DRAINING
        ResumeEventEpoch(activeEpoch)
    }

    suspend fun processOrBuffer(
        event: Event,
        applyEvent: suspend (Event) -> Unit,
    ): ResumeEventOfferDisposition = when (queueIfResumePaused(event)) {
        QueueDisposition.BUFFERED -> ResumeEventOfferDisposition.BUFFERED
        QueueDisposition.OVERFLOW -> ResumeEventOfferDisposition.OVERFLOW
        QueueDisposition.LIVE -> applyMutex.withLock {
            // A resume can start after the optimistic LIVE check but before this mutex is won.
            // Recheck under the application gate so that event cannot cross the snapshot.
            when (queueIfResumePaused(event)) {
                QueueDisposition.BUFFERED -> ResumeEventOfferDisposition.BUFFERED
                QueueDisposition.OVERFLOW -> ResumeEventOfferDisposition.OVERFLOW
                QueueDisposition.LIVE -> {
                    applyEvent(event)
                    ResumeEventOfferDisposition.APPLIED
                }
            }
        }
    }

    suspend fun drainAfterResume(
        epoch: ResumeEventEpoch,
        applySnapshot: () -> Unit,
        applyEvent: suspend (Event) -> Unit,
    ): ResumeEventDrainDisposition = applyMutex.withLock {
        when (prepareDrain(epoch)) {
            ResumeEventDrainDisposition.STALE -> return@withLock ResumeEventDrainDisposition.STALE
            ResumeEventDrainDisposition.OVERFLOW -> return@withLock ResumeEventDrainDisposition.OVERFLOW
            ResumeEventDrainDisposition.DRAINED -> Unit
        }

        applySnapshot()
        while (true) {
            when (val next = nextEvent(epoch)) {
                is NextEvent.Value -> applyEvent(next.event)
                NextEvent.Complete -> return@withLock ResumeEventDrainDisposition.DRAINED
                NextEvent.Stale -> return@withLock ResumeEventDrainDisposition.STALE
                NextEvent.Overflow -> return@withLock ResumeEventDrainDisposition.OVERFLOW
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ResumeEventDrainDisposition.DRAINED
    }

    suspend fun drainAbandonedResume(
        epoch: ResumeEventEpoch,
        applyEvent: suspend (Event) -> Unit,
    ): ResumeEventDrainDisposition = drainAfterResume(
        epoch = epoch,
        applySnapshot = {},
        applyEvent = applyEvent,
    )

    fun reset() {
        synchronized(stateLock) {
            epochSequence += 1L
            activeEpoch = epochSequence
            clearBufferedEvents()
            mode = Mode.LIVE
        }
    }

    private fun queueIfResumePaused(event: Event): QueueDisposition {
        synchronized(stateLock) {
            if (mode == Mode.LIVE) return QueueDisposition.LIVE
            if (mode == Mode.OVERFLOW) return QueueDisposition.OVERFLOW
        }

        // Measuring a large event may be non-trivial, so never do it while holding stateLock.
        val weight = measureEvent(event).coerceAtLeast(1L)
        return synchronized(stateLock) {
            when (mode) {
                Mode.LIVE -> QueueDisposition.LIVE
                Mode.OVERFLOW -> QueueDisposition.OVERFLOW
                Mode.BUFFERING,
                Mode.DRAINING,
                -> {
                    val weightWouldOverflow =
                        weight > maxBufferedWeight || bufferedWeight > maxBufferedWeight - weight
                    if (bufferedEvents.size >= maxBufferedEvents || weightWouldOverflow) {
                        clearBufferedEvents()
                        mode = Mode.OVERFLOW
                        QueueDisposition.OVERFLOW
                    } else {
                        bufferedEvents.addLast(BufferedEvent(event, weight))
                        bufferedWeight += weight
                        QueueDisposition.BUFFERED
                    }
                }
            }
        }
    }

    private fun prepareDrain(epoch: ResumeEventEpoch): ResumeEventDrainDisposition =
        synchronized(stateLock) {
            when {
                epoch.value != activeEpoch -> ResumeEventDrainDisposition.STALE
                mode == Mode.OVERFLOW -> ResumeEventDrainDisposition.OVERFLOW
                else -> {
                    mode = Mode.DRAINING
                    ResumeEventDrainDisposition.DRAINED
                }
            }
        }

    private fun nextEvent(epoch: ResumeEventEpoch): NextEvent<Event> = synchronized(stateLock) {
        when {
            epoch.value != activeEpoch -> NextEvent.Stale
            mode == Mode.OVERFLOW -> NextEvent.Overflow
            bufferedEvents.isNotEmpty() -> {
                val buffered = bufferedEvents.removeFirst()
                bufferedWeight -= buffered.weight
                NextEvent.Value(buffered.event)
            }
            else -> {
                mode = Mode.LIVE
                NextEvent.Complete
            }
        }
    }

    private fun clearBufferedEvents() {
        bufferedEvents.clear()
        bufferedWeight = 0L
    }
}
