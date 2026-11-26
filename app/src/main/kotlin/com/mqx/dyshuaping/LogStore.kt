package com.mqx.dyshuaping

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object LogStore {
    private const val MAX_LINES = 200
    private const val ANDROID_TAG = "Dyshuaping"

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(category: String, message: String) {
        val line = "[${timeFormat.format(Date())}] [$category] $message"
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        Log.d(ANDROID_TAG, line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun asText(): String = lines.joinToString(separator = "\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
