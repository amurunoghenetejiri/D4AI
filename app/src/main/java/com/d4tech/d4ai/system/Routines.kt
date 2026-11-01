package com.d4tech.d4ai.system

import android.content.Context

/** Simple voice routines (good morning / night / driving). */
class Routines(private val context: Context) {
    private val sys = SystemControlManager(context)

    fun goodMorning(): String {
        val parts = listOf(
            sys.getCurrentTime(),
            sys.getBatteryPercent(),
            sys.setRingerMode("normal"),
            "Have a great day.",
        )
        return parts.joinToString(" ")
    }

    fun goodNight(): String {
        val parts = listOf(
            sys.setRingerMode("vibrate"),
            sys.setBrightness(15),
            sys.getBatteryPercent(),
            "Good night. Sleep well.",
        )
        return parts.joinToString(" ")
    }

    fun drivingMode(): String {
        return sys.setRingerMode("vibrate") + " Driving mode: vibrate on. Stay safe."
    }
}
