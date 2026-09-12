package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.memory.data.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ForgetMemoryTool : Tool {
    override val name: String = "forget_memory"
    override val supportedActions: Set<String> = setOf("FORGET_MEMORY", "DELETE_MEMORY")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = false,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val jsonStr = request.arguments.toString()
            val jsonObj = JSONObject(jsonStr)

            val query = jsonObj.optString("query", "")
                .ifBlank { jsonObj.optString("topic", "") }
                .ifBlank { jsonObj.optString("content", "") }
                .ifBlank { jsonObj.optString("text", "") }

            if (query.isBlank()) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "EMPTY_QUERY",
                    message = "Please specify what memory you would like me to forget."
                )
            }

            val repository = MemoryRepository.getInstance(context)
            val deletedCount = repository.forgetMemory(query)

            if (deletedCount > 0) {
                ToolResult(
                    success = true,
                    toolName = name,
                    message = "Successfully forgot $deletedCount memory record(s) matching '$query'."
                )
            } else {
                ToolResult(
                    success = true,
                    toolName = name,
                    message = "No memories found matching '$query'."
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "FORGET_FAILED",
                message = "Failed to forget memory: ${e.message}"
            )
        }
    }
}
