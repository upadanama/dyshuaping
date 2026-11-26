package com.mqx.dyshuaping

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

data class NodeHit(
    val bounds: Rect,
    val source: String,
    val className: String?,
    val text: String?,
    val viewId: String?
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val boundsLabel: String get() = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"

    fun shortDetail(): String =
        "$source class=${className ?: "?"} id=${viewId ?: "-"} text=${text?.take(40) ?: "-"}"
}

data class SendLookup(
    val hit: NodeHit?,
    val found: Boolean,
    val disabled: Boolean,
    val detail: String
)

data class EditableHit(
    val node: AccessibilityNodeInfo,
    val hit: NodeHit
)

object NodeTools {
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 55
    private val inputTerms = listOf("说点什么", "和主播聊两句", "写评论", "发个评论")

    private data class Ranked(val hit: NodeHit, val score: Double, val disabled: Boolean = false)

    fun targetRoot(service: AccessibilityService, targetPackage: String): AccessibilityNodeInfo? {
        val root = try {
            service.rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        try {
            root.refresh()
        } catch (_: Throwable) {
            // A stale root is handled by the caller as a failed lookup.
        }
        return if (safePackage(root) == targetPackage) root else null
    }

    fun activePackage(service: AccessibilityService): String? {
        val root = try {
            service.rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        return safePackage(root)
    }

    /**
     * Text-first lookup followed by an editable-node lookup. The caller may use
     * placeholderOnly=true to check specifically for the hint text reappearing.
     */
    fun findInput(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int,
        placeholderOnly: Boolean = false,
        includeEditableFallback: Boolean = true
    ): NodeHit? {
        val ranked = ArrayList<Ranked>()

        for (term in inputTerms) {
            val infos = try {
                root.findAccessibilityNodeInfosByText(term)
            } catch (_: Throwable) {
                emptyList()
            }
            for (node in infos) {
                addInputCandidate(ranked, node, targetPackage, screenWidth, screenHeight, "text[$term]")
            }
        }

        // Some vendor builds do not return partial text matches from the API above.
        walk(root) { node ->
            val text = safeText(node)
            val desc = safeContentDescription(node)
            for (term in inputTerms) {
                if (text.contains(term) || desc.contains(term)) {
                    addInputCandidate(
                        ranked,
                        node,
                        targetPackage,
                        screenWidth,
                        screenHeight,
                        "treeText[$term]"
                    )
                    break
                }
            }
        }

        val textBest = ranked.distinctBy { "${it.hit.bounds};${it.hit.className}" }.maxByOrNull { it.score }
        if (textBest != null || placeholderOnly || !includeEditableFallback) return textBest?.hit

        val editableRanked = ArrayList<Ranked>()
        walk(root) { node ->
            if (isEditableNode(node)) {
                addEditableCandidate(
                    editableRanked,
                    node,
                    targetPackage,
                    screenWidth,
                    screenHeight,
                    "editable",
                    requireFocused = false
                )
            }
        }
        return editableRanked.distinctBy { it.hit.bounds.toString() }.maxByOrNull { it.score }?.hit
    }

    fun findEditable(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int,
        requireFocused: Boolean
    ): EditableHit? {
        val ranked = ArrayList<Pair<EditableHit, Double>>()
        walk(root) { node ->
            if (isEditableNode(node)) {
                if (requireFocused && !safeFocused(node)) return@walk
                val bounds = boundsOf(node, targetPackage, screenWidth, screenHeight) ?: return@walk
                val focused = safeFocused(node)
                val hit = NodeHit(
                    bounds = bounds,
                    source = if (focused) "editable[focused]" else "editable",
                    className = safeClassName(node),
                    text = safeText(node),
                    viewId = safeViewId(node)
                )
                var score = bounds.centerY().toDouble()
                if (focused) score += 1_000_000.0
                ranked.add(EditableHit(node, hit) to score)
            }
        }
        return ranked.maxByOrNull { it.second }?.first
    }

    fun findSend(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int
    ): SendLookup {
        val rawNodes = ArrayList<AccessibilityNodeInfo>()
        try {
            rawNodes.addAll(root.findAccessibilityNodeInfosByText("发送"))
        } catch (_: Throwable) {
            // Supplemented by tree traversal below.
        }
        walk(root) { node ->
            if (safeText(node).contains("发送") || safeContentDescription(node).contains("发送")) {
                rawNodes.add(node)
            }
        }

        val usable = ArrayList<Ranked>()
        val disabled = ArrayList<Ranked>()
        var found = false

        for (node in rawNodes) {
            if (safePackage(node) != targetPackage) continue
            val rawBounds = boundsOf(node, targetPackage, screenWidth, screenHeight) ?: continue
            found = true

            val clickable = resolveClickableTarget(node, targetPackage, screenWidth, screenHeight)
            if (clickable != null) {
                val (clickNode, clickBounds) = clickable
                val hit = NodeHit(
                    bounds = clickBounds,
                    source = "text[发送]",
                    className = safeClassName(clickNode),
                    text = safeText(clickNode).ifEmpty { safeText(node) },
                    viewId = safeViewId(clickNode)
                )
                usable.add(Ranked(hit, sendScore(clickBounds, screenWidth, screenHeight)))
            } else {
                // A non-clickable send label is meaningful only near the bottom-right,
                // where the real compose button lives. This avoids comment text matches.
                val likelyButton = rawBounds.centerX() > screenWidth * 0.55f &&
                    rawBounds.centerY() > screenHeight * 0.35f
                if (likelyButton) {
                    val hit = NodeHit(
                        bounds = rawBounds,
                        source = "text[发送]/disabled",
                        className = safeClassName(node),
                        text = safeText(node),
                        viewId = safeViewId(node)
                    )
                    disabled.add(Ranked(hit, sendScore(rawBounds, screenWidth, screenHeight), disabled = true))
                }
            }
        }

        val usableBest = usable.distinctBy { it.hit.bounds.toString() }.maxByOrNull { it.score }
        if (usableBest != null) {
            return SendLookup(usableBest.hit, found = true, disabled = false, detail = usableBest.hit.shortDetail())
        }
        val disabledBest = disabled.distinctBy { it.hit.bounds.toString() }.maxByOrNull { it.score }
        if (disabledBest != null) {
            return SendLookup(
                disabledBest.hit,
                found = true,
                disabled = true,
                detail = disabledBest.hit.shortDetail()
            )
        }
        return SendLookup(
            hit = null,
            found = found,
            disabled = false,
            detail = if (found) "找到了「发送」相关节点，但没有可用的按钮区域" else "未找到目标包名内文本为「发送」的节点"
        )
    }

    private fun addInputCandidate(
        output: MutableList<Ranked>,
        original: AccessibilityNodeInfo,
        targetPackage: String,
        width: Int,
        height: Int,
        source: String
    ) {
        val targetNode = editableSelfOrAncestor(original, targetPackage) ?: original
        val bounds = boundsOf(targetNode, targetPackage, width, height) ?: return
        val hit = NodeHit(
            bounds = bounds,
            source = source,
            className = safeClassName(targetNode),
            text = safeText(targetNode).ifEmpty { safeText(original) },
            viewId = safeViewId(targetNode)
        )
        var score = bounds.centerY().toDouble()
        if (isEditableNode(targetNode)) score += 1_000_000.0
        output.add(Ranked(hit, score))
    }

    private fun addEditableCandidate(
        output: MutableList<Ranked>,
        node: AccessibilityNodeInfo,
        targetPackage: String,
        width: Int,
        height: Int,
        source: String,
        requireFocused: Boolean
    ) {
        if (requireFocused && !safeFocused(node)) return
        val bounds = boundsOf(node, targetPackage, width, height) ?: return
        val hit = NodeHit(
            bounds = bounds,
            source = source,
            className = safeClassName(node),
            text = safeText(node),
            viewId = safeViewId(node)
        )
        var score = bounds.centerY().toDouble()
        if (safeFocused(node)) score += 1_000_000.0
        output.add(Ranked(hit, score))
    }

    private fun resolveClickableTarget(
        node: AccessibilityNodeInfo,
        targetPackage: String,
        width: Int,
        height: Int
    ): Pair<AccessibilityNodeInfo, Rect>? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (safePackage(current) != targetPackage) break
            val bounds = boundsOf(current, targetPackage, width, height)
            val area = if (bounds == null) 0L else bounds.width().toLong() * bounds.height().toLong()
            val screenArea = width.toLong() * height.toLong()
            if (bounds != null && safeClickable(current) && safeEnabled(current) &&
                area <= screenArea * 25L / 100L
            ) {
                return current to bounds
            }
            current = try {
                current.parent
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    private fun editableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        targetPackage: String
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (safePackage(current) != targetPackage) return null
            if (isEditableNode(current)) return current
            current = try {
                current.parent
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    private fun sendScore(bounds: Rect, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) return 0.0
        val nx = bounds.centerX().toDouble() / width
        val ny = bounds.centerY().toDouble() / height
        return nx * 3.0 + ny * 2.0
    }

    private fun boundsOf(
        node: AccessibilityNodeInfo,
        targetPackage: String,
        screenWidth: Int,
        screenHeight: Int
    ): Rect? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        if (safePackage(node) != targetPackage) return null
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Throwable) {
            return null
        }
        if (rect.width() < 10 || rect.height() < 10) return null
        if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= screenWidth || rect.top >= screenHeight) return null
        if (!rect.intersect(0, 0, screenWidth, screenHeight)) return null
        if (rect.width() < 10 || rect.height() < 10) return null
        return rect
    }

