package com.d4tech.d4ai.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Screen control for D4AI: lets the assistant see what is on the screen
 * ("read my screen"), tap anything on it ("press 2 times 2 =" in the
 * calculator), and perform system actions (back, home, recents, screenshots,
 * lock screen, app drawer) — all driven by voice, like a human using the
 * phone. Must be switched on once by the user in Accessibility settings.
 */
class D4AiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {

        @Volatile
        var instance: D4AiAccessibilityService? = null
            private set

        fun connected(): Boolean = instance != null

        // ---------- see the screen ----------

        /** Reads the visible text of the active window, most useful lines first. */
        fun readScreen(): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val pkg = root.packageName?.toString().orEmpty()
            val appLabel = try {
                val pm = svc.applicationContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            val lines = LinkedHashSet<String>()
            collectText(root, lines, 0)
            root.recycleSafe()
            if (lines.isEmpty()) return "I'm on $appLabel but couldn't read anything on the screen yet."
            val body = lines.take(24).joinToString(" · ")
            return "On $appLabel I can see: $body"
        }

        private fun collectText(node: AccessibilityNodeInfo?, out: LinkedHashSet<String>, depth: Int) {
            if (node == null || depth > 25 || out.size > 60) return
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }?.let { out.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }?.let { out.add(it) }
            for (i in 0 until node.childCount) {
                collectText(node.getChild(i), out, depth + 1)
            }
        }

        private fun AccessibilityNodeInfo.recycleSafe() {
            // recycle() is deprecated and a no-op on modern Android; nothing to do.
        }

        // ---------- tap the screen ----------

        /** Finds the first tappable/visible node matching label and taps its center. */
        fun tap(label: String, done: (Boolean) -> Unit) {
            val svc = instance ?: run { done(false); return }
            val root = svc.rootInActiveWindow
            val node = root?.let { findNode(it, label, 0) }
            if (node == null) {
                done(false)
                return
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) {
                done(false)
                return
            }
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            svc.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    done(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    done(false)
                }
            }, Handler(Looper.getMainLooper()))
        }

        /**
         * Taps a sequence of labels (e.g. 2, ×, 2, =) one by one; when done it
         * reports which keys landed. 550ms between taps keeps UIs responsive.
         */
        fun tapSequence(labels: List<String>, onResult: (String) -> Unit) {
            val svc = instance
            if (svc == null) {
                onResult("")
                return
            }
            val results = ArrayList<String>(labels.size)
            fun step(i: Int) {
                if (i >= labels.size) {
                    onResult(results.joinToString(", "))
                    return
                }
                tap(labels[i]) { ok ->
                    results.add(if (ok) labels[i] else "missed ${labels[i]}")
                    Handler(Looper.getMainLooper()).postDelayed({ step(i + 1) }, 550)
                }
            }
            step(0)
        }

        private fun findNode(node: AccessibilityNodeInfo, label: String, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            val texts = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
            ).map { it.trim() }
            for (t in texts) {
                if (t.equals(label, ignoreCase = true) ||
                    t.replace(" ", "") == label.replace(" ", "") ||
                    (label.length >= 2 && t.startsWith(label, ignoreCase = true) && t.length <= label.length + 2)
                ) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                findNode(node.getChild(i), label, depth + 1)?.let { return it }
            }
            return null
        }

        // ---------- gestures ----------

        fun swipe(direction: String): Boolean {
            val svc = instance ?: return false
            val w = svc.rootInActiveWindow?.let { r ->
                val rect = Rect(); r.getBoundsInScreen(rect); rect.width()
            } ?: svc.resources.displayMetrics.widthPixels
            val h = svc.resources.displayMetrics.heightPixels
            val path = Path()
            val midX = w / 2f
            when (direction) {
                "up" -> {
                    path.moveTo(midX, h * 0.75f); path.lineTo(midX, h * 0.25f)
                }
                "down" -> {
                    path.moveTo(midX, h * 0.25f); path.lineTo(midX, h * 0.75f)
                }
                "left" -> {
                    path.moveTo(w * 0.8f, h * 0.5f); path.lineTo(w * 0.2f, h * 0.5f)
                }
                else -> {
                    path.moveTo(w * 0.2f, h * 0.5f); path.lineTo(w * 0.8f, h * 0.5f)
                }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }

        // ---------- system actions ----------

        /** Runs a named global action; returns null when unsupported or offline. */
        fun global(name: String): Boolean? {
            val svc = instance ?: return null
            val action = when (name) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                "lock_screen" ->
                    if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
                "screenshot" ->
                    if (android.os.Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
                "all_apps" ->
                    if (android.os.Build.VERSION.SDK_INT >= 30) GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS else return false
                else -> return false
            }
            return try {
                svc.performGlobalAction(action)
            } catch (_: Exception) {
                false
            }
        }

        /** Convenience for callers that only have a Context. */
        fun isConnectedStatic(context: Context): Boolean = connected()
    }

}
