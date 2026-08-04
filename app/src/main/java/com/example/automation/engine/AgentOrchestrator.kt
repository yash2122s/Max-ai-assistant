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
            // Record execution in WorldState
            WorldStateManager.recordActionExecuted(toolName, "Fast-path execution for '$prompt'")
            val totalLatency = System.currentTimeMillis() - totalStartTime
            return@withContext "Fast-path executed tool '$toolName' in ${totalLatency}ms."
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
