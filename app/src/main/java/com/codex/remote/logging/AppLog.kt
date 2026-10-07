package com.codex.remote.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide file logger. Every entry is mirrored to Logcat and appended to a
 * rotating file under app-private storage, so a report can be produced after the
 * fact for a crash, a failed SSH connection, or a user-triggered export.
 */
object AppLog {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "codex-remote.log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_ROTATED_FILES = 3

    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // Writes are serialized on a single background thread so callers never block on file IO.
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLog-writer").apply { isDaemon = true }
    }

    @Volatile private var logDir: File? = null
    @Volatile private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        val dir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
        dir.mkdirs()
        logDir = dir
        i("AppLog", "log_init dir=${dir.absolutePath}")
    }

    /** Installs a crash handler that logs the fatal exception and exports the log before dying. */
    fun installCrashHandler(context: Context) {
        if (previousUncaughtHandler != null) return
        val appContext = context.applicationContext
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("AppLog", "uncaught_exception thread=${thread.name}", throwable)
                LogExporter.exportBlocking(appContext, reason = "crash")
            }
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }
        val dir = logDir ?: return
        val line = buildString {
            append(timestampFormat.get()!!.format(System.currentTimeMillis()))
            append(' ').append(level.letter).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                append('\n')
                append(stackTraceOf(throwable))
            }
            append('\n')
        }
        writer.execute {
            runCatching { appendToFile(dir, line) }
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private fun appendToFile(dir: File, line: String) {
        val file = File(dir, LOG_FILE_NAME)
        if (file.exists() && file.length() > MAX_FILE_BYTES) rotate(dir, file)
        file.appendText(line, Charsets.UTF_8)
    }

    private fun rotate(dir: File, current: File) {
        for (index in MAX_ROTATED_FILES downTo 1) {
            val src = if (index == 1) current else File(dir, "$LOG_FILE_NAME.${index - 1}")
            val dst = File(dir, "$LOG_FILE_NAME.$index")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }

    /** Reads an immutable, chronological snapshot between queued writes and rotations. */
    internal fun snapshotBlocking(context: Context, timeoutMillis: Long = 2_000): String {
        val dir = logDir ?: File(context.applicationContext.filesDir, LOG_DIR_NAME)
        val snapshot = writer.submit<String> {
            val files = (MAX_ROTATED_FILES downTo 0).map { index ->
                File(dir, if (index == 0) LOG_FILE_NAME else "$LOG_FILE_NAME.$index")
            }.filter { it.isFile && it.length() > 0 }
            files.joinToString(separator = "\n") { it.readText(Charsets.UTF_8) }
        }
        return try {
            snapshot.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            snapshot.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }
}
