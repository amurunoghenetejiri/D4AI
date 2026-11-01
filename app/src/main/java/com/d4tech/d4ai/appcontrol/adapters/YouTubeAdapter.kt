package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class YouTubeAdapter(private val control: AppControlManager) {
    fun play(query: String) = control.executeNatural("youtube play $query")
}
