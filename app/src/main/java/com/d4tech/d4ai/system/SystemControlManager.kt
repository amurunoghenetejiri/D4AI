package com.d4tech.d4ai.system

import com.d4tech.d4ai.screen.D4AiAccessibilityService
import com.d4tech.d4ai.appcontrol.AppMemoryManager

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * PHASE 1–2 real device controls. Privileged toggles fall back to Settings panels
 * when the OS blocks silent change (Android 10+).
 */
class SystemControlManager(private val context: Context) {

    private var torchOn = false
    private val main = Handler(Looper.getMainLooper())

    // -------- GROUP 1 BATTERY --------

    fun getBatteryPercent(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryStatus()
        val charge = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "unknown"
        }
        val extras = lastBatteryIntent()
        val temp = extras?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let {
            if (it > 0) " Temperature ${(it / 10f)}°C." else ""
        } ?: ""
        val voltage = extras?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.let {
            if (it > 0) " ${it} mV." else ""
        } ?: ""
        return "Battery is $pct percent and $charge.$temp$voltage"
    }

    fun getBatteryTimeLeft(): String {
        if (Build.VERSION.SDK_INT < 28) {
            return getBatteryPercent() + " Time remaining is not available on this Android version."
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val micros = bm.computeChargeTimeRemaining()
        if (micros < 0) return getBatteryPercent() + " Time remaining is not available right now."
        val mins = (micros / 1_000_000L / 60L).toInt()
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "About $h hours and $m minutes left while charging."
        else "About $m minutes left while charging."
    }

    fun setBatterySaver(enabled: Boolean): String {
        // Cannot flip saver silently on most devices → open settings
        return try {
            val i = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            if (enabled) "Opening battery saver so you can turn it on."
            else "Opening battery saver so you can turn it off."
        } catch (_: Exception) {
            openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Opening battery settings.")
        }
    }

    fun batteryLowSave(): String {
        setBrightness(20)
        setBatterySaver(true)
        return "I set brightness to 20 percent and opened battery saver. Confirm saver on the next screen if needed."
    }

    private fun batteryStatus(): Int {
        val i = lastBatteryIntent() ?: return BatteryManager.BATTERY_STATUS_UNKNOWN
        return i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    }

    private fun lastBatteryIntent(): Intent? {
        return try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
    }

    // -------- GROUP 3 DISPLAY & SOUND --------

    fun setBrightness(percent: Int): String {
        val p = percent.coerceIn(0, 100)
        return try {
            if (!Settings.System.canWrite(context)) {
                val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                return "Allow D4AI to change system settings, then ask again for brightness $p percent."
            }
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val v = (p / 100f * 255).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "Brightness set to $p percent."
        } catch (e: Exception) {
            "Couldn't set brightness: ${e.message}"
        }
    }

    fun bumpBrightness(delta: Int): String {
        return try {
            if (!Settings.System.canWrite(context)) {
                return setBrightness(50) // triggers permission flow
            }
            val cur = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            )
            val next = (cur + (delta / 100f * 255)).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
            val pct = (next / 255f * 100).roundToInt()
            "Brightness is now about $pct percent."
        } catch (e: Exception) {
            "Couldn't change brightness: ${e.message}"
        }
    }

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight on this device."
            cm.setTorchMode(id, enabled)
            torchOn = enabled
            if (enabled) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Couldn't control flashlight: ${e.message}"
        }
    }

    fun blinkFlashlightSOS(): String {
        Thread {
            try {
                // SOS: ... --- ...
                val pattern = listOf(
                    200, 200, 200, 200, 200, 600, // S
                    600, 200, 600, 200, 600, 600, // O
                    200, 200, 200, 200, 200, 600, // S
                )
                var on = true
                for (ms in pattern) {
                    setFlashlight(on)
                    Thread.sleep(ms.toLong())
                    on = !on
                }
                setFlashlight(false)
            } catch (_: Exception) {
            }
        }.start()
        return "Blinking flashlight SOS."
    }

    fun setRingerMode(mode: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when (mode.lowercase()) {
                "silent" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    "Phone is on silent."
                }
                "vibrate" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "Phone is on vibrate."
                }
                else -> {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "Phone is on normal ring."
                }
            }
        } catch (e: Exception) {
            "Couldn't change ringer: ${e.message}"
        }
    }

    fun setVolume(stream: String, level0to100: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val type = when (stream.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(type)
        val v = (max * (level0to100.coerceIn(0, 100) / 100f)).roundToInt().coerceIn(0, max)
        am.setStreamVolume(type, v, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $level0to100 percent."
    }

    fun adjustVolume(steps: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        return if (steps > 0) "Volume up." else "Volume down."
    }

    fun setDarkMode(enabled: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val ui = context.getSystemService(UiModeManager::class.java)
                ui?.setApplicationNightMode(
                    if (enabled) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO,
                )
                // App-level only; for system dark mode open display settings
            }
            openSettings(Settings.ACTION_DISPLAY_SETTINGS, if (enabled) {
                "Opening display settings — turn on Dark theme if you want it system-wide."
            } else {
                "Opening display settings — turn off Dark theme if you want light mode."
            })
        } catch (e: Exception) {
            "Couldn't change dark mode: ${e.message}"
        }
    }

    // -------- GROUP 2 NETWORK (settings panels — silent toggle needs privileged API) --------

    fun openWifiPanel(): String = panelOrSettings(
        if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else null,
        Settings.ACTION_WIFI_SETTINGS,
        "Opening Wi‑Fi.",
    )

    fun openBluetoothPanel(): String =
        openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth.")

    fun openAirplanePanel(): String =
        openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opening airplane mode settings.")

    fun openDataPanel(): String =
        openSettings(Settings.ACTION_DATA_ROAMING_SETTINGS, "Opening mobile data settings.")

    fun openHotspotPanel(): String {
        return try {
            val i = Intent().setClassName(
                "com.android.settings",
                "com.android.settings.TetherSettings",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "Opening hotspot settings."
        } catch (_: Exception) {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS, "Opening wireless settings for hotspot.")
        }
    }

    // -------- GROUP 4 / 5 / 10 --------

    fun getCurrentTime(): String {
        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        return "It's ${fmt.format(java.util.Date())}."
    }

    fun getCurrentDate(): String {
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
        return "Today is ${fmt.format(java.util.Date())}."
    }

    fun getStorageLeft(): String {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            "About %.1f GB free of %.1f GB.".format(free / 1e9, total / 1e9)
        } catch (e: Exception) {
            "Couldn't read storage: ${e.message}"
        }
    }

    fun getRamUsage(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val avail = mi.availMem / 1e9
            val total = mi.totalMem / 1e9
            "About %.1f GB RAM free of %.1f GB.".format(avail, total)
        } catch (e: Exception) {
            "Couldn't read RAM: ${e.message}"
        }
    }

    fun getPhoneSpecs(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). " +
            "Device ${Build.MANUFACTURER} ${Build.MODEL}."
    }

    fun readClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (t.isBlank()) "Clipboard is empty." else "Clipboard says: ${t.take(200)}"
        } catch (e: Exception) {
            "Couldn't read clipboard: ${e.message}"
        }
    }

    fun clearClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            "Clipboard cleared."
        } catch (e: Exception) {
            "Couldn't clear clipboard: ${e.message}"
        }
    }

    fun goHome(): String {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return "Home screen."
    }

    fun openApp(name: String): String {
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
            "calculator" to listOf("com.android.calculator2", "com.google.android.calculator"),
            "gallery" to listOf("com.android.gallery3d", "com.google.android.apps.photos"),
            "messages" to listOf("com.android.mms", "com.google.android.apps.messaging"),
            "clock" to listOf("com.android.deskclock", "com.google.android.deskclock"),
            "play store" to listOf("com.android.vending"),
            "instagram" to listOf("com.instagram.android"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "spotify" to listOf("com.spotify.music"),
            "facebook" to listOf("com.facebook.katana"),
        )
        aliases[n]?.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return "Opening $name."
            }
        }
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
        if (hit != null) {
            pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return "Opening ${hit.loadLabel(pm)}."
            }
        }
        return "I couldn't find the app $name."
    }

    fun webSearch(query: String): String {
        return try {
            val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Searching for $query."
        } catch (e: Exception) {
            "Couldn't open search: ${e.message}"
        }
    }

    fun takeScreenshotHint(): String {
        // Full silent screenshot needs MediaProjection or accessibility API 30+
        return try {
            // Open recents tip — accessibility path handled elsewhere
            "Use the system screenshot gesture, or enable D4AI Accessibility for assisted capture."
        } catch (e: Exception) {
            e.message ?: "Screenshot not available."
        }
    }

    fun findMyPhoneRing(): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, max, 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ring = RingtoneManager.getRingtone(context, uri)
            ring?.play()
            main.postDelayed({ try { ring?.stop() } catch (_: Exception) {} }, 8000)
            "Ringing your phone."
        } catch (e: Exception) {
            "Couldn't ring: ${e.message}"
        }
    }

    fun openSettingsPage(page: String): String {
        val action = when (page.lowercase()) {
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "privacy" -> if (Build.VERSION.SDK_INT >= 28) Settings.ACTION_PRIVACY_SETTINGS else Settings.ACTION_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return openSettings(action, "Opening $page settings.")
    }

    // ---- helpers ----

    private fun openSettings(action: String, msg: String): String {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            msg
        } catch (e: Exception) {
            "Couldn't open settings: ${e.message}"
        }
    }

    private fun panelOrSettings(panel: String?, fallback: String, msg: String): String {
        return try {
            if (panel != null && Build.VERSION.SDK_INT >= 29) {
                context.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                msg
            } else {
                openSettings(fallback, msg)
            }
        } catch (_: Exception) {
            openSettings(fallback, msg)
        }
    }



    // -------- SCREEN SEE + TOUCH (Accessibility) --------

    fun readMyScreen(): String {
        if (!D4AiAccessibilityService.connected()) {
            return D4AiAccessibilityService.openAccessibilitySettings(context)
        }
        return D4AiAccessibilityService.readScreen()
            ?: "I can see the screen service is on but nothing readable right now."
    }

    fun tapOnScreen(label: String): String {
        if (!D4AiAccessibilityService.connected()) {
            return D4AiAccessibilityService.openAccessibilitySettings(context)
        }
        var ok = false
        val lock = Object()
        D4AiAccessibilityService.tap(label) { success ->
            ok = success
            synchronized(lock) { lock.notifyAll() }
        }
        try {
            synchronized(lock) { lock.wait(2500) }
        } catch (_: Exception) {
        }
        return if (ok) "Tapped $label." else "I couldn't find \"$label\" on the screen."
    }

    fun swipeScreen(direction: String): String {
        if (!D4AiAccessibilityService.connected()) {
            return D4AiAccessibilityService.openAccessibilitySettings(context)
        }
        val ok = D4AiAccessibilityService.swipe(direction.lowercase())
        return if (ok) "Swiped $direction." else "Swipe failed."
    }

    fun globalAction(name: String): String {
        if (!D4AiAccessibilityService.connected()) {
            return D4AiAccessibilityService.openAccessibilitySettings(context)
        }
        val ok = D4AiAccessibilityService.global(name)
        return when (ok) {
            true -> when (name) {
                "back" -> "Went back."
                "home" -> "Home screen."
                "recents" -> "Opened recent apps."
                "notifications" -> "Opened notifications."
                "screenshot" -> "Took a screenshot."
                "lock_screen" -> "Locked the phone."
                else -> "Done."
            }
            false -> "That action isn't supported on this phone."
            null -> D4AiAccessibilityService.openAccessibilitySettings(context)
        }
    }

    fun typeOnScreen(text: String): String {
        if (!D4AiAccessibilityService.connected()) {
            return D4AiAccessibilityService.openAccessibilitySettings(context)
        }
        val ok = D4AiAccessibilityService.typeText(text)
        return if (ok) "Typed: $text" else "No text field focused. Open a field first, then ask me to type."
    }

    fun startScreenRecord(): String {
        return try {
            MediaProjectionActivity.requestRecord(context)
            "Confirm the screen capture permission. Recording will start after you allow it."
        } catch (e: Exception) {
            "Couldn't start screen record: ${e.message}"
        }
    }

    fun stopScreenRecord(): String {
        return try {
            if (!ScreenRecordService.isRecording && ScreenRecordService.lastStatus.isBlank()) {
                return "No active screen recording."
            }
            ScreenRecordService.stop(context)
            val status = ScreenRecordService.lastStatus.ifBlank { "Stopped recording." }
            status
        } catch (e: Exception) {
            "Couldn't stop recording: ${e.message}"
        }
    }

    fun takeScreenshotCapture(): String {
        return try {
            MediaProjectionActivity.requestShot(context)
            "Confirm screen capture permission for the screenshot."
        } catch (e: Exception) {
            "Couldn't start screenshot: ${e.message}"
        }
    }

    fun readNotifications(n: Int = 5): String = D4AiNotificationListener.readLast(n)

    fun clearNotifications(): String = D4AiNotificationListener.clearAll()

    fun execute(action: String, arg: String = "", arg2: String = ""): String {
        return when (action) {
            "battery_status" -> getBatteryPercent()
            "battery_time" -> getBatteryTimeLeft()
            "battery_saver_on" -> setBatterySaver(true)
            "battery_saver_off" -> setBatterySaver(false)
            "battery_low_save" -> batteryLowSave()
            "flashlight_on" -> setFlashlight(true)
            "flashlight_off" -> setFlashlight(false)
            "flashlight_sos" -> blinkFlashlightSOS()
            "brightness" -> setBrightness(arg.toIntOrNull() ?: 50)
            "brightness_up" -> bumpBrightness(15)
            "brightness_down" -> bumpBrightness(-15)
            "volume_up" -> adjustVolume(1)
            "volume_down" -> adjustVolume(-1)
            "volume_mute" -> setVolume("media", 0)
            "volume_max" -> setVolume("media", 100)
            "ringer_silent" -> setRingerMode("silent")
            "ringer_vibrate" -> setRingerMode("vibrate")
            "ringer_normal" -> setRingerMode("normal")
            "wifi_panel" -> openWifiPanel()
            "bluetooth_panel" -> openBluetoothPanel()
            "airplane_panel" -> openAirplanePanel()
            "data_panel" -> openDataPanel()
            "hotspot_panel" -> openHotspotPanel()
            "dark_mode_on" -> setDarkMode(true)
            "dark_mode_off" -> setDarkMode(false)
            "time" -> getCurrentTime()
            "date" -> getCurrentDate()
            "storage" -> getStorageLeft()
            "ram" -> getRamUsage()
            "phone_specs" -> getPhoneSpecs()
            "clipboard_read" -> readClipboard()
            "clipboard_clear" -> clearClipboard()
            "go_home" -> goHome()
            "open_app" -> openApp(arg)
            "web_search" -> webSearch(arg)
            "screenshot" -> takeScreenshotCapture()
            "screen_record_start" -> startScreenRecord()
            "screen_record_stop" -> stopScreenRecord()
            "notifications_read" -> readNotifications()
            "notifications_clear" -> clearNotifications()
            "read_screen" -> readMyScreen()
            "tap" -> tapOnScreen(arg)
            "swipe" -> swipeScreen(arg.ifBlank { "up" })
            "type_text" -> typeOnScreen(arg)
            "global_back" -> globalAction("back")
            "global_home" -> globalAction("home")
            "global_recents" -> globalAction("recents")
            "global_notifications" -> globalAction("notifications")
            "global_lock" -> globalAction("lock_screen")
            "global_screenshot" -> globalAction("screenshot")
            "remember" -> {
                AppMemoryManager(context).rememberStatement(arg.lowercase(), arg2)
                "Okay, I'll remember ${arg} is ${arg2}."
            }
            "find_phone" -> findMyPhoneRing()
            "open_settings" -> openSettingsPage(arg.ifBlank { "settings" })
            "routine_morning" -> Routines(context).goodMorning()
            "routine_night" -> Routines(context).goodNight()
            "routine_driving" -> Routines(context).drivingMode()
            "where_am_i" -> LocationHelper(context).whereAmI()
            "navigate" -> LocationHelper(context).navigateTo(arg)
            else -> "I don't know how to run $action yet."
        }
    }
}
