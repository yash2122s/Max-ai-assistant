package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.ScreenSummaryFormatter
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class InspectScreenTool : Tool {
    override val name: String = "inspect_screen"
    override val supportedActions: Set<String> = setOf("INSPECT_SCREEN")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = false,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult =
        withContext(Dispatchers.IO) {
            val service = JarvisAccessibilityService.instance
                ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
                ?: return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "SERVICE_UNAVAILABLE",
                    message = "Accessibility Service is not running."
                )

            val waitForContent = request.arguments.get("wait_for_content")?.asBoolean ?: false

            var root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            var elements = ScreenGroundingEngine.scanTargets(root)

            if (waitForContent) {
                var attempts = 0
                while (attempts < 4 && elements.none {
                        !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()
                    }) {
                    delay(250)
                    root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
                    elements = ScreenGroundingEngine.scanTargets(root)
                    attempts++
                }
            }

            if (root == null && elements.isEmpty()) {
                return@withContext ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "BLANK_SCREEN",
                    message = "Screen context unavailable: Screen is blank or inaccessible."
                )
            }

            // JARVIS migration — OTP notice
            val recentOtp = JarvisAccessibilityService.getRecentOtp()
            val otpNotice = if (recentOtp != null)
                "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]"
            else ""

            val screenSource = "[Current accessibility labels are available. " +
                    "Use a unique labeled target for actions.]\n"

            val summary = screenSource +
                    ScreenSummaryFormatter.format(elements, root) +
                    otpNotice

            ToolResult(
                success = true,
                toolName = name,
                message = summary,
                currentStateSummary = summary
            )
        }
}
