package com.example.features.youtube

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class YoutubeTool : Tool {
    private val TAG = "YoutubeTool"
    override val name: String = "youtube"
    override val supportedActions: Set<String> = setOf(
        "YOUTUBE_SEARCH", "OPEN_YOUTUBE", "SEARCH_YOUTUBE", "PLAY_YOUTUBE",
        "LAUNCH_YOUTUBE", "PAUSE_YOUTUBE", "YOUTUBE_PAUSE",
        "RESUME_YOUTUBE", "YOUTUBE_RESUME", "NEXT_YOUTUBE_VIDEO", "YOUTUBE_NEXT",
        "PREVIOUS_YOUTUBE_VIDEO", "YOUTUBE_PREVIOUS", "SKIP_AD", "YOUTUBE_SKIP_AD",
        "FULLSCREEN_YOUTUBE", "YOUTUBE_FULLSCREEN"
    )
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val nonQueryActions = setOf(
            "OPEN_YOUTUBE", "LAUNCH_YOUTUBE",
            "PAUSE_YOUTUBE", "YOUTUBE_PAUSE",
            "RESUME_YOUTUBE", "YOUTUBE_RESUME",
            "NEXT_YOUTUBE_VIDEO", "YOUTUBE_NEXT",
            "PREVIOUS_YOUTUBE_VIDEO", "YOUTUBE_PREVIOUS",
            "SKIP_AD", "YOUTUBE_SKIP_AD",
            "FULLSCREEN_YOUTUBE", "YOUTUBE_FULLSCREEN"
        )
        if (request.action in nonQueryActions) return true
        val query = (request.arguments.get("query")?.asString
            ?: request.arguments.get("search_query")?.asString
            ?: "").trim()
        return query.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val action = request.action.uppercase()
        Log.d(TAG, "Executing YouTube action: '$action'")

        if (action == "OPEN_YOUTUBE" || action == "LAUNCH_YOUTUBE") {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            return if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = true,
                    metadata = JSONObject().put("action", action).put("package", "com.google.android.youtube")
                )
            } else {
                ToolResult(
                    success = false,
                    toolName = name,
                    message = "YouTube app is not installed on this device"
                )
            }
        }

        return when (action) {
            "PAUSE_YOUTUBE", "YOUTUBE_PAUSE" -> {
                try {
                    val success = YoutubeAutomation.pausePlayback(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = success,
                        metadata = JSONObject().put("action", action)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "RESUME_YOUTUBE", "YOUTUBE_RESUME" -> {
                try {
                    val success = YoutubeAutomation.resumePlayback(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = success,
                        metadata = JSONObject().put("action", action)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "NEXT_YOUTUBE_VIDEO", "YOUTUBE_NEXT" -> {
                try {
                    val success = YoutubeAutomation.nextVideo(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = success,
                        metadata = JSONObject().put("action", action)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "PREVIOUS_YOUTUBE_VIDEO", "YOUTUBE_PREVIOUS" -> {
                try {
                    val success = YoutubeAutomation.previousVideo(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = success,
                        metadata = JSONObject().put("action", action)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "SKIP_AD", "YOUTUBE_SKIP_AD" -> {
                try {
                    val success = YoutubeAutomation.skipAdIfPresent(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        metadata = JSONObject().put("action", action).put("ad_skipped", success)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "FULLSCREEN_YOUTUBE", "YOUTUBE_FULLSCREEN" -> {
                try {
                    val success = YoutubeAutomation.toggleFullscreen(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = success,
                        metadata = JSONObject().put("action", action)
                    )
                } catch (e: Exception) {
                    ToolResult(success = false, toolName = name, message = e.message)
                }
            }
            "YOUTUBE_SEARCH", "SEARCH_YOUTUBE", "PLAY_YOUTUBE" -> {
                val query = (request.arguments.get("query")?.asString
                    ?: request.arguments.get("search_query")?.asString
                    ?: "").trim()

                if (query.isEmpty()) {
                    return ToolResult(
                        success = false,
                        toolName = name,
                        message = "Missing search query for YouTube search action"
                    )
                }

                try {
                    val success = YoutubeAutomation.searchAndPlay(context, query)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("query", query)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing YouTube automation", e)
                    ToolResult(
                        success = false,
                        toolName = name,
                        message = e.message ?: "Unknown error during YouTube automation"
                    )
                }
            }
            else -> {
                ToolResult(
                    success = false,
                    toolName = name,
                    message = "Unsupported action: $action"
                )
            }
        }
    }
}
