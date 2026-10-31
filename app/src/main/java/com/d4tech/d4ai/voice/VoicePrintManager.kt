package com.d4tech.d4ai.voice

import android.content.Context
import java.io.File

/**
 * Stores voice enrollment samples under filesDir/voiceprints/
 * With Picovoice Eagle, replace [isLikelyOwner] with real speaker verification.
 * Without Eagle: enrollment marks "owner enrolled" and we still require wake phrase
 * (speaker ID is best-effort).
 */
class VoicePrintManager(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "voiceprints").also { if (!it.exists()) it.mkdirs() }

    fun isEnrolled(): Boolean = File(dir, "enrolled.flag").exists()

    fun markEnrolled() {
        File(dir, "enrolled.flag").writeText(System.currentTimeMillis().toString())
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun saveSample(index: Int, pcmOrText: String) {
        File(dir, "sample_$index.txt").writeText(pcmOrText)
    }

    /** Without Eagle SDK we accept any speaker after enrollment + wake phrase. */
    fun isLikelyOwner(@Suppress("UNUSED_PARAMETER") heard: String): Boolean {
        if (!isEnrolled()) return true // not enrolled → don't block (first-time UX)
        // TODO: Eagle profile.compare(heard) > 0.8
        return true
    }
}
