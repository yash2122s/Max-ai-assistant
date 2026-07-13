package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class YoutubeTool : Tool {
    private val TAG = "YoutubeTool"
    override val name: String = "youtube_search"
    override val supportedActions: Set<String> = setOf("YOUTUBE_SEARCH")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val query = request.arguments.get("query")?.asString
            ?: request.arguments.get("app")?.asString
            ?: request.arguments.get("app_name")?.asString
            ?: ""
        return query.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val query = request.arguments.get("query")?.asString
            ?: request.arguments.get("app")?.asString
            ?: request.arguments.get("app_name")?.asString
            ?: ""

        Log.d(TAG, "YouTube search query: '$query'")
        return try {
            val success = com.example.automation.YoutubeAutomation.searchAndPlay(context, query)
            if (success) {
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = true,
                    metadata = JSONObject().put("query", query)
                )
            } else {
                ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "YOUTUBE_SEARCH_FAILED",
                    message = "YouTube automation failed to locate or play results"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search thrown exception", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "YOUTUBE_EXCEPTION",
                message = e.message ?: "YouTube search exception"
            )
        }
    }
}
