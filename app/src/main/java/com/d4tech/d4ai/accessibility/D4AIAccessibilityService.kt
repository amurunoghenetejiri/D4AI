package com.d4tech.d4ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional user-enabled UI automation bridge.
 * D4AI never enables this service itself; the device owner must enable it in Settings.
 */
class D4AIAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen content is read only when D4AI explicitly requests an operation.
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun visibleText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        collect(root, result)
        return result.distinct()
    }

    private fun collect(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(output::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child, output)
                child.recycle()
            }
        }
    }

    fun tapByText(value: String, partial: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findText(root, value, partial) ?: return false
        val clicked = clickNodeOrParent(node)
        node.recycle()
        return clicked
    }

    private fun findText(node: AccessibilityNodeInfo, value: String, partial: Boolean): AccessibilityNodeInfo? {
        val candidates = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        val match = candidates.any {
            if (partial) it.contains(value, ignoreCase = true) else it.equals(value, ignoreCase = true)
        }
        if (match) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findText(child, value, partial)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        repeat(6) {
            val item = current ?: return false
            if (item.isClickable) {
                val ok = item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                item.recycle()
                return ok
            }
            val parent = item.parent
            item.recycle()
            current = parent
        }
        current?.recycle()
        return false
    }

    fun tapByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val node = nodes.firstOrNull() ?: return false
        val result = clickNodeOrParent(node)
        nodes.drop(1).forEach { it.recycle() }
        return result
    }

    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        return result
    }

    fun scrollForward(): Boolean = performScroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollBackward(): Boolean = performScroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun performScroll(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollable(root) ?: return false
        val result = node.performAction(action)
        node.recycle()
        return result
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        @Volatile var instance: D4AIAccessibilityService? = null
            private set
    }
}
