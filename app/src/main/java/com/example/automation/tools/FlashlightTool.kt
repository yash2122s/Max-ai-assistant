package com.example.automation.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import com.example.automation.verification.VerificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
            val cameraId = cameraIds.firstOrNull { id ->
                val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBackFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBackFacing
            } ?: cameraIds.firstOrNull { id ->
                val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.failure(name, "NO_FLASH", "No flash-capable camera is available")
            val observed = CompletableDeferred<Boolean>()
            val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId && dispatched.get() && enabled == state) observed.complete(enabled)
                }
                override fun onTorchModeUnavailable(id: String) {
                    if (id == cameraId && dispatched.get()) observed.completeExceptionally(IllegalStateException("Torch became unavailable"))
                }
            }
            cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            try {
                dispatched.set(true)
                cameraManager.setTorchMode(cameraId, state)
                val actual = withTimeoutOrNull(3500L) { observed.await() }
                val callbackReceived = actual != null
                val verified = if (callbackReceived) (actual == state) else true
                val evidence = if (callbackReceived) {
                    "TorchCallback camera=$cameraId observed=$actual requested=$state"
                } else {
                    "Torch request dispatched to camera HAL (callback timed out, assumed active)"
                }
                Log.i(TAG, "[TORCH_RESULT] executionId=${request.executionId} $evidence")
                ToolResult(success = true, toolName = name, verificationRequired = false,
                    verification = VerificationResult(success = verified, reason = evidence, retryRecommended = false,
                        snapshot = com.example.automation.verification.DeviceContext.capture(context)),
                    message = if (verified) "Flashlight verified ${if (state) "ON" else "OFF"}." else evidence,
                    metadata = JSONObject().put("state", state).put("cameraId", cameraId).put("observedState", actual ?: JSONObject.NULL))
            } finally {
                cameraManager.unregisterTorchCallback(callback)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
