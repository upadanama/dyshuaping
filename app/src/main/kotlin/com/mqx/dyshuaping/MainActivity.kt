package com.mqx.dyshuaping

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val REQ_NOTIFICATION = 2001
        const val REQ_STORAGE = 2002
        const val REQ_OVERLAY = 2003
        val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }

    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnOverlayPermission: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotificationPermission: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibilityPermission: TextView

    private lateinit var tvQuickStatus: TextView
    private lateinit var tvQuickCount: TextView
    private lateinit var btnQuickStart: TextView
    private lateinit var btnQuickPause: TextView
    private lateinit var btnQuickStop: TextView
    private lateinit var btnShowOverlay: TextView

    private lateinit var etMessages: EditText
    private lateinit var etSingleShotText: EditText
    private lateinit var rgMode: RadioGroup
    private lateinit var rbRandom: Button
    private lateinit var rbSequential: Button
    private lateinit var swSuffix: Switch
    private lateinit var cbSuffixSymbol: CheckBox
    private lateinit var cbSuffixNumber: CheckBox
    private lateinit var cbSuffixEmoji: CheckBox
    private lateinit var layoutSuffixTypes: View
    private lateinit var tvSuffixCount: TextView
    private lateinit var sbSuffixCount: SeekBar
    private lateinit var etIntervalMin: EditText
    private lateinit var etIntervalMax: EditText
    private lateinit var tvTimingPreview: TextView
    private lateinit var swJitter: Switch
    private lateinit var layoutJitterMax: View
    private lateinit var etJitterMax: EditText
    private lateinit var etTargetPackage: EditText
    private lateinit var tvCalibration: TextView
    private lateinit var btnCalibrate: TextView
    private lateinit var btnOpenLogs: TextView
    private lateinit var btnSaveSettings: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private var updatingTimingFields = false
    private var lastAccessibilityEnabled = false
    private var lastOverlayGranted = false
    private var storageRequested = false

    private val saveMessagesRunnable = Runnable {
        persistMessages()
    }

    private val saveTargetRunnable = Runnable {
        persistTargetIfValid()
    }

    private val saveTimingRunnable = Runnable {
        applyTimingInputs(rewriteFields = false, showToast = true)
    }

    private val quickTicker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            refreshQuickControls()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadSettingsIntoUi()
        setupListeners()
        lastAccessibilityEnabled = isAccessibilityEnabled()
        lastOverlayGranted = isOverlayGranted()
        refreshPermissionStatuses()
        refreshQuickControls()
        updateCalibrationSummary()
        requestLegacyStorageIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        syncSingleShotFieldIfIdle()
        val accEnabled = isAccessibilityEnabled()
        val overlayGranted = isOverlayGranted()
        if (accEnabled) {
            ControlService.start(this)
            if (overlayGranted && (!lastAccessibilityEnabled || !lastOverlayGranted)) {
                AutomationAccessibilityService.instance?.ensureOverlay()
            }
        }
        lastAccessibilityEnabled = accEnabled
        lastOverlayGranted = overlayGranted
        refreshPermissionStatuses()
        refreshQuickControls()
        updateCalibrationSummary()
        startQuickTicker()
    }

    override fun onPause() {
        persistMessages()
        persistTargetIfValid()
        persistSingleShotText()
        applyTimingInputs(rewriteFields = false, showToast = false)
        stopQuickTicker()
        super.onPause()
    }

    private fun bindViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        btnNotificationPermission = findViewById(R.id.btnNotificationPermission)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibilityPermission = findViewById(R.id.btnAccessibilityPermission)
        tvQuickStatus = findViewById(R.id.tvQuickStatus)
        tvQuickCount = findViewById(R.id.tvQuickCount)
        btnQuickStart = findViewById(R.id.btnQuickStart)
        btnQuickPause = findViewById(R.id.btnQuickPause)
        btnQuickStop = findViewById(R.id.btnQuickStop)
        btnShowOverlay = findViewById(R.id.btnShowOverlay)
        etMessages = findViewById(R.id.etMessages)
        etSingleShotText = findViewById(R.id.etSingleShotText)
        rgMode = findViewById(R.id.rgMode)
        rbRandom = findViewById(R.id.rbRandom)
        rbSequential = findViewById(R.id.rbSequential)
        swSuffix = findViewById(R.id.swSuffix)
        cbSuffixSymbol = findViewById(R.id.cbSuffixSymbol)
        cbSuffixNumber = findViewById(R.id.cbSuffixNumber)
        cbSuffixEmoji = findViewById(R.id.cbSuffixEmoji)
        layoutSuffixTypes = findViewById(R.id.layoutSuffixTypes)
        tvSuffixCount = findViewById(R.id.tvSuffixCount)
        sbSuffixCount = findViewById(R.id.sbSuffixCount)
        etIntervalMin = findViewById(R.id.etIntervalMin)
        etIntervalMax = findViewById(R.id.etIntervalMax)
        tvTimingPreview = findViewById(R.id.tvTimingPreview)
        swJitter = findViewById(R.id.swJitter)
        layoutJitterMax = findViewById(R.id.layoutJitterMax)
        etJitterMax = findViewById(R.id.etJitterMax)
        etTargetPackage = findViewById(R.id.etTargetPackage)
        tvCalibration = findViewById(R.id.tvCalibration)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnOpenLogs = findViewById(R.id.btnOpenLogs)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
    }

    private fun loadSettingsIntoUi() {
        val settings = AppConfig.load(this)
        etMessages.setText(settings.messages.joinToString("\n"))
        etSingleShotText.setText(settings.singleShotText)
        rgMode.check(if (settings.randomMode) R.id.rbRandom else R.id.rbSequential)
        swSuffix.isChecked = settings.suffixEnabled
        cbSuffixSymbol.isChecked = settings.suffixSymbols
        cbSuffixNumber.isChecked = settings.suffixNumbers
        cbSuffixEmoji.isChecked = settings.suffixEmoji
        sbSuffixCount.max = 2
        sbSuffixCount.progress = settings.suffixCount - 1
        setTimingFields(
            settings.intervalMinTenths,
            settings.intervalMaxTenths,
            settings.jitterEnabled,
            settings.jitterMaxTenths
        )
        updateJitterVisibility()
        updateTimingPreview(settings.intervalMinTenths, settings.intervalMaxTenths)
        etTargetPackage.setText(settings.targetPackage)
        tvSuffixCount.text = getString(R.string.label_suffix_count, settings.suffixCount)
        updateSuffixEnabledVisuals()
    }

    private fun setupListeners() {
        etMessages.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                uiHandler.removeCallbacks(saveMessagesRunnable)
                uiHandler.postDelayed(saveMessagesRunnable, 350L)
            }
        })

        etSingleShotText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                persistSingleShotText()
            }
        })
        etSingleShotText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (etSingleShotText.text?.toString()?.trim().isNullOrEmpty()) {
                    etSingleShotText.error = getString(R.string.single_shot_empty)
                } else {
                    etSingleShotText.error = null
                    persistSingleShotText()
                }
            }
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbRandom -> AppConfig.saveRandomMode(this, true)
                R.id.rbSequential -> AppConfig.saveRandomMode(this, false)
            }
        }

        swSuffix.setOnCheckedChangeListener { _, _ ->
            updateSuffixEnabledVisuals()
            persistSuffix()
        }
        cbSuffixSymbol.setOnCheckedChangeListener { _, _ -> persistSuffix() }
        cbSuffixNumber.setOnCheckedChangeListener { _, _ -> persistSuffix() }
        cbSuffixEmoji.setOnCheckedChangeListener { _, _ -> persistSuffix() }
        sbSuffixCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = progress + 1
                tvSuffixCount.text = getString(R.string.label_suffix_count, count)
                persistSuffix()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val timingWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingTimingFields) return
                refreshTimingPreviewFromFields()
                uiHandler.removeCallbacks(saveTimingRunnable)
                uiHandler.postDelayed(saveTimingRunnable, 500L)
            }
        }
        etIntervalMin.addTextChangedListener(timingWatcher)
        etIntervalMax.addTextChangedListener(timingWatcher)
        etJitterMax.addTextChangedListener(timingWatcher)
        val timingFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                applyTimingInputs(rewriteFields = true, showToast = true)
            }
        }
        etIntervalMin.setOnFocusChangeListener(timingFocus)
        etIntervalMax.setOnFocusChangeListener(timingFocus)
        etJitterMax.setOnFocusChangeListener(timingFocus)
        swJitter.setOnCheckedChangeListener { _, _ ->
            updateJitterVisibility()
            refreshTimingPreviewFromFields()
            if (!updatingTimingFields) {
                applyTimingInputs(rewriteFields = false, showToast = false)
            }
        }

        etTargetPackage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                uiHandler.removeCallbacks(saveTargetRunnable)
                uiHandler.postDelayed(saveTargetRunnable, 450L)
            }
        })
        etTargetPackage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateAndPersistTarget(showError = true, silent = true)
            }
        }

        btnOverlayPermission.setOnClickListener {
            openOverlaySettings()
        }
        btnNotificationPermission.setOnClickListener {
            handleNotificationPermissionClick()
        }
        btnAccessibilityPermission.setOnClickListener {
            openAccessibilitySettings()
        }

        btnQuickStart.setOnClickListener {
            val engine = AutomationAccessibilityService.instance?.engine
            if (engine == null) {
                toast(getString(R.string.service_not_ready_toast))
                return@setOnClickListener
            }
            engine.start()
            refreshQuickControls()
        }
        btnQuickPause.setOnClickListener {
            val engine = AutomationAccessibilityService.instance?.engine
            if (engine == null) {
                toast(getString(R.string.service_not_ready_toast))
                return@setOnClickListener
            }
            engine.pause()
            refreshQuickControls()
        }
        btnQuickStop.setOnClickListener {
            val engine = AutomationAccessibilityService.instance?.engine
            if (engine == null) {
                toast(getString(R.string.service_not_ready_toast))
                return@setOnClickListener
            }
            engine.stop()
            refreshQuickControls()
        }
        btnShowOverlay.setOnClickListener {
            showFloatingOverlay()
        }

        btnCalibrate.setOnClickListener {
            startCalibrationFromMain()
        }
        btnOpenLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        btnSaveSettings.setOnClickListener {
            if (saveAllSettings(showFeedback = true)) {
                toast(getString(R.string.saved_toast))
            }
        }
    }

    private fun persistMessages() {
        uiHandler.removeCallbacks(saveMessagesRunnable)
        AppConfig.saveMessages(this, etMessages.text?.toString().orEmpty())
    }

    private fun persistSingleShotText() {
        val text = etSingleShotText.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            etSingleShotText.error = null
            AppConfig.saveSingleShotText(this, text)
        }
    }

    private fun syncSingleShotFieldIfIdle() {
        if (etSingleShotText.hasFocus()) return
        val preferred = AppConfig.load(this).singleShotText
        if (etSingleShotText.text?.toString() != preferred) {
            etSingleShotText.setText(preferred)
            etSingleShotText.setSelection(etSingleShotText.text?.length ?: 0)
        }
    }

    private fun persistTargetIfValid() {
        uiHandler.removeCallbacks(saveTargetRunnable)
        val packageName = etTargetPackage.text?.toString()?.trim().orEmpty()
        if (isValidPackageName(packageName)) {
            etTargetPackage.error = null
            AppConfig.saveTargetPackage(this, packageName)
        }
    }

    private fun validateAndPersistTarget(showError: Boolean, silent: Boolean): Boolean {
        val packageName = etTargetPackage.text?.toString()?.trim().orEmpty()
        if (!isValidPackageName(packageName)) {
            if (showError) {
                etTargetPackage.error = "包名格式不正确"
            }
            if (!silent) {
                toast("目标包名格式不正确，未保存")
            }
            return false
        }
        etTargetPackage.error = null
        AppConfig.saveTargetPackage(this, packageName)
        return true
    }

    private fun persistSuffix() {
        AppConfig.saveSuffixSettings(
            this,
            enabled = swSuffix.isChecked,
            symbols = cbSuffixSymbol.isChecked,
            numbers = cbSuffixNumber.isChecked,
            emoji = cbSuffixEmoji.isChecked,
            count = sbSuffixCount.progress + 1
        )
        updateSuffixEnabledVisuals()
    }

    private fun setTimingFields(
        minTenths: Int,
        maxTenths: Int,
        jitterEnabled: Boolean,
        jitterMaxTenths: Int
    ) {
        updatingTimingFields = true
        etIntervalMin.setText(formatSeconds(minTenths))
        etIntervalMax.setText(formatSeconds(maxTenths))
        swJitter.isChecked = jitterEnabled
        etJitterMax.setText(formatSeconds(jitterMaxTenths))
        updatingTimingFields = false
    }

    private fun updateJitterVisibility() {
        layoutJitterMax.visibility = if (swJitter.isChecked) View.VISIBLE else View.GONE
    }

    private fun refreshTimingPreviewFromFields() {
        val minRaw = parseSecondsToTenths(etIntervalMin.text?.toString().orEmpty())
        val maxRaw = parseSecondsToTenths(etIntervalMax.text?.toString().orEmpty())
        if (minRaw == null || maxRaw == null) {
            val saved = AppConfig.load(this)
            updateTimingPreview(saved.intervalMinTenths, saved.intervalMaxTenths)
            return
        }
        var min = minRaw.coerceIn(0, 600)
        var max = maxRaw.coerceIn(0, 600)
        if (min > max) max = min
        updateTimingPreview(min, max)
    }

    private fun updateTimingPreview(minTenths: Int, maxTenths: Int) {
        val jitterEnabled = swJitter.isChecked
        val jitterTenths = if (jitterEnabled) {
            val parsed = parseSecondsToTenths(etJitterMax.text?.toString().orEmpty())
            (parsed ?: AppConfig.load(this).jitterMaxTenths).coerceIn(1, 600)
        } else {
            0
        }
        tvTimingPreview.text = if (jitterEnabled) {
            getString(
                R.string.timing_preview_jitter,
                formatSeconds(minTenths),
                formatSeconds(maxTenths),
                formatSeconds(jitterTenths)
            )
        } else {
            getString(
                R.string.timing_preview_no_jitter,
                formatSeconds(minTenths),
                formatSeconds(maxTenths)
            )
        }
    }

    private fun applyTimingInputs(rewriteFields: Boolean, showToast: Boolean): Boolean {
        if (updatingTimingFields) return false
        uiHandler.removeCallbacks(saveTimingRunnable)
        val minRaw = parseSecondsToTenths(etIntervalMin.text?.toString().orEmpty())
        val maxRaw = parseSecondsToTenths(etIntervalMax.text?.toString().orEmpty())
        if (minRaw == null || maxRaw == null) {
            if (rewriteFields) {
                toast(getString(R.string.timing_input_invalid))
                val saved = AppConfig.load(this)
                setTimingFields(
                    saved.intervalMinTenths,
                    saved.intervalMaxTenths,
                    saved.jitterEnabled,
                    saved.jitterMaxTenths
                )
                updateJitterVisibility()
                updateTimingPreview(saved.intervalMinTenths, saved.intervalMaxTenths)
            }
            return false
        }

        var rangeCorrected = false
        var boundsCorrected = false
        var min = minRaw.coerceIn(0, 600)
        var max = maxRaw.coerceIn(0, 600)
        if (min != minRaw || max != maxRaw) {
            rangeCorrected = true
        }
        if (min > max) {
            max = min
            boundsCorrected = true
        }

        val jitterEnabled = swJitter.isChecked
        val jitterRaw = parseSecondsToTenths(etJitterMax.text?.toString().orEmpty())
        val savedJitter = AppConfig.load(this).jitterMaxTenths
        var jitterMax = if (jitterEnabled) {
            if (jitterRaw == null) {
                if (rewriteFields) {
                    toast(getString(R.string.timing_input_invalid))
                    val saved = AppConfig.load(this)
                    setTimingFields(
                        saved.intervalMinTenths,
                        saved.intervalMaxTenths,
                        saved.jitterEnabled,
                        saved.jitterMaxTenths
                    )
                    updateJitterVisibility()
                    updateTimingPreview(saved.intervalMinTenths, saved.intervalMaxTenths)
                }
                return false
            }
            val clamped = jitterRaw.coerceIn(1, 600)
            if (clamped != jitterRaw) rangeCorrected = true
            clamped
        } else {
            (jitterRaw ?: savedJitter).coerceIn(1, 600)
        }

        AppConfig.saveTiming(this, min, max, jitterEnabled, jitterMax)
        if (rewriteFields) {
            setTimingFields(min, max, jitterEnabled, jitterMax)
        }
        updateJitterVisibility()
        updateTimingPreview(min, max)
        if (showToast) {
            if (rangeCorrected) toast(getString(R.string.timing_value_range_error))
            if (boundsCorrected) toast(getString(R.string.timing_min_max_error))
        }
        return true
    }

    private fun parseSecondsToTenths(text: String): Int? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return (value * 10.0).roundToInt()
    }

    private fun formatSeconds(tenths: Int): String =
        String.format(Locale.US, "%.1f", tenths / 10.0)

    private fun updateSuffixEnabledVisuals() {
        val enabled = swSuffix.isChecked
        layoutSuffixTypes.alpha = if (enabled) 1f else 0.4f
        cbSuffixSymbol.isEnabled = enabled
        cbSuffixNumber.isEnabled = enabled
        cbSuffixEmoji.isEnabled = enabled
        sbSuffixCount.isEnabled = enabled
        tvSuffixCount.alpha = if (enabled) 1f else 0.4f
    }

    private fun saveAllSettings(showFeedback: Boolean): Boolean {
        val messages = etMessages.text?.toString().orEmpty()
        val hasMessages = AppConfig.parseMessages(messages).isNotEmpty()
        if (!validateAndPersistTarget(showError = true, silent = !showFeedback)) {
            return false
        }
        val singleShot = etSingleShotText.text?.toString()?.trim().orEmpty()
        if (singleShot.isEmpty()) {
            etSingleShotText.error = getString(R.string.single_shot_empty)
            if (showFeedback) toast(getString(R.string.single_shot_empty))
            return false
        }
        etSingleShotText.error = null
        if (!applyTimingInputs(rewriteFields = true, showToast = showFeedback)) {
            return false
        }
        AppConfig.saveMessages(this, messages)
        AppConfig.saveSingleShotText(this, singleShot)
        persistSuffix()
        AppConfig.saveRandomMode(this, rgMode.checkedRadioButtonId == R.id.rbRandom)
        updateSuffixEnabledVisuals()
        if (!hasMessages) {
            if (showFeedback) toast(getString(R.string.messages_empty_toast))
            return false
        }
        return true
    }

    private fun refreshPermissionStatuses() {
        val overlayGranted = isOverlayGranted()
        updatePermissionRow(
            tvOverlayStatus,
            btnOverlayPermission,
            overlayGranted,
            grantedText = getString(R.string.status_granted),
            missingText = getString(R.string.status_not_granted),
            grantedAction = getString(R.string.action_manage),
            missingAction = getString(R.string.action_grant)
        )

        val notificationsGranted = isNotificationGranted()
        updatePermissionRow(
            tvNotificationStatus,
            btnNotificationPermission,
            notificationsGranted,
            grantedText = getString(R.string.status_granted),
            missingText = getString(R.string.status_not_granted),
            grantedAction = getString(R.string.action_manage),
            missingAction = getString(R.string.action_grant)
        )

        val accessibilityEnabled = isAccessibilityEnabled()
        updatePermissionRow(
            tvAccessibilityStatus,
            btnAccessibilityPermission,
            accessibilityEnabled,
            grantedText = getString(R.string.status_enabled),
            missingText = getString(R.string.status_disabled),
            grantedAction = getString(R.string.action_manage),
            missingAction = getString(R.string.action_enable)
        )
    }

    private fun updatePermissionRow(
        status: TextView,
        action: TextView,
        granted: Boolean,
        grantedText: String,
        missingText: String,
        grantedAction: String,
        missingAction: String
    ) {
        status.text = if (granted) grantedText else missingText
        status.setTextColor(getColor(if (granted) R.color.green else R.color.amber))
        status.setBackgroundResource(if (granted) R.drawable.bg_pill_ok else R.drawable.bg_pill_warn)
        action.text = if (granted) grantedAction else missingAction
    }

    private fun refreshQuickControls() {
        if (!isAccessibilityEnabled()) {
            tvQuickStatus.text = getString(R.string.quick_status_disconnected)
            tvQuickStatus.setTextColor(getColor(R.color.text_muted))
            tvQuickCount.text = getString(R.string.quick_count_format, 0)
            setQuickButtonEnabled(btnQuickStart, false)
            setQuickButtonEnabled(btnQuickPause, false)
            setQuickButtonEnabled(btnQuickStop, false)
            return
        }
        val engine = AutomationAccessibilityService.instance?.engine
        if (engine == null) {
            tvQuickStatus.text = "无障碍已开启，服务正在连接…"
            tvQuickStatus.setTextColor(getColor(R.color.text_dim))
            tvQuickCount.text = getString(R.string.quick_count_format, 0)
            setQuickButtonEnabled(btnQuickStart, false)
            setQuickButtonEnabled(btnQuickPause, false)
            setQuickButtonEnabled(btnQuickStop, false)
            return
        }

        val state = engine.snapshot()
        tvQuickStatus.text = state.statusMessage
        tvQuickStatus.setTextColor(quickStatusColor(state))
        tvQuickCount.text = getString(R.string.quick_count_format, state.sentCount)
        val startEnabled = !state.roundActive &&
            !state.trialRunning &&
            (state.phase == AutomationEngine.Phase.IDLE || state.phase == AutomationEngine.Phase.PAUSED)
        val pauseEnabled = state.trialRunning ||
            state.phase == AutomationEngine.Phase.RUNNING ||
            state.phase == AutomationEngine.Phase.WAITING_TARGET
        val stopEnabled = state.phase != AutomationEngine.Phase.IDLE ||
            state.sentCount > 0 ||
            state.roundActive ||
            state.trialRunning
        setQuickButtonEnabled(btnQuickStart, startEnabled)
        setQuickButtonEnabled(btnQuickPause, pauseEnabled)
        setQuickButtonEnabled(btnQuickStop, stopEnabled)
    }

    private fun quickStatusColor(state: AutomationEngine.UiState): Int {
        return when {
            state.trialRunning -> getColor(R.color.cyan)
            state.phase == AutomationEngine.Phase.RUNNING -> getColor(R.color.green)
            state.phase == AutomationEngine.Phase.WAITING_TARGET ||
                state.phase == AutomationEngine.Phase.PAUSED -> getColor(R.color.amber)
            else -> getColor(R.color.text_dim)
        }
    }

    private fun setQuickButtonEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun updateCalibrationSummary() {
        val settings = AppConfig.load(this)
        tvCalibration.text = if (settings.hasCalibration) {
            getString(
                R.string.calibration_set,
                (settings.inputX * 100).roundToInt(),
                (settings.inputY * 100).roundToInt(),
                (settings.sendX * 100).roundToInt(),
                (settings.sendY * 100).roundToInt()
            )
        } else {
            getString(R.string.calibration_unset)
        }
    }

    private fun showFloatingOverlay() {
        if (!isOverlayGranted()) {
            toast(getString(R.string.overlay_permission_needed))
            openOverlaySettings()
            return
        }
        if (!isAccessibilityEnabled()) {
            toast(getString(R.string.accessibility_permission_needed))
            return
        }
        val service = AutomationAccessibilityService.instance
        if (service == null) {
            toast(getString(R.string.service_not_ready_toast))
            return
        }
        if (service.ensureOverlay()) {
            toast("悬浮窗已显示")
        } else {
            toast("悬浮窗打开失败，请检查权限")
        }
    }

    private fun startCalibrationFromMain() {
        val service = AutomationAccessibilityService.instance
        // Pause any running loop and hide the floating panel before opening the
        // fully transparent calibration page. Accessibility can be off: the page
        // still records coordinates and only skips gesture replay.
        service?.engine?.pause()
        service?.onCalibrationActivityOpened()
        if (!CalibrationActivity.launch(this)) {
            if (service != null && !service.hasOverlay()) service.ensureOverlay()
            toast("无法打开校准页面，请重试")
            return
        }
        if (service == null) {
            toast("请先开启无障碍服务；当前只能记录坐标，无法回放点击")
        }
        toast("透明校准页已打开：先点评论输入框，再点发送按钮")
        // The calibration Activity lives in its own task. Move the settings task
        // behind it so the app underneath (normally the Douyin live room) is what
        // the transparent page and its replayed taps operate on.
        uiHandler.postDelayed({ moveTaskToBack(true) }, 250L)
    }

    private fun handleNotificationPermissionClick() {
        if (isNotificationGranted()) {
            openNotificationSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            openNotificationSettings()
        }
    }

    private fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun isNotificationGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled()
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val manager = getSystemService(AccessibilityManager::class.java)
            val expected = ComponentName(this, AutomationAccessibilityService::class.java)
            val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (info in services) {
                val serviceInfo = info.resolveInfo?.serviceInfo ?: continue
                if (serviceInfo.packageName == expected.packageName &&
                    serviceInfo.name == expected.className
                ) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQ_OVERLAY)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (t: Throwable) {
                toast("无法打开悬浮窗设置：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, AutomationAccessibilityService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                intent.putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                toast("无法打开无障碍设置：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            }
            startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开通知设置：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            !storageRequested &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storageRequested = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATION -> {
                refreshPermissionStatuses()
                if (isAccessibilityEnabled()) ControlService.start(this)
                if (!isNotificationGranted()) {
                    toast("通知未开启，可在系统设置中手动允许")
                }
            }

            REQ_STORAGE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    toast("存储权限已授予，节点树将写入 Download")
                } else {
                    toast("未授予存储权限，节点树将保存到应用私有目录")
                }
            }
        }
    }

    @Deprecated("Deprecated in Activity, retained for the overlay settings return path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            refreshPermissionStatuses()
            lastOverlayGranted = false
            if (isOverlayGranted() && isAccessibilityEnabled()) {
                AutomationAccessibilityService.instance?.ensureOverlay()
            }
        }
    }

    private fun startQuickTicker() {
        if (tickerRunning) return
        tickerRunning = true
        uiHandler.post(quickTicker)
    }

    private fun stopQuickTicker() {
        tickerRunning = false
        uiHandler.removeCallbacks(quickTicker)
    }

    private fun isValidPackageName(value: String): Boolean =
        value.isNotEmpty() && PACKAGE_NAME_REGEX.matches(value)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
