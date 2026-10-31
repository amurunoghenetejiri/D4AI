package com.d4tech.d4ai.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlin.math.roundToInt

sealed class RouteResult {
    data class Spoke(val message: String) : RouteResult()
    data class NeedConfirm(val action: PendingAction, val prompt: String) : RouteResult()
    data object NotCommand : RouteResult()
}

data class PendingAction(val type: String, val arg: String = "")

/**
 * Offline-capable phone commands. Network is only needed for general chat.
 */
class CommandRouter(private val context: Context) {

    private var pending: PendingAction? = null
    private var torchOn = false

    fun handle(raw: String): RouteResult {
        val text = raw.trim()
        val lower = text.lowercase().trim()

        pending?.let { p ->
            if (lower in YES) {
                val r = execute(p)
                pending = null
                return RouteResult.Spoke(r)
            }
            if (lower in NO) {
                pending = null
                return RouteResult.Spoke("Okay, cancelled.")
            }
        }

        if (lower in listOf("sleep", "go to sleep", "stop listening", "sleep d4ai", "d4ai sleep") ||
            lower.startsWith("sleep ")
        ) {
            return RouteResult.Spoke("__SLEEP__")
        }

        // Open / launch / start app
        Regex("^(open|launch|start|run)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return RouteResult.Spoke(openApp(m.groupValues[2].trim()))
        }
        Regex("^open\\s+(.+?)\\s+app$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return RouteResult.Spoke(openApp(m.groupValues[1].trim()))
        }

        // Call / dial
        Regex("^(call|dial)\\s+([0-9+\\s\\-()]+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val num = m.groupValues[2].replace(Regex("[^0-9+]"), "")
            pending = PendingAction("call", num)
            return RouteResult.NeedConfirm(pending!!, "Call $num? Say yes or no.")
        }

        when {
            lower == "mute" || lower.contains("mute volume") || lower == "silence" -> {
                setVolume(0f); return RouteResult.Spoke("Muted.")
            }
            lower.contains("unmute") || lower == "volume on" -> {
                setVolume(0.5f); return RouteResult.Spoke("Unmuted.")
            }
            lower.contains("volume up") || lower == "louder" -> {
                adjustVolume(1); return RouteResult.Spoke("Volume up.")
            }
            lower.contains("volume down") || lower == "quieter" -> {
                adjustVolume(-1); return RouteResult.Spoke("Volume down.")
            }
            lower.contains("max volume") || lower.contains("volume maximum") -> {
                setVolume(1f); return RouteResult.Spoke("Volume at maximum.")
            }
            Regex("(?:set\\s+)?(?:volume|brightness)\\s+(?:to\\s+)?(\\d{1,3})\\s*%?").find(lower)?.let { m ->
                val n = m.groupValues[1].toInt().coerceIn(0, 100)
                return if (lower.contains("bright")) {
                    setBrightness(n / 100f)
                    RouteResult.Spoke("Brightness set to $n percent.")
                } else {
                    setVolume(n / 100f)
                    RouteResult.Spoke("Volume set to $n percent.")
                }
            } ?: false -> {}
        }

        when {
            lower.contains("flashlight on") || lower.contains("torch on") || lower == "flashlight" -> {
                setTorch(true); return RouteResult.Spoke("Flashlight on.")
            }
            lower.contains("flashlight off") || lower.contains("torch off") || lower.contains("light off") -> {
                setTorch(false); return RouteResult.Spoke("Flashlight off.")
            }
            lower.contains("toggle flashlight") || lower.contains("toggle torch") -> {
                setTorch(!torchOn); return RouteResult.Spoke(if (torchOn) "Flashlight on." else "Flashlight off.")
            }
        }

        if (lower.contains("settings") && (lower.contains("open") || lower.startsWith("settings"))) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return RouteResult.Spoke("Opening settings.")
            } catch (_: Exception) {
            }
        }

        if (lower.contains("what can you do") || lower == "help" || lower == "commands") {
            return RouteResult.Spoke(
                "I can open apps, call numbers, change volume and brightness, " +
                    "toggle flashlight, and answer questions. Say sleep to stop listening.",
            )
        }

        // Pure command verbs without match → still try open as last resort
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            return RouteResult.Spoke(openApp(text.substringAfter(' ').trim()))
        }

        return RouteResult.NotCommand
    }

    private fun execute(p: PendingAction): String {
        return when (p.type) {
            "call" -> {
                try {
                    val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${p.arg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    "Calling ${p.arg}."
                } catch (_: SecurityException) {
                    val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.arg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    "Opening dialer for ${p.arg}."
                } catch (e: Exception) {
                    "Could not call: ${e.message}"
                }
            }
            else -> "Done."
        }
    }

    /**
     * Robust launcher lookup — fuzzy match label, package, and common aliases.
     * Works offline. Needs QUERY_ALL_PACKAGES / queries on Android 11+.
     */
    private fun openApp(name: String): String {
        val q = name.lowercase().trim()
            .removeSuffix(" app").removeSuffix(" application").trim()
        if (q.isEmpty()) return "Which app should I open?"

        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        val list = try {
            pm.queryIntentActivities(main, flags)
        } catch (_: Exception) {
            pm.queryIntentActivities(main, 0)
        }

        data class Cand(val label: String, val pkg: String, val cls: String, val score: Int)

        val aliasPkg = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            "chrome" to listOf("com.android.chrome", "com.chrome.beta"),
            "youtube" to listOf("com.google.android.youtube"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "twitter" to listOf("com.twitter.android"),
            "x" to listOf("com.twitter.android"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "settings" to listOf("com.android.settings"),
            "phone" to listOf("com.android.dialer", "com.google.android.dialer"),
            "messages" to listOf("com.android.mms", "com.google.android.apps.messaging"),
            "sms" to listOf("com.android.mms", "com.google.android.apps.messaging"),
            "gallery" to listOf("com.android.gallery3d", "com.google.android.apps.photos"),
            "photos" to listOf("com.google.android.apps.photos"),
            "play store" to listOf("com.android.vending"),
            "store" to listOf("com.android.vending"),
            "calculator" to listOf("com.android.calculator2", "com.google.android.calculator"),
            "clock" to listOf("com.android.deskclock", "com.google.android.deskclock"),
            "files" to listOf("com.android.documentsui", "com.google.android.apps.nbu.files"),
            "file manager" to listOf("com.android.documentsui"),
            "browser" to listOf("com.android.chrome", "com.android.browser"),
            "spotify" to listOf("com.spotify.music"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "snapchat" to listOf("com.snapchat.android"),
            "netflix" to listOf("com.netflix.mediaclient"),
            "capcut" to listOf("com.lemon.lvoverseas", "com.lemon.lv"),
        )

        // 1) Direct package alias
        aliasPkg[q]?.forEach { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "Opening $name."
            }
        }

        val cands = mutableListOf<Cand>()
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val ll = label.lowercase()
            val pkg = ri.activityInfo.packageName
            val cls = ri.activityInfo.name
            var score = 0
            when {
                ll == q -> score = 100
                ll.startsWith(q) -> score = 90
                ll.contains(q) -> score = 80
                q.contains(ll) && ll.length >= 3 -> score = 70
                pkg.lowercase().contains(q.replace(" ", "")) -> score = 60
                else -> {
                    // token overlap
                    val tokens = q.split(Regex("\\s+"))
                    if (tokens.any { it.length >= 3 && ll.contains(it) }) score = 50
                }
            }
            // alias label hit
            aliasPkg.forEach { (alias, pkgs) ->
                if ((q == alias || q.contains(alias) || alias.contains(q)) && pkg in pkgs) {
                    score = maxOf(score, 95)
                }
            }
            if (score > 0) cands.add(Cand(label, pkg, cls, score))
        }

        val best = cands.maxByOrNull { it.score }
        if (best != null && best.score >= 50) {
            return try {
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(best.pkg, best.cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                "Opening ${best.label}."
            } catch (_: Exception) {
                val launch = pm.getLaunchIntentForPackage(best.pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    "Opening ${best.label}."
                } else {
                    "I found ${best.label} but couldn't open it."
                }
            }
        }

        // Fallback: try package substring search across installed packages
        try {
            val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in pkgs) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label == q || label.contains(q) || q.contains(label)) {
                    val launch = pm.getLaunchIntentForPackage(app.packageName) ?: continue
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return "Opening ${pm.getApplicationLabel(app)}."
                }
            }
        } catch (_: Exception) {
        }

        return "App not found: $name. Say the name exactly as on your home screen."
    }

    private fun setVolume(fraction: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (max * fraction).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    private fun adjustVolume(steps: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        }
    }

    private fun setBrightness(fraction: Float) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (fraction * 255).roundToInt().coerceIn(1, 255),
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
        }
    }

    companion object {
        private val YES = listOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "do it")
        private val NO = listOf("no", "nope", "cancel", "stop", "don't", "do not")
    }
}
