package org.arnor.extshare

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device event log shown in the app, so failures are visible without adb.
 * Persisted to a file so it survives crashes.
 */
object DebugLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "debug.log")
        file = f
        runCatching { f.readLines() }.getOrNull()?.takeLast(MAX_LINES)?.let { lines.addAll(it) }
    }

    @Synchronized
    fun log(tag: String, message: String, t: Throwable? = null) {
        if (t == null) Log.d(tag, message) else Log.e(tag, message, t)
        val suffix = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        lines.addLast("${timeFormat.format(Date())} $tag: $message$suffix")
        while (lines.size > MAX_LINES) lines.removeFirst()
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n")) } }
    }

    /** Exception summary with its cause chain and the top frames of each. */
    fun describe(t: Throwable): String = generateSequence(t) { it.cause }.take(4).joinToString(" <- caused by ") { e ->
        val frames = e.stackTrace.take(3).joinToString(" / ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        "${e.javaClass.name}: ${e.message?.take(200)} [$frames]"
    }

    @Synchronized
    fun clear() {
        lines.clear()
        file?.let { f -> runCatching { f.writeText("") } }
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
}
