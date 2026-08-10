package com.codex.remote.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeEventCoordinatorTest {
    @Test
    fun moreThanSharedFlowCapacityCannotBlockTheResumeResponse() = runTest {
        val coordinator = ResumeEventCoordinator<Int>(maxBufferedEvents = 512)
        val epoch = coordinator.beginResume()
        val events = MutableSharedFlow<Int>(extraBufferCapacity = 128)
        val responseReached = CompletableDeferred<Unit>()
        val applied = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { event ->
                coordinator.processOrBuffer(event) { ordered -> applied += "event:$ordered" }
            }
        }

        val soleReader = launch {
            repeat(256) { event -> events.emit(event) }
            responseReached.complete(Unit)
        }

        withTimeout(1_000) { responseReached.await() }
        assertTrue(applied.isEmpty())
        assertEquals(
            ResumeEventDrainDisposition.DRAINED,
            coordinator.drainAfterResume(
                epoch = epoch,
                applySnapshot = { applied += "snapshot" },
                applyEvent = { event -> applied += "event:$event" },
            ),
        )
        soleReader.join()
        withTimeout(1_000) {
            while (applied.size < 257) yield()
        }
        collector.cancelAndJoin()

        assertEquals("snapshot", applied.first())
        assertEquals((0 until 256).map { "event:$it" }, applied.drop(1))
    }

    @Test
    fun eventsArrivingDuringDrainStayBehindEarlierWireEvents() = runTest {
        val coordinator = ResumeEventCoordinator<Int>(maxBufferedEvents = 8)
        val epoch = coordinator.beginResume()
        val applied = mutableListOf<String>()
        assertEquals(
            ResumeEventOfferDisposition.BUFFERED,
            coordinator.processOrBuffer(1) { error("buffered event applied early") },
        )
        assertEquals(
            ResumeEventOfferDisposition.BUFFERED,
            coordinator.processOrBuffer(2) { error("buffered event applied early") },
        )

        assertEquals(
            ResumeEventDrainDisposition.DRAINED,
            coordinator.drainAfterResume(
                epoch = epoch,
                applySnapshot = { applied += "snapshot" },
                applyEvent = { event ->
                    applied += "event:$event"
                    if (event == 1) {
                        assertEquals(
                            ResumeEventOfferDisposition.BUFFERED,
                            coordinator.processOrBuffer(3) { error("draining event applied out of order") },
                        )
                    }
                },
            ),
        )

        assertEquals(listOf("snapshot", "event:1", "event:2", "event:3"), applied)
    }

    @Test
    fun newerResumeSupersedesTheOwnerWithoutDiscardingQueuedEvents() = runTest {
        val coordinator = ResumeEventCoordinator<Int>(maxBufferedEvents = 8)
        val first = coordinator.beginResume()
        coordinator.processOrBuffer(1) { error("buffered event applied early") }
        val second = coordinator.beginResume()
        coordinator.processOrBuffer(2) { error("buffered event applied early") }
        val applied = mutableListOf<String>()

        assertEquals(
            ResumeEventDrainDisposition.STALE,
            coordinator.drainAfterResume(
                epoch = first,
                applySnapshot = { error("stale snapshot applied") },
                applyEvent = { error("stale owner drained events") },
            ),
        )
        assertEquals(
            ResumeEventDrainDisposition.DRAINED,
            coordinator.drainAfterResume(
                epoch = second,
                applySnapshot = { applied += "snapshot" },
                applyEvent = { event -> applied += "event:$event" },
            ),
        )

        assertEquals(listOf("snapshot", "event:1", "event:2"), applied)
    }

    @Test
    fun abandoningResumeDrainsWithoutSnapshotAndMakesTheOldResponseStale() = runTest {
        val coordinator = ResumeEventCoordinator<Int>(maxBufferedEvents = 8)
        val oldResume = coordinator.beginResume()
        coordinator.processOrBuffer(1) { error("buffered event applied early") }
        coordinator.processOrBuffer(2) { error("buffered event applied early") }
        val abandoned = checkNotNull(coordinator.abandonResume())
        coordinator.processOrBuffer(3) { error("event applied before abandon drain") }
        val applied = mutableListOf<Int>()

        assertEquals(
            ResumeEventDrainDisposition.DRAINED,
            coordinator.drainAbandonedResume(abandoned) { event ->
                applied += event
                if (event == 1) {
                    assertEquals(
                        ResumeEventOfferDisposition.BUFFERED,
                        coordinator.processOrBuffer(4) { error("draining event applied out of order") },
                    )
                }
            },
        )
        assertEquals(listOf(1, 2, 3, 4), applied)
        assertEquals(
            ResumeEventDrainDisposition.STALE,
            coordinator.drainAfterResume(
                epoch = oldResume,
                applySnapshot = { error("late abandoned snapshot applied") },
                applyEvent = { error("late abandoned response drained events") },
            ),
        )
    }

    @Test
    fun overflowFailsClosedAndResetDoesNotReplayDroppedEvents() = runTest {
        val coordinator = ResumeEventCoordinator<Int>(maxBufferedEvents = 2)
        val epoch = coordinator.beginResume()
        coordinator.processOrBuffer(1) { error("buffered event applied early") }
        coordinator.processOrBuffer(2) { error("buffered event applied early") }

        assertEquals(
            ResumeEventOfferDisposition.OVERFLOW,
            coordinator.processOrBuffer(3) { error("overflow event applied") },
        )
        assertEquals(
            ResumeEventDrainDisposition.OVERFLOW,
            coordinator.drainAfterResume(
                epoch = epoch,
                applySnapshot = { error("snapshot applied after overflow") },
                applyEvent = { error("event replayed after overflow") },
            ),
        )

        coordinator.reset()
        val applied = mutableListOf<Int>()
        assertEquals(
            ResumeEventOfferDisposition.APPLIED,
            coordinator.processOrBuffer(4) { applied += it },
        )
        assertEquals(listOf(4), applied)
    }

    @Test
    fun aggregateWeightOverflowFailsClosedBeforeTheCountLimit() = runTest {
        val coordinator = ResumeEventCoordinator<String>(
            maxBufferedEvents = 10,
            maxBufferedWeight = 5L,
            measureEvent = { event -> event.length.toLong() },
        )
        val epoch = coordinator.beginResume()

        assertEquals(
            ResumeEventOfferDisposition.BUFFERED,
            coordinator.processOrBuffer("abc") { error("buffered event applied early") },
        )
        assertEquals(
            ResumeEventOfferDisposition.OVERFLOW,
            coordinator.processOrBuffer("def") { error("overweight event applied") },
        )
        assertEquals(
            ResumeEventDrainDisposition.OVERFLOW,
            coordinator.drainAfterResume(
                epoch = epoch,
                applySnapshot = { error("snapshot applied after weight overflow") },
                applyEvent = { error("event replayed after weight overflow") },
            ),
        )
    }
}
