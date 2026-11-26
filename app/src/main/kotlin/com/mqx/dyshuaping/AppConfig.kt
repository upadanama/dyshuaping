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
    val intervalMinTenths: Int,
    val intervalMaxTenths: Int,
    val jitterEnabled: Boolean,
    val jitterMaxTenths: Int,
    val targetPackage: String,
    val inputX: Float,
    val inputY: Float,
    val sendX: Float,
    val sendY: Float,
    val singleShotText: String
) {
    val hasCalibration: Boolean
        get() = inputX in 0f..1f && inputY in 0f..1f && sendX in 0f..1f && sendY in 0f..1f
}

object AppConfig {
    const val DEFAULT_TARGET_PACKAGE = "com.ss.android.ugc.aweme"
    const val DEFAULT_MESSAGES = "主播唱得真好听\n来了来了，支持一下\n讲得真好，学到了"
    const val DEFAULT_SINGLE_SHOT_TEXT = "怎么回事？发不了了"
    const val DEFAULT_INTERVAL_MIN_TENTHS = 20
    const val DEFAULT_INTERVAL_MAX_TENTHS = 50
    const val DEFAULT_JITTER_MAX_TENTHS = 5

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
    private const val KEY_INTERVAL_MIN_TENTHS = "interval_min_tenths"
    private const val KEY_INTERVAL_MAX_TENTHS = "interval_max_tenths"
    private const val KEY_JITTER_ENABLED = "jitter_enabled"
    private const val KEY_JITTER_MAX_TENTHS = "jitter_max_tenths"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_INPUT_X = "calib_input_x"
    private const val KEY_INPUT_Y = "calib_input_y"
    private const val KEY_SEND_X = "calib_send_x"
    private const val KEY_SEND_Y = "calib_send_y"
    private const val KEY_SINGLE_SHOT_TEXT = "single_shot_text"

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
        var minTenths: Int
        var maxTenths: Int
        val hasNewMin = p.contains(KEY_INTERVAL_MIN_TENTHS)
        val hasNewMax = p.contains(KEY_INTERVAL_MAX_TENTHS)
        if (!hasNewMin && !hasNewMax) {
            val hasOld = p.contains(KEY_INTERVAL_MIN) || p.contains(KEY_INTERVAL_MAX)
            if (hasOld) {
                val oldMin = safeGetInt(p, KEY_INTERVAL_MIN, 3).coerceIn(1, 60)
                val oldMax = safeGetInt(p, KEY_INTERVAL_MAX, 8).coerceIn(1, 60)
                if (oldMin == 3 && oldMax == 8) {
                    // v1.1 default pair: apply the new v1.2 defaults for an in-place update.
                    minTenths = DEFAULT_INTERVAL_MIN_TENTHS
                    maxTenths = DEFAULT_INTERVAL_MAX_TENTHS
                } else {
                    minTenths = oldMin * 10
                    maxTenths = oldMax * 10
                }
            } else {
                minTenths = DEFAULT_INTERVAL_MIN_TENTHS
                maxTenths = DEFAULT_INTERVAL_MAX_TENTHS
            }
        } else {
            minTenths = if (hasNewMin) {
                safeGetInt(p, KEY_INTERVAL_MIN_TENTHS, DEFAULT_INTERVAL_MIN_TENTHS)
            } else {
                DEFAULT_INTERVAL_MIN_TENTHS
            }
            maxTenths = if (hasNewMax) {
                safeGetInt(p, KEY_INTERVAL_MAX_TENTHS, DEFAULT_INTERVAL_MAX_TENTHS)
            } else {
                DEFAULT_INTERVAL_MAX_TENTHS
            }
        }
        minTenths = minTenths.coerceIn(1, 600)
        maxTenths = maxTenths.coerceIn(1, 600)
        if (maxTenths < minTenths) maxTenths = minTenths
        val jitterEnabled = try {
            p.getBoolean(KEY_JITTER_ENABLED, false)
        } catch (_: Throwable) {
            false
        }
        val jitterMaxTenths = safeGetInt(p, KEY_JITTER_MAX_TENTHS, DEFAULT_JITTER_MAX_TENTHS)
            .coerceIn(1, 600)
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
            intervalMinTenths = minTenths,
            intervalMaxTenths = maxTenths,
            jitterEnabled = jitterEnabled,
            jitterMaxTenths = jitterMaxTenths,
            targetPackage = target,
            inputX = p.getFloat(KEY_INPUT_X, -1f),
            inputY = p.getFloat(KEY_INPUT_Y, -1f),
            sendX = p.getFloat(KEY_SEND_X, -1f),
            sendY = p.getFloat(KEY_SEND_Y, -1f),
            singleShotText = p.getString(KEY_SINGLE_SHOT_TEXT, DEFAULT_SINGLE_SHOT_TEXT)
                ?.trim()
                .orEmpty()
                .ifEmpty { DEFAULT_SINGLE_SHOT_TEXT }
        )
    }

    fun saveMessages(context: Context, messages: String) {
        edit(context) { putString(KEY_MESSAGES, messages) }
    }

    fun saveSingleShotText(context: Context, text: String) {
        edit(context) { putString(KEY_SINGLE_SHOT_TEXT, text.trim()) }
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

    fun saveTiming(
        context: Context,
        minTenths: Int,
        maxTenths: Int,
        jitterEnabled: Boolean,
        jitterMaxTenths: Int
    ) {
        val lo = minTenths.coerceIn(1, 600)
        val hi = maxTenths.coerceIn(lo, 600)
        val jitter = jitterMaxTenths.coerceIn(1, 600)
        edit(context) {
            putInt(KEY_INTERVAL_MIN_TENTHS, lo)
            putInt(KEY_INTERVAL_MAX_TENTHS, hi)
            putBoolean(KEY_JITTER_ENABLED, jitterEnabled)
            putInt(KEY_JITTER_MAX_TENTHS, jitter)
            // Clear obsolete v1.1 integer keys after successful migration.
            remove(KEY_INTERVAL_MIN)
            remove(KEY_INTERVAL_MAX)
        }
    }

    private fun safeGetInt(prefs: SharedPreferences, key: String, fallback: Int): Int {
        return try {
            prefs.getInt(key, fallback)
        } catch (_: Throwable) {
            fallback
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
