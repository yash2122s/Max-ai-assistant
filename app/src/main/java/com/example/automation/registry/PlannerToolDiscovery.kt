package com.example.automation.registry

import com.example.automation.tools.Tool
import com.example.automation.tools.ToolRegistry

/**
 * P1.1 — Dynamic planner-visible tool discovery.
 *
 * The ToolRegistry is the runtime source of truth for planner capabilities:
 *  - every registered tool with `capabilities.supportsPlanner == true`
 *    contributes one [ToolDescriptor] per supported action,
 *  - adding/removing a tool automatically changes what the planner sees,
 *  - no hardcoded "N tools / M actions" count is ever used when generating
 *    planner context.
 *
 * Human descriptions / parameter schemas are declared once here (the canonical
 * planner metadata catalog) and are NOT duplicated in PromptBuilder.
 */
object PlannerToolDiscovery {

    // --- Registry access -------------------------------------------------

    /** Tools whose capabilities mark them visible to the planner. */
    fun getPlannerVisibleTools(): List<Tool> =
        ToolRegistry.getAllTools()
            .filter { it.capabilities.supportsPlanner }
            .sortedBy { it.name.lowercase() }

    /** Distinct planner-visible action types currently registered. */
    fun getPlannerVisibleActionTypes(): List<String> =
        getPlannerVisibleTools()
            .flatMap { it.supportedActions }
            .map { it.uppercase() }
            .distinct()
            .sorted()

    // --- Risk metadata (informational only — ActionAuthorizer enforces) ---

    private val CRITICAL_KEYWORDS = listOf(
        "ADB", "SHIZUKU", "ROOT", "FACTORY", "WIPE", "FORMAT",
        "PIN", "UPI", "PASSWORD", "PAYMENT", "OTP", "CREDENTIAL",
        "WINDOWS_AGENT"
    )

    private val CAUTION_ACTIONS = setOf(
        "SEND_WHATSAPP", "SEND_WHATSAPP_MESSAGE", "OPEN_WHATSAPP_CHAT", "SEND_SMS",
        "CALL_PHONE", "ANSWER_CALL", "REJECT_CALL", "REPLY_NOTIFICATION",
        "CREATE_CONTACT", "UNLOCK_DEVICE", "DELETE_ROUTINE", "FORGET_MEMORY",
        "DELETE_MEMORY", "CLEAR_ALL_PERIOD_DATA", "TAKE_PHOTO", "GET_LOCATION",
        "SEND_LOCATION", "GET_NOTIFICATIONS", "FORGET_*"
    )

    /**
     * Classifies an action into an informational risk level.
     *
     * Informational only: approval/denial is always enforced by the
     * ActionAuthorizer + approval policy. CRITICAL actions are never
     * auto-approved in any mode.
     */
    fun classifyRisk(actionType: String): ToolRiskLevel {
        val key = actionType.uppercase().trim()
        if (key.isBlank()) return ToolRiskLevel.SAFE
        if (CRITICAL_KEYWORDS.any { key.contains(it) }) return ToolRiskLevel.CRITICAL
        if (CAUTION_ACTIONS.any { key == it || (it.endsWith("*") && key.startsWith(it.dropLast(1))) }) return ToolRiskLevel.CAUTION
        return ToolRiskLevel.SAFE
    }

    // --- Human-readable metadata catalog ---------------------------------
    // Keyed by uppercase action type. Missing keys fall back to a derived name.

