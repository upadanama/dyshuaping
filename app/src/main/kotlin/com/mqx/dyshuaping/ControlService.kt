package com.mqx.dyshuaping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ControlService : Service() {
    companion object {
        private const val CHANNEL_ID = "dyshuaping_runtime"
        private const val NOTIFICATION_ID = 2207
        private const val ACTION_TOGGLE = "com.mqx.dyshuaping.action.TOGGLE"
        private const val ACTION_STOP = "com.mqx.dyshuaping.action.STOP"
        private const val ACTION_REFRESH = "com.mqx.dyshuaping.action.REFRESH"

        @Volatile
        private var instance: ControlService? = null

        fun start(context: Context) {
            val existing = instance
            if (existing != null) {
                existing.updateNotification()
                return
            }
            val intent = Intent(context.applicationContext, ControlService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (t: Throwable) {
                LogStore.add("通知服务", "启动前台服务失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, ControlService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun refresh() {
            instance?.updateNotification()
        }
    }

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val engine = AutomationAccessibilityService.instance?.engine
                if (engine == null) {
                    LogStore.add("通知服务", "控制操作失败：无障碍服务尚未连接")
                } else {
                    engine.toggle()
                }
            }

            ACTION_STOP -> {
                val engine = AutomationAccessibilityService.instance?.engine
                if (engine == null) {
                    LogStore.add("通知服务", "停止操作失败：无障碍服务尚未连接")
                } else {
                    engine.stop()
                }
            }

            ACTION_REFRESH -> Unit
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        foregroundStarted = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            LogStore.add("通知服务", "常驻前台服务已启动")
        } catch (t: Throwable) {
            LogStore.add("通知服务", "startForeground 失败：${t.javaClass.simpleName}: ${t.message}")
            foregroundStarted = false
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            LogStore.add("通知服务", "更新通知失败：${t.javaClass.simpleName}")
        }
    }

    private fun buildNotification(): Notification {
        val engine = AutomationAccessibilityService.instance?.engine
        val state = engine?.snapshot()
        val phaseLabel = when {
            engine == null -> "无障碍未连接"
            state == null -> "待命"
            state.trialRunning -> "试跑中"
            state.phase == AutomationEngine.Phase.RUNNING -> "运行中"
            state.phase == AutomationEngine.Phase.WAITING_TARGET -> "已离开抖音"
            state.phase == AutomationEngine.Phase.PAUSED -> "已暂停"
            else -> "待命"
        }
        val count = state?.sentCount ?: 0
        val summary = if (engine == null) {
            "无障碍未连接，请打开 App 完成权限设置"
        } else {
            "$phaseLabel · 已发送 $count 条"
        }
        val detail = state?.statusMessage ?: summary
        val toggleLabel = when {
            state == null -> "开始"
            state.trialRunning || state.phase == AutomationEngine.Phase.RUNNING -> "暂停"
            state.phase == AutomationEngine.Phase.PAUSED ||
                state.phase == AutomationEngine.Phase.WAITING_TARGET -> "继续"
            else -> "开始"
        }
        val toggleIcon = if (toggleLabel == "暂停") {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.accent))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(
                Notification.Action.Builder(
                    toggleIcon,
                    toggleLabel,
                    servicePendingIntent(ACTION_TOGGLE, 101)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    servicePendingIntent(ACTION_STOP, 102)
                ).build()
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ControlService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
