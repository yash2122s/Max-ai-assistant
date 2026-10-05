package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.registry.ToolCapabilityRegistry
import com.example.automation.state.WorldStateManager
import com.example.network.GeminiWebSocketClient
import com.example.service.AccessibilityTreePruner
import com.example.service.JarvisAccessibilityService
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.voice.vision.GeminiVisionReasoner
import com.example.voice.vision.ScreenCaptureProvider
import com.example.voice.vision.VisionReasoner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AgentOrchestrator(
    private val context: Context,
    private val webSocketClient: GeminiWebSocketClient
) {
    private val visionReasoner: VisionReasoner = GeminiVisionReasoner(webSocketClient)

    suspend fun processUserPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        val totalStartTime = System.currentTimeMillis()
        Log.d(TAG, "AgentOrchestrator processing prompt: '$prompt'")

        // 1. Intent Classification (<20ms latency target)
        val classification = IntentClassifier.classify(prompt)
        Log.d(TAG, "Intent classification completed in ${classification.classificationLatencyMs}ms. Route: ${classification.routeType}")

        if (classification.routeType == IntentRouteType.DIRECT_FAST_PATH) {
            val toolName = classification.targetToolName ?: "unknown"
            Log.d(TAG, "Executing fast-path direct tool: '$toolName'")
            val action = when (toolName) {
                "open_app" -> "OPEN_APP"
                "flashlight" -> if (classification.extractedArgs["enabled"] == false) "FLASHLIGHT_OFF" else "FLASHLIGHT_ON"
                "set_dnd" -> "SET_DND"
                "get_battery_status" -> "GET_BATTERY_STATUS"
                else -> null
            }
            if (action == null) {
                return@withContext "Fast-path rejected: unsupported direct tool '$toolName'."
            }

            // Task-scoped execution truth contract
            val taskId = "task_fast_" + java.util.UUID.randomUUID().toString().take(8)
            val stepId = "step_1"
            val execId = java.util.UUID.randomUUID().toString()
            val store = runCatching { com.example.automation.engine.state.AgentTaskStore(context.applicationContext) }.getOrNull()

            store?.createTask(taskId = taskId, goal = prompt, totalSteps = 1, source = "FAST_PATH")
            store?.updateStep(
                taskId = taskId,
                step = com.example.automation.state.ActionStep(stepId = stepId, actionType = action),
                stepIndex = 0,
                status = "READY",
                executionId = execId
            )

            // Authorize action
            val target = classification.extractedArgs["app"]?.toString()
                ?: classification.extractedArgs["target"]?.toString()
                ?: action
            val auth = com.example.automation.engine.ActionAuthorizer.authorizeAction(prompt, action, target)
            if (!auth.authorized) {
                store?.transitionStep(
                    taskId = taskId,
                    stepId = stepId,
                    executionId = execId,
                    targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_FAILED,
                    actor = "ExecutionEngine",
                    failureReason = auth.reason
                )
                com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(
                    taskId = taskId,
                    stepId = stepId,
                    executionId = execId,
                    actionName = action,
                    executorSuccess = false,
                    verifiedSuccess = false,
                    failureReason = auth.reason,
                    message = "Action blocked by authorizer"
                )
                return@withContext "Fast-path blocked: ${auth.reason}"
            }

            val args = JSONObject().apply {
                put("action", action)
                classification.extractedArgs.forEach { (key, value) -> put(key, value) }
                put("last_input_transcription", prompt)
                put("taskId", taskId)
                put("stepId", stepId)
                put("executionId", execId)
            }

            store?.transitionStep(
                taskId = taskId,
                stepId = stepId,
                executionId = execId,
                targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_DISPATCHING,
                actor = "CanonicalRequestAdapter"
            )
            store?.transitionStep(
                taskId = taskId,
                stepId = stepId,
                executionId = execId,
                targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_RUNNING,
                actor = "ExecutionEngine"
            )

            val result = com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, args)
            val resultJson = runCatching { JSONObject(result) }.getOrNull()
            val succeeded = resultJson?.optBoolean("success", false) == true
            val errorMsg = resultJson?.optString("message")?.ifEmpty { resultJson.optString("reason") }

            store?.transitionStep(
                taskId = taskId,
                stepId = stepId,
                executionId = execId,
                targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_VERIFYING,
                actor = "ExecutionEngine",
                executorResultJson = result
            )

            if (succeeded) {
                store?.transitionStep(
                    taskId = taskId,
                    stepId = stepId,
                    executionId = execId,
                    targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_SUCCEEDED,
                    actor = "VERIFIER",
                    verificationEvidence = "Fast-path execution verified",
                    executorResultJson = result
                )
                WorldStateManager.recordActionExecuted(toolName, "Fast-path execution for '$prompt'", success = true)
            } else {
                store?.transitionStep(
                    taskId = taskId,
                    stepId = stepId,
                    executionId = execId,
                    targetStatus = com.example.automation.engine.truth.TaskTransitionAuthority.STATUS_FAILED,
                    actor = "VERIFIER",
                    failureReason = errorMsg ?: "Fast-path execution failed",
                    executorResultJson = result
                )
                WorldStateManager.recordActionExecuted(toolName, "Fast-path failed for '$prompt'", success = false)
            }

            com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(
                taskId = taskId,
                stepId = stepId,
                executionId = execId,
                actionName = action,
                executorSuccess = succeeded,
                verifiedSuccess = succeeded,
                failureReason = if (!succeeded) errorMsg else null,
                message = if (succeeded) "Fast-path verified" else errorMsg
            )

            val totalLatency = System.currentTimeMillis() - totalStartTime
            return@withContext if (succeeded) {
                "Fast-path verified tool '$toolName' in ${totalLatency}ms."
            } else {
                "Fast-path failed for '$toolName': ${errorMsg.orEmpty()}"
            }
        }

        // 2. Complex Vision Path - Perception & World State Update
        val screenJpeg = ScreenCaptureProvider.captureCompressedJpeg()
        val accessibilityService = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
        val rootNode = accessibilityService?.rootInActiveWindow
        val prunedNodes = AccessibilityTreePruner.pruneTree(rootNode)

        // Update foreground app in WorldState
        accessibilityService?.rootInActiveWindow?.packageName?.toString()?.let { packageName ->
            WorldStateManager.setForegroundApp(packageName)
        }

        val worldState = WorldStateManager.getCurrentState()

        // 3. Verify Tool Capability Registry
        val capabilities = ToolCapabilityRegistry.getCapabilities(context)
        Log.d(TAG, "Active capabilities checked: ${capabilities.filterValues { it.status == com.example.automation.registry.CapabilityStatus.AVAILABLE }.keys}")

        // 4. Construct VisionContext & Delegate to Vision Reasoner
        val visionContext = com.example.voice.vision.VisionContext(
            screenshotJpeg = screenJpeg,
            prunedTree = prunedNodes,
            worldState = worldState,
            userGoal = prompt
        )
        val visionResult = visionReasoner.analyzeScreen(visionContext)

        val totalLatency = System.currentTimeMillis() - totalStartTime
        Log.d(TAG, "Complex vision orchestrator processing completed in ${totalLatency}ms. Cached result: ${visionResult.isCachedResult}")
        return@withContext visionResult.description
    }

    companion object {
        private const val TAG = "AgentOrchestrator"
    }
}