    private inline fun walk(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                action(node)
            } catch (_: Throwable) {
                // Defensive: one malformed node must not abort the whole search.
            }
            if (depth >= MAX_DEPTH) continue
            val childCount = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (index in 0 until childCount) {
                val child = try {
                    node.getChild(index)
                } catch (_: Throwable) {
                    null
                }
                if (child != null) queue.addLast(child to depth + 1)
            }
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.isEditable || (node.className?.toString()?.contains("EditText") == true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun safePackage(node: AccessibilityNodeInfo): String? =
        try {
            node.packageName?.toString()
        } catch (_: Throwable) {
            null
        }

    private fun safeText(node: AccessibilityNodeInfo): String =
        try {
            node.text?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safeContentDescription(node: AccessibilityNodeInfo): String =
        try {
            node.contentDescription?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun safeClassName(node: AccessibilityNodeInfo): String? =
        try {
            node.className?.toString()
        } catch (_: Throwable) {
            null
        }

    private fun safeViewId(node: AccessibilityNodeInfo): String? =
        try {
            node.viewIdResourceName
        } catch (_: Throwable) {
            null
        }

    private fun safeClickable(node: AccessibilityNodeInfo): Boolean =
        try {
            node.isClickable
        } catch (_: Throwable) {
            false
        }

    private fun safeEnabled(node: AccessibilityNodeInfo): Boolean =
        try {
            node.isEnabled
        } catch (_: Throwable) {
            true
        }

    private fun safeFocused(node: AccessibilityNodeInfo): Boolean =
        try {
            node.isFocused
        } catch (_: Throwable) {
            false
        }
}
