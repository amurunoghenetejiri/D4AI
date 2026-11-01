package com.d4tech.d4ai.ai

/**
 * Structured phone action produced by the AI / classifier layer.
 * CommandRouter.runPlan executes these.
 */
data class DeviceCommand(
    val action: String,
    val arg: String = "",
    val arg2: String = "",
)
