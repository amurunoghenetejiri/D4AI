package com.d4tech.d4ai.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.d4tech.d4ai.system.SystemControlManager
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
    private val system = SystemControlManager(context)

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

        // Direct phone-state questions must use Android APIs, not the language model.
        if (lower in listOf("battery", "my battery", "battery percentage", "battery percent", "how much battery")) {
            return RouteResult.Spoke(system.batterySummary())
        }
        if (lower in listOf("time", "what time is it", "current time")) {
            return RouteResult.Spoke(system.currentTime())
        }
        if (lower in listOf("date", "today's date", "what is the date", "what's the date")) {
            return RouteResult.Spoke(system.currentDate())
        }
        if (lower in listOf("battery saver", "open battery saver", "battery settings")) {
            return RouteResult.Spoke(system.openBatterySettings())
        }

        // Open an app by explicit phrase, or a known app name spoken by itself.
        val openMatch = Regex("^(open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (openMatch != null) {
            val appName = openMatch.groupValues[2].trim()
            return RouteResult.Spoke(openApp(appName))
        }
        val appOnly = lower.removePrefix("my ").trim()
        val knownStandaloneApps = setOf(
            "whatsapp", "telegram", "chrome", "camera", "settings", "youtube",
            "gmail", "maps", "phone", "jumia", "facebook", "instagram", "tiktok",
            "spotify", "boomplay", "play store", "clock", "calculator", "gallery",
            "photos", "files", "file manager", "contacts", "messages", "messenger",
            "x", "twitter", "amazon", "konga", "jiji", "aliexpress",
        )
        if (appOnly in knownStandaloneApps) {
            return RouteResult.Spoke(openApp(appOnly))
        }

        // Alarm: Android Clock presents the alarm for the user to review and save.
        val alarmMatch = Regex(
            "^(?:set\\s+)?alarm(?:\\s+for)?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (alarmMatch != null) {
            val hourRaw = alarmMatch.groupValues[1].toIntOrNull() ?: -1
            val minute = alarmMatch.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
            val meridiem = alarmMatch.groupValues[3].lowercase()
            if (hourRaw in 1..12 && minute in 0..59) {
                val hour = when (meridiem) {
                    "am" -> if (hourRaw == 12) 0 else hourRaw
                    "pm" -> if (hourRaw == 12) 12 else hourRaw + 12
                    else -> hourRaw
                }
                return RouteResult.Spoke(setAlarm(hour, minute))
            }
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

        // Flashlight: report the actual CameraManager result.
        if (lower in listOf("flashlight", "torch", "flashlight on", "torch on", "turn on flashlight", "turn flashlight on")) {
            return RouteResult.Spoke(system.setFlashlight(true))
        }
        if (lower in listOf("flashlight off", "torch off", "turn off flashlight", "turn flashlight off")) {
            return RouteResult.Spoke(system.setFlashlight(false))
        }

        // Camera
        if (lower.contains("open camera") || lower == "camera") {
            return RouteResult.Spoke(openApp("camera"))
        }

        if (lower.contains("enable d4ai accessibility") || lower.contains("accessibility settings") || lower.contains("screen control settings")) {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                RouteResult.Spoke("Opened Accessibility settings. Find D4AI screen control and enable it when you're ready.")
            } catch (_: Exception) {
                RouteResult.Spoke("I couldn't open Accessibility settings.")
            }
        }

        // Settings shortcuts
        if (lower.contains("mobile data") && (lower.contains("settings") || lower.contains("open") || lower.contains("turn on") || lower.contains("turn off"))) {
            return RouteResult.Spoke(system.openMobileDataSettings())
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
        val n = name.lowercase()
            .replace(Regex("\\b(app|application|account)\\b"), " ")
            .trim()
        val aliases = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram" to listOf("org.telegram.messenger"),
            "chrome" to listOf("com.android.chrome"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "settings" to listOf("com.android.settings"),
            "youtube" to listOf("com.google.android.youtube"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "phone" to listOf("com.android.dialer", "com.google.android.dialer"),
        )
        val packages = aliases[n].orEmpty()
        for (pkg in packages) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                return try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    "Opening $n."
                } catch (_: Exception) {
                    "I found $n, but Android did not allow it to open."
                }
            }
        }

        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            emptyList()
        }
        val hit = apps.firstOrNull {
            it.loadLabel(pm).toString().trim().equals(n, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(n)
        }
        if (hit != null) {
            val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            if (launch != null) {
                return try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    "Opening ${hit.loadLabel(pm)}."
                } catch (_: Exception) {
                    "I found ${hit.loadLabel(pm)}, but Android did not allow it to open."
                }
            }
        }

        return try {
            val knownPackage = packages.firstOrNull()
            val store = if (knownPackage != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$knownPackage"))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(n)}"))
            }
            store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(store)
            "$n is not installed. I opened Google Play so you can install it."
        } catch (_: Exception) {
            "I couldn't find $n installed, and Google Play could not be opened. Search for $n in the Play Store."
        }
    }

    private fun setAlarm(hour: Int, minute: Int): String {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening your Clock app to set the alarm. Please save it there."
        } catch (_: Exception) {
            "I couldn't open the Clock app to set that alarm."
        }
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

}
