package com.poscafe.pos

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * On-device crash reports. The uncaught-exception handler writes the stack
 * trace to `files/crashes/` (newest [KEEP] kept) before the process dies; the
 * Sync screen shows the latest one so staff can share it with support. Nothing
 * leaves the device unless someone shares it, and only the stack trace, app
 * version and device model are recorded — no request bodies or tokens.
 */
object CrashLog {

    data class Report(val file: File, val at: Long, val summary: String, val text: String)

    private const val KEEP = 10

    fun install(context: Context) {
        val dir = dir(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun latest(context: Context): Report? =
        dir(context).listFiles()?.maxByOrNull { it.lastModified() }?.let { f ->
            val text = f.readText()
            Report(f, f.lastModified(), text.lineSequence().drop(4).firstOrNull().orEmpty(), text)
        }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun dir(context: Context) = File(context.filesDir, "crashes").apply { mkdirs() }

    private fun write(dir: File, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val now = System.currentTimeMillis()
        File(dir, "crash-$now.txt").writeText(
            buildString {
                appendLine("time: ${Instant.ofEpochMilli(now)}")
                appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                appendLine("thread: ${thread.name}")
                append(trace)
            },
        )
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }
}
