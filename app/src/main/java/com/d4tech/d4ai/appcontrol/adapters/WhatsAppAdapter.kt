package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class WhatsAppAdapter(private val control: AppControlManager) {
    fun open() = control.executeNatural("open whatsapp")
    fun send(to: String, message: String) =
        control.executeNatural("send $message to $to on whatsapp")
    fun readScreen() = control.readMessagesStyle()
}
