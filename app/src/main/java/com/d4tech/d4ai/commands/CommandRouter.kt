package com.d4tech.d4ai.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.hardware.camera2.CameraManager
import kotlin.math.roundToInt

sealed class RouteResult {
    data class Spoke(val message: String) : RouteResult()
    data class NeedConfirm(val action: PendingAction, val prompt: String) : RouteResult()
    data object NotCommand : RouteResult()
}

data class PendingAction(
    val type: String,
    val arg: String = "",
)

/**
 * Separates phone commands from normal questions.
 */
class CommandRouter(private val context: Context) {

    private var pending: PendingAction? = null

    fun handle(raw: String): RouteResult {
        val text = raw.trim()
        val lower = text.lowercase()

        // Confirmation flow
        pending?.let { p ->
            if (lower in listOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm")) {
                val r = execute(p)
                pending = null
                return RouteResult.Spoke(r)
            }
            if (lower in listOf("no", "nope", "cancel", "stop")) {
                pending = null
                return RouteResult.Spoke("Okay, cancelled.")
            }
        }

        // Sleep
        if (lower == "sleep" || lower.startsWith("sleep d4") || lower.contains("go to sleep") ||
            lower == "stop listening"
        ) {
            return RouteResult.Spoke("__SLEEP__")
        }

        // Open app
        val openMatch = Regex("^(open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (openMatch != null) {
            val appName = openMatch.groupValues[2].trim()
            return RouteResult.Spoke(openApp(appName))
        }

        // Call
        val callMatch = Regex("^(call|dial)\\s+([0-9+\\s]+)$", RegexOption.IGNORE_CASE).find(text)
        if (callMatch != null) {
            val num = callMatch.groupValues[2].replace(" ", "")
            pending = PendingAction("call", num)
            return RouteResult.NeedConfirm(pending!!, "Call $num? Say yes or no.")
        }

        // Volume
        when {
            lower.contains("mute") -> {
                setVolume(0f); return RouteResult.Spoke("Muted.")
            }
            lower.contains("volume up") || lower == "louder" -> {
                adjustVolume(1); return RouteResult.Spoke("Volume up.")
            }
            lower.contains("volume down") || lower == "quieter" -> {
                adjustVolume(-1); return RouteResult.Spoke("Volume down.")
            }
            lower.contains("max volume") -> {
                setVolume(1f); return RouteResult.Spoke("Volume max.")
            }
        }

        // Brightness
        val bright = Regex("(?:set\\s+)?brightness\\s+(?:to\\s+)?(\\d{1,3})\\s*%?", RegexOption.IGNORE_CASE).find(text)
        if (bright != null) {
            val pct = bright.groupValues[1].toInt().coerceIn(0, 100)
            return RouteResult.Spoke(setBrightness(pct))
        }

        // Flashlight
        if (lower.contains("flashlight on") || lower.contains("torch on")) {
            torch(true); return RouteResult.Spoke("Flashlight on.")
        }
        if (lower.contains("flashlight off") || lower.contains("torch off")) {
            torch(false); return RouteResult.Spoke("Flashlight off.")
        }

        // Camera
        if (lower.contains("open camera") || lower == "camera") {
            return RouteResult.Spoke(openApp("camera"))
        }

        // Settings shortcuts
        if (lower.contains("mobile data") && (lower.contains("settings") || lower.contains("open"))) {
            context.startActivity(
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return RouteResult.Spoke("Opened mobile data settings.")
        }

        return RouteResult.NotCommand
    }

    private fun execute(action: PendingAction): String {
        return when (action.type) {
            "call" -> {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${action.arg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(i)
                    "Calling ${action.arg}."
                } catch (_: SecurityException) {
                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.arg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(dial)
                    "Opening dialer for ${action.arg}."
                }
            }
            else -> "Done."
        }
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        val n = name.lowercase().trim()
        val aliases = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "chrome" to listOf("com.android.chrome"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "settings" to listOf("com.android.settings"),
            "youtube" to listOf("com.google.android.youtube"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "phone" to listOf("com.android.dialer", "com.google.android.dialer"),
        )
        val pkgs = aliases[n]
        if (pkgs != null) {
            for (pkg in pkgs) {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return "Opening $name."
                }
            }
        }
        // Search installed apps by label
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        val hit = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(n)
        }
        if (hit != null) {
            val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "Opening ${hit.loadLabel(pm)}."
            }
        }
        return "I couldn't find the app $name."
    }

    private fun adjustVolume(steps: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun setVolume(fraction: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (max * fraction).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
    }

    private fun setBrightness(percent: Int): String {
        return try {
            val v = (percent / 100f * 255).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                v,
            )
            "Brightness set to $percent percent."
        } catch (_: Exception) {
            // Need WRITE_SETTINGS
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Allow D4AI to change system settings, then try again."
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(id, on)
        } catch (_: Exception) {
        }
    }
}
