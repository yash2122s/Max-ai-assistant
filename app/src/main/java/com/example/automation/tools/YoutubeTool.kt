package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
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
                    errorCode = "YOUTUBE_NOT_INSTALLED",
                    message = "YouTube application is not installed on this device"
                )
            }
        }

        return try {
            when (action) {
                "PAUSE_YOUTUBE", "YOUTUBE_PAUSE" -> {
                    val success = com.example.automation.YoutubeAutomation.pausePlayback(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", action)
                    )
                }
                "RESUME_YOUTUBE", "YOUTUBE_RESUME" -> {
                    val success = com.example.automation.YoutubeAutomation.resumePlayback(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", action)
                    )
                }
                "NEXT_YOUTUBE_VIDEO", "YOUTUBE_NEXT" -> {
                    val success = com.example.automation.YoutubeAutomation.nextVideo(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", action)
                    )
                }
                "PREVIOUS_YOUTUBE_VIDEO", "YOUTUBE_PREVIOUS" -> {
                    val success = com.example.automation.YoutubeAutomation.previousVideo(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", action)
                    )
                }
                "SKIP_AD", "YOUTUBE_SKIP_AD" -> {
                    val success = com.example.automation.YoutubeAutomation.skipAdIfPresent(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = false,
                        metadata = JSONObject().put("action", action)
                    )
                }
                "FULLSCREEN_YOUTUBE", "YOUTUBE_FULLSCREEN" -> {
                    val success = com.example.automation.YoutubeAutomation.toggleFullscreen(context)
                    ToolResult(
                        success = success,
                        toolName = name,
                        verificationRequired = true,
                        metadata = JSONObject().put("action", action)
                    )
                }
                else -> {
                    // Search & Play actions
                    val query = (request.arguments.get("query")?.asString
                        ?: request.arguments.get("search_query")?.asString
                        ?: "").trim()

                    if (query.isBlank()) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_ARGUMENTS",
                            message = "YouTube search query cannot be empty"
                        )
                    }

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

