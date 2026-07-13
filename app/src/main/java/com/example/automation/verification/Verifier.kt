package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

interface Verifier {
    val supportedTools: Set<String>
    fun verify(context: Context, request: ExecutionRequest, result: ToolResult, snapshot: DeviceContext): VerificationResult
}
