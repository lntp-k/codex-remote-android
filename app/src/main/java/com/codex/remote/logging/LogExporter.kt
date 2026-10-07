package com.codex.remote.logging

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

private const val TAG = "LogExporter"
private const val EXPORT_SUBDIR = "CodexRemote"

sealed interface LogExportResult {
    data class Success(val label: String) : LogExportResult
    data class Failure(val reason: String) : LogExportResult
}

/** Copies the app's log files into a dedicated folder under the public Downloads directory. */
object LogExporter {
    private val fileNameFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }

    suspend fun export(context: Context, reason: String): LogExportResult = withContext(Dispatchers.IO) {
        exportBlocking(context, reason)
    }

    /** Synchronous variant, safe to call from a non-coroutine context such as a crash handler. */
    fun exportBlocking(context: Context, reason: String): LogExportResult {
        val appContext = context.applicationContext
        return runCatching {
            val combined = AppLog.snapshotBlocking(appContext)
            if (combined.isEmpty()) {
                AppLog.w(TAG, "export_skipped reason=$reason cause=empty")
                return LogExportResult.Failure("No log content to export yet")
            }
            val fileName = "codex-remote-log-${fileNameFormat.get()!!.format(System.currentTimeMillis())}-${UUID.randomUUID()}.txt"
            val label = writeToDownloads(appContext, fileName, combined)
            AppLog.i(TAG, "export_succeeded reason=$reason target=$label")
            LogExportResult.Success(label)
        }.getOrElse { error ->
            AppLog.e(TAG, "export_failed reason=$reason", error)
            LogExportResult.Failure(error.message ?: "Export failed")
        }
    }

    /** Fire-and-forget export for error paths that aren't already inside a coroutine. */
    fun exportAsync(context: Context, reason: String) {
        val appContext = context.applicationContext
        Thread({ exportBlocking(appContext, reason) }, "AppLog-export").apply { isDaemon = true }.start()
    }

    private fun writeToDownloads(context: Context, fileName: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context.contentResolver, fileName, bytes)
        } else {
            writeViaLegacyFile(fileName, bytes)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(resolver: ContentResolver, fileName: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIR")
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create the export entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open export stream")
        return "Download/$EXPORT_SUBDIR/$fileName"
    }

    private fun writeViaLegacyFile(fileName: String, bytes: ByteArray): String {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, EXPORT_SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("Could not create $dir")
        val target = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", dir)
        FileOutputStream(target).use { it.write(bytes) }
        return target.absolutePath
    }
}
