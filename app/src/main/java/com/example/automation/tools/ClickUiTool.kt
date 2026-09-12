package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.GroundingStatus
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService

class ClickUiTool : Tool {
    private val TAG = "ClickUiTool"
    override val name: String = "click_element"
    override val supportedActions: Set<String> = setOf("CLICK_TEXT", "CLICK_ELEMENT", "CLICK_UI", "CLICK_INDEX", "TAP")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val args = request.arguments
        val index = args.get("index")?.asInt ?: args.get("element_index")?.asInt
        val text = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val desc = args.get("description")?.asString
            ?: args.get("desc")?.asString
            ?: ""
        val resId = args.get("resourceId")?.asString
            ?: args.get("id")?.asString
            ?: ""
        return (index != null && index > 0) || text.isNotBlank() || desc.isNotBlank() || resId.isNotBlank()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val index = args.get("index")?.asInt ?: args.get("element_index")?.asInt
        val text = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val desc = args.get("description")?.asString
            ?: args.get("desc")?.asString
            ?: ""
        val resId = args.get("resourceId")?.asString
            ?: args.get("id")?.asString
            ?: ""

        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service == null) {
            Log.e(TAG, "JarvisAccessibilityService is not running or accessible")
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "SERVICE_UNAVAILABLE",
                message = "Accessibility service is not active"
            )
        }

        // 1. Direct indexed element click if index is provided
        if (index != null && index > 0) {
            Log.d(TAG, "Executing indexed click for index=[$index]")
            val (success, message) = service.clickIndexedElement(index)
            return ToolResult(
                success = success,
                toolName = name,
                message = message,
                errorCode = if (!success) "INDEX_CLICK_FAILED" else null
            )
        }

        // 2. Screen Grounding with live Accessibility Tree (bounded polling up to 2500ms)
        val query = text.ifEmpty { desc }.ifEmpty { resId }
        Log.d("UI_GROUND", "[UI_GROUND] Grounding target=\"$query\" resId=\"$resId\" desc=\"$desc\"")
        val startTime = System.currentTimeMillis()
        var lastMessage = "Target '$query' not found on current screen"
        var clickSuccess = false

        while (System.currentTimeMillis() - startTime < 2500L) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                // Tier 1: High-confidence UiTargetResolver
                val resolverResult = com.example.automation.resolver.UiTargetResolver.resolve(
                    query = query,
                    explicitResourceId = resId.ifEmpty { null },
                    explicitContentDescription = desc.ifEmpty { null },
                    requireClickable = true,
                    rootNode = rootNode
                )

                if (resolverResult.success && resolverResult.confidence >= com.example.automation.resolver.UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD && resolverResult.matchedNode != null) {
                    val node = resolverResult.matchedNode
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    com.example.automation.event.AutomationEventBus.publish(
                        com.example.automation.event.AutomationEvent.TargetGrounded(
                            executionId = request.executionId,
                            bounds = bounds,
                            label = query,
                            actionType = "CLICK"
                        )
                    )
                    Log.d("UI_CLICK", "[UI_CLICK] Resolved target '$query' (${resolverResult.method}) with confidence ${resolverResult.confidence}. Performing click escalation.")
                    val escalationResult = service.performClickWithEscalation(node)
                    clickSuccess = (escalationResult == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            escalationResult == ClickEscalationResult.GESTURE_CONFIRMED)
                    lastMessage = "Clicked '$query' successfully ($escalationResult)"
                    if (clickSuccess) break
                }

                // Tier 2: ScreenGroundingEngine
                val groundingResult = ScreenGroundingEngine.groundTarget(
                    target = query,
                    rootNode = rootNode,
                    explicitResourceId = resId.ifEmpty { null },
                    explicitContentDescription = desc.ifEmpty { null }
                )

                when (groundingResult.status) {
                    GroundingStatus.FOUND -> {
                        val node = groundingResult.matchedNode
                        if (node != null) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            com.example.automation.event.AutomationEventBus.publish(
                                com.example.automation.event.AutomationEvent.TargetGrounded(
                                    executionId = request.executionId,
                                    bounds = bounds,
                                    label = query,
                                    actionType = "CLICK"
                                )
                            )
                            Log.d("UI_CLICK", "[UI_CLICK] Grounded node found at $bounds. Executing click escalation on \"$query\"")
                            val escalationResult = service.performClickWithEscalation(node)
                            clickSuccess = (escalationResult == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                                    escalationResult == ClickEscalationResult.GESTURE_CONFIRMED)
                            lastMessage = "Clicked '$query' successfully ($escalationResult)"
                            Log.d("UI_VERIFY", "[UI_VERIFY] Grounded click result: $escalationResult (success=$clickSuccess)")
                            if (clickSuccess) break
                        }
                    }
                    GroundingStatus.AMBIGUOUS -> {
                        Log.w("UI_GROUND", "[UI_GROUND] Ambiguous match for \"$query\" (${groundingResult.candidateCount} candidates)")
                        lastMessage = groundingResult.reason
                    }
                    GroundingStatus.NOT_FOUND -> {
                        lastMessage = groundingResult.reason
                    }
                }
            }

            kotlinx.coroutines.delay(200L)
        }

        // 3. Fallback to legacy clickVisibleText only if grounding did not resolve within timeout
        if (!clickSuccess && (text.isNotEmpty() || desc.isNotEmpty())) {
            val (success, message) = service.clickVisibleText(text, desc)
            if (success) {
                clickSuccess = true
                lastMessage = message
            }
        }

        return ToolResult(
            success = clickSuccess,
            toolName = name,
            message = lastMessage,
            errorCode = if (!clickSuccess) "CLICK_FAILED" else null
        )
    }
}
