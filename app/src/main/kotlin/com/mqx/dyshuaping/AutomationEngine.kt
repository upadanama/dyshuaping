package com.mqx.dyshuaping

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale
import java.util.Random
import kotlin.math.ceil

class AutomationEngine(private val service: AutomationAccessibilityService) {
    enum class Phase {
        IDLE,
        RUNNING,
        WAITING_TARGET,
        PAUSED
    }

    data class UiState(
        val phase: Phase,
        val sentCount: Int,
        val nextRoundAtElapsed: Long,
        val roundActive: Boolean,
        val trialRunning: Boolean,
        val statusMessage: String,
        val singleShotRunning: Boolean,
        val singleShotCooldownUntilElapsed: Long,
        val singleShotResult: CycleResult?,
        val singleShotResultAtElapsed: Long,
        val lastRoundDurationMs: Long,
        val lastRoundPath: String
    )

    enum class CycleFailureStage {
        NONE,
        LOCATE_INPUT,
        INPUT_TEXT,
        CLICK_SEND,
        VERIFY
    }

    data class CycleTimings(
        val locateMs: Long,
        val inputMs: Long,
        val sendMs: Long,
        val verifyMs: Long,
        val idleMs: Long,
        val totalMs: Long
    )

    data class CycleResult(
        val success: Boolean,
        val failureStage: CycleFailureStage,
        val error: String?,
        val banSuspected: Boolean,
        val timings: CycleTimings,
        val content: String,
        val path: String
    )

    private enum class CycleOrigin {
        MAIN_LOOP,
        TRIAL,
        SINGLE_SHOT
    }

    private enum class InputStrategy {
        SET_TEXT,
        PASTE
    }

    private data class Outgoing(
        val settings: Settings,
        val baseMessage: String,
        val suffix: String,
        val fullText: String,
        val roundNumber: Int
    )

    var onChanged: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scheduleToken = Any()
    private val random = Random()

    private var destroyed = false
    private var phase = Phase.IDLE
    private var statusMessage = "未开始"
    private var sentCount = 0
    private var failureCount = 0
    private var nextRoundAtElapsed = 0L
    private var operationEpoch = 0
    private var roundActive = false
    private var trialRunning = false
    private var cycleOrigin = CycleOrigin.MAIN_LOOP
    private var roundSequence = 0
    private var activeOutgoing: Outgoing? = null
    private var targetForRound = ""

    private var trialRestorePhase = Phase.IDLE
    private var trialRestoreStatus = "未开始"

    private var singleShotRunning = false
    private var singleShotCooldownUntilElapsed = 0L
    private var lastSingleShotResult: CycleResult? = null
    private var lastSingleShotResultAtElapsed = 0L
    private var singleShotRestorePhase = Phase.PAUSED
    private var singleShotRestoreStatus = ""

    private var currentCycleStage = CycleFailureStage.NONE
    private var currentCycleBanSuspected = false
    private var cycleStartedAtElapsed = 0L
    private var locateFinishedAtElapsed = 0L
    private var inputStartedAtElapsed = 0L
    private var inputFinishedAtElapsed = 0L
    private var sendStartedAtElapsed = 0L
    private var sendFinishedAtElapsed = 0L
    private var verifyStartedAtElapsed = 0L
    private var cycleContentSnapshot = ""
    private var currentCycleIdleMs = 0L
    private var pendingIdleMs = 0L
    private var currentInputMode = ""
    private var lastRoundDurationMs = 0L
    private var lastRoundPath = ""

    private var sendDisabledGrace = 0
    private var sendMissingGrace = 0
    private var sendRootRetry = 0
    private var sendRefillCount = 0

    fun snapshot(): UiState = UiState(
        phase = phase,
        sentCount = sentCount,
        nextRoundAtElapsed = if (phase == Phase.RUNNING && !roundActive) nextRoundAtElapsed else 0L,
        roundActive = roundActive,
        trialRunning = trialRunning,
        statusMessage = when {
            trialRunning -> "试跑中 · $statusMessage"
            else -> statusMessage
        },
        singleShotRunning = singleShotRunning,
        singleShotCooldownUntilElapsed = singleShotCooldownUntilElapsed,
        singleShotResult = lastSingleShotResult,
        singleShotResultAtElapsed = lastSingleShotResultAtElapsed,
        lastRoundDurationMs = lastRoundDurationMs,
        lastRoundPath = lastRoundPath
    )

    fun start() {
        if (destroyed) return
        if (trialRunning || roundActive || singleShotRunning) {
            toast("正在执行，请稍候")
            return
        }
        if (phase == Phase.RUNNING) {
            toast("已在运行中")
            return
        }
        val settings = AppConfig.load(service)
        if (settings.messages.isEmpty()) {
            phase = Phase.PAUSED
            statusMessage = "消息列表为空，无法开始"
            LogStore.add("状态", "启动失败：消息列表为空")
            toast("请先在设置中填写至少一条评论")
            notifyChanged()
            return
        }
        if (settings.targetPackage.isBlank()) {
            phase = Phase.PAUSED
            statusMessage = "目标包名为空，无法开始"
            LogStore.add("状态", "启动失败：目标包名为空")
            toast("请先填写目标应用包名")
            notifyChanged()
            return
        }

        val resuming = phase != Phase.IDLE
        cancelPendingCallbacks()
        trialRunning = false
        cycleOrigin = CycleOrigin.MAIN_LOOP
        roundActive = false
        activeOutgoing = null
        failureCount = 0
        phase = Phase.RUNNING
        statusMessage = if (resuming) "继续运行，准备开始" else "准备开始"
        LogStore.add("状态", if (resuming) "继续运行（计数保留）" else "开始运行")
        notifyChanged()

        val active = NodeTools.activePackage(service)
        if (active != null && active != settings.targetPackage) {
            enterWaitingTarget("当前不是抖音：切回抖音后自动开始", toastIt = true)
            return
        }
        scheduleInitialRound(resuming)
    }

