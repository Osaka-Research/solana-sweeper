package com.harvest.solsweep

import java.text.SimpleDateFormat
import java.util.Locale

/** Small in-memory ring buffer of log lines + a listener the UI can attach while visible. */
object SweepLog {
    private const val MAX_LINES = 200
    private val lines = mutableListOf<String>()
    private var listener: ((List<String>) -> Unit)? = null

    @Synchronized
    fun add(line: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(System.currentTimeMillis())
        lines.add(0, "[$time] $line")
        while (lines.size > MAX_LINES) lines.removeAt(lines.size - 1)
        listener?.invoke(lines.toList())
    }

    @Synchronized
    fun setListener(l: ((List<String>) -> Unit)?) {
        listener = l
        l?.invoke(lines.toList())
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
