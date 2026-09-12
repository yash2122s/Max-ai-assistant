package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class YoutubeTool : Tool {
    private val TAG = "YoutubeTool"
    override val name: String = "youtube"
    override val supportedActions: Set<String> = setOf("YOUTUBE_SEARCH", "OPEN_YOUTUBE", "SEARCH_YOUTUBE", "PLAY_YOUTUBE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        if (request.action == "OPEN_YOUTUBE") return true
        val query = request.arguments.get("query")?.asString
            ?: request.arguments.get("search_query")?.asString
            ?: request.arguments.get("app")?.asString
            ?: request.arguments.get("app_name")?.asString
            ?: ""
        return query.isNotEmpty() || request.action == "OPEN_YOUTUBE"
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val query = request.arguments.get("query")?.asString
            ?: request.arguments.get("search_query")?.asString
            ?: request.arguments.get("app")?.asString
            ?: request.arguments.get("app_name")?.asString
            ?: ""

        Log.d(TAG, "YouTube action '${request.action}' with query: '$query'")
        return try {
            if (request.action == "OPEN_YOUTUBE" || query.isBlank()) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolResult(
                        success = true,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", "OPEN_YOUTUBE").put("package", "com.google.android.youtube")
                    )
                } else {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "YOUTUBE_NOT_INSTALLED",
                        message = "YouTube application is not installed on this device"
                    )
                }
            } else {
                val success = com.example.automation.YoutubeAutomation.searchAndPlay(context, query)
                if (success) {
                    ToolResult(
                        success = true,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("query", query).put("action", request.action)
                    )
                } else {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "YOUTUBE_SEARCH_FAILED",
                        message = "YouTube automation failed to locate or play results for '$query'"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube execution threw exception", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "YOUTUBE_EXCEPTION",
                message = e.message ?: "YouTube operation failed"
            )
        }
    }
}
