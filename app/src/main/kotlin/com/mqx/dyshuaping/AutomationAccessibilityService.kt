package com.mqx.dyshuaping

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
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
        try {
            CalibrationOverlay.cancelActive()
        } catch (_: Throwable) {
        }
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

    fun startCalibration(onFinished: (() -> Unit)? = null): Boolean {
        if (calibrationInProgress) return false
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            return false
        }
        calibrationInProgress = true
        engine?.pause()
        val wasOverlayVisible = overlay?.isShowing() == true
        if (wasOverlayVisible) overlay?.hide(showToast = false)
        val started = CalibrationOverlay.start(this) { saved ->
            calibrationInProgress = false
            if (wasOverlayVisible) ensureOverlay()
            if (saved) {
                toast("校准已保存，可回到悬浮窗继续")
            } else {
                toast("已取消校准")
            }
            try {
                onFinished?.invoke()
            } catch (_: Throwable) {
            }
        }
        if (!started) {
            calibrationInProgress = false
            if (wasOverlayVisible) ensureOverlay()
            toast("无法打开校准层，请检查悬浮窗权限")
        }
        return started
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
