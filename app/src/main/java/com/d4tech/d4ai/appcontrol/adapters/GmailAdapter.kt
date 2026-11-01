package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class GmailAdapter(private val control: AppControlManager) {
    fun openAndRead() = control.executeNatural("open gmail and read screen")
}
