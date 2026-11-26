package com.mqx.dyshuaping

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object NodeDump {
    private const val FILE_NAME = "dyshuaping_nodes.txt"
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 60

    private data class DumpDestination(val uri: Uri, val displayPath: String)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun captureAndShare(service: AccessibilityService) {
        val settings = AppConfig.load(service)
        val target = settings.targetPackage
        val root = NodeTools.targetRoot(service, target)
        if (root == null) {
            val active = NodeTools.activePackage(service) ?: "<null>"
            val msg = "抓节点失败：当前前台不是目标应用。前台=$active，目标=$target"
            LogStore.add("抓节点", msg)
            toast(service, "抓节点失败：请先将抖音直播间切到前台")
            return
        }

        val dump = buildDump(service, root, target, settings)
        Thread({
            try {
                val destination = writeDump(service, dump)
                mainHandler.post {
                    LogStore.add("抓节点", "节点树已写入 ${destination.displayPath}")
                    toast(service, "节点树已保存：${destination.displayPath}")
                    share(service, destination)
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    LogStore.add("抓节点", "写文件失败：${t.javaClass.simpleName}: ${t.message}")
                    toast(service, "节点树写文件失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "dyshuaping-node-dump").start()
    }

    private fun buildDump(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        target: String,
        settings: Settings
    ): String {
        val screen = ScreenUtils.realSize(service)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val builder = StringBuilder(32 * 1024)
        builder.append("dyshuaping node dump\n")
        builder.append("time=").append(time).append('\n')
        builder.append("target_package=").append(target).append('\n')
        builder.append("active_package=").append(NodeTools.activePackage(service) ?: "<null>").append('\n')
        builder.append("display=").append(screen.x).append('x').append(screen.y).append('\n')
        builder.append("calibration=")
        if (settings.hasCalibration) {
            builder.append("input=(").append(settings.inputX).append(',').append(settings.inputY)
                .append(") send=(").append(settings.sendX).append(',').append(settings.sendY).append(")\n")
        } else {
            builder.append("unset\n")
        }
        builder.append("----------------------------------------\n")

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var count = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (count >= MAX_NODES) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            count++
            builder.append('#').append(count).append(' ')
            repeat(minOf(depth, 18)) { builder.append("  ") }
            builder.append("class=").append(safeClass(node))
            builder.append(" pkg=").append(safePackage(node))
            builder.append(" text=\"").append(escape(safeText(node))).append('"')
            builder.append(" desc=\"").append(escape(safeDesc(node))).append('"')
            builder.append(" id=").append(safeId(node))
            val rect = Rect()
            val hasBounds = try {
                node.getBoundsInScreen(rect)
                true
            } catch (_: Throwable) {
                false
            }
            builder.append(" bounds=")
            if (hasBounds) {
                builder.append('[').append(rect.left).append(',').append(rect.top).append("][")
                    .append(rect.right).append(',').append(rect.bottom).append(']')
            } else {
                builder.append("<null>")
            }
            builder.append(" clickable=").append(bool(node) { it.isClickable })
            builder.append(" enabled=").append(bool(node) { it.isEnabled })
            builder.append(" editable=").append(bool(node) { it.isEditable })
            builder.append(" focused=").append(bool(node) { it.isFocused })
            builder.append(" visible=").append(bool(node) { it.isVisibleToUser })
            builder.append(" children=").append(childCount(node))
            builder.append('\n')

            if (depth < MAX_DEPTH) {
                val n = childCount(node)
                for (i in 0 until n) {
                    val child = try {
                        node.getChild(i)
                    } catch (_: Throwable) {
                        null
                    }
                    if (child != null) queue.addLast(child to depth + 1)
                }
            }
        }

        if (truncated) {
            builder.append("--- truncated at ").append(MAX_NODES).append(" nodes ---\n")
        }
        builder.append("--- total captured: ").append(count).append(" nodes ---\n")
        return builder.toString()
    }

    private fun writeDump(context: Context, content: String): DumpDestination {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return writeViaMediaStore(context, bytes)
            } catch (t: Throwable) {
                LogStore.add("抓节点", "MediaStore 写入失败，尝试应用目录：${t.javaClass.simpleName}")
            }
        } else {
            try {
                return writeDirectDownload(context, bytes)
            } catch (t: Throwable) {
                LogStore.add("抓节点", "公共 Download 写入失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return writeAppFallback(context, bytes)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, bytes: ByteArray): DumpDestination {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
        var existingId: Long? = null
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) existingId = cursor.getLong(0)
            }
            if (existingId != null) {
                resolver.delete(
                    Uri.withAppendedPath(collection, existingId.toString()),
                    null,
                    null
                )
            }
        } catch (_: Throwable) {
            // The insert below is still safe; MediaStore may add a numeric suffix.
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("openOutputStream returned null")
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
        val finish = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(uri, finish, null, null)
        return DumpDestination(uri, File(Environment.getExternalStorageDirectory(), "Download/$FILE_NAME").absolutePath)
    }

    @Suppress("DEPRECATION")
    private fun writeDirectDownload(context: Context, bytes: ByteArray): DumpDestination {
        val permission = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("未授予存储权限")
        }
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建 Download 目录")
        }
        val file = File(directory, FILE_NAME)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return DumpDestination(uri, file.absolutePath)
    }

    private fun writeAppFallback(context: Context, bytes: ByteArray): DumpDestination {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!base.exists()) base.mkdirs()
        val file = File(base, FILE_NAME)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        LogStore.add("抓节点", "已写入应用私有目录：${file.absolutePath}")
        return DumpDestination(uri, file.absolutePath)
    }

    private fun share(context: Context, destination: DumpDestination) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, destination.uri)
            putExtra(Intent.EXTRA_SUBJECT, "抖屏助手节点树")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "dyshuaping nodes", destination.uri)
        }
        val chooser = Intent.createChooser(intent, "分享节点树").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (t: Throwable) {
            LogStore.add("抓节点", "调起分享失败：${t.javaClass.simpleName}")
            toast(context, "分享面板打开失败，文件在：${destination.displayPath}")
        }
    }

    private fun toast(context: Context, message: String) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    private fun escape(value: String): String {
        val clipped = if (value.length > 600) value.substring(0, 600) + "…" else value
        return clipped
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
    }

    private fun bool(node: AccessibilityNodeInfo, getter: (AccessibilityNodeInfo) -> Boolean): Boolean =
        try {
            getter(node)
        } catch (_: Throwable) {
            false
        }

    private fun childCount(node: AccessibilityNodeInfo): Int =
        try {
            node.childCount
        } catch (_: Throwable) {
            0
        }

    private fun safeClass(node: AccessibilityNodeInfo): String =
        try {
            node.className?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safePackage(node: AccessibilityNodeInfo): String =
        try {
            node.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safeText(node: AccessibilityNodeInfo): String =
        try {
            node.text?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safeDesc(node: AccessibilityNodeInfo): String =
        try {
            node.contentDescription?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safeId(node: AccessibilityNodeInfo): String =
        try {
            node.viewIdResourceName ?: "-"
        } catch (_: Throwable) {
            "-"
        }
}
