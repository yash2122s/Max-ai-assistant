package com.example.automation.tools

import com.example.automation.engine.ExecutionMetrics
import com.example.automation.verification.VerificationResult
import org.json.JSONObject

data class ToolResult(
    val success: Boolean,
    val toolName: String,
    val attemptCount: Int = 1,
    val errorCode: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
    val verificationRequired: Boolean = true,
    val verification: VerificationResult? = null,
    val metrics: ExecutionMetrics? = null,
    val metadata: JSONObject = JSONObject()
)
