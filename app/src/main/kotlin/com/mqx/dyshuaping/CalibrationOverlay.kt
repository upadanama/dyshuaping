package com.mqx.dyshuaping

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * A full-screen translucent calibration layer. The dim backdrop is not touchable,
 * so the underlying Douyin live room remains usable. Only the two crosshair
 * windows and the top control card intercept touches.
 */
class CalibrationOverlay private constructor(
    private val context: Context,
    private val onFinished: ((Boolean) -> Unit)?
) {
    companion object {
        @JvmStatic
        private var active: CalibrationOverlay? = null

        fun isShowing(): Boolean = active != null

        fun start(context: Context, onFinished: ((Boolean) -> Unit)? = null): Boolean {
            if (!android.provider.Settings.canDrawOverlays(context)) return false
            active?.dismiss(notify = false, saved = false)
            val overlay = CalibrationOverlay(context.applicationContext, onFinished)
            return if (overlay.show()) {
                active = overlay
                true
            } else {
                overlay.cleanup()
                false
            }
        }

        fun cancelActive() {
            active?.dismiss(notify = false, saved = false)
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val screen: Point = ScreenUtils.realSize(context)
    private val handleSize = dp(112f)
    private val halfHandle = handleSize / 2

    private val windows = ArrayList<View>()
    private var inputView: CrosshairView? = null
    private var sendView: CrosshairView? = null
    private var inputParams: WindowManager.LayoutParams? = null
    private var sendParams: WindowManager.LayoutParams? = null
    private var coordsText: TextView? = null
    private var dismissed = false

    private fun show(): Boolean {
        return try {
            addDim()
            addHandles()
            addControlCard()
            updateCoordsText()
            true
        } catch (t: Throwable) {
            LogStore.add("校准", "打开校准层失败：${t.javaClass.simpleName}: ${t.message}")
            toast("无法打开校准层，请检查悬浮窗权限")
            false
        }
    }

    private fun addDim() {
        val dim = View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.scrim))
        }
        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(dim, params)
        windows.add(dim)
    }

    private fun addHandles() {
        val settings = AppConfig.load(context)
        val inputCenterX: Int
        val inputCenterY: Int
        val sendCenterX: Int
        val sendCenterY: Int
        if (settings.hasCalibration) {
            inputCenterX = (settings.inputX * screen.x).roundToInt()
            inputCenterY = (settings.inputY * screen.y).roundToInt()
            sendCenterX = (settings.sendX * screen.x).roundToInt()
            sendCenterY = (settings.sendY * screen.y).roundToInt()
        } else {
            inputCenterX = (screen.x * 0.44f).roundToInt()
            inputCenterY = (screen.y * 0.84f).roundToInt()
            sendCenterX = (screen.x * 0.91f).roundToInt()
            sendCenterY = (screen.y * 0.84f).roundToInt()
        }

        inputView = CrosshairView(context, "输入框", ContextCompat.getColor(context, R.color.cyan))
            .apply { contentDescription = "拖动输入框校准准星" }
        sendView = CrosshairView(context, "发送", ContextCompat.getColor(context, R.color.accent))
            .apply { contentDescription = "拖动发送按钮校准准星" }

        inputParams = addHandleWindow(inputView!!, inputCenterX, inputCenterY, "输入框")
        sendParams = addHandleWindow(sendView!!, sendCenterX, sendCenterY, "发送按钮")
    }

    private fun addHandleWindow(view: View, centerX: Int, centerY: Int, label: String): WindowManager.LayoutParams {
        val cx = centerX.coerceIn(halfHandle, screen.x - halfHandle)
        val cy = centerY.coerceIn(halfHandle, screen.y - halfHandle)
        val params = baseParams().apply {
            width = handleSize
            height = handleSize
            x = cx - halfHandle
            y = cy - halfHandle
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
        }
        installDrag(view, params, label)
        windowManager.addView(view, params)
        windows.add(view)
        return params
    }

    private fun addControlCard() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.parseColor("#F2171920"))
                setStroke(dp(1f), Color.parseColor("#4A5360"))
            }
        }

        val title = TextView(context).apply {
            text = "校准评论位置"
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(title)

        val hint = TextView(context).apply {
            text = "请先进入抖音直播间。拖动蓝色十字到「说点什么…」输入框中心，红色十字到红色「发送」按钮中心。"
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(dp(2f).toFloat(), 1f)
        }
        card.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) })

        val coords = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.cyan))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
        }
        coordsText = coords
        card.addView(coords, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = makeButton("取消", R.drawable.bg_button_ghost, ContextCompat.getColor(context, R.color.text))
        cancel.setOnClickListener {
            logCurrent("取消")
            dismiss(notify = true, saved = false)
        }
        val save = makeButton("保存校准", R.drawable.bg_button_success, Color.parseColor("#08130D"))
        save.setOnClickListener {
            saveAndClose()
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(44f), 1f))
        row.addView(save, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(8f) })
        card.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12f) })

        val maxWidth = (screen.x - dp(32f)).coerceAtMost(dp(380f)).coerceAtLeast(dp(260f))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST)
        )
        val width = card.measuredWidth
        val height = card.measuredHeight
        val statusBar = statusBarHeight()
        val params = baseParams().apply {
            this.width = width
            this.height = height
            x = ((screen.x - width) / 2).coerceAtLeast(0)
            y = statusBar + dp(10f)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(card, params)
        windows.add(card)
    }

    private fun installDrag(view: View, params: WindowManager.LayoutParams, label: String) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val maxX = (screen.x - params.width).coerceAtLeast(0)
                    val maxY = (screen.y - params.height).coerceAtLeast(0)
                    params.x = (startX + (event.rawX - downRawX).roundToInt()).coerceIn(0, maxX)
                    params.y = (startY + (event.rawY - downRawY).roundToInt()).coerceIn(0, maxY)
                    safeUpdate(view, params)
                    updateCoordsText()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        logCurrent("拖动$label")
                        updateCoordsText()
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun updateCoordsText() {
        val text = coordsText ?: return
        val input = inputParams
        val send = sendParams
        if (input == null || send == null) return
        val ix = (input.x + halfHandle).coerceIn(0, screen.x - 1)
        val iy = (input.y + halfHandle).coerceIn(0, screen.y - 1)
        val sx = (send.x + halfHandle).coerceIn(0, screen.x - 1)
        val sy = (send.y + halfHandle).coerceIn(0, screen.y - 1)
        val ixPercent = (ix * 100f / screen.x).roundToInt()
        val iyPercent = (iy * 100f / screen.y).roundToInt()
        val sxPercent = (sx * 100f / screen.x).roundToInt()
        val syPercent = (sy * 100f / screen.y).roundToInt()
        text.text = "输入框 ${ixPercent}%, ${iyPercent}%   ·   发送 ${sxPercent}%, ${syPercent}%"
    }

    private fun saveAndClose() {
        val input = inputParams ?: return
        val send = sendParams ?: return
        val ix = (input.x + halfHandle).coerceIn(0, screen.x - 1)
        val iy = (input.y + halfHandle).coerceIn(0, screen.y - 1)
        val sx = (send.x + halfHandle).coerceIn(0, screen.x - 1)
        val sy = (send.y + halfHandle).coerceIn(0, screen.y - 1)
        val normalizedInputX = ix.toFloat() / screen.x
        val normalizedInputY = iy.toFloat() / screen.y
        val normalizedSendX = sx.toFloat() / screen.x
        val normalizedSendY = sy.toFloat() / screen.y
        AppConfig.saveCalibration(
            context,
            normalizedInputX,
            normalizedInputY,
            normalizedSendX,
            normalizedSendY
        )
        LogStore.add(
            "校准",
            "保存坐标：输入框=($ix,$iy) 发送=($sx,$sy)，屏幕=${screen.x}x${screen.y}"
        )
        toast("校准已保存")
        dismiss(notify = true, saved = true)
    }

    private fun logCurrent(action: String) {
        val input = inputParams ?: return
        val send = sendParams ?: return
        val ix = input.x + halfHandle
        val iy = input.y + halfHandle
        val sx = send.x + halfHandle
        val sy = send.y + halfHandle
        LogStore.add("校准", "$action：输入框=($ix,$iy) 发送=($sx,$sy)")
    }

    private fun dismiss(notify: Boolean, saved: Boolean) {
        if (dismissed) return
        dismissed = true
        cleanup()
        if (active === this) active = null
        if (notify) {
            try {
                onFinished?.invoke(saved)
            } catch (_: Throwable) {
            }
        }
    }

    private fun cleanup() {
        for (view in windows.asReversed()) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        windows.clear()
        inputView = null
        sendView = null
        inputParams = null
        sendParams = null
        coordsText = null
    }

    private fun baseParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun makeButton(textValue: String, backgroundRes: Int, textColor: Int): TextView {
        return TextView(context).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            background = ContextCompat.getDrawable(context, backgroundRes)
            isClickable = true
            isFocusable = true
            setPadding(dp(12f), 0, dp(12f), 0)
        }
    }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun toast(message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private class CrosshairView(
        context: Context,
        private val label: String,
        private val color: Int
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = ColorUtils.withAlpha(color, 62)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            this.color = color
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            this.color = ColorUtils.withAlpha(Color.WHITE, 210)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = Color.WHITE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = ColorUtils.withAlpha(color, 235)
        }
        private val labelRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = 27f * density

            canvas.drawCircle(cx, cy, radius + 8f * density, fillPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)
            canvas.drawCircle(cx, cy, 3.5f * density, dotPaint)

            val textWidth = labelPaint.measureText(label)
            val padH = 12f * density
            val padV = 5f * density
            val labelTop = cy + radius + 12f * density
            labelRect.set(
                cx - textWidth / 2f - padH,
                labelTop,
                cx + textWidth / 2f + padH,
                labelTop + labelPaint.textSize + padV * 2f
            )
            canvas.drawRoundRect(labelRect, 8f * density, 8f * density, labelBg)
            canvas.drawText(
                label,
                cx,
                labelRect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                labelPaint
            )
        }
    }

    private object ColorUtils {
        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
