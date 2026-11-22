package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.utils.asStringOrNull
import com.example.voice.vision.ScreenContextProvider
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class ScreenContextTool : Tool {
    private val TAG = "ScreenContextTool"

    override val name: String = "get_screen_context"
    override val supportedActions: Set<String> = setOf("GET_SCREEN_CONTEXT", "SCREEN_AWARENESS", "SCREEN_CONTEXT")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val focus = request.arguments.get("focus").asStringOrNull() ?: "general"
            Log.d(TAG, "Executing ScreenContextTool with focus: '$focus'")

            // Safety-net timeout to prevent hanging the execution pipeline
            val payload = withTimeoutOrNull(6000L) {
                ScreenContextProvider.captureScreenContext(context, focus)
            }

            if (payload == null) {
                Log.w(TAG, "Screen context capture timed out after 6s")
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SCREEN_CONTEXT_TIMEOUT",
                    message = "Screen context capture timed out. The screen may be locked, or the Accessibility Service may need a restart. Please ensure the screen is active and try again."
                )
            }

            val extractedOutputData = mapOf<String, Any?>(
                "text" to payload.visibleText,
                "packageName" to (payload.packageName ?: ""),
                "elements" to payload.clickableElements
            )

            // If neither accessibility nor screenshot could be obtained, report failure
            if (!payload.hasAccessibility && payload.screenshotJpeg == null) {
                Log.w(TAG, "Screen context capture failed: Both accessibility and screenshot unavailable")
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SCREEN_CAPTURE_UNAVAILABLE",
                    message = "Unable to access screen content. Please ensure Accessibility permissions are granted and screen is active.",
                    outputData = extractedOutputData
                )
            }

            val meta = JSONObject().apply {
                put("foreground_app", payload.packageName ?: "Unknown")
                put("focus_mode", payload.focusMode)
                put("screenshot_sent", payload.isScreenshotSent)
                put("has_accessibility", payload.hasAccessibility)
                put("interactive_elements_count", payload.clickableElements.size)
                
                val elementsArray = JSONArray()
                payload.clickableElements.take(25).forEach { elementsArray.put(it) }
                put("interactive_elements", elementsArray)
                
                if (payload.visibleText.isNotEmpty()) {
                    put("visible_text_excerpt", payload.visibleText.take(4000))
                    put("full_visible_text", payload.visibleText)
                }
            }

            val messageSummary = buildString {
                append("Screen context gathered.")
                if (!payload.packageName.isNullOrBlank()) {
                    append(" Foreground App: ${payload.packageName}.")
                }
                if (payload.isScreenshotSent) {
                    append(" Visual frame transmitted to Gemini Live.")
                }
                if (payload.visibleText.isNotEmpty()) {
                    append("\nVisible text on display:\n${payload.visibleText.take(3000)}")
                }
                if (payload.clickableElements.isNotEmpty()) {
                    append("\nFound ${payload.clickableElements.size} interactive elements.")
                }
            }


            ToolResult(
                success = true,
                toolName = name,
                message = messageSummary,
                metadata = meta,
                outputData = extractedOutputData,
                verificationRequired = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing ScreenContextTool", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SCREEN_CONTEXT_ERROR",
                message = e.message ?: "Failed to capture screen context"
            )
        }
    }
}
