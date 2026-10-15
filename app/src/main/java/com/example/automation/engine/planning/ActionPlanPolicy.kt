package com.example.automation.engine.planning

import com.example.automation.state.ActionStep

/** Single boundary for model-authored plans. Keep this deliberately explicit. */
object ActionPlanPolicy {
    const val MAX_INITIAL_STEPS = 18
    const val MAX_REPLAN_STEPS = 12
    const val MAX_TEXT_LENGTH = 4096
    val ALLOWED_ACTION_TYPES: Set<String> = setOf(
        "OPEN_APP", "OPEN_SETTINGS", "LAUNCH_APP", "SETTINGS", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI",
        "FIND_ELEMENT", "TYPE_TEXT", "CLEAR_TEXT", "SCROLL_SCREEN", "PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER", "BACK",
        "PRESS_BACK", "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS", "TAP", "SWIPE", "WAIT_FOR_UI", "WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP",
        "SYSTEM_ACTION", "SET_VOLUME", "SET_BRIGHTNESS", "SET_DND", "GET_DND", "SET_RINGER_MODE", "GET_BATTERY_STATUS",
        "GET_DEVICE_STATUS", "SEND_WHATSAPP_MESSAGE", "SEND_WHATSAPP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "CALL_PHONE",
        "CREATE_CONTACT", "YOUTUBE_SEARCH", "OPEN_WHATSAPP_CHAT", "TAKE_SCREENSHOT", "SCROLL_DOWN", "SCROLL_UP",
        "MAX_DIAGNOSTICS", "UNLOCK_DEVICE"
    )

    fun validate(steps: List<ActionStep>, maxSteps: Int = MAX_INITIAL_STEPS): String? {
        if (steps.isEmpty()) return "Generated plan is empty."
        if (steps.size > maxSteps) return "Plan exceeds maximum allowed steps (${steps.size} > $maxSteps)."
        val ids = steps.map { it.stepId }
        if (ids.any { it.isBlank() }) return "INVALID_PLAN_DUPLICATE_STEP_ID: Step has blank stepId."
        if (ids.toSet().size != ids.size) return "INVALID_PLAN_DUPLICATE_STEP_ID: Duplicate stepId found."
        val idSet = ids.toSet()
        if (steps.any { it.dependsOn.any { dep -> dep !in idSet } }) return "INVALID_PLAN_MISSING_DEPENDENCY: Step depends on unknown stepId."
        if (steps.any { it.dependsOn.contains(it.stepId) }) return "Plan contains a self dependency."
        val pending = steps.associate { it.stepId to it.dependsOn.toSet() }.toMutableMap()
        var resolved = emptySet<String>()
        while (pending.isNotEmpty()) {
            val ready = pending.filterValues { it.all(resolved::contains) }.keys
            if (ready.isEmpty()) return "INVALID_PLAN_CIRCULAR_DEPENDENCY: Plan dependencies contain a cycle."
            ready.forEach(pending::remove)
            resolved = resolved + ready
        }
        for (step in steps) {
            val action = step.actionType.trim().uppercase()
            if (action !in ALLOWED_ACTION_TYPES) return "Unsupported actionType '$action'."
            if (step.targetText.orEmpty().length > MAX_TEXT_LENGTH || step.contentDescription.orEmpty().length > MAX_TEXT_LENGTH) return "Step text is oversized."
            if (step.params.values.any { it.length > MAX_TEXT_LENGTH }) return "Step parameter is oversized."
            val pkg = step.params["package"] ?: step.params["packageName"] ?: if (action == "OPEN_APP" || action == "LAUNCH_APP") step.targetText?.takeIf { it.contains('.') } else null
            if (pkg != null && pkg.isNotBlank() && !Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(pkg)) return "Invalid package name."
            if (step.fallbackX != null || step.fallbackY != null) {
                if (step.fallbackX == null || step.fallbackY == null || step.fallbackX !in 0f..1f || step.fallbackY !in 0f..1f) return "Invalid coordinate pair or range."
            }
            if (step.fallbackAction != null) return "Fallback actions are not supported in model plans."
            if (action == "TYPE_TEXT" && (step.params["text"] ?: step.targetText).isNullOrBlank()) return "TYPE_TEXT requires text."
        }
        return null
    }
}
