package com.aalyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last error on the phone so problems seen in the car (where there's no logcat) can be
 * read on the phone screen afterwards. Also records anything that would crash the app.
 */
object CrashLog {
    private const val TAG = "AALyricsError"
    private const val FILE = "last_error.txt"
    private var file: File? = null
    private var installed = false

    /** Coroutine handler for app scopes: log and keep running instead of crashing. */
    val handler = CoroutineExceptionHandler { _, e -> record("coroutine", e) }

    fun install(context: Context) {
        if (installed) return
        installed = true
        file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record("crash on ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(where: String, e: Throwable) {
        Log.e(TAG, "error in $where", e)
        val f = file ?: return
        runCatching {
            val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.writeText("$time  ($where)\n${trace.lineSequence().take(25).joinToString("\n")}")
        }
    }

    fun read(): String? = file?.takeIf { it.exists() }?.readText()

    fun clear() {
        file?.delete()
    }
}
