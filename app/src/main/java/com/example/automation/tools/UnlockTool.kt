package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.security.SecuritySettings
import com.example.security.UnlockManager
import com.example.service.JarvisAccessibilityService

class UnlockTool : Tool {
    override val name: String = "UnlockTool"
    override val supportedActions: Set<String> = setOf("UNLOCK_DEVICE")
    override val retryPolicy: com.example.automation.verification.RetryPolicy = com.example.automation.verification.RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val securitySettings = SecuritySettings(context)
        
        if (!securitySettings.autoUnlockEnabled) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "AUTO_UNLOCK_DISABLED",
                message = "Auto-unlock is currently disabled in Security Settings."
            )
        }

        val pin = securitySettings.getDecryptedPin()
        if (pin.isEmpty()) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "PIN_NOT_SET",
                message = "Device PIN is not set in Security Settings."
            )
        }

        // Wake screen if off
        val unlockManager = UnlockManager(context)
        unlockManager.ensureScreenOn()

        val service = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
        if (service == null) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "ACCESSIBILITY_SERVICE_OFF",
                message = "Accessibility service is not running or disabled."
            )
        }

        Log.d("UnlockTool", "Executing unlock with stored PIN...")
        val triggered = service.unlockWithPin(pin)

        return ToolResult(
            success = triggered,
            toolName = name,
            message = if (triggered) "Unlock sequence initiated successfully." else "Failed to initiate unlock sequence."
        )
    }
}
