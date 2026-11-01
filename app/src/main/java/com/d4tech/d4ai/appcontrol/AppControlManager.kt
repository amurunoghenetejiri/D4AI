package com.d4tech.d4ai.appcontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.d4tech.d4ai.screen.D4AiAccessibilityService
import com.d4tech.d4ai.system.SystemControlManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes planned app steps with voice-friendly status strings.
 * Requires Accessibility for on-screen tap/type.
 */
class AppControlManager(private val context: Context) {

    private val sys = SystemControlManager(context)
    private val memory = AppMemoryManager(context)
    private val main = Handler(Looper.getMainLooper())

    fun getInstalledApps(): List<String> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { pm.getApplicationLabel(it).toString() }
            .sorted()
    }

    fun executeNatural(input: String): String {
        val plan = AppTaskPlanner.plan(input)
        if (plan.isEmpty()) return ""
        val log = StringBuilder()
        for (step in plan) {
            val msg = runStep(step)
            if (msg.isNotBlank()) log.append(msg).append(' ')
            Thread.sleep(700)
        }
        val result = log.toString().trim().ifBlank { "Done." }
        memory.logCommand(input, result, true)
        return result
    }

    private fun runStep(step: TaskStep): String {
        return when (step.type) {
            StepType.OPEN_APP -> {
                val r = sys.openApp(step.arg)
                Thread.sleep(1200)
                "Opening ${step.arg}. $r"
            }
            StepType.WAIT -> {
                Thread.sleep(step.arg.toLongOrNull() ?: 1000)
                ""
            }
            StepType.TAP_TEXT -> {
                if (!D4AiAccessibilityService.connected()) {
                    if (!a11yEnabled()) return a11yPrompt()
                    Thread.sleep(1000)
                    if (!D4AiAccessibilityService.connected()) {
                        return "Accessibility is on but not connected yet. Open D4AI once, then try the command again."
                    }
                }
                val ok = tapSync(step.arg)
                if (ok) "Tapped ${step.arg}." else "Couldn't find ${step.arg} on screen. Say read my screen."
            }
            StepType.TYPE -> {
                if (!ensureA11y()) return a11yPrompt()
                // Focus search if needed
                AppScreenAnalyzer.findSearchBar()?.let { bar ->
                    if (bar.text.isNotBlank() || bar.desc.isNotBlank()) {
                        tapSync(bar.text.ifBlank { bar.desc })
                        Thread.sleep(400)
                    }
                }
                val ok = D4AiAccessibilityService.typeText(step.arg)
                if (ok) "Typed ${step.arg}." else "Couldn't type."
            }
            StepType.PRESS_SEARCH -> {
                if (!ensureA11y()) return a11yPrompt()
                // Try search / enter by tapping Search or using global - limited
                if (tapSync("Search")) "Searching."
                else if (tapSync("search")) "Searching."
                else "Search submitted if keyboard did it."
            }
            StepType.SCROLL -> {
                if (!ensureA11y()) return a11yPrompt()
                D4AiAccessibilityService.swipe(step.arg.ifBlank { "down" })
                "Scrolling."
            }
            StepType.SWIPE -> {
                if (!ensureA11y()) return a11yPrompt()
                D4AiAccessibilityService.swipe(step.arg.ifBlank { "up" })
                "Swiped."
            }
            StepType.FIND_CHEAPEST_TAP -> {
                if (!ensureA11y()) return a11yPrompt()
                val cheap = AppScreenAnalyzer.cheapestPrice()
                if (cheap == null) {
                    D4AiAccessibilityService.swipe("up")
                    Thread.sleep(800)
                    val c2 = AppScreenAnalyzer.cheapestPrice()
                    if (c2 != null) {
                        return "Cheapest about ${c2.raw}. Opening it if visible."
                    }
                    return "Couldn't parse prices on screen."
                }
                // Tap text containing price amount
                val label = cheap.raw
                val ok = tapSync(label) || tapSync(cheap.amount.toInt().toString())
                "Cheapest looks like ${cheap.raw}. ${if (ok) "Tapped." else "See it on screen."}"
            }
            StepType.TAP_ADD_CART -> {
                if (!ensureA11y()) return a11yPrompt()
                val btn = AppScreenAnalyzer.findAddToCart()
                if (btn != null) {
                    val label = btn.text.ifBlank { btn.desc }
                    val ok = tapSync(label)
                    if (ok) "Added to cart." else "Found add-to-cart but tap missed."
                } else {
                    D4AiAccessibilityService.swipe("up")
                    Thread.sleep(600)
                    val btn2 = AppScreenAnalyzer.findAddToCart()
                    if (btn2 != null && tapSync(btn2.text.ifBlank { btn2.desc })) "Added to cart."
                    else "Couldn't find Add to cart — try saying tap Add to cart."
                }
            }
            StepType.READ_SCREEN -> {
                if (!ensureA11y()) return a11yPrompt()
                D4AiAccessibilityService.readScreen() ?: AppScreenAnalyzer.summarizeScreen()
            }
            StepType.SPEAK -> step.arg
            StepType.GLOBAL_BACK -> {
                D4AiAccessibilityService.global("back")
                "Back."
            }
            StepType.CLOSE_POPUP -> {
                if (!ensureA11y()) return ""
                val p = AppScreenAnalyzer.findClosePopup()
                if (p != null) {
                    tapSync(p.text.ifBlank { p.desc })
                    "Closed popup."
                } else ""
            }
            StepType.WEB_SEARCH -> sys.webSearch(step.arg)
            StepType.SEND_MESSAGE -> "Use: send Hi to John on WhatsApp"
            StepType.PLAY_FIRST -> {
                if (!ensureA11y()) return a11yPrompt()
                // Tap first large result heuristically — first clickable with long text
                val node = AppScreenAnalyzer.dumpVisible()
                    .filter { it.clickable && (it.text.length > 8 || it.desc.length > 8) }
                    .drop(1) // skip search bar area
                    .firstOrNull()
                if (node != null && tapSync(node.text.ifBlank { node.desc })) "Playing first result."
                else "Search results are open — say tap on a title."
            }
        }
    }

    private fun tapSync(label: String): Boolean {
        if (label.isBlank()) return false
        // Wait briefly for accessibility service connection after user enabled it
        if (!D4AiAccessibilityService.connected()) {
            if (a11yEnabled()) {
                Thread.sleep(800)
            } else {
                return false
            }
        }
        if (!D4AiAccessibilityService.connected()) return false
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            D4AiAccessibilityService.tap(label) {
                ok = it
                latch.countDown()
            }
        }
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    private fun ensureA11y() = D4AiAccessibilityService.connected()
    private fun a11yEnabled() = D4AiAccessibilityService.ready(context)
    private fun a11yPrompt() = D4AiAccessibilityService.openAccessibilitySettings(context)

    fun readMessagesStyle(): String {
        if (!ensureA11y()) return a11yPrompt()
        return "On screen: " + (D4AiAccessibilityService.readScreen() ?: AppScreenAnalyzer.summarizeScreen())
    }
}
