package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

/** Fallback for any installed app by name. */
class GenericAdapter(private val control: AppControlManager) {
    fun open(name: String) = control.executeNatural("open $name")
    fun openAndRead(name: String) = control.executeNatural("open $name and read screen")
}
