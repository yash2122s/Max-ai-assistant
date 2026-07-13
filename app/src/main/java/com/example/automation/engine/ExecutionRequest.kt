package com.example.automation.engine

import com.google.gson.JsonObject
import java.util.UUID

data class ExecutionRequest(
    val executionId: UUID = UUID.randomUUID(),
    val action: String,
    val arguments: JsonObject = JsonObject(),
    val source: ExecutionSource = ExecutionSource.GEMINI_LIVE,
    val cancellationToken: CancellationToken = CancellationToken(),
    val timestamp: Long = System.currentTimeMillis()
)