    private val DESCRIPTIONS = mapOf(
        "OPEN_APP" to "Launch an installed app (e.g. WhatsApp, Settings) on the phone.",
        "SEND_WHATSAPP_MESSAGE" to "Send a WhatsApp message to a contact name or phone number.",
        "SEND_WHATSAPP" to "Send a WhatsApp message to a contact name or phone number.",
        "OPEN_WHATSAPP_CHAT" to "Open a WhatsApp chat with a contact.",
        "YOUTUBE_SEARCH" to "Search YouTube for a query and open the results.",
        "SEARCH_YOUTUBE" to "Search YouTube for a query and open the results.",
        "OPEN_YOUTUBE" to "Open the YouTube app.",
        "CALL_PHONE" to "Place a phone call to a resolved contactId/phoneId.",
        "SEARCH_CONTACT" to "Search the device contacts for a name; returns contactId/phoneId.",
        "CREATE_CONTACT" to "Create a new contact with name and phone.",
        "SEND_SMS" to "Send an SMS text message to a number or contact.",
        "ANSWER_CALL" to "Answer the current incoming call.",
        "REJECT_CALL" to "Reject/decline the current incoming call.",
        "SYSTEM_ACTION" to "Perform a system navigation gesture (home/back/recent/notifications/quick_settings/screenshot).",
        "FLASHLIGHT_ON" to "Turn the flashlight ON.",
        "FLASHLIGHT_OFF" to "Turn the flashlight OFF.",
        "SET_VOLUME" to "Set media volume to a percentage or direction.",
        "VOLUME_UP" to "Increase media volume.",
        "VOLUME_DOWN" to "Decrease media volume.",
        "SET_BRIGHTNESS" to "Set screen brightness to a percentage.",
        "BRIGHTNESS_UP" to "Increase screen brightness.",
        "BRIGHTNESS_DOWN" to "Decrease screen brightness.",
        "SET_DND" to "Enable or disable Do Not Disturb.",
        "DND_ON" to "Enable Do Not Disturb.",
        "DND_OFF" to "Disable Do Not Disturb.",
        "SET_RINGER_MODE" to "Set the ringer mode (silent/vibrate/normal).",
        "SET_BLUETOOTH" to "Enable or disable Bluetooth.",
        "TOGGLE_WIFI" to "Toggle Wi-Fi.",
        "SET_ALARM" to "Set an alarm on the device clock.",
        "SET_TIMER" to "Start a countdown timer.",
        "SET_REMINDER" to "Set a reminder for a task at a time.",
        "CREATE_REMINDER" to "Set a reminder for a task at a time.",
        "GET_BATTERY_STATUS" to "Read battery percentage and charging state.",
        "GET_DEVICE_STATUS" to "Read device status summary (battery/storage/RAM/settings).",
        "GET_APP_USAGE" to "Read today's foreground app usage statistics.",
        "GET_CALENDAR_EVENTS" to "List calendar events for the next 7 days.",
        "ADD_CALENDAR_EVENT" to "Schedule a calendar event.",
        "GET_NOTIFICATIONS" to "List active notifications from messaging apps.",
        "REPLY_NOTIFICATION" to "Reply to an active notification.",
        "GET_SCREEN_CONTEXT" to "Capture + analyze the current screen (summary/read_text/explain_error/find_element).",
        "CLICK_TEXT" to "Click a visible UI element by text or description.",
        "CLICK_ELEMENT" to "Click a visible UI element by text or description.",
        "SCROLL_SCREEN" to "Scroll the current screen up or down.",
        "SCROLL_UP" to "Scroll the current screen up.",
        "SCROLL_DOWN" to "Scroll the current screen down.",
        "TAKE_SCREENSHOT" to "Take a screenshot of the current screen.",
        "TAKE_PHOTO" to "Take a photo using the camera.",
        "SAVE_MEMORY" to "Save a user fact/preference to permanent memory.",
        "FORGET_MEMORY" to "Delete a saved memory by topic.",
        "DELETE_MEMORY" to "Delete a saved memory by topic.",
        "RUN_ROUTINE" to "Run a routine by name.",
        "CREATE_ROUTINE" to "Create or update a custom routine from steps_json.",
        "DELETE_ROUTINE" to "Delete a custom routine by name.",
        "LIST_ROUTINES" to "List available built-in and custom routines.",
        "RUN_ADB_COMMAND" to "Run an ADB shell command via Shizuku (privileged).",
        "UNLOCK_DEVICE" to "Unlock the phone with the stored PIN automation.",
        "SCHEDULE_TASK" to "Schedule a tool to run later (flashlight/whatsapp/open_app).",
        "CANCEL_TASK" to "Cancel a scheduled task by id.",
        "LIST_TASKS" to "List active scheduled tasks.",
        "CONTROL_MEDIA" to "Control media playback (play/pause/next/previous).",
        "GET_CLIPBOARD" to "Read the system clipboard.",
        "SET_CLIPBOARD" to "Copy text to the clipboard.",
        "SEARCH_FILES" to "Search Downloads/Documents/Pictures for matching files.",
        "GET_LOCATION" to "Read current GPS location.",
        "PLAY_MEDIA" to "Play the active media session.",
        "PAUSE_MEDIA" to "Pause the active media session.",
        "NEXT_MEDIA" to "Skip to next track.",
        "PREVIOUS_MEDIA" to "Go to previous track.",
        "MAX_DIAGNOSTICS" to "Report registered tools and capabilities."
    )

