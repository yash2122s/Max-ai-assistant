package com.example.automation.engine

import org.json.JSONObject

data class ExecutionPlan(
    val steps: List<ExecutionStep>
)

data class ExecutionStep(
    val toolName: String,
    val arguments: JSONObject
)
