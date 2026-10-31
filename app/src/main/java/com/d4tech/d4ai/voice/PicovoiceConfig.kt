package com.d4tech.d4ai.voice

/**
 * Get a free AccessKey at https://console.picovoice.ai
 * Then paste it here (or set BuildConfig.PICOVOICE_ACCESS_KEY).
 *
 * When empty, D4AI uses offline keyword filtering on short STT windows
 * (wake phrases: "d4ai", "d4a", "hey d4ai") — still pauses during TTS/music.
 */
object PicovoiceConfig {
    const val ACCESS_KEY: String = "" // "YOUR_KEY_HERE"
    fun hasKey(): Boolean = ACCESS_KEY.isNotBlank()
}
