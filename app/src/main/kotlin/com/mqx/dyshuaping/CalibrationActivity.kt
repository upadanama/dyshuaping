package com.mqx.dyshuaping

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class CalibrationActivity : Activity() {
    companion object {
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun launch(context: Context): Boolean {
            val intent = Intent(context, CalibrationActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            return try {
                context.startActivity(intent)
                true
            } catch (t: Throwable) {
                LogStore.add("校准", "打开校准界面失败：${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    private enum class ActiveTarget { INPUT, SEND }

    private data class CalPoint(var x: Int, var y: Int)

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var canvasView: CalibrationCanvasView
    private lateinit var coordsText: TextView
    private lateinit var inputTab: TextView
    private lateinit var sendTab: TextView
    private lateinit var testButton: TextView
    private var inputPoint = CalPoint(0, 0)
    private var sendPoint = CalPoint(0, 0)
    private var activeTarget = ActiveTarget.INPUT
    private var screenWidth = 0
    private var screenHeight = 0
    private var testAvailable = false
    private var testReadyAt = 0L
    private var finishing = false

    private val testButtonReset = Runnable {
        if (!finishing) {
            testButton.isEnabled = testAvailable
            testButton.isClickable = testAvailable
            testButton.alpha = if (testAvailable) 1f else 0.5f
        }
    }

    private val testTouchableReset = Runnable {
        if (!finishing) {
            try {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        running = true
        configureWindow()
        val real = ScreenUtils.realSize(this)
        screenWidth = real.x.coerceAtLeast(1)
        screenHeight = real.y.coerceAtLeast(1)
        loadPoints()
        testAvailable = AutomationAccessibilityService.instance?.canTestCalibrationClick() == true
        setContentView(buildUi())
        applyImmersiveMode()
        updateUi()
        AutomationAccessibilityService.instance?.onCalibrationActivityOpened()
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {
        }
        LogStore.add("校准", "校准界面已打开，屏幕=${screenWidth}x$screenHeight")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onDestroy() {
        finishing = true
        uiHandler.removeCallbacksAndMessages(null)
        running = false
        super.onDestroy()
        if (isFinishing) {
            AutomationAccessibilityService.instance?.onCalibrationScreenFinished()
        }
    }

    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    private fun loadPoints() {
        val settings = AppConfig.load(this)
        if (settings.hasCalibration) {
            inputPoint = CalPoint(
                ScreenUtils.normalizedToPx(settings.inputX, screenWidth),
                ScreenUtils.normalizedToPx(settings.inputY, screenHeight)
            )
            sendPoint = CalPoint(
                ScreenUtils.normalizedToPx(settings.sendX, screenWidth),
                ScreenUtils.normalizedToPx(settings.sendY, screenHeight)
            )
        } else {
            inputPoint = CalPoint(
                (screenWidth * 0.45f).roundToInt(),
                (screenHeight * 0.84f).roundToInt()
            )
            sendPoint = CalPoint(
                (screenWidth * 0.92f).roundToInt(),
                (screenHeight * 0.84f).roundToInt()
            )
        }
        clampPoint(inputPoint)
        clampPoint(sendPoint)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        canvasView = CalibrationCanvasView(this)
        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = panelDrawable()
        }
        topCard.addView(
            label("校准评论位置", 15f, ContextCompat.getColor(this, R.color.text), bold = true)
        )
        topCard.addView(
            label(
                "手指点哪儿，当前准星就移到哪儿；可拖到屏幕边缘，也可用下方微调。",
                11f,
                ContextCompat.getColor(this, R.color.text_dim)
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        )

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        inputTab = targetTab("输入框", ContextCompat.getColor(this, R.color.cyan))
        sendTab = targetTab("发送按钮", ContextCompat.getColor(this, R.color.accent))
        inputTab.setOnClickListener {
            activeTarget = ActiveTarget.INPUT
            updateUi()
        }
        sendTab.setOnClickListener {
            activeTarget = ActiveTarget.SEND
            updateUi()
        }
        tabs.addView(
            inputTab,
            LinearLayout.LayoutParams(0, dp(42f), 1f)
        )
        tabs.addView(
            sendTab,
            LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginStart = dp(8f) }
        )
        topCard.addView(
            tabs,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        )

        coordsText = label("", 11f, ContextCompat.getColor(this, R.color.text_dim)).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(5f), 0, 0)
        }
        topCard.addView(
            coordsText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = actionButton(
            "取消",
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(this, R.color.text_dim)
        )
        cancel.setOnClickListener {
            toast("已取消校准")
            finish()
        }
        val save = actionButton(
            "保存校准",
            R.drawable.bg_button_primary,
            Color.WHITE
        )
        save.setOnClickListener { saveAndFinish() }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(44f), 1f))
        actions.addView(
            save,
            LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(8f) }
        )
        topCard.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        )

        root.addView(
            topCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                leftMargin = dp(12f)
                rightMargin = dp(12f)
                topMargin = dp(16f)
            }
        )

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            background = panelDrawable(Color.parseColor("#C0171920"))
        }
        val up = directionButton("上")
        val down = directionButton("下")
        val left = directionButton("左")
        val right = directionButton("右")
        installRepeat(up, 0, -1)
        installRepeat(down, 0, 1)
        installRepeat(left, -1, 0)
        installRepeat(right, 1, 0)
        bottomBar.addView(up, LinearLayout.LayoutParams(0, dp(44f), 1f))
        bottomBar.addView(down, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(6f) })
        bottomBar.addView(left, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(6f) })
        bottomBar.addView(right, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(6f) })
        testButton = actionButton(
            "测试点击",
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(this, R.color.cyan)
        ).apply {
            setOnClickListener { performTestClick() }
        }
        bottomBar.addView(testButton, LinearLayout.LayoutParams(0, dp(44f), 1.5f).apply {
            marginStart = dp(6f)
        })
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(12f)
                rightMargin = dp(12f)
                bottomMargin = dp(14f)
            }
        )

        return root
    }

    private fun updateUi() {
        if (!::coordsText.isInitialized) return
        val active = activePoint()
        coordsText.text =
            "当前：${if (activeTarget == ActiveTarget.INPUT) "输入框" else "发送按钮"} " +
                "(${active.x}, ${active.y})    " +
                "输入框 (${inputPoint.x}, ${inputPoint.y})  ·  发送 (${sendPoint.x}, ${sendPoint.y})"
        inputTab.background = chipDrawable(
            activeTarget == ActiveTarget.INPUT,
            ContextCompat.getColor(this, R.color.cyan)
        )
        inputTab.setTextColor(
            ContextCompat.getColor(
                this,
                if (activeTarget == ActiveTarget.INPUT) R.color.cyan else R.color.text_dim
            )
        )
        sendTab.background = chipDrawable(
            activeTarget == ActiveTarget.SEND,
            ContextCompat.getColor(this, R.color.accent)
        )
        sendTab.setTextColor(
            ContextCompat.getColor(
                this,
                if (activeTarget == ActiveTarget.SEND) R.color.accent else R.color.text_dim
            )
        )
        val testCoolingDown = SystemClock.elapsedRealtime() < testReadyAt
        val canTest = testAvailable && !testCoolingDown
        testButton.isEnabled = canTest
        testButton.isClickable = canTest
        testButton.alpha = if (canTest) 1f else 0.45f
        canvasView.invalidate()
    }

    private fun activePoint(): CalPoint =
        if (activeTarget == ActiveTarget.INPUT) inputPoint else sendPoint

    private fun moveActive(x: Int, y: Int) {
        val point = activePoint()
        point.x = x.coerceIn(0, screenWidth - 1)
        point.y = y.coerceIn(0, screenHeight - 1)
        updateUi()
    }

    private fun moveActiveBy(dx: Int, dy: Int) {
        val point = activePoint()
        moveActive(point.x + dx, point.y + dy)
    }

    private fun performTestClick() {
        val now = SystemClock.elapsedRealtime()
        if (now < testReadyAt) return
        if (!testAvailable) {
            toast("无障碍服务未连接，测试点击不可用")
            return
        }
        val service = AutomationAccessibilityService.instance
        if (service == null || !service.canTestCalibrationClick()) {
            testAvailable = false
            updateUi()
            toast("无障碍服务未连接，测试点击不可用")
            return
        }
        val point = activePoint()
        testReadyAt = now + 1000L
        testButton.isEnabled = false
        testButton.isClickable = false
        testButton.alpha = 0.45f
        uiHandler.postDelayed(testButtonReset, 1000L)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } catch (_: Throwable) {
        }
        uiHandler.postDelayed({
            if (!finishing) {
                val ok = service.dispatchCalibrationTestClick(point.x, point.y)
                toast(if (ok) "测试点击已发送" else "测试点击失败")
            }
            uiHandler.postDelayed(testTouchableReset, 350L)
        }, 60L)
    }

    private fun installRepeat(button: TextView, dx: Int, dy: Int) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveActiveBy(dx, dy)
                    val repeat = object : Runnable {
                        override fun run() {
                            moveActiveBy(dx, dy)
                            uiHandler.postDelayed(this, 50L)
                        }
                    }
                    view.tag = repeat
                    uiHandler.postDelayed(repeat, 350L)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (view.tag as? Runnable)?.let { uiHandler.removeCallbacks(it) }
                    view.tag = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun saveAndFinish() {
        if (screenWidth <= 0 || screenHeight <= 0) {
            toast("无法获取屏幕尺寸，保存失败")
            return
        }
        val inputX = inputPoint.x.toFloat() / screenWidth
        val inputY = inputPoint.y.toFloat() / screenHeight
        val sendX = sendPoint.x.toFloat() / screenWidth
        val sendY = sendPoint.y.toFloat() / screenHeight
        AppConfig.saveCalibration(this, inputX, inputY, sendX, sendY)
        LogStore.add(
            "校准",
            "保存：屏幕=${screenWidth}x$screenHeight 输入框=(${inputPoint.x},${inputPoint.y}) " +
                "发送=(${sendPoint.x},${sendPoint.y})"
        )
        toast("校准已保存")
        finish()
    }

    private fun clampPoint(point: CalPoint) {
        point.x = point.x.coerceIn(0, screenWidth - 1)
        point.y = point.y.coerceIn(0, screenHeight - 1)
    }

    private fun directionButton(textValue: String): TextView {
        return actionButton(
            textValue,
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(this, R.color.text)
        )
    }

    private fun targetTab(textValue: String, color: Int): TextView {
        return label(textValue, 13f, color, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(8f), 0, dp(8f), 0)
            background = chipDrawable(false, color)
        }
    }

    private fun label(textValue: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
        }
    }

    private fun actionButton(textValue: String, backgroundRes: Int, textColor: Int): TextView {
        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            background = ContextCompat.getDrawable(this@CalibrationActivity, backgroundRes)?.mutate()
            isClickable = true
            isFocusable = true
            minHeight = 0
            setPadding(dp(6f), 0, dp(6f), 0)
        }
    }

    private fun panelDrawable(color: Int = Color.parseColor("#F2171920")): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(color)
            setStroke(dp(1f), Color.parseColor("#4A5360"))
        }
    }

    private fun chipDrawable(selected: Boolean, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            if (selected) {
                setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke(dp(1f), color)
            } else {
                setColor(Color.parseColor("#20232B"))
                setStroke(dp(1f), Color.parseColor("#303542"))
            }
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldW = screenWidth
        val oldH = screenHeight
        val real = ScreenUtils.realSize(this)
        screenWidth = real.x.coerceAtLeast(1)
        screenHeight = real.y.coerceAtLeast(1)
        if (oldW > 0 && oldH > 0) {
            inputPoint.x = (inputPoint.x.toFloat() / oldW * screenWidth).roundToInt()
            inputPoint.y = (inputPoint.y.toFloat() / oldH * screenHeight).roundToInt()
            sendPoint.x = (sendPoint.x.toFloat() / oldW * screenWidth).roundToInt()
            sendPoint.y = (sendPoint.y.toFloat() / oldH * screenHeight).roundToInt()
            clampPoint(inputPoint)
            clampPoint(sendPoint)
        }
        updateUi()
        applyImmersiveMode()
        window.decorView.post { applyImmersiveMode() }
    }

    private inner class CalibrationCanvasView(context: Context) : View(context) {
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(92, 0, 0, 0)
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var originX = 0
        private var originY = 0
        private val span = dp(10f).toFloat()
        private val dotRadius = dp(1.5f).toFloat()

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "全屏校准区域，手指点哪里当前准星就去哪里"
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val location = IntArray(2)
            getLocationOnScreen(location)
            originX = location[0]
            originY = location[1]
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val inputColor = ContextCompat.getColor(this@CalibrationActivity, R.color.cyan)
            val sendColor = ContextCompat.getColor(this@CalibrationActivity, R.color.accent)
            if (activeTarget == ActiveTarget.INPUT) {
                drawCross(canvas, sendPoint, sendColor, 80)
                drawCross(canvas, inputPoint, inputColor, 255)
            } else {
                drawCross(canvas, inputPoint, inputColor, 80)
                drawCross(canvas, sendPoint, sendColor, 255)
            }
        }

        private fun drawCross(canvas: Canvas, point: CalPoint, color: Int, alpha: Int) {
            val cx = (point.x - originX).toFloat()
            val cy = (point.y - originY).toFloat()
            val drawColor = Color.argb(
                alpha.coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            linePaint.color = drawColor
            dotPaint.color = drawColor
            canvas.drawLine(cx - span, cy, cx + span, cy, linePaint)
            canvas.drawLine(cx, cy - span, cx, cy + span, linePaint)
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    moveActive(event.rawX.roundToInt(), event.rawY.roundToInt())
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
