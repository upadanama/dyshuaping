package com.mqx.dyshuaping

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class FloatingOverlay(
    private val service: AutomationAccessibilityService,
    private val engine: AutomationEngine
) {
    private enum class Side { LEFT, RIGHT }

    private val context = service
    private val windowManager = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dotSize = dp(54f)
    private val edgeMargin = dp(8f)
    private var panelWidth = dp(300f)

    private var root: FrameLayout? = null
    private var dot: TextView? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var visible = false
    private var side = Side.RIGHT
    private var interactive = true

    private var statusText: TextView? = null
    private var statusDot: TextView? = null
    private var countText: TextView? = null
    private var countdownText: TextView? = null
    private var btnStart: TextView? = null
    private var btnPause: TextView? = null
    private var btnStop: TextView? = null
    private var btnTrial: TextView? = null
    private var btnSingleShot: TextView? = null
    private var singleShotResultView: TextView? = null
    private var btnDump: TextView? = null
    private var btnCalibrate: TextView? = null

    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!visible || !expanded) {
                tickerRunning = false
                return
            }
            updateCountdown()
            updateSingleShotUi(engine.snapshot())
            mainHandler.postDelayed(this, 500L)
        }
    }

    fun isShowing(): Boolean = visible

    fun show(): Boolean {
        if (visible) return true
        if (!android.provider.Settings.canDrawOverlays(service)) {
            LogStore.add("悬浮窗", "未授予悬浮窗权限，无法显示")
            return false
        }
        return try {
            build()
            visible = true
            refresh()
            true
        } catch (t: Throwable) {
            LogStore.add("悬浮窗", "显示失败：${t.javaClass.simpleName}: ${t.message}")
            destroyViews()
            false
        }
    }

    fun hide(showToast: Boolean = true) {
        if (!visible && root == null) return
        collapseInternal()
        destroyViews()
        visible = false
        if (showToast) {
            toast("悬浮窗已隐藏；可从 App 的「显示 / 恢复悬浮窗」重新打开")
        }
    }

    fun destroy() {
        stopTicker()
        destroyViews()
        visible = false
    }

    fun refresh() {
        val ui = engine.snapshot()
        updateDotColor(ui)
        if (!visible || root == null) return
        statusText?.text = ui.statusMessage
        statusDot?.setTextColor(statusColor(ui))
        countText?.text = context.getString(R.string.quick_count_format, ui.sentCount)
        updateCountdown()
        setActionEnabled(btnStart, !ui.roundActive && !ui.trialRunning && ui.phase != AutomationEngine.Phase.RUNNING)
        setActionEnabled(
            btnPause,
            ui.phase == AutomationEngine.Phase.RUNNING ||
                ui.phase == AutomationEngine.Phase.WAITING_TARGET ||
                ui.trialRunning
        )
        setActionEnabled(
            btnStop,
            ui.phase != AutomationEngine.Phase.IDLE || ui.sentCount > 0 || ui.roundActive || ui.trialRunning
        )
        setActionEnabled(
            btnTrial,
            !ui.roundActive &&
                !ui.trialRunning &&
                (ui.phase == AutomationEngine.Phase.IDLE || ui.phase == AutomationEngine.Phase.PAUSED)
        )
        setActionEnabled(btnDump, !ui.roundActive)
        setActionEnabled(btnCalibrate, !ui.roundActive)
        updateSingleShotUi(ui)
        applyTouchability(!ui.roundActive)
        if (expanded && !tickerRunning) startTicker()
        if (!expanded) stopTicker()
    }

    fun onConfigurationChanged() {
        if (!visible) return
        val p = params ?: return
        val screen = ScreenUtils.realSize(service)
        panelWidth = dp(300f).coerceAtMost(screen.x - dp(20f))
        if (expanded) {
            p.width = panelWidth
        } else {
            p.width = dotSize
            p.height = dotSize
        }
        p.x = if (side == Side.RIGHT) screen.x - p.width - edgeMargin else edgeMargin
        p.y = p.y.coerceIn(edgeMargin, (screen.y - p.height - edgeMargin).coerceAtLeast(edgeMargin))
        safeUpdateLayout(p)
        if (expanded) root?.post { clampPanel() }
    }

    private fun build() {
        panelWidth = dp(300f).coerceAtMost(ScreenUtils.realSize(service).x - dp(20f))
        val frame = FrameLayout(service)
        root = frame

        val bubble = buildDot()
        dot = bubble
        frame.addView(
            bubble,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val panelView = buildPanel()
        panel = panelView
        panelView.visibility = View.GONE
        frame.addView(
            panelView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        val screen = ScreenUtils.realSize(service)
        val layoutParams = baseParams().apply {
            width = dotSize
            height = dotSize
            x = screen.x - dotSize - edgeMargin
            y = (screen.y * 0.46f).roundToInt()
            gravity = Gravity.TOP or Gravity.START
        }
        params = layoutParams
        windowManager.addView(frame, layoutParams)
        bubble.post { clampBubble() }
        refresh()
    }

    private fun buildDot(): TextView {
        return TextView(service).apply {
            text = "评"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            background = circleDrawable(ContextCompat.getColor(service, R.color.accent))
            elevation = dp(8f).toFloat()
            contentDescription = "打开自动评论悬浮窗"
            isClickable = true
            isFocusable = true
            installDrag(this, onTap = { expandPanel() })
        }
    }

    private fun buildPanel(): LinearLayout {
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
            background = panelDrawable()
            elevation = dp(10f).toFloat()
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(service).apply {
            text = "抖屏助手"
            setTextColor(ContextCompat.getColor(service, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            contentDescription = "拖动面板"
            installDrag(this, onTap = null)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(30f), 1f))
        val collapse = TextView(service).apply {
            text = "收起"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(service, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8f), 0, dp(4f), 0)
            isClickable = true
            setOnClickListener { collapsePanel() }
        }
        header.addView(collapse, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)))
        container.addView(header)

        val statusRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bullet = TextView(service).apply {
            text = "●"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(service, R.color.amber))
        }
        statusDot = bullet
        statusRow.addView(bullet, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        val status = TextView(service).apply {
            text = "未开始"
            setTextColor(ContextCompat.getColor(service, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        }
        statusText = status
        statusRow.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(statusRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })

        val statsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val count = TextView(service).apply {
            text = "已发送 0 条"
            setTextColor(ContextCompat.getColor(service, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        countText = count
        val countdown = TextView(service).apply {
            text = "下一轮：--"
            setTextColor(ContextCompat.getColor(service, R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
        }
        countdownText = countdown
        statsRow.addView(count, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(countdown, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(statsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5f) })

        val singleResult = TextView(service).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        singleShotResultView = singleResult
        container.addView(singleResult, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })

        val actionRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val start = actionButton("开始", R.drawable.bg_button_success, Color.parseColor("#08130D"))
        val pause = actionButton("暂停", R.drawable.bg_button_warning, Color.parseColor("#160F02"))
        val stop = actionButton("停止", R.drawable.bg_button_danger, Color.parseColor("#180707"))
        btnStart = start
        btnPause = pause
        btnStop = stop
        start.setOnClickListener {
            engine.start()
            toast("已开始；若当前不在抖音，切回后会自动开始")
        }
        pause.setOnClickListener {
            engine.pause()
            toast("已暂停，计数保留")
        }
        stop.setOnClickListener {
            engine.stop()
            toast("已停止，计数已清除")
        }
        actionRow.addView(start, rowButtonParams(0f))
        actionRow.addView(pause, rowButtonParams(8f))
        actionRow.addView(stop, rowButtonParams(8f))
        container.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42f)
        ).apply { topMargin = dp(12f) })

        val toolsRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val trial = actionButton("试一轮", R.drawable.bg_button_ghost, ContextCompat.getColor(service, R.color.text))
        val singleShot = actionButton(
            service.getString(R.string.action_single_shot),
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(service, R.color.text)
        )
        val dump = actionButton("抓节点", R.drawable.bg_button_ghost, ContextCompat.getColor(service, R.color.text))
        btnTrial = trial
        btnSingleShot = singleShot
        btnDump = dump
        singleShot.contentDescription = "补发一次，长按修改补发内容"
        trial.setOnClickListener {
            val state = engine.snapshot()
            if (state.roundActive || state.trialRunning || state.phase == AutomationEngine.Phase.RUNNING) {
                toast("请先暂停，再手动试跑一轮")
            } else {
                engine.trialOnce()
            }
        }
        singleShot.setOnClickListener {
            engine.singleShotOnce()
        }
        singleShot.setOnLongClickListener {
            showSingleShotEditor()
            true
        }
        dump.setOnClickListener {
            NodeDump.captureAndShare(service)
        }
        toolsRow.addView(trial, rowButtonParams(0f))
        toolsRow.addView(singleShot, rowButtonParams(8f))
        toolsRow.addView(dump, rowButtonParams(8f))
        container.addView(toolsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44f)
        ).apply { topMargin = dp(8f) })

        val bottomRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val calibrate = actionButton(
            "校准坐标",
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(service, R.color.cyan)
        )
        val hide = actionButton(
            "隐藏",
            R.drawable.bg_button_ghost,
            ContextCompat.getColor(service, R.color.text_dim)
        )
        btnCalibrate = calibrate
        calibrate.setOnClickListener {
            val state = engine.snapshot()
            if (state.roundActive) {
                toast("正在执行一轮，请稍后再校准")
            } else {
                service.startCalibration()
            }
        }
        hide.setOnClickListener {
            hide(showToast = true)
        }
        bottomRow.addView(calibrate, rowButtonParams(0f))
        bottomRow.addView(hide, rowButtonParams(8f))
        container.addView(bottomRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42f)
        ).apply { topMargin = dp(8f) })

        val note = TextView(service).apply {
            text = "只读取目标包名窗口 · 暂停/停止也可在通知栏操作"
            setTextColor(ContextCompat.getColor(service, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
        }
        container.addView(note, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })

        return container
    }

    private fun expandPanel() {
        if (!visible || expanded) return
        expanded = true
        dot?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        val p = params ?: return
        val screen = ScreenUtils.realSize(service)
        p.width = panelWidth
        p.height = WindowManager.LayoutParams.WRAP_CONTENT
        p.x = if (side == Side.RIGHT) screen.x - panelWidth - edgeMargin else edgeMargin
        p.y = p.y.coerceAtLeast(edgeMargin)
        safeUpdateLayout(p)
        root?.post {
            clampPanel()
            refresh()
        }
        startTicker()
    }

    private fun collapsePanel() {
        if (!expanded) return
        collapseInternal()
        stopTicker()
        refresh()
    }

    private fun collapseInternal() {
        expanded = false
        panel?.visibility = View.GONE
        dot?.visibility = View.VISIBLE
        val p = params ?: return
        val screen = ScreenUtils.realSize(service)
        p.width = dotSize
        p.height = dotSize
        p.x = if (side == Side.RIGHT) screen.x - dotSize - edgeMargin else edgeMargin
        p.y = p.y.coerceIn(edgeMargin, (screen.y - dotSize - edgeMargin).coerceAtLeast(edgeMargin))
        safeUpdateLayout(p)
    }

    private fun clampBubble() {
        val p = params ?: return
        val screen = ScreenUtils.realSize(service)
        p.y = p.y.coerceIn(edgeMargin, (screen.y - dotSize - edgeMargin).coerceAtLeast(edgeMargin))
        safeUpdateLayout(p)
    }

    private fun clampPanel() {
        val p = params ?: return
        val view = root ?: return
        val screen = ScreenUtils.realSize(service)
        val height = view.height.takeIf { it > 0 } ?: (screen.y / 2)
        p.y = p.y.coerceIn(edgeMargin, (screen.y - height - edgeMargin).coerceAtLeast(edgeMargin))
        safeUpdateLayout(p)
    }

    private fun installDrag(view: View, onTap: (() -> Unit)?) {
        val touchSlop = android.view.ViewConfiguration.get(service).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        val screen = ScreenUtils.realSize(service)
                        val currentHeight = root?.height?.takeIf { it > 0 } ?: p.height
                        val maxX = (screen.x - p.width).coerceAtLeast(0)
                        val maxY = (screen.y - currentHeight).coerceAtLeast(0)
                        p.x = (startX + dx.roundToInt()).coerceIn(0, maxX)
                        p.y = (startY + dy.roundToInt()).coerceIn(0, maxY)
                        safeUpdateLayout(p)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        snapToEdge()
                    } else {
                        view.performClick()
                        onTap?.invoke()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val p = params ?: return
        val screen = ScreenUtils.realSize(service)
        val center = p.x + p.width / 2
        side = if (center < screen.x / 2) Side.LEFT else Side.RIGHT
        p.x = if (side == Side.RIGHT) screen.x - p.width - edgeMargin else edgeMargin
        val viewHeight = root?.height?.takeIf { it > 0 } ?: p.height
        p.y = p.y.coerceIn(edgeMargin, (screen.y - viewHeight - edgeMargin).coerceAtLeast(edgeMargin))
        safeUpdateLayout(p)
    }

    private fun updateDotColor(ui: AutomationEngine.UiState) {
        val color = when {
            ui.trialRunning -> ContextCompat.getColor(service, R.color.cyan)
            ui.phase == AutomationEngine.Phase.RUNNING -> ContextCompat.getColor(service, R.color.green)
            ui.phase == AutomationEngine.Phase.WAITING_TARGET ||
                ui.phase == AutomationEngine.Phase.PAUSED -> ContextCompat.getColor(service, R.color.amber)
            else -> ContextCompat.getColor(service, R.color.accent)
        }
        dot?.background = circleDrawable(color)
    }

    private fun statusColor(ui: AutomationEngine.UiState): Int {
        return when {
            ui.trialRunning -> ContextCompat.getColor(service, R.color.cyan)
            ui.phase == AutomationEngine.Phase.RUNNING -> ContextCompat.getColor(service, R.color.green)
            ui.phase == AutomationEngine.Phase.WAITING_TARGET ||
                ui.phase == AutomationEngine.Phase.PAUSED -> ContextCompat.getColor(service, R.color.amber)
            else -> ContextCompat.getColor(service, R.color.text_dim)
        }
    }

    private fun updateSingleShotUi(ui: AutomationEngine.UiState) {
        val button = btnSingleShot ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val coolingDown = now < ui.singleShotCooldownUntilElapsed
        setActionEnabled(button, !ui.singleShotRunning && !coolingDown)

        val banner = singleShotResultView ?: return
        val result = ui.singleShotResult
        val resultAge = if (ui.singleShotResultAtElapsed > 0L) now - ui.singleShotResultAtElapsed else Long.MAX_VALUE
        val shouldShow = result != null && resultAge in 0L..5000L
        val wasShown = banner.visibility == View.VISIBLE
        if (shouldShow && result != null) {
            val color = singleShotResultColor(result)
            banner.visibility = View.VISIBLE
            banner.text = singleShotResultText(result)
            banner.setTextColor(color)
            banner.background = singleShotBannerDrawable(color)
        } else {
            banner.visibility = View.GONE
        }
        if (shouldShow != wasShown && expanded) {
            root?.post { clampPanel() }
        }
    }

    private fun singleShotResultText(result: AutomationEngine.CycleResult): String {
        return when {
            result.success -> service.getString(R.string.single_shot_result_sent)
            result.failureStage == AutomationEngine.CycleFailureStage.LOCATE_INPUT ->
                service.getString(R.string.single_shot_result_no_input)
            result.failureStage == AutomationEngine.CycleFailureStage.INPUT_TEXT ->
                service.getString(R.string.single_shot_result_text_failed)
            else -> service.getString(R.string.single_shot_result_button_disabled)
        }
    }

    private fun singleShotResultColor(result: AutomationEngine.CycleResult): Int {
        return when {
            result.success -> ContextCompat.getColor(service, R.color.green)
            result.failureStage == AutomationEngine.CycleFailureStage.LOCATE_INPUT ||
                result.failureStage == AutomationEngine.CycleFailureStage.INPUT_TEXT ->
                ContextCompat.getColor(service, R.color.amber)
            else -> ContextCompat.getColor(service, R.color.red)
        }
    }

    private fun singleShotBannerDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1f), Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    private fun showSingleShotEditor() {
        try {
            val current = AppConfig.load(service).singleShotText
            val input = EditText(service).apply {
                setText(current)
                setSelection(current.length)
                setSingleLine(true)
                maxLines = 1
                hint = service.getString(R.string.hint_single_shot)
                setTextColor(ContextCompat.getColor(service, R.color.text))
                setHintTextColor(ContextCompat.getColor(service, R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = ContextCompat.getDrawable(service, R.drawable.bg_input)?.mutate()
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            }
            val wrapper = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(8f), dp(20f), 0)
                addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50f)
                ))
            }
            val dialog = AlertDialog.Builder(service)
                .setTitle(service.getString(R.string.single_shot_editor_title))
                .setView(wrapper)
                .setPositiveButton("保存") { _, _ ->
                    val value = input.text?.toString()?.trim().orEmpty()
                    if (value.isEmpty()) {
                        toast(service.getString(R.string.single_shot_empty))
                    } else {
                        AppConfig.saveSingleShotText(service, value)
                        toast(service.getString(R.string.single_shot_saved))
                    }
                }
                .setNegativeButton("取消", null)
                .create()
            dialog.window?.apply {
                setType(overlayWindowType())
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            dialog.setOnShowListener {
                input.requestFocus()
                input.post {
                    try {
                        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    } catch (_: Throwable) {
                    }
                }
            }
            dialog.show()
        } catch (t: Throwable) {
            LogStore.add("补发", "打开补发内容编辑框失败：${t.javaClass.simpleName}: ${t.message}")
            toast("打开编辑框失败，请到 App 设置中修改补发内容")
        }
    }

    private fun updateCountdown() {
        val view = countdownText ?: return
        val ui = engine.snapshot()
        view.text = when {
            ui.roundActive -> "下一轮：执行中"
            ui.nextRoundAtElapsed > 0L -> {
                val remaining = ui.nextRoundAtElapsed - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0L) "下一轮：即将开始"
                else "下一轮：${ceil(remaining / 1000.0).toInt()} 秒"
            }
            else -> "下一轮：--"
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.post(ticker)
        updateCountdown()
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    private fun applyTouchability(enabled: Boolean) {
        if (interactive == enabled) return
        interactive = enabled
        val p = params ?: return
        p.flags = if (enabled) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        safeUpdateLayout(p)
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun baseParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun safeUpdateLayout(p: WindowManager.LayoutParams) {
        val view = root ?: return
        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: Throwable) {
        }
    }

    private fun destroyViews() {
        stopTicker()
        val view = root
        root = null
        dot = null
        panel = null
        params = null
        statusText = null
        statusDot = null
        countText = null
        countdownText = null
        btnStart = null
        btnPause = null
        btnStop = null
        btnTrial = null
        btnSingleShot = null
        singleShotResultView = null
        btnDump = null
        btnCalibrate = null
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
    }

    private fun actionButton(textValue: String, backgroundRes: Int, textColor: Int): TextView {
        return TextView(service).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            background = ContextCompat.getDrawable(service, backgroundRes)?.mutate()
            isClickable = true
            isFocusable = true
            minHeight = 0
            setPadding(dp(6f), 0, dp(6f), 0)
        }
    }

    private fun rowButtonParams(startMargin: Float): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(startMargin)
        }
    }

    private fun setActionEnabled(view: TextView?, enabled: Boolean) {
        if (view == null) return
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else 0.45f
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1f), Color.parseColor("#55FFFFFF"))
        }
    }

    private fun panelDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(Color.parseColor("#F2171920"))
            setStroke(dp(1f), Color.parseColor("#4A5360"))
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun toast(message: String) {
        try {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
