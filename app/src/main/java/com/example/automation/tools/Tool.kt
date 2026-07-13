package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy

interface Tool {
    val name: String
    val supportedActions: Set<String>
    val retryPolicy: RetryPolicy
    val capabilities: ToolCapabilities
    
    fun validate(request: ExecutionRequest): Boolean
    suspend fun execute(context: Context, request: ExecutionRequest): ToolResult
}
