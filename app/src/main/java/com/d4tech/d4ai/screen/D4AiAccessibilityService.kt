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

        /** True if user enabled D4AI in system Accessibility settings (even before onServiceConnected). */
        fun isEnabledInSettings(context: Context): Boolean {
            return try {
                val flat = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: return false
                val pkg = context.packageName
                flat.split(':').any { it.contains(pkg, ignoreCase = true) && it.contains("D4AiAccessibility", ignoreCase = true) } ||
                    flat.contains(pkg, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        fun ready(context: Context): Boolean = connected() || isEnabledInSettings(context)

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
            if (depth > 28) return null
            val q = label.trim().lowercase()
            val synonyms = synonymExpand(q)
            val texts = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName?.substringAfterLast('/'),
                node.hintText?.toString(),
            ).map { it.trim() }.filter { it.isNotEmpty() }
            for (t in texts) {
                val tl = t.lowercase()
                if (synonyms.any { s ->
                    tl == s ||
                        tl.replace(" ", "") == s.replace(" ", "") ||
                        tl.contains(s) ||
                        s.contains(tl) && tl.length >= 3
                }) {
                    return node
                }
            }
            // Prefer clickable matches deeper
            for (i in 0 until node.childCount) {
                findNode(node.getChild(i), label, depth + 1)?.let { return it }
            }
            return null
        }

        private fun synonymExpand(q: String): List<String> {
            val base = mutableListOf(q)
            val map = mapOf(
                "mic" to listOf("mic", "microphone", "voice", "record", "speak", "audio", "voice input", "voice search"),
                "microphone" to listOf("mic", "microphone", "voice", "record", "speak"),
                "search" to listOf("search", "query", "find", "look up", "search bar"),
                "send" to listOf("send", "submit", "paper plane", "reply"),
                "back" to listOf("back", "navigate up", "close"),
                "home" to listOf("home", "launcher"),
                "menu" to listOf("menu", "more options", "more", "overflow"),
                "camera" to listOf("camera", "photo", "take picture", "shutter"),
                "gallery" to listOf("gallery", "photos", "images"),
                "call" to listOf("call", "dial", "phone"),
                "attach" to listOf("attach", "attachment", "paperclip", "clip"),
                "emoji" to listOf("emoji", "sticker", "gif"),
                "close" to listOf("close", "cancel", "dismiss", "x"),
                "play" to listOf("play", "start", "resume"),
                "pause" to listOf("pause", "stop"),
                "settings" to listOf("settings", "preferences", "gear"),
            )
            for ((k, vs) in map) {
                if (q == k || q.contains(k) || k.contains(q)) base.addAll(vs)
            }
            return base.distinct()
        }

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


        /** Types into the focused editable field (or first EditText found). */
        fun typeText(text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null || !target.isEditable) {
                target = findEditable(root, 0)
            }
            if (target == null) return false
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 25) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                findEditable(node.getChild(i), depth + 1)?.let { return it }
            }
            return null
        }

        /** Tap absolute screen coordinates. */
        fun tapCoords(x: Float, y: Float, done: (Boolean) -> Unit) {
            val svc = instance ?: run { done(false); return }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            svc.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = done(true)
                override fun onCancelled(g: GestureDescription?) = done(false)
            }, Handler(Looper.getMainLooper()))
        }

        fun openAccessibilitySettings(context: Context): String {
            return try {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Open Accessibility settings and turn on D4AI, then try again."
            } catch (_: Exception) {
                "Open Settings → Accessibility and enable D4AI."
            }
        }

        /** Convenience for callers that only have a Context. */
        fun isConnectedStatic(context: Context): Boolean = connected()
    }

}
