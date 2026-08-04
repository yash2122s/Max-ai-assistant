package com.example.automation.tools

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.service.WhatsAppNotificationService
import org.json.JSONObject

class MediaTool : Tool {
    private val TAG = "MediaTool"
    override val name: String = "media"
    override val supportedActions: Set<String> = setOf("PLAY_MEDIA", "PAUSE_MEDIA", "NEXT_MEDIA", "PREVIOUS_MEDIA")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val action = request.action
        
        return try {
            val service = WhatsAppNotificationService.getInstance()
            if (service == null) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "LISTENER_NOT_RUNNING",
                    message = "Notification Listener service is not active. Please grant Notification Access in Settings."
                )
            }

            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val serviceComponent = ComponentName(context, WhatsAppNotificationService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(serviceComponent)

            if (controllers.isEmpty()) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "NO_ACTIVE_SESSIONS",
                    message = "No active media playback sessions found on the device."
                )
            }

            controllers.forEach { controller ->
                val controls = controller.transportControls
                when (action) {
                    "PLAY_MEDIA" -> controls.play()
                    "PAUSE_MEDIA" -> controls.pause()
                    "NEXT_MEDIA" -> controls.skipToNext()
                    "PREVIOUS_MEDIA" -> controls.skipToPrevious()
                }
            }

            val actionDescription = when (action) {
                "PLAY_MEDIA" -> "Resume playback"
                "PAUSE_MEDIA" -> "Pause playback"
                "NEXT_MEDIA" -> "Skip to next track"
                "PREVIOUS_MEDIA" -> "Skip to previous track"
                else -> "Media control action"
            }

            ToolResult(
                success = true,
                toolName = name,
                message = "$actionDescription successfully triggered on active sessions."
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Missing Notification Listener permission for MediaSessions", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "PERMISSION_DENIED",
                message = "Media control requires Notification Access permission."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing media action: $action", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "MEDIA_ERROR",
                message = e.message ?: "Unknown media control error"
            )
        }
    }
}
