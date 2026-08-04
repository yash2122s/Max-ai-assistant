package com.example.automation.tools

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.automation.RemoteInputSender
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.service.WhatsAppNotificationService
import org.json.JSONArray
import org.json.JSONObject

class NotificationTool : Tool {
    private val TAG = "NotificationTool"
    override val name: String = "notification"
    override val supportedActions: Set<String> = setOf("GET_NOTIFICATIONS", "REPLY_NOTIFICATION")
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
        val args = JSONObject(request.arguments.toString())

        return try {
            val service = WhatsAppNotificationService.getInstance()
            if (service == null) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "LISTENER_NOT_RUNNING",
                    message = "Notification Listener service is not running. Please grant Notification Access in Settings."
                )
            }

            when (action) {
                "GET_NOTIFICATIONS" -> {
                    val activeNotifs = service.activeNotifications ?: emptyArray()
                    val resultList = JSONArray()
                    
                    val systemPackages = setOf("android", "com.android.systemui")

                    for (sbn in activeNotifs) {
                        if (sbn.isOngoing) continue 
                        if (systemPackages.contains(sbn.packageName)) continue

                        val extras = sbn.notification.extras ?: continue
                        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                        
                        if (title.isBlank() && text.isBlank()) continue

                        val item = JSONObject().apply {
                            put("id", sbn.id)
                            put("packageName", sbn.packageName)
                            put("sender", title)
                            put("text", text)
                            put("postTime", sbn.postTime)
                        }
                        resultList.put(item)
                    }

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Retrieved ${resultList.length()} active notifications.",
                        metadata = JSONObject().apply { put("notifications", resultList) },
                        verificationRequired = false
                    )
                }
                "REPLY_NOTIFICATION" -> {
                    val contact = args.optString("contact", "").trim()
                    val replyMessage = args.optString("message", "").trim()

                    if (contact.isBlank() || replyMessage.isBlank()) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_ARGUMENTS",
                            message = "Both contact name and message reply text are required."
                        )
                    }

                    val activeNotifs = service.activeNotifications ?: emptyArray()
                    
                    val sbn = activeNotifs.firstOrNull { s ->
                        val extras = s.notification.extras ?: return@firstOrNull false
                        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                        title.equals(contact, ignoreCase = true) || s.packageName.equals(contact, ignoreCase = true)
                    }

                    if (sbn == null) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NOTIFICATION_NOT_FOUND",
                            message = "No active notification found from: '$contact'."
                        )
                    }

                    val sent = RemoteInputSender.sendReply(context, sbn, replyMessage)
                    if (sent) {
                        ToolResult(
                            success = true,
                            toolName = name,
                            message = "Successfully sent reply message to '$contact'."
                        )
                    } else {
                        ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "REPLY_FAILED",
                            message = "Target notification doesn't support direct remote input replies."
                        )
                    }
                }
                else -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "UNSUPPORTED_ACTION",
                        message = "Unsupported notification action: $action"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing notification action: $action", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "NOTIFICATION_ERROR",
                message = e.message ?: "Unknown error occurred"
            )
        }
    }
}
