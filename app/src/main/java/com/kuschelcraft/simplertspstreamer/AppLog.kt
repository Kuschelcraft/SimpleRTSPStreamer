package com.kuschelcraft.simplertspstreamer

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring buffer log, shown in the app so problems can be diagnosed without adb. */
object AppLog {
    private const val TAG = "SimpleRTSP"
    private const val MAX_LINES = 1000
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        Log.i(TAG, message)
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(format.format(Date()) + "  " + message)
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }
}
