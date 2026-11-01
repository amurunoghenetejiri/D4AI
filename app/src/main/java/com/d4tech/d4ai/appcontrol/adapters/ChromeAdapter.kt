package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class ChromeAdapter(private val control: AppControlManager) {
    fun search(q: String) = control.executeNatural("open chrome and search $q")
}
