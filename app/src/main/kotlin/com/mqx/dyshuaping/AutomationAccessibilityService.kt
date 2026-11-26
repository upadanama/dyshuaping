package com.mqx.dyshuaping

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

class AutomationAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set
    }

    var engine: AutomationEngine? = null
        private set

    private var overlay: FloatingOverlay? = null
    private var calibrationInProgress = false
    private var calibrationBackgroundPackage: String? = null
    private var calibrationOverlayWasVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (engine != null) return
        val newEngine = AutomationEngine(this)
        newEngine.onChanged = {
            overlay?.refresh()
            ControlService.refresh()
        }
        engine = newEngine
        instance = this
        LogStore.add("服务", "无障碍服务已连接")
        toast("抖屏助手无障碍服务已连接")

        try {
            ControlService.start(this)
        } catch (t: Throwable) {
            LogStore.add("服务", "启动常驻通知失败：${t.javaClass.simpleName}: ${t.message}")
        }

        if (Settings.canDrawOverlays(this)) {
            ensureOverlay()
        } else {
            LogStore.add("服务", "未授予悬浮窗权限，等待在 App 内授权")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        val activePackage = NodeTools.activePackage(this)
        val reported = activePackage ?: eventPackage
        if (isInputMethodPackage(reported)) return

        if (reported == packageName) {
            val className = event.className?.toString().orEmpty()
            if (className == MainActivity::class.java.name || className == LogActivity::class.java.name) {
                engine?.onForegroundPackageChanged(packageName)
            }
            return
        }
        engine?.onForegroundPackageChanged(reported)
    }

    override fun onInterrupt() {
        LogStore.add("服务", "无障碍服务收到 onInterrupt")
        engine?.onInterrupt()
        toast("无障碍服务被中断，任务已暂停")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        LogStore.add("服务", "无障碍服务已断开")
        overlay?.destroy()
        overlay = null
        engine?.destroy()
        engine = null
        instance = null
        calibrationInProgress = false
        calibrationBackgroundPackage = null
        calibrationOverlayWasVisible = false
        mainHandler.removeCallbacksAndMessages(null)
        ControlService.stop(this)
        super.onDestroy()
    }

    fun ensureOverlay(): Boolean {
        val currentEngine = engine ?: return false
        if (!Settings.canDrawOverlays(this)) {
            LogStore.add("悬浮窗", "无悬浮窗权限")
            return false
        }
        var candidate = overlay
        if (candidate == null) {
            candidate = FloatingOverlay(this, currentEngine)
            overlay = candidate
        }
        return if (candidate.show()) {
            candidate.refresh()
            true
        } else {
            LogStore.add("悬浮窗", "悬浮窗创建失败")
            false
        }
    }

    fun hideOverlay() {
        overlay?.hide(showToast = true)
    }

    fun hasOverlay(): Boolean = overlay?.isShowing() == true

    fun startCalibration(): Boolean {
        if (calibrationInProgress) return false
        val target = AppConfig.load(this).targetPackage
        val active = NodeTools.activePackage(this)
        if (active == null || active != target) {
            toast("请先进入抖音直播间，再从悬浮窗点「校准坐标」")
            return false
        }

        calibrationInProgress = true
        calibrationBackgroundPackage = target
        engine?.pause()
        val wasOverlayVisible = overlay?.isShowing() == true
        calibrationOverlayWasVisible = wasOverlayVisible
        // Launch while the overlay is still visible: the visible overlay window is
        // what permits a background activity launch on Android 10+.
        val started = CalibrationActivity.launch(this)
        if (started) {
            // Keep the overlay visible until CalibrationActivity.onCreate calls
            // onCalibrationActivityOpened(); a visible overlay is the background
            // activity launch exemption we rely on here.
            toast("校准层已打开；手指点哪儿准星就去哪儿")
            mainHandler.postDelayed({
                if (calibrationInProgress && !CalibrationActivity.isRunning()) {
                    calibrationInProgress = false
                    calibrationBackgroundPackage = null
                    if (calibrationOverlayWasVisible) ensureOverlay()
                    calibrationOverlayWasVisible = false
                    LogStore.add("校准", "校准界面启动超时，已恢复悬浮窗")
                    toast("未能打开校准界面，请重试")
                }
            }, 2500L)
        } else {
            calibrationInProgress = false
            calibrationBackgroundPackage = null
            calibrationOverlayWasVisible = false
            toast("无法打开校准界面，请检查悬浮窗权限")
        }
        return started
    }

    fun onCalibrationActivityOpened() {
        if (!calibrationInProgress) return
        overlay?.hide(showToast = false)
    }

    fun onCalibrationScreenFinished() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!calibrationInProgress) return
        calibrationInProgress = false
        calibrationBackgroundPackage = null
        val restoreOverlay = calibrationOverlayWasVisible
        calibrationOverlayWasVisible = false
        if (restoreOverlay) {
            ensureOverlay()
        }
        LogStore.add("校准", "校准界面已关闭")
    }

    fun canTestCalibrationClick(): Boolean {
        if (!calibrationInProgress) return false
        val target = AppConfig.load(this).targetPackage
        return calibrationBackgroundPackage == target
    }

    /**
     * Manual test tap from the calibration screen. The calibration Activity makes
     * its own window non-touchable before calling this, so the injected gesture
     * can reach the live room underneath.
     */
    fun dispatchCalibrationTestClick(x: Int, y: Int): Boolean {
        if (!canTestCalibrationClick()) {
            toast("未确认抖音在后台，测试点击已取消")
            return false
        }
        val screen = ScreenUtils.realSize(this)
        val cx = x.coerceIn(0, screen.x - 1)
        val cy = y.coerceIn(0, screen.y - 1)
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo(
                (cx + 1).coerceAtMost(screen.x - 1).toFloat(),
                (cy + 1).coerceAtMost(screen.y - 1).toFloat()
            )
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        val accepted = try {
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            LogStore.add("校准", "测试点击异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
        LogStore.add("校准", "测试点击坐标=($cx,$cy) accepted=$accepted")
        return accepted
    }

    private fun isInputMethodPackage(packageName: String): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName } ||
                imm.inputMethodList.any { it.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
