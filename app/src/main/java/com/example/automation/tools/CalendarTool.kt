package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.CalendarAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class CalendarTool(private val calendarAction: CalendarAction) : Tool {
    override val name: String = "calendar"
    override val supportedActions: Set<String> = setOf("GET_CALENDAR_EVENTS", "ADD_CALENDAR_EVENT")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            jsonPayload.put("action", request.action)
            
            if (request.action == "GET_CALENDAR_EVENTS") {
                val events = calendarAction.getCalendarEvents(context)
                return ToolResult(
                    success = true,
                    toolName = name,
                    message = "Retrieved ${events.length()} calendar events.",
                    metadata = JSONObject().put("events", events),
                    verificationRequired = true
                )
            }
            
            calendarAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CALENDAR_ERROR",
                message = e.message ?: "Calendar operation failed"
            )
        }
    }
}