    fun pause() {
        if (destroyed) return
        if (phase == Phase.IDLE && !roundActive && !trialRunning && !singleShotRunning) return
        val wasTrial = trialRunning
        val wasSingleShot = singleShotRunning
        cancelPendingCallbacks()
        pendingIdleMs = 0L
        roundActive = false
        nextRoundAtElapsed = 0L
        activeOutgoing = null
        trialRunning = false
        singleShotRunning = false
        cycleOrigin = CycleOrigin.MAIN_LOOP
        phase = Phase.PAUSED
        statusMessage = when {
            wasTrial -> "已暂停（试跑已取消），计数保留"
            wasSingleShot -> "已暂停（补发已取消），计数保留"
            else -> "已暂停，计数保留"
        }
        LogStore.add("状态", "手动暂停，已发送 $sentCount 条")
        notifyChanged()
    }

    fun stop() {
        if (destroyed) return
        cancelPendingCallbacks()
        pendingIdleMs = 0L
        phase = Phase.IDLE
        roundActive = false
        trialRunning = false
        singleShotRunning = false
        cycleOrigin = CycleOrigin.MAIN_LOOP
        nextRoundAtElapsed = 0L
        sentCount = 0
        failureCount = 0
        activeOutgoing = null
        MessageProvider.reset()
        statusMessage = "已停止，计数已清零"
        LogStore.add("状态", "已停止，发送计数清零")
        notifyChanged()
    }

    fun toggle() {
        if (trialRunning || phase == Phase.RUNNING) {
            pause()
        } else {
            start()
        }
    }

    fun trialOnce() {
        if (destroyed) return
        if (roundActive || trialRunning || singleShotRunning) {
            toast("正在执行，请稍候")
            return
        }
        if (phase == Phase.RUNNING) {
            toast("请先暂停，再手动试跑一轮")
            return
        }
        val settings = AppConfig.load(service)
        if (settings.messages.isEmpty()) {
            toast("请先填写至少一条评论")
            return
        }
        val active = NodeTools.activePackage(service)
        if (active == null || active != settings.targetPackage) {
            LogStore.add("试跑", "无法试跑：当前前台不是目标应用。前台=${active ?: "<null>"} 目标=${settings.targetPackage}")
            toast("请先进入抖音直播间，再点试一轮")
            return
        }

        cancelPendingCallbacks()
        pendingIdleMs = 0L
        trialRestorePhase = phase
        trialRestoreStatus = statusMessage
        trialRunning = true
        cycleOrigin = CycleOrigin.TRIAL
        roundActive = false
        phase = if (phase == Phase.WAITING_TARGET) Phase.PAUSED else phase
        statusMessage = "准备手动试跑"
        LogStore.add("试跑", "开始手动试跑一轮")
        notifyChanged()
        beginRound(isTrial = true)
    }

    /**
     * 补发一次：one shared runOneCycle, fixed text, no suffix, no loop resumption.
     */
    fun singleShotOnce() {
        if (destroyed) return
        if (singleShotRunning) return
        val now = SystemClock.elapsedRealtime()
        if (now < singleShotCooldownUntilElapsed) return
        if (roundActive || trialRunning || phase == Phase.RUNNING || phase == Phase.WAITING_TARGET) {
            toast(service.getString(R.string.single_shot_need_pause))
            return
        }
        if (phase != Phase.PAUSED && phase != Phase.IDLE) {
            toast(service.getString(R.string.single_shot_need_pause))
            return
        }
        val settings = AppConfig.load(service)
        if (settings.targetPackage.isBlank()) {
            toast("请先在设置中填写目标包名")
            return
        }
        val active = NodeTools.activePackage(service)
        if (active == null || active != settings.targetPackage) {
            LogStore.add("补发", "未开始：当前前台不是目标应用。前台=${active ?: "<null>"} 目标=${settings.targetPackage}")
            toast(service.getString(R.string.single_shot_need_target))
            return
        }

        val content = settings.singleShotText.ifBlank { AppConfig.DEFAULT_SINGLE_SHOT_TEXT }
        cancelPendingCallbacks()
        pendingIdleMs = 0L
        singleShotRestorePhase = phase
        singleShotRestoreStatus = statusMessage
        singleShotRunning = true
        cycleOrigin = CycleOrigin.SINGLE_SHOT
        singleShotCooldownUntilElapsed = now + 3000L
        lastSingleShotResult = null
        lastSingleShotResultAtElapsed = 0L
        statusMessage = "补发中：准备开始"
        LogStore.add("补发", "开始补发一次，内容=\"$content\"")
        notifyChanged()
        runOneCycle(
            baseText = content,
            appendSuffix = false,
            origin = CycleOrigin.SINGLE_SHOT,
            settings = settings
        )
    }

    fun onForegroundPackageChanged(packageName: String) {
        if (destroyed) return
        val target = AppConfig.load(service).targetPackage
        if (packageName == target) {
            if (phase == Phase.WAITING_TARGET) {
                failureCount = 0
                phase = Phase.RUNNING
                statusMessage = "已回到抖音，准备继续"
                LogStore.add("状态", "目标应用回到前台，自动继续")
                toast("已回到抖音，自动继续")
                notifyChanged()
                scheduleInitialRound(fromResume = true)
            }
            return
        }
        if (singleShotRunning) {
            handleWrongForeground(packageName)
            return
        }
        if (phase == Phase.RUNNING) {
            handleWrongForeground(packageName)
        }
    }

    fun onInterrupt() {
        if (destroyed) return
        if (phase == Phase.IDLE && !roundActive && !trialRunning && !singleShotRunning) return
        cancelPendingCallbacks()
        roundActive = false
        trialRunning = false
        singleShotRunning = false
        cycleOrigin = CycleOrigin.MAIN_LOOP
        nextRoundAtElapsed = 0L
        activeOutgoing = null
        phase = Phase.PAUSED
        statusMessage = "无障碍服务被系统中断，已暂停"
        LogStore.add("状态", "onInterrupt：任务已暂停")
        notifyChanged()
    }

