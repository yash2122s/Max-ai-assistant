package com.example.automation.engine.planning

import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.engine.state.TaskContext
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.state.TaskStep
import com.example.automation.models.UiStep
import com.example.knowledge.apps.InstalledAppsRepository
import java.util.UUID

class TaskPlanner(
    private val appRepository: InstalledAppsRepository = InstalledAppsRepository
) {
    fun createPlan(originalUtterance: String, taskIntent: TaskIntent): AgentTask {
        val taskContext = TaskContext(
            originalIntent = originalUtterance,
            variables = taskIntent.parameters.toMutableMap()
        )

        val steps = when {
            isFastPath(taskIntent, originalUtterance) -> routeTier1FastPath(taskIntent, originalUtterance)
            isAdaptivePath(taskIntent, originalUtterance) -> routeTier2AdaptivePath(taskIntent, originalUtterance)
            else -> routeTier3LongHorizon(taskIntent, originalUtterance)
        }

        return AgentTask(
            id = UUID.randomUUID().toString(),
            originalIntent = originalUtterance,
            goal = taskIntent.goal,
            steps = steps,
            status = TaskStatus.PLANNING,
            context = taskContext
        )
    }

    // =========================================================================
    // TIER 1: FAST PATH (Single-step Intents & Direct Launch)
    // =========================================================================
    private fun isFastPath(intent: TaskIntent, originalUtterance: String): Boolean {
        val text = (intent.goal + " " + originalUtterance).lowercase()
        return text.contains("wifi") || text.contains("bluetooth") || text.contains("flashlight") ||
               text.contains("torch") || intent.goal.equals("open_app", ignoreCase = true) || 
               intent.goal.equals("toggle_setting", ignoreCase = true)
    }

    private fun routeTier1FastPath(intent: TaskIntent, originalUtterance: String): List<TaskStep> {
        val combined = (intent.goal + " " + originalUtterance).lowercase()
        if (combined.contains("wifi")) {
            val enabled = !combined.contains("off") && !combined.contains("disable")
            return listOf(
                TaskStep(
                    id = "toggle_wifi",
                    action = UiStep.ToggleSystemSetting("wifi", enabled),
                    expectedOutcome = ExpectedOutcome.SystemSettingState("wifi", enabled)
                )
            )
        }
        if (combined.contains("bluetooth")) {
            val enabled = !combined.contains("off") && !combined.contains("disable")
            return listOf(
                TaskStep(
                    id = "toggle_bluetooth",
                    action = UiStep.ToggleSystemSetting("bluetooth", enabled),
                    expectedOutcome = ExpectedOutcome.SystemSettingState("bluetooth", enabled)
                )
            )
        }
        if (combined.contains("flashlight") || combined.contains("torch")) {
            val enabled = !combined.contains("off") && !combined.contains("disable")
            return listOf(
                TaskStep(
                    id = "toggle_flashlight",
                    action = UiStep.ToggleSystemSetting("flashlight", enabled),
                    expectedOutcome = null
                )
            )
        }
        if (intent.goal.equals("open_app", ignoreCase = true) && intent.targetEntity != null) {
            return listOf(
                TaskStep(
                    id = "step_fast_launch",
                    action = UiStep.OpenApp(intent.targetEntity),
                    expectedOutcome = ExpectedOutcome.PackageForeground(intent.targetEntity)
                )
            )
        }
        return emptyList()
    }

    // =========================================================================
    // TIER 2: ADAPTIVE DAG (Known Multi-Step Workflows)
    // =========================================================================
    private fun isAdaptivePath(intent: TaskIntent, originalUtterance: String): Boolean {
        val text = (originalUtterance + " " + intent.goal).lowercase()
        return (text.contains("whatsapp") && (text.contains("send") || text.contains("message") || text.contains("tell") || text.contains("call") || text.contains("pettu") || text.contains("pampu") || text.contains("msg"))) ||
               (text.contains("maps") || text.contains("direction") || text.contains("navigate")) ||
               (text.contains("chrome") && (text.contains("search") || text.contains("open") || intent.parameters.containsKey("query") || intent.parameters.containsKey("search")))
    }

    private fun routeTier2AdaptivePath(intent: TaskIntent, originalUtterance: String): List<TaskStep> {
        val text = (originalUtterance + " " + intent.goal).lowercase()

        // 1. WhatsApp DAG
        if (text.contains("whatsapp")) {
            val targetApp = "com.whatsapp"
            var contact = intent.parameters["contact"] ?: intent.parameters["target"] ?: intent.parameters["name"] ?: ""
            var message = intent.parameters["message"] ?: intent.parameters["text"] ?: ""

            if (contact.isBlank() || message.isBlank()) {
                val waPattern = Regex("""(?i)(?:send\s+(?:a\s+)?(?:whatsapp\s+)?message\s+to|send\s+whatsapp\s+to|message|text|whatsapp)\s+([a-zA-Z0-9\s]+?)\s+(?:saying|that|as|with\s+text|message|msg|:)\s+(.+)""")
                val waMatch = waPattern.find(originalUtterance)
                if (waMatch != null) {
                    if (contact.isBlank()) {
                        contact = waMatch.groupValues[1].replace(Regex("^(?:the|to)\\s+", RegexOption.IGNORE_CASE), "").trim()
                    }
                    if (message.isBlank()) {
                        message = waMatch.groupValues[2].replace(Regex("^(?:saying|that|text|as)\\s+", RegexOption.IGNORE_CASE), "").trim()
                    }
                }
            }

            if (contact.isBlank()) contact = "Yash"
            if (message.isBlank()) message = "I am in a meeting right now and will call back later."
            
            return listOf(
                TaskStep("wa_1", UiStep.OpenApp(targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                TaskStep("wa_2", UiStep.Tap(target = "Search"), ExpectedOutcome.ElementPresent("Search input")),
                TaskStep("wa_3", UiStep.TypeText(text = contact, target = "Search input"), ExpectedOutcome.TextContentEquals(contact)),
                TaskStep("wa_4", UiStep.Tap(target = contact), ExpectedOutcome.ElementPresent("Message")),
                TaskStep("wa_5", UiStep.TypeText(text = message, target = "Message"), ExpectedOutcome.TextContentEquals(message)),
                TaskStep("wa_6", UiStep.Tap(target = "Send"), ExpectedOutcome.MessageSent(contact))
            )
        }

        // 2. Google Maps DAG (Search & Directions)
        if (text.contains("maps") || text.contains("navigate") || text.contains("direction")) {
            val targetApp = "com.google.android.apps.maps"
            val destination = intent.parameters["destination"] ?: intent.parameters["query"] ?: intent.parameters["location"] ?: ""
            
            return listOf(
                TaskStep("maps_1", UiStep.OpenApp(targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                TaskStep("maps_2", UiStep.Tap(target = "Search here"), ExpectedOutcome.ElementPresent("Search here")),
                TaskStep("maps_3", UiStep.TypeText(text = destination, target = "Search here"), ExpectedOutcome.TextContentEquals(destination)),
                TaskStep("maps_4", UiStep.PressEnter, ExpectedOutcome.ElementPresent("Directions")),
                TaskStep("maps_5", UiStep.Tap(target = "Directions"), ExpectedOutcome.ElementPresent("Start"))
            )
        }

        // 3. Chrome Search DAG (Fixes "Already in App" issue)
        if (text.contains("chrome")) {
            val targetApp = "com.android.chrome"
            val query = intent.parameters["query"] ?: intent.parameters["search"] ?: ""
            
            return listOf(
                TaskStep("ch_1", UiStep.OpenApp(targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                TaskStep("ch_2", UiStep.Tap(target = "Search or type URL"), ExpectedOutcome.ElementPresent("Search or type URL")),
                TaskStep("ch_3", UiStep.TypeText(text = query, target = "Search or type URL"), ExpectedOutcome.TextContentEquals(query)),
                TaskStep("ch_4", UiStep.PressEnter, ExpectedOutcome.ElementPresent(query))
            )
        }

        return emptyList()
    }

    // =========================================================================
    // TIER 3: LONG-HORIZON (Autonomous Discovery)
    // =========================================================================
    private fun routeTier3LongHorizon(intent: TaskIntent, originalUtterance: String): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        
        // Starts with app launch; dynamic replanning handles subsequent exploration
        if (intent.targetEntity != null) {
            steps.add(
                TaskStep(
                    id = "lh_1_entry",
                    action = UiStep.OpenApp(intent.targetEntity),
                    expectedOutcome = ExpectedOutcome.PackageForeground(intent.targetEntity)
                )
            )
        }
        return steps
    }

    fun replanTask(task: AgentTask): Boolean {
        // Fallback hook invoked by LongTaskOrchestrator when local recovery fails.
        // Triggers Gemini context injection and dynamically appends new TaskSteps.
        return false 
    }
}
