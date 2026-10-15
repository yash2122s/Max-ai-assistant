package com.example.automation.engine.planning

import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.engine.state.TaskContext
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.state.TaskStep
import com.example.automation.models.UiStep
import com.example.automation.state.ActionStep
import com.example.knowledge.apps.InstalledAppsRepository
import com.example.voice.vision.ScreenContextPayload
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

        val missingInput = if (steps.isEmpty()) missingInputQuestion(originalUtterance) else null
        if (missingInput != null) taskContext.variables["needs_input"] = missingInput

        return AgentTask(
            id = UUID.randomUUID().toString(),
            originalIntent = originalUtterance,
            goal = taskIntent.goal,
            steps = steps,
            status = if (missingInput != null) TaskStatus.WAITING_FOR_INPUT else TaskStatus.PLANNING,
            context = taskContext
        )
    }

    private fun missingInputQuestion(utterance: String): String? {
        val text = utterance.lowercase()
        return when {
            text.contains("whatsapp") -> "Who should receive the WhatsApp message, and what exact message should MAX send?"
            text.contains("maps") || text.contains("navigate") || text.contains("direction") -> "What exact destination should MAX use?"
            text.contains("chrome") -> "What exact search query or URL should MAX open in Chrome?"
            else -> null
        }
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
               (text.contains("chrome") && (text.contains("search") || text.contains("open") || intent.parameters.containsKey("query") || intent.parameters.containsKey("search"))) ||
               (text.contains("instagram"))
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

            if (contact.isBlank() || message.isBlank()) return emptyList()
            
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
            var destination = intent.parameters["destination"] ?: intent.parameters["query"] ?: intent.parameters["location"] ?: ""
            if (destination.isBlank()) {
                val toMatch = Regex("""(?i)(?:search|to|towards|navigate to|directions to)\s+([^,]+)""").find(originalUtterance)
                destination = toMatch?.groupValues?.get(1)?.trim() ?: ""
            }
            if (destination.isBlank()) return emptyList()

            return listOf(
                TaskStep("maps_1", UiStep.OpenApp(appName = "Google Maps", packageName = targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                TaskStep("maps_2", UiStep.Tap(target = "Search here"), ExpectedOutcome.ElementPresent("Search here")),
                TaskStep("maps_3", UiStep.TypeText(text = destination, target = "Search here"), ExpectedOutcome.TextContentEquals(destination)),
                TaskStep("maps_4", UiStep.PressEnter, ExpectedOutcome.ElementPresent("Directions")),
                TaskStep("maps_5", UiStep.Tap(target = "Directions"), ExpectedOutcome.ElementPresent("Start"))
            )
        }

        // 3. Chrome Search & In-App Long Horizon DAG (e.g. DEV-01)
        if (text.contains("chrome")) {
            val targetApp = "com.android.chrome"
            var query = intent.parameters["query"] ?: intent.parameters["search"] ?: ""
            if (query.isBlank()) {
                val qMatch = Regex("""(?i)(?:search|open|find)\s+([a-zA-Z0-9\s]+?)(?:,\s*open|\s+and\s+open|\s+then|\s+and|$)""").find(originalUtterance)
                query = qMatch?.groupValues?.get(1)?.replace(Regex("(?i)chrome"), "")?.trim() ?: ""
                if (query.lowercase() in setOf("in", "on", "with", "using", "for")) query = ""
            }
            if (query.isBlank()) return emptyList()

            val steps = mutableListOf(
                TaskStep("ch_1", UiStep.OpenApp(appName = "Chrome", packageName = targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                TaskStep("ch_2", UiStep.Tap(target = "Search or type URL"), ExpectedOutcome.ElementPresent("Search or type URL")),
                TaskStep("ch_3", UiStep.TypeText(text = query, target = "Search or type URL"), ExpectedOutcome.TextContentEquals(query)),
                TaskStep("ch_4", UiStep.PressEnter, ExpectedOutcome.ElementPresent(query))
            )
            if (text.contains("first result") || text.contains("first video") || text.contains("top result")) {
                steps.add(TaskStep("ch_5", UiStep.WaitForUi(targetText = "first result", timeoutMs = 2500L), ExpectedOutcome.ElementPresent("first result")))
                steps.add(TaskStep("ch_6", UiStep.Tap(target = "first result"), ExpectedOutcome.ElementPresent("Play")))
            }
            if (text.contains("play")) {
                steps.add(TaskStep("ch_7", UiStep.Tap(target = "Play"), ExpectedOutcome.PlaybackActive()))
            }
            return steps
        }

        // 4. Instagram Navigation & Workflows
        if (text.contains("instagram")) {
            val targetApp = "com.instagram.android"

            // Case A: Reels navigation
            if (text.contains("reels") || text.contains("reel") || text.contains("clips")) {
                return listOf(
                    TaskStep("ig_reels_1", UiStep.OpenApp(appName = targetApp, packageName = targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                    TaskStep("ig_reels_2", UiStep.Tap(target = "Reels"), ExpectedOutcome.ElementPresent("Reels"))
                )
            }

            // Case B: Direct Messages / Chats / Inbox navigation
            if (text.contains("dms") || text.contains("dm") || text.contains("chat") || text.contains("inbox") || (text.contains("messages") && !text.contains("search"))) {
                return listOf(
                    TaskStep("ig_dm_1", UiStep.OpenApp(appName = targetApp, packageName = targetApp), ExpectedOutcome.PackageForeground(targetApp)),
                    TaskStep("ig_dm_2", UiStep.Tap(target = "Messages"), ExpectedOutcome.ElementPresent("Messages"))
                )
            }

            // Case C: Search user / account -> Select match -> Message
            var username = intent.parameters["username"] ?: intent.parameters["query"] ?: intent.parameters["target"] ?: ""
            if (username.isBlank()) {
                val uMatch = Regex("""(?i)(?:search|profile of|user|account)\s+([a-zA-Z0-9._]+)""").find(originalUtterance)
                username = uMatch?.groupValues?.get(1)?.trim() ?: ""
            }

            val steps = mutableListOf<TaskStep>()
            steps.add(TaskStep("ig_1", UiStep.OpenApp(appName = targetApp, packageName = targetApp), ExpectedOutcome.PackageForeground(targetApp)))
            steps.add(TaskStep("ig_2", UiStep.Tap(target = "Search"), ExpectedOutcome.ElementPresent("Search input")))

            if (username.isNotBlank()) {
                steps.add(TaskStep("ig_3", UiStep.Tap(target = "Search input"), ExpectedOutcome.ElementPresent("Search input")))
                steps.add(TaskStep("ig_4", UiStep.TypeText(text = username, target = "Search input"), ExpectedOutcome.TextContentEquals(username)))
                steps.add(TaskStep("ig_5", UiStep.WaitForUi(targetText = username, timeoutMs = 2500L), ExpectedOutcome.ElementPresent(username)))

                if (text.contains("first") || text.contains("account") || text.contains("profile") || text.contains("open") || text.contains("message")) {
                    val resultTarget = if (text.contains("first")) "first account" else username
                    steps.add(TaskStep("ig_6", UiStep.Tap(target = resultTarget), ExpectedOutcome.ElementPresent("Message", timeoutMs = 5000L)))
                }

                if (text.contains("message") || text.contains("chat")) {
                    steps.add(TaskStep("ig_7", UiStep.Tap(target = "Message"), ExpectedOutcome.ElementPresent("Message composer", timeoutMs = 5000L)))
                    if (text.contains("wait")) {
                        steps.add(TaskStep("ig_8", UiStep.WaitForUi(targetText = "Message composer", timeoutMs = 5000L), ExpectedOutcome.ElementPresent("Message composer", timeoutMs = 5000L)))
                    }

                    // Send message ONLY if explicitly requested by user with actual content
                    val messageText = intent.parameters["message"] ?: intent.parameters["text"] ?: ""
                    val isExplicitSendRequested = (text.contains("send") || text.contains("saying") || text.contains("with text")) && messageText.isNotBlank()
                    if (isExplicitSendRequested) {
                        steps.add(TaskStep("ig_9", UiStep.TypeText(text = messageText, target = "Message composer"), ExpectedOutcome.TextContentEquals(messageText)))
                        steps.add(TaskStep("ig_10", UiStep.Tap(target = "Send"), ExpectedOutcome.ElementPresent("Message composer")))
                    }
                }
            }

            return steps
        }

        return emptyList()
    }

    // =========================================================================
    // TIER 3: LONG-HORIZON (Autonomous Discovery)
    // =========================================================================
    private fun routeTier3LongHorizon(intent: TaskIntent, originalUtterance: String): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
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

    suspend fun replanTask(task: AgentTask, context: android.content.Context? = null): Boolean {
        val provider = com.example.automation.engine.GoalPlanner.getPlanningProvider() ?: return false
        return try {
            val ctx = context ?: com.example.service.JarvisAccessibilityService.instance?.applicationContext
            val liveScreen = if (ctx != null) {
                try {
                    com.example.voice.vision.ScreenContextProvider.captureScreenContext(ctx, focus = "general")
                } catch (e: Exception) {
                    android.util.Log.w("TaskPlanner", "Failed capturing live screen context for replan: ${e.message}")
                    null
                }
            } else null

            val screenPayload = liveScreen ?: ScreenContextPayload(
                packageName = (task.context.currentApp ?: "").ifBlank { com.example.automation.state.WorldStateManager.getCurrentState().currentApp.ifBlank { null } },
                prunedNodes = emptyList(),
                visibleText = "",
                clickableElements = emptyList(),
                screenshotJpeg = null,
                isScreenshotSent = false,
                hasAccessibility = com.example.service.JarvisAccessibilityService.instance != null,
                focusMode = "general"
            )

            val currentStep = task.getCurrentStep()
            val remainingGoalPrompt = "Recovery re-plan for goal: '${task.goal}'. Failed step '${currentStep?.id}' (${currentStep?.action?.let { it::class.simpleName } ?: ""}). Current foreground package: '${screenPayload.packageName}'. Generate the remaining steps to achieve the goal from the current screen state."

            val request = com.example.network.provider.PlanningRequest(
                goal = remainingGoalPrompt,
                screenContext = screenPayload
            )
            val result = provider.generatePlan(request)
            if (result.success && result.plan.isNotEmpty()) {
                val validationError = com.example.automation.engine.GoalPlanner.validatePlan(
                    result.plan,
                    task.goal,
                    ActionPlanPolicy.MAX_REPLAN_STEPS
                )
                if (validationError != null) {
                    android.util.Log.w("TaskPlanner", "Replanned steps failed validation: $validationError")
                    return false
                }
                val newSteps = result.plan.map { actionStep ->
                    TaskStep(
                        id = actionStep.stepId,
                        action = actionStep.toUiStep(),
                        expectedOutcome = null
                    )
                }
                val currentRemaining = task.steps.take(task.currentStepIndex).toMutableList()
                currentRemaining.addAll(newSteps)
                task.steps = currentRemaining
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("TaskPlanner", "replanTask failed: ${e.message}")
            false
        }
    }
}
