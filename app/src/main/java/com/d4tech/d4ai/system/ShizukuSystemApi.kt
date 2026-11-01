package com.d4tech.d4ai.system

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Privileged actions. Without Shizuku/root we open the correct Settings page
 * and tell the user honestly — never fake success.
 */
object ShizukuSystemApi {
    fun isAvailable(): Boolean = false // wire real Shizuku later if installed

    fun wifiEnable(context: Context, on: Boolean): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "I can't flip Wi‑Fi silently without elevated access. Opened Wi‑Fi settings — please toggle it."
        } catch (e: Exception) {
            e.message ?: "Wi‑Fi settings unavailable."
        }
    }

    fun mobileDataEnable(context: Context, on: Boolean): String {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opened mobile data settings. Toggle data there (Android blocks silent change for normal apps)."
        } catch (e: Exception) {
            e.message ?: "Data settings unavailable."
        }
    }

    fun reboot(context: Context): String =
        "Restarting the phone needs system privileges. Hold the power button to restart."
}