    private val CATEGORIES = mapOf(
        "OPEN_APP" to "app",
        "SEND_WHATSAPP_MESSAGE" to "communication",
        "SEND_WHATSAPP" to "communication",
        "OPEN_WHATSAPP_CHAT" to "communication",
        "CALL_PHONE" to "communication",
        "SEARCH_CONTACT" to "contacts",
        "CREATE_CONTACT" to "contacts",
        "SEND_SMS" to "communication",
        "ANSWER_CALL" to "communication",
        "REJECT_CALL" to "communication",
        "REPLY_NOTIFICATION" to "communication",
        "GET_NOTIFICATIONS" to "system",
        "YOUTUBE_SEARCH" to "media",
        "SYSTEM_ACTION" to "navigation",
        "FLASHLIGHT_ON" to "device",
        "FLASHLIGHT_OFF" to "device",
        "SET_VOLUME" to "device",
        "VOLUME_UP" to "device",
        "VOLUME_DOWN" to "device",
        "SET_BRIGHTNESS" to "device",
        "BRIGHTNESS_UP" to "device",
        "BRIGHTNESS_DOWN" to "device",
        "SET_DND" to "device",
        "SET_RINGER_MODE" to "device",
        "SET_BLUETOOTH" to "device",
        "TOGGLE_WIFI" to "device",
        "SET_ALARM" to "time",
        "SET_TIMER" to "time",
        "SET_REMINDER" to "time",
        "CREATE_REMINDER" to "time",
        "GET_BATTERY_STATUS" to "system",
        "GET_DEVICE_STATUS" to "system",
        "GET_APP_USAGE" to "system",
        "GET_CALENDAR_EVENTS" to "calendar",
        "ADD_CALENDAR_EVENT" to "calendar",
        "GET_SCREEN_CONTEXT" to "ui",
        "CLICK_TEXT" to "ui",
        "CLICK_ELEMENT" to "ui",
        "SCROLL_SCREEN" to "ui",
        "SCROLL_UP" to "ui",
        "SCROLL_DOWN" to "ui",
        "TAKE_SCREENSHOT" to "ui",
        "TAKE_PHOTO" to "media",
        "SAVE_MEMORY" to "memory",
        "FORGET_MEMORY" to "memory",
        "DELETE_MEMORY" to "memory",
        "RUN_ROUTINE" to "routine",
        "CREATE_ROUTINE" to "routine",
        "DELETE_ROUTINE" to "routine",
        "LIST_ROUTINES" to "routine",
        "RUN_ADB_COMMAND" to "system",
        "UNLOCK_DEVICE" to "security",
        "SCHEDULE_TASK" to "time",
        "CANCEL_TASK" to "time",
        "LIST_TASKS" to "time",
        "GET_CLIPBOARD" to "system",
        "SET_CLIPBOARD" to "system",
        "SEARCH_FILES" to "files",
        "GET_LOCATION" to "location",
        "MAX_DIAGNOSTICS" to "system"
    )

