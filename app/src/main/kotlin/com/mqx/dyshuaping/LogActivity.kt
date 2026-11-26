package com.mqx.dyshuaping

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LogActivity : Activity() {
    private lateinit var logsView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        logsView = findViewById(R.id.tvLogs)
        logScroll = findViewById(R.id.logScroll)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRefreshLogs).setOnClickListener { refreshLogs() }
        findViewById<TextView>(R.id.btnCopyLogs).setOnClickListener { copyLogs() }
        findViewById<TextView>(R.id.btnShareLogs).setOnClickListener { shareLogs() }
        findViewById<TextView>(R.id.btnClearLogs).setOnClickListener { confirmClearLogs() }
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        val text = LogStore.asText()
        if (text.isEmpty()) {
            logsView.text = getString(R.string.logs_empty)
            logsView.setTextColor(getColor(R.color.text_muted))
        } else {
            logsView.text = text
            logsView.setTextColor(getColor(R.color.text_dim))
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogs() {
        val text = LogStore.asText()
        if (text.isEmpty()) {
            toast("暂无日志可复制")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("dyshuaping logs", text))
        toast(getString(R.string.logs_copied))
    }

    private fun shareLogs() {
        val text = LogStore.asText()
        if (text.isEmpty()) {
            toast("暂无日志可分享")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "抖屏助手运行日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.logs_share_title)))
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logs_clear))
            .setMessage(getString(R.string.logs_clear_confirm))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                LogStore.clear()
                refreshLogs()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
