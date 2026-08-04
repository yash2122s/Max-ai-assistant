package com.example.voice.vision

import android.util.Log
import com.example.network.GeminiWebSocketClient
import com.example.service.AccessibilityTreePruner

class GeminiVisionReasoner(
    private val webSocketClient: GeminiWebSocketClient
) : VisionReasoner {

    private companion object {
        private const val TAG = "GeminiVisionReasoner"
    }

    private var lastScreenSignature: String = ""
    private var lastResult: VisionResult? = null

    override suspend fun analyzeScreen(context: VisionContext): VisionResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Analyzing screen frame for user goal: '${context.userGoal}' (Signature: '${context.screenSignature}')")

        // Frame Deduplication: Reuse cached result if screen signature hasn't changed
        if (context.screenSignature.isNotBlank() && context.screenSignature == lastScreenSignature && lastResult != null) {
            Log.d(TAG, "Frame Deduplication Hit! Reusing cached vision result for signature '${context.screenSignature}' (0ms network cost)")
            return lastResult!!.copy(isCachedResult = true)
        }

        // 1. Send screenshot JPEG frame via transport layer if available
        if (context.screenshotJpeg != null && context.screenshotJpeg.isNotEmpty()) {
            webSocketClient.sendVideoFrame(context.screenshotJpeg)
        }

        // 2. Format pruned accessibility tree context
        val treeJson = AccessibilityTreePruner.toJsonArray(context.prunedTree).toString()
        val contextPrompt = """
            [SCREEN CONTEXT]
            Foreground App: ${context.foregroundPackageName}
            Screen Signature: ${context.screenSignature}
            UI Nodes: $treeJson
            User Goal: ${context.userGoal}
        """.trimIndent()

        // 3. Send contextual text over WebSocket transport
        webSocketClient.sendText(contextPrompt)

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Dispatched vision frame and contextual prompt to Gemini Live in ${duration}ms")

        val newResult = VisionResult(
            description = "Screen frame and accessibility context transmitted to Gemini Live.",
            confidence = 0.95f
        )

        if (context.screenSignature.isNotBlank()) {
            lastScreenSignature = context.screenSignature
            lastResult = newResult
        }

        return newResult
    }
}
