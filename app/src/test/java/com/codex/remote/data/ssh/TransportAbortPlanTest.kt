package com.codex.remote.data.ssh

import java.io.BufferedWriter
import java.io.IOException
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportAbortPlanTest {
    @Test
    fun abortBlocksOutboundBeforeClosingBufferedWriterAndContinuesAfterErrors() {
        val transmitted = StringBuilder()
        val attempts = mutableListOf<String>()
        var outboundBlocked = false
        val sink = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                if (outboundBlocked) throw IOException("outbound blocked")
                transmitted.append(buffer, offset, length)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val writer = BufferedWriter(sink)
        writer.write("buffered-approval-response")

        TransportAbortPlan(
            blockOutbound = listOf(
                {
                    attempts += "command"
                    throw IOException("command close failed")
                },
                {
                    attempts += "session"
                    outboundBlocked = true
                },
            ),
            cleanupStreams = listOf(
                {
                    attempts += "reader"
                    throw IOException("reader close failed")
                },
                {
                    attempts += "writer"
                    writer.close()
                },
                { attempts += "tail" },
            ),
        ).run()

        assertEquals(listOf("command", "session", "reader", "writer", "tail"), attempts)
        assertEquals("", transmitted.toString())
    }

    @Test
    fun transportCloseGateRunsCleanupExactlyOnceAcrossConcurrentCallers() {
        val gate = TransportCloseGate()
        val startsTogether = CountDownLatch(1)
        val completed = CountDownLatch(8)
        val cleanupCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.execute {
                startsTogether.await()
                gate.run { cleanupCount.incrementAndGet() }
                completed.countDown()
            }
        }
        startsTogether.countDown()

        try {
            assertEquals(true, completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, cleanupCount.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
