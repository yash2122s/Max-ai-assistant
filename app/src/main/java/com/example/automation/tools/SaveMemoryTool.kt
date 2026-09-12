package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SaveMemoryTool : Tool {
    override val name: String = "save_memory"
    override val supportedActions: Set<String> = setOf("SAVE_MEMORY")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = false,
        requiresAccessibility = false,
        requiresNetwork = true,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val jsonStr = request.arguments.toString()
            val jsonObj = JSONObject(jsonStr)

            val title = jsonObj.optString("title", "").ifBlank { "User Fact" }
            val content = jsonObj.optString("content", "")
            if (content.isBlank()) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "EMPTY_CONTENT",
                    message = "Memory content cannot be empty"
                )
            }

            val category = jsonObj.optString("category", MemoryCategory.PERSONAL.displayName)
            val typeStr = jsonObj.optString("type", "FACT").uppercase()
            val memoryType = try {
                MemoryType.valueOf(typeStr)
            } catch (e: Exception) {
                MemoryType.FACT
            }

            var tagsStr = jsonObj.optString("tags", "")
            if (tagsStr.isBlank()) {
                val hashtags = Regex("#([\\w:]+)").findAll("$title $content").map { it.groupValues[1] }.toList()
                if (hashtags.isNotEmpty()) {
                    tagsStr = hashtags.joinToString(",")
                }
            }

            val memory = PermanentMemory(
                title = title,
                content = content,
                category = category,
                type = memoryType,
                source = MemorySource.AUTO,
                tags = tagsStr
            )

            // Use singleton repository — NO new instance per call
            val repository = MemoryRepository.getInstance(context)
            val result = repository.addMemory(memory)

            return@withContext when (result) {
                is com.example.memory.data.MemoryValidationResult.Success -> {
                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Memory successfully saved: '$title - $content'"
                    )
                }
                is com.example.memory.data.MemoryValidationResult.Error -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "VALIDATION_FAILED",
                        message = result.message
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "SAVE_FAILED",
                message = "Failed to save memory: ${e.message}"
            )
        }
    }
}
