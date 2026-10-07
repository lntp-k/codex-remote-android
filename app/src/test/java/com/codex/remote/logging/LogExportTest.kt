package com.codex.remote.logging

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LogExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logDir get() = File(context.filesDir, "logs")

    @Before
    fun setUp() {
        AppLog.snapshotBlocking(context)
        logDir.deleteRecursively()
        AppLog.init(context)
        AppLog.snapshotBlocking(context)
    }

    @After
    fun tearDown() {
        AppLog.snapshotBlocking(context)
        logDir.deleteRecursively()
    }

    @Test
    fun snapshotRetainsEachRotatedSegmentOnceInChronologicalOrder() {
        val padding = "x".repeat(2 * 1024 * 1024)
        repeat(5) { index -> AppLog.i("snapshot-test", "SEGMENT-$index $padding") }

        val snapshot = AppLog.snapshotBlocking(context)

        assertFalse(snapshot.contains("SEGMENT-0"))
        val markers = Regex("SEGMENT-[0-9]+").findAll(snapshot).map { it.value }.toList()
        assertEquals(listOf("SEGMENT-1", "SEGMENT-2", "SEGMENT-3", "SEGMENT-4"), markers)
    }

    @Test
    fun concurrentSnapshotsRemainConsistentWhileLogsRotate() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val padding = "x".repeat(2 * 1024 * 1024)
            val writing = pool.submit {
                repeat(8) { index -> AppLog.i("snapshot-test", "SEGMENT-$index $padding") }
            }
            val reading = pool.submit {
                repeat(8) {
                    val snapshot = AppLog.snapshotBlocking(context)
                    val indices = Regex("SEGMENT-([0-9]+)").findAll(snapshot)
                        .map { match -> match.groupValues[1].toInt() }.toList()
                    assertEquals(indices.distinct().sorted(), indices)
                    indices.zipWithNext().forEach { (older, newer) -> assertEquals(older + 1, newer) }
                }
            }
            writing.get()
            reading.get()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun simultaneousLegacyExportsCreateSeparateCompleteReports() {
        AppLog.i("export-test", "retained diagnostic")
        val pool = Executors.newFixedThreadPool(4)
        val reports = mutableListOf<File>()
        try {
            val results = pool.invokeAll(List(8) {
                Callable { LogExporter.exportBlocking(context, "test") }
            }).map { it.get() }
            assertTrue(results.all { it is LogExportResult.Success })
            reports.addAll(results.filterIsInstance<LogExportResult.Success>().map { File(it.label) })
            assertEquals(8, reports.map { it.absolutePath }.distinct().size)
            reports.forEach { report ->
                assertTrue(report.isFile)
                assertTrue(report.readText().contains("retained diagnostic"))
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                assertEquals(File(downloads, "CodexRemote").canonicalFile, report.canonicalFile.parentFile)
            }
        } finally {
            pool.shutdownNow()
            reports.forEach { it.delete() }
        }
    }
}
