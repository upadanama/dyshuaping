package com.mqx.dyshuaping

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.4 calibration screen: a completely transparent, immersive page whose only
 * job is to catch two taps and replay each of them into the app underneath.
 *
 * There is deliberately no scrim, crosshair, panel, button or text view. The
 * only view is an empty transparent FrameLayout that gives the window a content
 * root; all user guidance is delivered through Toast.
 */
class CalibrationActivity : Activity() {
    companion object {
        private const val TAP_COMMIT_DELAY_MS = 350L
        private const val DOUBLE_TAP_WINDOW_MS = 300L
        private const val REPLAY_DEBOUNCE_MS = 500L
        private const val TOUCH_RESTORE_DELAY_MS = 400L
        private const val DOUBLE_TAP_SLOP_DP = 48f

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

    private val uiHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 1
    private var screenHeight = 1
    private var inputPoint: android.graphics.Point? = null
    private var sendPoint: android.graphics.Point? = null

    private var pendingCommit: Runnable? = null
    private var lastTapUpAtElapsed = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAtElapsed = 0L
    private var lastForwardAtElapsed = 0L
    private var finishing = false

    private val restoreTouchable = Runnable {
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

        // The page must remain visually empty; the transparent root only supplies
        // the window's content surface. Touch handling happens in dispatchTouchEvent.
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) })
        applyImmersiveMode()

        val service = AutomationAccessibilityService.instance
        // The entry points (MainActivity and the floating calibration button)
        // pause the engine before launching; this page only needs to clear any
        // floating window that could intercept touches.
        service?.onCalibrationActivityOpened()

        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {
        }
        toast("校准：先点评论输入框，再点发送按钮；双击撤销，返回取消")
        LogStore.add("校准", "透明校准页已打开，屏幕=${screenWidth}x$screenHeight")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downAtElapsed = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val moved = dx * dx + dy * dy
                val elapsed = SystemClock.elapsedRealtime() - downAtElapsed
                if (moved <= dp(24f) * dp(24f) && elapsed <= 1500L) {
                    onTransparentTap(event.rawX, event.rawY)
                }
            }
            MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    private fun onTransparentTap(rawX: Float, rawY: Float) {
        if (finishing) return
        val now = SystemClock.elapsedRealtime()
        // A replayed gesture must never be captured back by the transparent layer
        // as a new calibration tap. This 500ms guard swallows both the physical
        // bounce and any late synthetic event; the window itself is also marked
        // non-touchable for the first 400ms.
        if (lastForwardAtElapsed > 0L && now - lastForwardAtElapsed < REPLAY_DEBOUNCE_MS) {
            LogStore.add("校准", "500ms 防抖忽略触摸 (${rawX.toInt()},${rawY.toInt()})")
            return
        }
        val x = rawX.roundToInt().coerceIn(0, screenWidth - 1)
        val y = rawY.roundToInt().coerceIn(0, screenHeight - 1)
        val closeToLast = abs(rawX - lastTapX) <= dp(DOUBLE_TAP_SLOP_DP) &&
            abs(rawY - lastTapY) <= dp(DOUBLE_TAP_SLOP_DP)
        val isDoubleTap = now - lastTapUpAtElapsed in 1L..DOUBLE_TAP_WINDOW_MS &&
            closeToLast &&
            pendingCommit != null

        lastTapUpAtElapsed = now
        lastTapX = rawX
        lastTapY = rawY

        if (isDoubleTap) {
            pendingCommit?.let { uiHandler.removeCallbacks(it) }
            pendingCommit = null
            // Avoid a triple tap being interpreted as another double tap.
            lastTapUpAtElapsed = 0L
            undoLastPoint()
            return
        }

        lateinit var commit: Runnable
        commit = Runnable {
            if (pendingCommit === commit) pendingCommit = null
            commitPoint(x, y)
        }
        pendingCommit = commit
        uiHandler.postDelayed(commit, TAP_COMMIT_DELAY_MS)
    }

    private fun commitPoint(x: Int, y: Int) {
        if (finishing) return
        if (inputPoint == null) {
            inputPoint = android.graphics.Point(x, y)
            LogStore.add("校准", "记录评论输入框坐标=($x,$y)")
            forwardTap(x, y)
            toast("已记录评论输入框，请点击发送按钮")
            return
        }
        if (sendPoint == null) {
            sendPoint = android.graphics.Point(x, y)
            val input = inputPoint ?: return
            val inputX = normalize(input.x, screenWidth)
            val inputY = normalize(input.y, screenHeight)
            val sendX = normalize(x, screenWidth)
            val sendY = normalize(y, screenHeight)
            AppConfig.saveCalibration(this, inputX, inputY, sendX, sendY)
            LogStore.add(
                "校准",
                "校准已保存：输入框=(${format(inputX)},${format(inputY)}) " +
                    "发送按钮=(${format(sendX)},${format(sendY)})，屏幕=${screenWidth}x$screenHeight"
            )
            forwardTap(x, y)
            toast(
                String.format(
                    Locale.US,
                    "校准已保存：输入 (%.4f, %.4f)，发送 (%.4f, %.4f)",
                    inputX,
                    inputY,
                    sendX,
                    sendY
                )
            )
            finishing = true
            uiHandler.postDelayed({ if (!isFinishing) finish() }, 80L)
        }
    }

    private fun undoLastPoint() {
        when {
            sendPoint != null -> {
                sendPoint = null
                toast("已撤销发送按钮点，请重新点击")
                LogStore.add("校准", "双击撤销发送按钮点")
            }
            inputPoint != null -> {
                inputPoint = null
                toast("已撤销评论输入框点，请重新点击")
                LogStore.add("校准", "双击撤销评论输入框点")
            }
            else -> {
                toast("没有可撤销的点")
            }
        }
    }

    /**
     * Replay the same physical tap into the app below. The transparent window is
     * temporarily made non-touchable so the injected click is delivered below.
     */
    private fun forwardTap(x: Int, y: Int) {
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            toast("请先开启无障碍服务")
            LogStore.add("校准", "无障碍服务未开启，只记录坐标 ($x,$y)，不回放")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // The 500ms debounce is enforced when touches are received, before a
        // coordinate is recorded. Once a point is recorded here, it must replay.
        lastForwardAtElapsed = now
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } catch (_: Throwable) {
        }
        // Let the window-manager flag land before injecting the synthetic touch,
        // so the gesture goes to the app below rather than this transparent layer.
        uiHandler.postDelayed({
            val accepted = service.dispatchCalibrationTap(x, y)
            LogStore.add("校准", "透明层回放点击 ($x,$y) accepted=$accepted")
            uiHandler.removeCallbacks(restoreTouchable)
            uiHandler.postDelayed(restoreTouchable, TOUCH_RESTORE_DELAY_MS)
        }, 24L)
    }

    private fun normalize(value: Int, fullSize: Int): Float {
        if (fullSize <= 0) return 0f
        return (value.toFloat() / fullSize.toFloat()).coerceIn(0f, 1f)
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.4f", value)

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!finishing) {
            finishing = true
            uiHandler.removeCallbacksAndMessages(null)
            toast("已取消校准，未保存")
            LogStore.add("校准", "返回键取消校准，未保存")
        }
        super.onBackPressed()
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setFormat(PixelFormat.TRANSLUCENT)
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

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