    private val PARAMETER_SCHEMAS: Map<String, List<ToolParameter>> = mapOf(
        "OPEN_APP" to listOf(
            ToolParameter("app", "STRING", true, "App name to open (e.g. WhatsApp, Settings, YouTube).")
        ),
        "SEND_WHATSAPP_MESSAGE" to listOf(
            ToolParameter("contact", "STRING", true, "Contact name or phone number."),
            ToolParameter("message", "STRING", true, "Message text to send.")
        ),
        "OPEN_WHATSAPP_CHAT" to listOf(
            ToolParameter("contact", "STRING", true, "Contact name or phone number.")
        ),
        "YOUTUBE_SEARCH" to listOf(
            ToolParameter("query", "STRING", true, "Search query / song / video name.")
        ),
        "SEND_SMS" to listOf(
            ToolParameter("number", "STRING", true, "Recipient phone number."),
            ToolParameter("message", "STRING", true, "SMS message text.")
        ),
        "SEARCH_CONTACT" to listOf(
            ToolParameter("query", "STRING", true, "Contact name to search for.")
        ),
        "CREATE_CONTACT" to listOf(
            ToolParameter("name", "STRING", true, "Contact display name."),
            ToolParameter("phone", "STRING", true, "Phone number.")
        ),
        "CALL_PHONE" to listOf(
            ToolParameter("contactId", "INTEGER", true, "Contact ID from search_contact."),
            ToolParameter("phoneId", "INTEGER", true, "Phone ID from search_contact.")
        ),
        "SYSTEM_ACTION" to listOf(
            ToolParameter("action", "ENUM", true, "Navigation action.", allowedValues = listOf("home", "back", "recent", "notifications", "quick_settings", "screenshot"))
        ),
        "SET_VOLUME" to listOf(
            ToolParameter("percent", "INTEGER", false, "Volume percentage 0-100.", allowedValues = (0..100).map { it.toString() }),
            ToolParameter("direction", "ENUM", false, "Direction.", allowedValues = listOf("up", "down"))
        ),
        "SET_BRIGHTNESS" to listOf(
            ToolParameter("percent", "INTEGER", true, "Brightness percentage 0-100.")
        ),
        "SET_DND" to listOf(
            ToolParameter("enabled", "BOOLEAN", true, "True to enable DND, false to disable.")
        ),
        "SET_RINGER_MODE" to listOf(
            ToolParameter("mode", "ENUM", true, "Ringer mode.", allowedValues = listOf("silent", "vibrate", "normal"))
        ),
        "SET_BLUETOOTH" to listOf(
            ToolParameter("enabled", "BOOLEAN", true, "True to enable, false to disable.")
        ),
        "SET_ALARM" to listOf(
            ToolParameter("hour", "INTEGER", true, "Hour 0-23."),
            ToolParameter("minutes", "INTEGER", true, "Minutes 0-59."),
            ToolParameter("message", "STRING", false, "Alarm label.")
        ),
        "SET_TIMER" to listOf(
            ToolParameter("seconds", "INTEGER", true, "Countdown duration in seconds.")
        ),
        "SET_REMINDER" to listOf(
            ToolParameter("message", "STRING", true, "Reminder text."),
            ToolParameter("time_expression", "STRING", true, "Natural time e.g. 'in 10 minutes'.")
        ),
        "RUN_ROUTINE" to listOf(
            ToolParameter("routineName", "STRING", true, "Routine name to run."),
            ToolParameter("dryRun", "BOOLEAN", false, "Validate only without executing.", defaultValue = "false")
        ),
        "CREATE_ROUTINE" to listOf(
            ToolParameter("routineName", "STRING", true, "New routine name."),
            ToolParameter("steps_json", "STRING", true, "JSON array of {'action': ..., 'arguments': {...}} steps.")
        ),
        "DELETE_ROUTINE" to listOf(
            ToolParameter("routineName", "STRING", true, "Routine name to delete.")
        ),
        "GET_SCREEN_CONTEXT" to listOf(
            ToolParameter("focus", "ENUM", false, "Focus mode.", allowedValues = listOf("summary", "read_text", "explain_error", "find_element", "general"))
        ),
        "CLICK_TEXT" to listOf(
            ToolParameter("text", "STRING", true, "Visible label/text of the element to click.")
        ),
        "CLICK_ELEMENT" to listOf(
            ToolParameter("text", "STRING", true, "Visible label/text of the element to click.")
        ),
        "SCROLL_SCREEN" to listOf(
            ToolParameter("direction", "ENUM", true, "Scroll direction.", allowedValues = listOf("down", "up"))
        ),
        "SAVE_MEMORY" to listOf(
            ToolParameter("title", "STRING", true, "Short memory title."),
            ToolParameter("content", "STRING", true, "Memory content/fact."),
            ToolParameter("category", "ENUM", false, "Category.", allowedValues = listOf("Personal", "Preferences", "Work", "Goals", "General")),
            ToolParameter("type", "ENUM", false, "Memory type.", allowedValues = listOf("FACT", "PREFERENCE", "GOAL", "CUSTOM"))
        ),
        "FORGET_MEMORY" to listOf(
            ToolParameter("query", "STRING", true, "Memory topic/title to forget.")
        ),
        "RUN_ADB_COMMAND" to listOf(
            ToolParameter("command", "STRING", true, "Shell command to run (privileged).")
        ),
        "SCHEDULE_TASK" to listOf(
            ToolParameter("tool", "STRING", true, "Tool name: flashlight / send_whatsapp_message / open_app."),
            ToolParameter("time_expression", "STRING", true, "When it should run (e.g. 'in 5 minutes')."),
            ToolParameter("repeat_type", "ENUM", false, "Repeat interval.", allowedValues = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY"))
        ),
        "CONTROL_MEDIA" to listOf(
            ToolParameter("media_action", "ENUM", true, "Playback command.", allowedValues = listOf("PLAY_MEDIA", "PAUSE_MEDIA", "NEXT_MEDIA", "PREVIOUS_MEDIA"))
        ),
        "GET_LOCATION" to listOf(),
        "UNLOCK_DEVICE" to listOf()
    )

    private fun humanizedDescription(actionType: String): String {
        val key = actionType.uppercase().replace("_", " ").lowercase()
            .replaceFirstChar { it.uppercase() }
        return "Execute $key."
    }

    /** Description for an action, preferring the curated catalog. */
    fun describeAction(actionType: String): String =
        DESCRIPTIONS[actionType.uppercase()] ?: humanizedDescription(actionType)

    fun categoryFor(actionType: String): String =
        CATEGORIES[actionType.uppercase()] ?: "general"

    fun parametersFor(actionType: String): List<ToolParameter> =
        PARAMETER_SCHEMAS[actionType.uppercase()] ?: emptyList()

    // --- Descriptor generation -------------------------------------------

    /**
     * Generates the current planner-visible descriptor set straight from the
     * ToolRegistry. One descriptor per supported action; parameters come from
     * the catalog above. Pure function of the registry — used by the planner.
     */
    fun buildDescriptors(): List<ToolDescriptor> {
        return getPlannerVisibleTools().flatMap { tool ->
            tool.supportedActions.map { action ->
                val actionKey = action.uppercase()
                ToolDescriptor(
                    toolName = tool.name,
                    actionType = actionKey,
                    description = describeAction(actionKey),
                    category = categoryFor(actionKey),
                    riskLevel = classifyRisk(actionKey),
                    parameters = parametersFor(actionKey),
                    plannerVisible = true
                )
            }
        }.distinctBy { it.actionKey }.sortedBy { it.actionKey }
    }

    // --- Lightweight generation cache ------------------------------------
    // The registry is frozen after startup, so the markdown is cached and only
    // rebuilt when the registration count changes (tests register/unregister).

    @Volatile
    private var cachedGenerationVersion: Int = -1
    @Volatile
    private var cachedMarkdown: String = ""

    /**
     * Renders a set of descriptors into compact markdown for planner context.
     * Pure function — used by the registry-backed generator and by tests.
     */
    fun renderMarkdown(descriptors: List<ToolDescriptor>): String {
        if (descriptors.isEmpty()) return "(No planner-visible tools are currently registered.)"
        val grouped = descriptors.groupBy { it.toolName }
        val sb = StringBuilder()
        sb.appendLine("Available action types (${descriptors.size} actions across ${grouped.size} tools, generated live from the tool registry):")
        grouped.keys.sorted().forEach { toolName ->
            val toolDescriptors = grouped.getValue(toolName).sortedBy { it.actionKey }
            val first = toolDescriptors.first()
            val actionsText = toolDescriptors.joinToString(" | ") { it.actionKey }
            val desc = toolDescriptors.firstOrNull { it.description.isNotBlank() }?.description ?: ""
            val params = toolDescriptors.flatMap { it.parameters }.distinctBy { it.name }
            sb.append("- ${first.category}: $actionsText")
            if (desc.isNotBlank()) sb.append(" — $desc")
            if (params.isNotEmpty()) {
                val paramText = params.joinToString(", ") { p ->
                    val opt = if (p.required) "required" else "optional"
                    val allowed = if (p.allowedValues.isNotEmpty()) " [${p.allowedValues.joinToString("/") { it.ifBlank { "?" } }.take(120)}]" else ""
                    "${p.name}:${p.type}($opt)$allowed"
                }
                sb.append(" | params: $paramText")
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    /**
     * Compact markdown describing the current planner-visible capabilities.
     * Rebuilt automatically whenever tools are (re)registered.
     */
    fun buildPlannerCapabilitiesMarkdown(): String {
        val version = ToolRegistry.getRegistrationVersion()
        if (version == cachedGenerationVersion && cachedMarkdown.isNotEmpty()) {
            return cachedMarkdown
        }
        val text = renderMarkdown(buildDescriptors())
        cachedGenerationVersion = version
        cachedMarkdown = text
        return text
    }

    /** Invalidates the cached planner markdown (used by tests after reset). */
    fun invalidateCache() {
        cachedGenerationVersion = -1
        cachedMarkdown = ""
    }
}
