package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class InstagramAdapter(private val control: AppControlManager) {
    fun open() = control.executeNatural("open instagram")
}
