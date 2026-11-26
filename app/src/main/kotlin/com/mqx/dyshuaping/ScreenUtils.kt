package com.mqx.dyshuaping

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import kotlin.math.roundToInt

object ScreenUtils {
    /** Real display size, including status and navigation bars. */
    @Suppress("DEPRECATION")
    fun realSize(context: Context): Point {
        val point = Point()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealSize(point)
        } catch (_: Throwable) {
            val metrics = context.resources.displayMetrics
            point.set(metrics.widthPixels, metrics.heightPixels)
        }
        if (point.x <= 0 || point.y <= 0) {
            val metrics = context.resources.displayMetrics
            point.set(metrics.widthPixels, metrics.heightPixels)
        }
        return point
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun normalizedToPx(normalized: Float, fullSize: Int): Int {
        if (fullSize <= 1) return 0
        return (normalized.coerceIn(0f, 1f) * fullSize)
            .roundToInt()
            .coerceIn(0, fullSize - 1)
    }
}
