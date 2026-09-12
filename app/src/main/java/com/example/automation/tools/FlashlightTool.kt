package com.example.automation.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

import com.example.utils.asBooleanOrNull

class FlashlightTool : Tool {
    private val TAG = "FlashlightTool"
    override val name: String = "flashlight"
    override val supportedActions: Set<String> = setOf("FLASHLIGHT_ON", "FLASHLIGHT_OFF")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val arguments = request.arguments
        val state = arguments.get("state").asBooleanOrNull()
            ?: arguments.get("enabled").asBooleanOrNull()
            ?: (request.action.uppercase() == "FLASHLIGHT_ON")

        Log.d(TAG, "Toggling flashlight state to: $state")
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                return ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "NO_CAMERA",
                    message = "No camera hardware available for flashlight"
                )
            }
            val cameraId = cameraIds[0]
            cameraManager.setTorchMode(cameraId, state)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false,
                metadata = JSONObject().put("state", state)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CAMERA_ERROR",
                message = e.message ?: "Unknown camera/torch error"
            )
        }
    }
}
