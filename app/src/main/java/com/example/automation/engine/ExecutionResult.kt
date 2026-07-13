package com.example.automation.engine

import org.json.JSONObject

data class ExecutionResult(
    val success: Boolean,
    val output: JSONObject?,
    val error: String?,
    val duration: Long
)
