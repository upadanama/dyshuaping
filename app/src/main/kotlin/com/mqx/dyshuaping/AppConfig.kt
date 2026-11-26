package com.mqx.dyshuaping

import android.content.Context
import android.content.SharedPreferences

data class Settings(
    val messages: List<String>,
    val randomMode: Boolean,
    val suffixEnabled: Boolean,
    val suffixSymbols: Boolean,
    val suffixNumbers: Boolean,
    val suffixEmoji: Boolean,
    val suffixCount: Int,
    val intervalMinSec: Int,
    val intervalMaxSec: Int,
    val targetPackage: String,
    val inputX: Float,
    val inputY: Float,
    val sendX: Float,
    val sendY: Float
) {
    val hasCalibration: Boolean
        get() = inputX in 0f..1f && inputY in 0f..1f && sendX in 0f..1f && sendY in 0f..1f
}

object AppConfig {
    const val DEFAULT_TARGET_PACKAGE = "com.ss.android.ugc.aweme"
    const val DEFAULT_MESSAGES = "主播唱得真好听\n来了来了，支持一下\n讲得真好，学到了"

    private const val PREFS = "dyshuaping_prefs"
    private const val KEY_MESSAGES = "messages"
    private const val KEY_RANDOM_MODE = "random_mode"
    private const val KEY_SUFFIX_ENABLED = "suffix_enabled"
    private const val KEY_SUFFIX_SYMBOL = "suffix_symbol"
    private const val KEY_SUFFIX_NUMBER = "suffix_number"
    private const val KEY_SUFFIX_EMOJI = "suffix_emoji"
    private const val KEY_SUFFIX_COUNT = "suffix_count"
    private const val KEY_INTERVAL_MIN = "interval_min"
    private const val KEY_INTERVAL_MAX = "interval_max"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_INPUT_X = "calib_input_x"
    private const val KEY_INPUT_Y = "calib_input_y"
    private const val KEY_SEND_X = "calib_send_x"
    private const val KEY_SEND_Y = "calib_send_y"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs(context).edit()
        editor.block()
        editor.apply()
    }

    fun parseMessages(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun load(context: Context): Settings {
        val p = prefs(context)
        val messages = parseMessages(p.getString(KEY_MESSAGES, DEFAULT_MESSAGES) ?: DEFAULT_MESSAGES)
        var minSec = p.getInt(KEY_INTERVAL_MIN, 3).coerceIn(1, 60)
        var maxSec = p.getInt(KEY_INTERVAL_MAX, 8).coerceIn(1, 60)
        if (maxSec < minSec) maxSec = minSec
        val target = p.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)
            ?.trim()
            .orEmpty()
            .ifEmpty { DEFAULT_TARGET_PACKAGE }
        return Settings(
            messages = messages,
            randomMode = p.getBoolean(KEY_RANDOM_MODE, true),
            suffixEnabled = p.getBoolean(KEY_SUFFIX_ENABLED, true),
            suffixSymbols = p.getBoolean(KEY_SUFFIX_SYMBOL, true),
            suffixNumbers = p.getBoolean(KEY_SUFFIX_NUMBER, true),
            suffixEmoji = p.getBoolean(KEY_SUFFIX_EMOJI, true),
            suffixCount = p.getInt(KEY_SUFFIX_COUNT, 2).coerceIn(1, 3),
            intervalMinSec = minSec,
            intervalMaxSec = maxSec,
            targetPackage = target,
            inputX = p.getFloat(KEY_INPUT_X, -1f),
            inputY = p.getFloat(KEY_INPUT_Y, -1f),
            sendX = p.getFloat(KEY_SEND_X, -1f),
            sendY = p.getFloat(KEY_SEND_Y, -1f)
        )
    }

    fun saveMessages(context: Context, messages: String) {
        edit(context) { putString(KEY_MESSAGES, messages) }
    }

    fun saveRandomMode(context: Context, randomMode: Boolean) {
        edit(context) { putBoolean(KEY_RANDOM_MODE, randomMode) }
    }

    fun saveSuffixSettings(
        context: Context,
        enabled: Boolean,
        symbols: Boolean,
        numbers: Boolean,
        emoji: Boolean,
        count: Int
    ) {
        edit(context) {
            putBoolean(KEY_SUFFIX_ENABLED, enabled)
            putBoolean(KEY_SUFFIX_SYMBOL, symbols)
            putBoolean(KEY_SUFFIX_NUMBER, numbers)
            putBoolean(KEY_SUFFIX_EMOJI, emoji)
            putInt(KEY_SUFFIX_COUNT, count.coerceIn(1, 3))
        }
    }

    fun saveIntervals(context: Context, minSec: Int, maxSec: Int) {
        val lo = minSec.coerceIn(1, 60)
        val hi = maxSec.coerceIn(lo, 60)
        edit(context) {
            putInt(KEY_INTERVAL_MIN, lo)
            putInt(KEY_INTERVAL_MAX, hi)
        }
    }

    fun saveTargetPackage(context: Context, packageName: String) {
        edit(context) { putString(KEY_TARGET_PACKAGE, packageName.trim()) }
    }

    fun saveCalibration(context: Context, inputX: Float, inputY: Float, sendX: Float, sendY: Float) {
        edit(context) {
            putFloat(KEY_INPUT_X, inputX.coerceIn(0f, 1f))
            putFloat(KEY_INPUT_Y, inputY.coerceIn(0f, 1f))
            putFloat(KEY_SEND_X, sendX.coerceIn(0f, 1f))
            putFloat(KEY_SEND_Y, sendY.coerceIn(0f, 1f))
        }
    }

    fun clearCalibration(context: Context) {
        edit(context) {
            putFloat(KEY_INPUT_X, -1f)
            putFloat(KEY_INPUT_Y, -1f)
            putFloat(KEY_SEND_X, -1f)
            putFloat(KEY_SEND_Y, -1f)
        }
    }
}
