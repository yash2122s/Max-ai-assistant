package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.verification.RetryPolicy
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject

class RoutineTool : Tool {
    private val TAG = "RoutineTool"

    override val name: String = "routines"
    override val supportedActions: Set<String> = setOf("RUN_ROUTINE", "CREATE_ROUTINE", "DELETE_ROUTINE", "LIST_ROUTINES")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    data class Step(
        val action: String,
        val arguments: JSONObject
    )

    private val builtInRoutines = mapOf(
        "sleep" to listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 0)),
            Step("SET_BLUETOOTH", JSONObject().put("enabled", false))
        ),
        "morning" to listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", false)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 60)),
            Step("GET_DEVICE_STATUS", JSONObject())
        ),
        "work" to listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_VOLUME", JSONObject().put("percent", 0))
        ),
        "gaming" to listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 80)),
            Step("SET_VOLUME", JSONObject().put("percent", 50))
        )
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val argsObj = JSONObject(request.arguments.toString())
            when (request.action) {
                "CREATE_ROUTINE" -> handleCreateRoutine(context, argsObj)
                "DELETE_ROUTINE" -> handleDeleteRoutine(context, argsObj)
                "LIST_ROUTINES" -> handleListRoutines(context)
                "RUN_ROUTINE" -> handleRunRoutine(context, argsObj)
                else -> ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "UNSUPPORTED_ACTION",
                    message = "Action ${request.action} not supported"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during routine tool execute", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "ROUTINE_ERROR",
                message = e.message ?: "Unknown routines error"
            )
        }
    }

    private fun handleCreateRoutine(context: Context, args: JSONObject): ToolResult {
        val routineName = args.optString("routineName", "").trim()
        if (routineName.isEmpty()) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "INVALID_ARGUMENT",
                message = "routineName parameter is missing or empty"
            )
        }

        val normalized = normalizeName(routineName)
        if (normalized in builtInRoutines.keys) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "BUILTIN_PROTECTION",
                message = "Cannot create or overwrite built-in routine: '$routineName'"
            )
        }

        val steps = mutableListOf<Step>()
        try {
            if (args.has("steps_json")) {
                val jsonStr = args.getString("steps_json")
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val action = obj.getString("action")
                    val arguments = obj.optJSONObject("arguments") ?: JSONObject()
                    steps.add(Step(action, arguments))
                }
            } else if (args.has("steps")) {
                val array = args.getJSONArray("steps")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val action = obj.getString("action")
                    val arguments = obj.optJSONObject("arguments") ?: JSONObject()
                    steps.add(Step(action, arguments))
                }
            } else {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "INVALID_ARGUMENT",
                    message = "steps_json or steps parameter is missing or empty"
                )
            }
        } catch (e: Exception) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "JSON_PARSE_ERROR",
                message = "Failed to parse routine steps: ${e.message}"
            )
        }

        if (steps.isEmpty()) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "EMPTY_ROUTINE",
                message = "Routine must contain at least one execution step"
            )
        }

        // Validate each step's action compatibility via ToolRegistry
        for (step in steps) {
            val targetTool = ToolRegistry.getToolForAction(step.action)
            if (targetTool == null) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "INVALID_STEP_ACTION",
                    message = "Validation failed: Action '${step.action}' is not supported by any registered tool."
                )
            }
        }

        // Serialize and save to SharedPreferences
        val serialized = serializeSteps(steps)
        val prefs = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        prefs.edit().putString(normalized, serialized).apply()

        Log.d(TAG, "Successfully created custom routine: $normalized with ${steps.size} steps")
        return ToolResult(
            success = true,
            toolName = name,
            message = "Routine '$routineName' created/updated successfully with ${steps.size} steps."
        )
    }

    private fun handleDeleteRoutine(context: Context, args: JSONObject): ToolResult {
        val routineName = args.optString("routineName", "").trim()
        if (routineName.isEmpty()) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "INVALID_ARGUMENT",
                message = "routineName parameter is missing or empty"
            )
        }

        val normalized = normalizeName(routineName)
        if (normalized in builtInRoutines.keys) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "BUILTIN_PROTECTION",
                message = "Cannot delete built-in routine: '$routineName'"
            )
        }

        val prefs = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        if (!prefs.contains(normalized)) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "ROUTINE_NOT_FOUND",
                message = "Routine '$routineName' not found"
            )
        }

        prefs.edit().remove(normalized).apply()
        Log.d(TAG, "Deleted custom routine: $normalized")
        return ToolResult(
            success = true,
            toolName = name,
            message = "Routine '$routineName' deleted successfully."
        )
    }

    private fun handleListRoutines(context: Context): ToolResult {
        val listObj = JSONObject()
        
        // Load built-in routines
        val builtInArray = JSONArray()
        for ((key, steps) in builtInRoutines) {
            val rObj = JSONObject()
            rObj.put("name", key)
            rObj.put("type", "builtin")
            val stepsArr = JSONArray()
            for (step in steps) {
                stepsArr.put(JSONObject().apply {
                    put("action", step.action)
                    put("arguments", step.arguments)
                })
            }
            rObj.put("steps", stepsArr)
            builtInArray.put(rObj)
        }
        listObj.put("builtin_routines", builtInArray)

        // Load custom routines
        val customArray = JSONArray()
        val prefs = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        val allPrefs = prefs.all
        for ((key, value) in allPrefs) {
            if (value is String) {
                try {
                    val steps = deserializeSteps(value)
                    val rObj = JSONObject()
                    rObj.put("name", key)
                    rObj.put("type", "custom")
                    val stepsArr = JSONArray()
                    for (step in steps) {
                        stepsArr.put(JSONObject().apply {
                            put("action", step.action)
                            put("arguments", step.arguments)
                        })
                    }
                    rObj.put("steps", stepsArr)
                    customArray.put(rObj)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing custom routine: $key", e)
                }
            }
        }
        listObj.put("custom_routines", customArray)

        return ToolResult(
            success = true,
            toolName = name,
            message = "Routines retrieved successfully.",
            metadata = listObj
        )
    }

    private suspend fun handleRunRoutine(context: Context, args: JSONObject): ToolResult {
        val routineName = args.optString("routineName", "").trim()
        val dryRun = args.optBoolean("dryRun", false)
        
        if (routineName.isEmpty()) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "INVALID_ARGUMENT",
                message = "routineName parameter is missing or empty"
            )
        }

        val normalized = normalizeName(routineName)
        val steps = builtInRoutines[normalized] ?: run {
            val prefs = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(normalized, null)
            if (jsonStr != null) {
                try {
                    deserializeSteps(jsonStr)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }

        if (steps == null) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "ROUTINE_NOT_FOUND",
                message = "Routine '$routineName' not found."
            )
        }

        val traceArray = JSONArray()
        if (dryRun) {
            Log.d(TAG, "Running dry-run simulation for routine: $normalized")
            for ((index, step) in steps.withIndex()) {
                val targetTool = ToolRegistry.getToolForAction(step.action)
                val stepStatus = if (targetTool != null) "validated" else "unsupported"
                val stepTrace = JSONObject().apply {
                    put("step", index + 1)
                    put("action", step.action)
                    put("arguments", step.arguments)
                    put("status", stepStatus)
                }
                traceArray.put(stepTrace)
            }
            return ToolResult(
                success = true,
                toolName = name,
                message = "Dry-run simulation for routine '$routineName' completed successfully.",
                metadata = JSONObject().put("simulation_trace", traceArray)
            )
        }

        Log.d(TAG, "Executing routine steps sequentially: $normalized")
        val resultsArray = JSONArray()
        var hasFailedStep = false

        for ((index, step) in steps.withIndex()) {
            Log.d(TAG, "Executing routine step ${index + 1}/${steps.size}: ${step.action}")
            val stepResultObj = JSONObject().apply {
                put("step", index + 1)
                put("action", step.action)
            }

            try {
                val gsonArgs = JsonParser.parseString(step.arguments.toString()).asJsonObject
                val subRequest = ExecutionRequest(
                    action = step.action,
                    arguments = gsonArgs,
                    source = ExecutionSource.MANUAL
                )
                
                val toolResult = ExecutionEngine.execute(context, subRequest)
                stepResultObj.put("success", toolResult.success)
                stepResultObj.put("message", toolResult.message ?: "")
                if (!toolResult.success) {
                    hasFailedStep = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step ${step.action}", e)
                stepResultObj.put("success", false)
                stepResultObj.put("message", e.message ?: "Execution error")
                hasFailedStep = true
            }
            resultsArray.put(stepResultObj)
        }

        return ToolResult(
            success = !hasFailedStep,
            toolName = name,
            message = if (hasFailedStep) "Routine '$routineName' completed with errors." else "Routine '$routineName' executed successfully.",
            metadata = JSONObject().put("execution_log", resultsArray)
        )
    }

    private fun normalizeName(name: String): String {
        return name.trim().lowercase().replace("\\s+".toRegex(), "_")
    }

    private fun serializeSteps(steps: List<Step>): String {
        val array = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("action", step.action)
            obj.put("arguments", step.arguments)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeSteps(jsonStr: String): List<Step> {
        val list = mutableListOf<Step>()
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val action = obj.getString("action")
            val arguments = obj.optJSONObject("arguments") ?: JSONObject()
            list.add(Step(action, arguments))
        }
        return list
    }
}
