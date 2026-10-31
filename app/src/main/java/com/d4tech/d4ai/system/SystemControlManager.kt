package com.d4tech.d4ai.system

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.text.DateFormat
import java.util.Date

/**
 * Safe, user-visible Android system queries and controls available to a normal app.
 * Restricted controls are deliberately opened in Android Settings instead of bypassed.
 */
class SystemControlManager(private val context: Context) {
    fun batterySummary(): String {
        val battery = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return "I couldn't read the battery status."

        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return "I couldn't read the battery percentage."

        val percent = (level * 100 / scale).coerceIn(0, 100)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "fully charged"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "status unavailable"
        }
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> " by USB"
            BatteryManager.BATTERY_PLUGGED_AC -> " by AC power"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " wirelessly"
            else -> ""
        }
        return "Your battery is at $percent percent and is $state$source."
    }

    fun currentTime(): String =
        "The time is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

    fun currentDate(): String =
        "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = camera.cameraIdList.firstOrNull { cameraId ->
                camera.getCameraCharacteristics(cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This device does not report an available camera flash."
            camera.setTorchMode(id, enabled)
            if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (_: SecurityException) {
            "Android denied flashlight access. Check D4AI's camera permission."
        } catch (e: Exception) {
            "I couldn't change the flashlight: ${e.message ?: "device error"}."
        }
    }

    fun openMobileDataSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opened your mobile network settings. Android requires you to change mobile data there."
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Opened network settings. Please change mobile data there."
            } catch (_: Exception) {
                "I couldn't open network settings on this device."
            }
        }
    }

    fun openBatterySettings(): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opened battery saver settings. You can review and enable battery saver there."
        } catch (_: Exception) {
            "I couldn't open battery saver settings."
        }
    }
}