    fun destroy() {
        destroyed = true
        cancelPendingCallbacks()
        singleShotRunning = false
        activeOutgoing = null
        onChanged = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleInitialRound(fromResume: Boolean) {
        if (phase != Phase.RUNNING || trialRunning || destroyed) return
        cancelPendingCallbacks()
        pendingIdleMs = 0L
        nextRoundAtElapsed = 0L
        statusMessage = if (fromResume) "回到抖音，准备继续" else "准备开始"
        notifyChanged()
        beginRound(isTrial = false)
    }

    private fun scheduleNextRound() {
        if (phase != Phase.RUNNING || trialRunning || destroyed) return
        val settings = AppConfig.load(service)
        if (settings.messages.isEmpty()) {
            cancelPendingCallbacks()
            phase = Phase.PAUSED
            statusMessage = "消息列表为空，已自动暂停"
            LogStore.add("状态", "消息列表被清空，已自动暂停")
            notifyChanged()
            return
        }
        cancelPendingCallbacks()
        val intervalMinMs = settings.intervalMinTenths * 100L
        val intervalMaxMs = settings.intervalMaxTenths * 100L
        val intervalMs = randomLongBetween(intervalMinMs, intervalMaxMs)
        val jitterLimitMs = if (settings.jitterEnabled) settings.jitterMaxTenths * 100L else 0L
        val jitterMs = if (jitterLimitMs > 0L) randomLongBetween(0L, jitterLimitMs) else 0L
        val delay = intervalMs + jitterMs
        nextRoundAtElapsed = SystemClock.elapsedRealtime() + delay
        statusMessage = "等待下一轮"
        val jitterText = if (settings.jitterEnabled) {
            "抖动 +${formatSeconds(jitterMs)}秒（上限 ${formatSeconds(settings.jitterMaxTenths * 100L)}秒）"
        } else {
            "抖动已关闭"
        }
        LogStore.add(
            "节奏",
            "下一轮等待 ${formatSeconds(delay)} 秒（区间 ${formatSeconds(intervalMinMs)}~${formatSeconds(intervalMaxMs)}秒，$jitterText）"
        )
        pendingIdleMs = delay
        notifyChanged()
        // 0.0 interval must not acquire a minimum delay: schedule with later(0).
        later(if (delay <= 0L) 0L else delay) { beginRound(isTrial = false) }
    }

    private fun beginRound(isTrial: Boolean) {
        if (destroyed) return
        if (isTrial) {
            if (!trialRunning) return
        } else {
            if (phase != Phase.RUNNING || trialRunning || roundActive) return
        }
        cancelPendingCallbacks()
        roundActive = true
        cycleOrigin = if (isTrial) CycleOrigin.TRIAL else CycleOrigin.MAIN_LOOP
        sendDisabledGrace = 0
        sendMissingGrace = 0
        sendRootRetry = 0
        sendRefillCount = 0
        activeOutgoing = null

        val settings = AppConfig.load(service)
        targetForRound = settings.targetPackage
        if (settings.messages.isEmpty()) {
            failRound("消息列表为空")
            return
        }
        if (targetForRound.isBlank()) {
            failRound("目标包名为空")
            return
        }

        val active = NodeTools.activePackage(service)
        if (active != null && active != targetForRound) {
            roundActive = false
            if (isTrial) {
                finishTrialAfterInterruption("当前前台不是目标应用")
            } else {
                enterWaitingTarget("当前不是抖音：切回抖音后自动继续", toastIt = false)
            }
            return
        }

        val base = MessageProvider.peek(settings)
        if (base == null) {
            failRound("没有可发送的消息")
            return
        }
        runOneCycle(
            baseText = base,
            appendSuffix = true,
            origin = if (isTrial) CycleOrigin.TRIAL else CycleOrigin.MAIN_LOOP,
            settings = settings
        )
    }

    /**
     * Shared single-cycle implementation used by the main loop, 试一轮 and 补发一次.
     * Callers have already checked permissions/foreground and main-loop state.
     * The asynchronous completion is delivered through onRoundSuccess/failRound.
     */
    private fun runOneCycle(
        baseText: String,
        appendSuffix: Boolean,
        origin: CycleOrigin,
        settings: Settings
    ) {
        if (destroyed) return
        roundActive = true
        cycleOrigin = origin
        sendDisabledGrace = 0
        sendMissingGrace = 0
        sendRootRetry = 0
        sendRefillCount = 0
        currentCycleStage = CycleFailureStage.LOCATE_INPUT
        currentCycleBanSuspected = false
        currentCycleIdleMs = pendingIdleMs
        pendingIdleMs = 0L
        currentInputMode = ""
        cycleStartedAtElapsed = SystemClock.elapsedRealtime()
        locateFinishedAtElapsed = 0L
        inputStartedAtElapsed = 0L
        inputFinishedAtElapsed = 0L
        sendStartedAtElapsed = 0L
        sendFinishedAtElapsed = 0L
        verifyStartedAtElapsed = 0L
        targetForRound = settings.targetPackage

        val roundNumber = if (origin == CycleOrigin.SINGLE_SHOT) 0 else ++roundSequence
        val suffix = if (appendSuffix) SuffixGenerator.build(settings) else ""
        val fullText = baseText + suffix
        cycleContentSnapshot = fullText
        val outgoing = Outgoing(settings, baseText, suffix, fullText, roundNumber)
        activeOutgoing = outgoing

        statusMessage = if (origin == CycleOrigin.SINGLE_SHOT) {
            "补发中：直填/定位输入框"
        } else {
            "第${roundNumber}轮：直填/定位输入框"
        }
        val logMessage = when (origin) {
            CycleOrigin.SINGLE_SHOT -> "开始补发一次，内容=\"$fullText\""
            CycleOrigin.TRIAL -> "开始第${roundNumber}轮（手动试跑），目标=${targetForRound}，文本=\"$fullText\""
            CycleOrigin.MAIN_LOOP -> "开始第${roundNumber}轮（自动），目标=${targetForRound}，文本=\"$fullText\""
        }
        LogStore.add(if (origin == CycleOrigin.SINGLE_SHOT) "补发" else "轮次", logMessage)
        notifyChanged()
        attemptDirectFill()
    }

    /**
     * v1.4 fast path: write directly into an isEditable node with ACTION_SET_TEXT
     * (focus + retry only if the first attempt is rejected). On any failure it
     * falls back to the old click-input path; it never waits for a keyboard here.
     */
    private fun attemptDirectFill() {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        val outgoing = activeOutgoing ?: return
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        setStatus("直填评论输入框")
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            fallbackToClickInput("直填：获取窗口 root 失败")
            return
        }
        val editable = NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = false)
        if (editable == null) {
            fallbackToClickInput("直填：未找到 isEditable 节点")
            return
        }
        markLocateFinished()
        markStage(CycleFailureStage.INPUT_TEXT)

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                outgoing.fullText
            )
        }
        var setOk = try {
            editable.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
        var focusUsed = false
        if (!setOk) {
            focusUsed = true
            try {
                editable.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
            }
            setOk = try {
                editable.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Throwable) {
                false
            }
        }
        if (!setOk) {
            fallbackToClickInput("直填：ACTION_SET_TEXT 未被接受")
            return
        }

        // Verify on a short 20ms poll. Usually the text is already there on the
        // first check; polling only covers vendor builds that refresh node text a
        // frame later. Any timeout still falls back to the click-input path.
        var actual = ""
        awaitCondition(
            timeoutMs = 500L,
            intervalMs = 20L,
            condition = {
                val verifyRoot = NodeTools.targetRoot(service, target)
                val verifyEditable = verifyRoot?.let {
                    NodeTools.findEditable(it, target, screen.x, screen.y, requireFocused = false)
                }
                actual = verifyEditable?.hit?.text.orEmpty()
                actual.contains(outgoing.fullText)
            },
            onResult = { verified ->
                if (!isOperationActive()) return@awaitCondition
                LogStore.add(
                    "输入",
                    "直填：SET_TEXT=$setOk，focusRetry=$focusUsed，verified=$verified，text=\"${actual.take(80)}\""
                )
                if (!verified) {
                    fallbackToClickInput("直填：写入后校验未通过")
                    return@awaitCondition
                }
                currentInputMode = "直填"
                markInputFinished()
                setStatus(
                    if (cycleOrigin == CycleOrigin.SINGLE_SHOT) {
                        "补发：直填完成，发送"
                    } else {
                        "直填完成，立即发送"
                    }
                )
                LogStore.add("轮次", "本轮输入路径=直填${if (focusUsed) "（聚焦后重试）" else ""}")
                tapSend()
            }
        )
    }

    private fun fallbackToClickInput(reason: String) {
        if (!isOperationActive()) return
        currentInputMode = "点击输入框"
        LogStore.add("输入", "$reason，回退到点击输入框路径")
        locateInput()
    }

    private fun locateInput() {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        currentInputMode = "点击输入框"
        markStage(CycleFailureStage.LOCATE_INPUT)
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        setStatus("定位评论输入框")
        LogStore.add("定位", "开始定位输入框（20ms 轮询，上限 2000ms，下一轮定位兼作上一轮校验）")
        var hit: NodeHit? = null
        awaitCondition(
            timeoutMs = 2000L,
            intervalMs = 20L,
            condition = {
                if (!isOperationActive()) {
                    false
                } else {
                    val root = NodeTools.targetRoot(service, target)
                    hit = root?.let { NodeTools.findInput(it, target, screen.x, screen.y) }
                    hit != null
                }
            },
            onResult = { found ->
                if (!isOperationActive()) return@awaitCondition
                val foundHit = hit
                if (!found || foundHit == null) {
                    fallbackInput("无障碍定位输入框失败（已轮询 2000ms）")
                    return@awaitCondition
                }
                LogStore.add("定位", "找到输入框：${foundHit.shortDetail()} bounds=${foundHit.boundsLabel}")
                if (!clickAt(foundHit.centerX, foundHit.centerY, "输入框")) {
                    if (isOperationActive()) failRound("输入框点击未被系统接受")
                    return@awaitCondition
                }
                markLocateFinished()
                setStatus("已点击输入框，等待键盘")
                later(randomBetween(450, 650).toLong()) { beginTextEntry(1) }
            }
        )
    }

    private fun awaitCondition(
        timeoutMs: Long,
        intervalMs: Long = 20L,
        condition: () -> Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        fun tick() {
            if (!isOperationActive()) return
            val ok = try {
                condition()
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                onResult(true)
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= timeoutMs) {
                onResult(false)
                return
            }
            val wait = intervalMs.coerceAtLeast(1L).coerceAtMost(timeoutMs - elapsed)
            later(wait) { tick() }
        }
        tick()
    }

    private fun fallbackInput(reason: String) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        val settings = activeOutgoing?.settings ?: AppConfig.load(service)
        if (!settings.hasCalibration) {
            failRound("$reason，且尚未校准坐标")
            return
        }
        val screen = ScreenUtils.realSize(service)
        val x = ScreenUtils.normalizedToPx(settings.inputX, screen.x)
        val y = ScreenUtils.normalizedToPx(settings.inputY, screen.y)
        LogStore.add("定位", "$reason，使用校准坐标兜底输入框 ($x,$y)")
        if (!clickAt(x, y, "输入框(校准)")) {
            if (isOperationActive()) failRound("校准坐标点击输入框失败")
            return
        }
        markLocateFinished()
        setStatus("使用校准坐标，等待键盘")
        later(500L) { beginTextEntry(1) }
    }

    private fun beginTextEntry(attempt: Int) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        if (attempt > 3) {
            failRound("输入重试次数耗尽")
            return
        }
        markStage(CycleFailureStage.INPUT_TEXT)
        setStatus("写入文字（第${attempt}次）")
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            LogStore.add("输入", "写入前获取窗口失败（第${attempt}次）")
            if (attempt < 3) {
                later(450L) { beginTextEntry(attempt + 1) }
            } else {
                failRound("找不到输入窗口")
            }
            return
        }

        val focused = NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = true)
        val editable = focused ?: NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = false)
        if (editable == null) {
            LogStore.add("输入", "找不到可编辑输入框（第${attempt}次），等待键盘弹起")
            if (attempt < 3) {
                later(450L) { beginTextEntry(attempt + 1) }
            } else {
                failRound("找不到可编辑输入框")
            }
            return
        }

        val focusOk = try {
            editable.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Throwable) {
            false
        }
        LogStore.add(
            "输入",
            "第${attempt}次聚焦输入框：focusAction=$focusOk，节点=${editable.hit.shortDetail()}，bounds=${editable.hit.boundsLabel}"
        )
        later(160L) { setTextOnEditable(attempt, InputStrategy.SET_TEXT) }
    }

    private fun setTextOnEditable(attempt: Int, strategy: InputStrategy) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        val outgoing = activeOutgoing ?: return
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            retryTextEntry(attempt, "写入时获取窗口失败")
            return
        }
        val focused = NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = true)
        val editable = focused ?: NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = false)
        if (editable == null) {
            retryTextEntry(attempt, "写入时找不到可编辑节点")
            return
        }

        if (strategy == InputStrategy.SET_TEXT) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    outgoing.fullText
                )
            }
            val ok = try {
                editable.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } catch (_: Throwable) {
                false
            }
            LogStore.add("输入", "第${attempt}次 ACTION_SET_TEXT 结果=$ok，文本长度=${outgoing.fullText.length}")
            later(280L) { verifyText(attempt, strategy) }
        } else {
            val clipboardOk = try {
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("dyshuaping", outgoing.fullText))
                true
            } catch (_: Throwable) {
                false
            }
            val ok = try {
                editable.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: Throwable) {
                false
            }
            LogStore.add("输入", "第${attempt}次剪贴板粘贴：写入剪贴板=$clipboardOk，ACTION_PASTE=$ok")
            later(350L) { verifyText(attempt, strategy) }
        }
    }

    private fun verifyText(attempt: Int, strategy: InputStrategy) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        val outgoing = activeOutgoing ?: return
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            retryTextEntry(attempt, "校验文字时获取窗口失败")
            return
        }
        val focused = NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = true)
        val editable = focused ?: NodeTools.findEditable(root, target, screen.x, screen.y, requireFocused = false)
        if (editable == null) {
            retryTextEntry(attempt, "校验文字时找不到可编辑节点")
            return
        }

        val actual = editable.hit.text.orEmpty()
        val verified = actual.contains(outgoing.fullText)
        val source = if (focused != null) "焦点输入框" else "可编辑节点"
        LogStore.add(
            "输入",
            "第${attempt}次校验（${if (strategy == InputStrategy.SET_TEXT) "SET_TEXT" else "PASTE"}）：" +
                "verified=$verified，$source text=\"${actual.take(80)}\""
        )
        if (verified) {
            markInputFinished()
            setStatus("文字已写入，准备点击发送")
            later(280L) { tapSend() }
            return
        }

        if (strategy == InputStrategy.SET_TEXT) {
            LogStore.add("输入", "SET_TEXT 校验未通过，改用剪贴板粘贴")
            later(160L) { setTextOnEditable(attempt, InputStrategy.PASTE) }
        } else {
            retryTextEntry(attempt, "粘贴后文字校验仍未通过")
        }
    }

    private fun retryTextEntry(attempt: Int, reason: String) {
        if (!isOperationActive()) return
        LogStore.add("输入", reason)
        if (attempt < 3) {
            later(350L) { beginTextEntry(attempt + 1) }
        } else {
            failRound("输入文字失败：$reason")
        }
    }

    private fun tapSend() {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        markStage(CycleFailureStage.CLICK_SEND)
        setStatus("查找发送按钮")
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            LogStore.add("发送", "查找发送按钮时获取窗口失败")
            if (sendRootRetry < 1) {
                sendRootRetry++
                later(400L) { tapSend() }
            } else if (currentInputMode == "直填") {
                currentInputMode = "点击输入框"
                fallbackToClickInput("直填后获取发送按钮窗口失败")
            } else {
                fallbackSend("获取发送按钮窗口失败")
            }
            return
        }

        val lookup = NodeTools.findSend(root, target, screen.x, screen.y)
        if (lookup.hit == null) {
            LogStore.add("发送", "未找到可用发送节点：${lookup.detail}")
            if (sendMissingGrace < 1) {
                sendMissingGrace++
                later(400L) { tapSend() }
            } else if (currentInputMode == "直填") {
                currentInputMode = "点击输入框"
                LogStore.add("发送", "直填后仍找不到发送按钮，回退点击输入框路径")
                fallbackToClickInput("直填后无障碍定位发送按钮失败")
            } else {
                fallbackSend("无障碍定位发送按钮失败")
            }
            return
        }

        if (lookup.disabled) {
            currentCycleBanSuspected = true
            LogStore.add("发送", "发送按钮不可点击/置灰：${lookup.detail}")
            if (sendDisabledGrace < 1) {
                sendDisabledGrace++
                setStatus("发送按钮置灰，稍后重查")
                later(400L) { tapSend() }
                return
            }
            if (currentInputMode == "直填") {
                currentInputMode = "点击输入框"
                LogStore.add("发送", "直填后发送按钮仍置灰，回退点击输入框路径")
                fallbackToClickInput("直填后发送按钮置灰")
                return
            }
            if (sendRefillCount < 2) {
                sendRefillCount++
                setStatus("文字可能未写入，重新输入")
                LogStore.add("发送", "发送按钮仍置灰，回到输入步骤重新写入（${sendRefillCount}/2）")
                later(300L) { beginTextEntry(1) }
                return
            }
            failRound("发送按钮始终置灰，文字可能未写入")
            return
        }

        val hit = lookup.hit
        LogStore.add("发送", "找到发送按钮：${hit.shortDetail()} bounds=${hit.boundsLabel}")
        if (cycleOrigin == CycleOrigin.MAIN_LOOP) {
            val accepted = clickAt(hit.centerX, hit.centerY, "发送按钮") { completed ->
                if (!isOperationActive()) return@clickAt
                if (!completed) {
                    failRound("发送手势被系统取消")
                } else {
                    markSendFinished()
                    setStatus("已点击发送")
                    onRoundSuccess("已点击发送（零等待，下一轮定位兼作校验）")
                }
            }
            if (!accepted && isOperationActive()) failRound("发送按钮点击未被系统接受")
            return
        }
        if (!clickAt(hit.centerX, hit.centerY, "发送按钮")) {
            if (isOperationActive()) failRound("发送按钮点击未被系统接受")
            return
        }
        markSendFinished()
        setStatus("已点击发送，校验结果")
        later(randomBetween(800, 1500).toLong()) { checkSendResult(first = true) }
    }

    private fun fallbackSend(reason: String) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        markStage(CycleFailureStage.CLICK_SEND)
        val settings = activeOutgoing?.settings ?: AppConfig.load(service)
        if (!settings.hasCalibration) {
            failRound("$reason，且尚未校准发送按钮坐标")
            return
        }
        val screen = ScreenUtils.realSize(service)
        val x = ScreenUtils.normalizedToPx(settings.sendX, screen.x)
        val y = ScreenUtils.normalizedToPx(settings.sendY, screen.y)
        LogStore.add("发送", "$reason，使用校准坐标兜底发送按钮 ($x,$y)")
        if (cycleOrigin == CycleOrigin.MAIN_LOOP) {
            val accepted = clickAt(x, y, "发送按钮(校准)") { completed ->
                if (!isOperationActive()) return@clickAt
                if (!completed) {
                    failRound("发送手势被系统取消")
                } else {
                    markSendFinished()
                    setStatus("已点击发送")
                    onRoundSuccess("已点击发送（零等待，下一轮定位兼作校验）")
                }
            }
            if (!accepted && isOperationActive()) failRound("校准坐标点击发送按钮失败")
            return
        }
        if (!clickAt(x, y, "发送按钮(校准)")) {
            if (isOperationActive()) failRound("校准坐标点击发送按钮失败")
            return
        }
        markSendFinished()
        setStatus("已点击校准发送坐标，校验结果")
        later(randomBetween(800, 1500).toLong()) { checkSendResult(first = true) }
    }

    private fun checkSendResult(first: Boolean) {
        if (!isOperationActive()) return
        if (!ensureTargetForeground()) return
        markStage(CycleFailureStage.VERIFY)
        val target = targetForRound
        val screen = ScreenUtils.realSize(service)
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            LogStore.add("发送", "校验发送结果时获取窗口失败")
            if (first) {
                later(500L) { checkSendResult(first = false) }
            } else {
                failRound("无法确认发送结果：窗口不可用")
            }
            return
        }

        val send = NodeTools.findSend(root, target, screen.x, screen.y)
        val placeholder = NodeTools.findInput(
            root,
            target,
            screen.x,
            screen.y,
            placeholderOnly = true,
            includeEditableFallback = false
        )
        if (placeholder != null) {
            onRoundSuccess("输入框占位符重新出现")
            return
        }
        if (send.hit == null) {
            onRoundSuccess("发送按钮节点已消失")
            return
        }
        if (send.disabled) {
            onRoundSuccess("发送按钮已不可点击")
            return
        }

        LogStore.add("发送", "发送按钮仍在且可点击")
        if (first) {
            later(650L) { checkSendResult(first = false) }
        } else {
            failRound("发送按钮仍在且仍可点击")
        }
    }

    private fun onRoundSuccess(reason: String) {
        val outgoing = activeOutgoing ?: return
        val origin = cycleOrigin
        val result = CycleResult(
            success = true,
            failureStage = CycleFailureStage.NONE,
            error = null,
            banSuspected = currentCycleBanSuspected,
            timings = buildCycleTimings(),
            content = outgoing.fullText,
            path = currentInputMode.ifEmpty { "未进入输入" }
        )
        recordLastRound(result, origin)
        roundActive = false
        nextRoundAtElapsed = 0L
        activeOutgoing = null
        cancelPendingCallbacks()

        if (origin == CycleOrigin.SINGLE_SHOT) {
            finishSingleShotCycle(result)
            return
        }

        val wasTrial = origin == CycleOrigin.TRIAL
        sentCount++
        failureCount = 0
        MessageProvider.advanceAfterSuccess(outgoing.settings)
        if (outgoing.suffix.isNotEmpty()) {
            SuffixGenerator.markUsed(outgoing.suffix)
        }
        LogStore.add(
            "轮次",
            "✅ 第${outgoing.roundNumber}轮发送成功（$reason），已发送 $sentCount 条，后缀=\"${outgoing.suffix}\""
        )
        if (wasTrial) {
            trialRunning = false
            cycleOrigin = CycleOrigin.MAIN_LOOP
            phase = trialRestorePhase
            statusMessage = "试跑成功 · 已发送 $sentCount 条"
            toast("试跑成功，已发送 $sentCount 条")
            notifyChanged()
        } else {
            statusMessage = "发送成功 · 已发送 $sentCount 条"
            notifyChanged()
            scheduleNextRound()
        }
    }

    private fun recordLastRound(result: CycleResult, origin: CycleOrigin) {
        lastRoundDurationMs = result.timings.totalMs
        lastRoundPath = result.path
        if (origin == CycleOrigin.SINGLE_SHOT) return
        val t = result.timings
        val label = if (origin == CycleOrigin.TRIAL) "试跑" else "第${roundSequence}轮"
        LogStore.add(
            "耗时",
            "$label 路径=${result.path} 定位=${t.locateMs}ms 输入=${t.inputMs}ms " +
                "发送=${t.sendMs}ms 空闲=${t.idleMs}ms 总计=${t.totalMs}ms"
        )
    }

    private fun failRound(reason: String) {
        val origin = cycleOrigin
        val stage = if (currentCycleStage == CycleFailureStage.NONE) {
            CycleFailureStage.LOCATE_INPUT
        } else {
            currentCycleStage
        }
        val result = CycleResult(
            success = false,
            failureStage = stage,
            error = reason,
            banSuspected = currentCycleBanSuspected,
            timings = buildCycleTimings(),
            content = activeOutgoing?.fullText ?: cycleContentSnapshot,
            path = currentInputMode.ifEmpty { "未进入输入" }
        )
        recordLastRound(result, origin)
        val wasTrial = origin == CycleOrigin.TRIAL
        cancelPendingCallbacks()
        roundActive = false
        activeOutgoing = null
        nextRoundAtElapsed = 0L

        if (origin == CycleOrigin.SINGLE_SHOT) {
            singleShotRunning = false
            finishSingleShotCycle(result)
            return
        }

        LogStore.add("轮次", "❌ 第${roundSequence}轮失败：$reason")

        if (wasTrial) {
            trialRunning = false
            cycleOrigin = CycleOrigin.MAIN_LOOP
            phase = trialRestorePhase
            statusMessage = "试跑失败：$reason"
            toast("试跑失败：$reason")
            notifyChanged()
            return
        }
        if (phase != Phase.RUNNING) {
            notifyChanged()
            return
        }

        failureCount++
        if (failureCount >= 3) {
            phase = Phase.PAUSED
            statusMessage = "连续失败 3 次，已自动暂停"
            LogStore.add("状态", "连续失败 3 次，自动暂停以避免空转")
            toast("连续失败 3 次，已自动暂停")
            notifyChanged()
        } else {
            statusMessage = "本轮失败：$reason"
            LogStore.add("状态", "连续失败 ${failureCount}/3，继续下一轮")
            notifyChanged()
            scheduleNextRound()
        }
    }

    private fun finishSingleShotCycle(result: CycleResult) {
        singleShotRunning = false
        roundActive = false
        nextRoundAtElapsed = 0L
        activeOutgoing = null
        cycleOrigin = CycleOrigin.MAIN_LOOP
        phase = singleShotRestorePhase
        statusMessage = singleShotRestoreStatus.ifBlank {
            if (phase == Phase.PAUSED) "已暂停，计数保留" else "未开始"
        }
        lastSingleShotResult = result
        lastSingleShotResultAtElapsed = SystemClock.elapsedRealtime()
        logSingleShotResult(result)
        notifyChanged()
    }

    private fun finishSingleShotInterrupted(reason: String) {
        val stage = if (currentCycleStage == CycleFailureStage.NONE) {
            CycleFailureStage.LOCATE_INPUT
        } else {
            currentCycleStage
        }
        val result = CycleResult(
            success = false,
            failureStage = stage,
            error = reason,
            banSuspected = currentCycleBanSuspected,
            timings = buildCycleTimings(),
            content = activeOutgoing?.fullText ?: cycleContentSnapshot,
            path = currentInputMode.ifEmpty { "未进入输入" }
        )
        recordLastRound(result, CycleOrigin.SINGLE_SHOT)
        cancelPendingCallbacks()
        singleShotRunning = false
        roundActive = false
        activeOutgoing = null
        nextRoundAtElapsed = 0L
        cycleOrigin = CycleOrigin.MAIN_LOOP
        phase = singleShotRestorePhase
        statusMessage = "补发中断：$reason"
        lastSingleShotResult = result
        lastSingleShotResultAtElapsed = SystemClock.elapsedRealtime()
        logSingleShotResult(result)
        toast("已离开抖音，补发中断")
        notifyChanged()
    }

    private fun markStage(stage: CycleFailureStage) {
        val now = SystemClock.elapsedRealtime()
        when (stage) {
            CycleFailureStage.INPUT_TEXT -> {
                if (inputStartedAtElapsed == 0L) inputStartedAtElapsed = now
            }
            CycleFailureStage.CLICK_SEND -> {
                if (sendStartedAtElapsed == 0L) sendStartedAtElapsed = now
            }
            CycleFailureStage.VERIFY -> {
                if (verifyStartedAtElapsed == 0L) verifyStartedAtElapsed = now
            }
            else -> Unit
        }
        currentCycleStage = stage
    }

    private fun markLocateFinished() {
        if (locateFinishedAtElapsed == 0L) {
            locateFinishedAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun markInputFinished() {
        if (inputFinishedAtElapsed == 0L) {
            inputFinishedAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun markSendFinished() {
        val now = SystemClock.elapsedRealtime()
        if (sendFinishedAtElapsed == 0L) sendFinishedAtElapsed = now
        if (verifyStartedAtElapsed == 0L) verifyStartedAtElapsed = now
        currentCycleStage = CycleFailureStage.VERIFY
    }

    private fun buildCycleTimings(): CycleTimings {
        val now = SystemClock.elapsedRealtime()
        if (cycleStartedAtElapsed <= 0L) {
            return CycleTimings(0L, 0L, 0L, 0L, 0L, 0L)
        }
        val locateEnd = when {
            locateFinishedAtElapsed > 0L -> locateFinishedAtElapsed
            currentCycleStage == CycleFailureStage.LOCATE_INPUT -> now
            inputStartedAtElapsed > 0L -> inputStartedAtElapsed
            else -> now
        }
        val locateMs = (locateEnd - cycleStartedAtElapsed).coerceAtLeast(0L)
        val inputEnd = when {
            inputFinishedAtElapsed > 0L -> inputFinishedAtElapsed
            inputStartedAtElapsed > 0L -> now
            else -> 0L
        }
        val inputMs = if (inputStartedAtElapsed > 0L) {
            (inputEnd - inputStartedAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val sendEnd = when {
            sendFinishedAtElapsed > 0L -> sendFinishedAtElapsed
            sendStartedAtElapsed > 0L -> now
            else -> 0L
        }
        val sendMs = if (sendStartedAtElapsed > 0L) {
            (sendEnd - sendStartedAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val verifyMs = if (verifyStartedAtElapsed > 0L) {
            (now - verifyStartedAtElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val totalMs = currentCycleIdleMs + (now - cycleStartedAtElapsed).coerceAtLeast(0L)
        return CycleTimings(
            locateMs = locateMs,
            inputMs = inputMs,
            sendMs = sendMs,
            verifyMs = verifyMs,
            idleMs = currentCycleIdleMs,
            totalMs = totalMs
        )
    }

    private fun singleShotResultHeadline(result: CycleResult): String {
        return when {
            result.success -> service.getString(R.string.single_shot_result_sent)
            result.failureStage == CycleFailureStage.LOCATE_INPUT ->
                service.getString(R.string.single_shot_result_no_input)
            result.failureStage == CycleFailureStage.INPUT_TEXT ->
                service.getString(R.string.single_shot_result_text_failed)
            else -> service.getString(R.string.single_shot_result_button_disabled)
        }
    }

    private fun cycleStageLabel(stage: CycleFailureStage): String {
        return when (stage) {
            CycleFailureStage.NONE -> "无"
            CycleFailureStage.LOCATE_INPUT -> "LOCATE_INPUT(定位输入框)"
            CycleFailureStage.INPUT_TEXT -> "INPUT_TEXT(输入文字)"
            CycleFailureStage.CLICK_SEND -> "CLICK_SEND(点击发送)"
            CycleFailureStage.VERIFY -> "VERIFY(发送结果校验)"
        }
    }

    private fun logSingleShotResult(result: CycleResult) {
        val timings = result.timings
        LogStore.add(
            "补发",
            "结果=${singleShotResultHeadline(result)}；原文=\"${result.content}\"；" +
                "路径=${result.path}；耗时 定位=${timings.locateMs}ms 输入=${timings.inputMs}ms " +
                "发送=${timings.sendMs}ms 空闲=${timings.idleMs}ms 校验=${timings.verifyMs}ms " +
                "总计=${timings.totalMs}ms；失败阶段=${cycleStageLabel(result.failureStage)}；" +
                "失败原因=${result.error ?: "无"}；疑似禁言=${result.banSuspected}"
        )
    }

    private fun handleWrongForeground(activePackage: String) {
        if (destroyed) return
        val target = targetForRound.ifEmpty { AppConfig.load(service).targetPackage }
        if (activePackage == target) return
        if (singleShotRunning) {
            finishSingleShotInterrupted("已离开抖音")
            return
        }
        if (trialRunning) {
            cancelPendingCallbacks()
            roundActive = false
            activeOutgoing = null
            trialRunning = false
            cycleOrigin = CycleOrigin.MAIN_LOOP
            phase = Phase.PAUSED
            statusMessage = "试跑已中断：已离开抖音"
            LogStore.add("状态", "检测到前台 ${activePackage}，试跑中断并暂停")
            toast("已离开抖音，试跑中断")
            notifyChanged()
            return
        }
        if (phase == Phase.RUNNING) {
            cancelPendingCallbacks()
            roundActive = false
            activeOutgoing = null
            phase = Phase.WAITING_TARGET
            statusMessage = "已离开抖音：切回后自动继续"
            LogStore.add("状态", "检测到前台 ${activePackage}，非目标应用，自动暂停")
            toast("已离开抖音，切回后自动继续")
            notifyChanged()
        }
    }

    private fun finishTrialAfterInterruption(reason: String) {
        cancelPendingCallbacks()
        roundActive = false
        activeOutgoing = null
        trialRunning = false
        cycleOrigin = CycleOrigin.MAIN_LOOP
        phase = Phase.PAUSED
        statusMessage = "试跑中断：$reason"
        LogStore.add("试跑", reason)
        toast("试跑中断：$reason")
        notifyChanged()
    }

    private fun enterWaitingTarget(message: String, toastIt: Boolean) {
        cancelPendingCallbacks()
        roundActive = false
        nextRoundAtElapsed = 0L
        activeOutgoing = null
        phase = Phase.WAITING_TARGET
        statusMessage = message
        LogStore.add("状态", message)
        if (toastIt) toast(message)
        notifyChanged()
    }

    private fun ensureTargetForeground(): Boolean {
        if (destroyed) return false
        val active = NodeTools.activePackage(service)
        if (active == null) return true
        if (active != targetForRound) {
            handleWrongForeground(active)
            return false
        }
        return true
    }

    private fun isOperationActive(): Boolean {
        if (destroyed || !roundActive) return false
        return when (cycleOrigin) {
            CycleOrigin.TRIAL -> trialRunning
            CycleOrigin.SINGLE_SHOT -> singleShotRunning
            CycleOrigin.MAIN_LOOP -> phase == Phase.RUNNING
        }
    }

    private fun clickAt(
        x: Int,
        y: Int,
        label: String,
        onGestureResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!isOperationActive()) return false
        val target = targetForRound
        val active = NodeTools.activePackage(service)
        if (active == null || active != target) {
            if (active != null) handleWrongForeground(active)
            LogStore.add("手势", "$label 点击取消：当前无法确认目标应用在前台（前台=${active ?: "<null>"}，目标=$target）")
            return false
        }
        val screen = ScreenUtils.realSize(service)
        val jitterX = randomBetween(-3, 3)
        val jitterY = randomBetween(-3, 3)
        val clickX = (x + jitterX).coerceIn(1, (screen.x - 2).coerceAtLeast(1))
        val clickY = (y + jitterY).coerceIn(1, (screen.y - 2).coerceAtLeast(1))
        val duration = randomBetween(25, 40).toLong()
        val path = Path().apply {
            moveTo(clickX.toFloat(), clickY.toFloat())
            lineTo((clickX + 1).toFloat(), (clickY + 1).toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        val accepted = try {
            val resultCallback = onGestureResult
            if (resultCallback == null) {
                service.dispatchGesture(gesture, null, null)
            } else {
                val epoch = operationEpoch
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        deliverGestureResult(epoch, true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        deliverGestureResult(epoch, false)
                    }

                    private fun deliverGestureResult(epoch: Int, completed: Boolean) {
                        handler.post {
                            if (destroyed || epoch != operationEpoch) return@post
                            resultCallback(completed)
                        }
                    }
                }
                service.dispatchGesture(gesture, callback, null)
            }
        } catch (t: Throwable) {
            LogStore.add("手势", "$label dispatchGesture 异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
        LogStore.add("手势", "$label 点击坐标=($clickX,$clickY) 按压=${duration}ms accepted=$accepted")
        return accepted
    }

    private fun setStatus(message: String) {
        statusMessage = message
        notifyChanged()
    }

    private fun notifyChanged() {
        if (destroyed) return
        try {
            onChanged?.invoke()
        } catch (t: Throwable) {
            LogStore.add("状态", "刷新界面失败：${t.javaClass.simpleName}")
        }
    }

    private fun cancelPendingCallbacks() {
        operationEpoch++
        handler.removeCallbacksAndMessages(scheduleToken)
        nextRoundAtElapsed = 0L
    }

    private fun later(delayMs: Long, action: () -> Unit) {
        val epoch = operationEpoch
        val runnable = Runnable {
            if (!destroyed && epoch == operationEpoch) {
                try {
                    action()
                } catch (t: Throwable) {
                    LogStore.add("异常", "步骤执行异常：${t.javaClass.simpleName}: ${t.message}")
                    if (isOperationActive()) {
                        failRound("步骤异常：${t.javaClass.simpleName}")
                    }
                }
            }
        }
        handler.postAtTime(
            runnable,
            scheduleToken,
            SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L)
        )
    }

    private fun randomBetween(min: Int, max: Int): Int {
        val low = minOf(min, max)
        val high = maxOf(min, max)
        return low + random.nextInt(high - low + 1)
    }

    private fun randomLongBetween(min: Long, max: Long): Long {
        val low = minOf(min, max)
        val high = maxOf(min, max)
        if (high <= low) return low
        return low + (random.nextDouble() * (high - low + 1)).toLong()
    }

    private fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.1f", milliseconds / 1000.0)

    private fun toast(message: String) {
        try {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
