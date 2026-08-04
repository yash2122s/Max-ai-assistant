package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.actions.CameraAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.voice.assistant.ConnectionManager
import com.example.voice.vision.ScreenCaptureProvider
import org.json.JSONObject

class CameraTool(private val cameraAction: CameraAction) : Tool {
    override val name: String = "camera"
    override val supportedActions: Set<String> = setOf("TAKE_SCREENSHOT", "TAKE_PHOTO")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val actionName = request.action.uppercase()
            if (actionName == "TAKE_SCREENSHOT") {
                Log.d("CameraTool", "Executing TAKE_SCREENSHOT via ScreenCaptureProvider...")
                val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    val wsClient = ServiceRegistry.get<com.example.network.GeminiWebSocketClient>(ServiceType.VOICE)
                    wsClient?.sendVideoFrame(jpeg)
                    Log.d("CameraTool", "Successfully captured screen frame (${jpeg.size} bytes) and transmitted to Gemini WebSocket")
                    
                    val meta = JSONObject().apply {
                        put("image_size_bytes", jpeg.size)
                        put("status", "Screen capture frame transmitted successfully to model")
                    }
                    
                    return ToolResult(
                        success = true,
                        toolName = name,
                        message = "Screen capture frame successfully sent to Gemini Live. You can now analyze the user's screen content.",
                        metadata = meta,
                        verificationRequired = false
                    )
                } else {
                    Log.w("CameraTool", "ScreenCaptureProvider returned null/empty bytes, falling back to CameraAction")
                }
            }
            
            val jsonPayload = JSONObject(request.arguments.toString())
            jsonPayload.put("action", request.action)
            cameraAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CAMERA_ERROR",
                message = e.message ?: "Camera/Screenshot operation failed"
            )
        }
    }
}
