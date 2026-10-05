package com.example.automation.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.keyboard.KeyboardController
import com.example.automation.state.SemanticNode
import com.example.service.JarvisAccessibilityService

enum class ExecutionTier {
    TIER_1_ACCESSIBILITY_API,
    TIER_2_GESTURE,
    TIER_3_SHELL,
    NONE
}

data class ActionResult(
    val success: Boolean,
    val tier: ExecutionTier = ExecutionTier.NONE,
    val message: String = ""
)

sealed class GenericAction {
    data class ClickNode(val target: SemanticNode) : GenericAction()
    data class LongClickNode(val target: SemanticNode) : GenericAction()
    data class SetTextNode(val target: SemanticNode, val text: String) : GenericAction()
    data class Scroll(val direction: String = "DOWN", val target: SemanticNode? = null) : GenericAction()

    data class ClickCoordinates(val x: Int, val y: Int) : GenericAction()
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L
    ) : GenericAction()

    data class KeyPress(val keyName: String) : GenericAction()
    data class TypeText(val text: String) : GenericAction()

    sealed class GlobalSystemAction(val globalActionId: Int) : GenericAction() {
        object Back : GlobalSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
        object Home : GlobalSystemAction(AccessibilityService.GLOBAL_ACTION_HOME)
        object Recents : GlobalSystemAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        object Notifications : GlobalSystemAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        object QuickSettings : GlobalSystemAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }
}

object GenericActionExecutor {
    private const val TAG = "GenericActionExecutor"

    var testGestureDispatcher: ((Int, Int) -> Boolean)? = null
    var testGlobalActionDispatcher: ((Int) -> Boolean)? = null

    suspend fun execute(action: GenericAction): ActionResult {
        return when (action) {
            is GenericAction.ClickNode -> executeClickNode(action.target)
            is GenericAction.LongClickNode -> executeLongClickNode(action.target)
            is GenericAction.SetTextNode -> executeSetTextNode(action.target, action.text)
            is GenericAction.ClickCoordinates -> executeClickCoordinates(action.x, action.y)
            is GenericAction.Swipe -> executeSwipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
            is GenericAction.Scroll -> executeScroll(action.direction, action.target)
            is GenericAction.GlobalSystemAction -> executeGlobalSystemAction(action.globalActionId)
            is GenericAction.KeyPress -> executeKeyPress(action.keyName)
            is GenericAction.TypeText -> executeTypeText(action.text)
        }
    }

    suspend fun executeComputerAction(action: com.example.automation.computeruse.ComputerAction): ActionResult {
        return when (action) {
            is com.example.automation.computeruse.ComputerAction.TapElement -> {
                val snapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
                val targetQuery = action.targetText.ifBlank { action.contentDescription ?: action.resourceId ?: "" }
                val grounded = if (targetQuery.isNotBlank() && snapshot.nodes.isNotEmpty()) {
                    com.example.automation.grounding.ScoredTargetGroundingEngine.groundTarget(snapshot, targetQuery).bestCandidate
                } else null

                val target = grounded ?: SemanticNode(
                    id = action.resourceId ?: "target_${targetQuery.hashCode()}",
                    text = action.targetText.takeIf { it.isNotBlank() },
                    contentDescription = action.contentDescription,
                    bounds = action.grounding?.bounds ?: android.graphics.Rect(),
                    isClickable = true
                )
                executeClickNode(target)
            }

            is com.example.automation.computeruse.ComputerAction.TapCoordinate -> {
                executeClickCoordinates(action.x, action.y)
            }

            is com.example.automation.computeruse.ComputerAction.LongPress -> {
                if (dispatchLongPress(action.x, action.y, action.durationMs)) {
                    ActionResult(true, ExecutionTier.TIER_2_GESTURE, "Long pressed at (${action.x}, ${action.y})")
                } else if (JarvisAccessibilityService.runShell("input swipe ${action.x} ${action.y} ${action.x} ${action.y} ${action.durationMs}")) {
                    ActionResult(true, ExecutionTier.TIER_3_SHELL, "Long pressed via Shell")
                } else {
                    ActionResult(false, ExecutionTier.NONE, "Long press failed")
                }
            }

            is com.example.automation.computeruse.ComputerAction.Swipe -> {
                executeSwipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
            }

            is com.example.automation.computeruse.ComputerAction.Drag -> {
                executeSwipe(action.x1, action.y1, action.x2, action.y2, 800L)
            }

            is com.example.automation.computeruse.ComputerAction.TypeText -> {
                val fieldQuery = action.targetField
                val targetNode = if (!fieldQuery.isNullOrBlank()) {
                    val snapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
                    com.example.automation.grounding.ScoredTargetGroundingEngine.groundTarget(snapshot, fieldQuery).bestCandidate
                } else null

                if (targetNode != null) {
                    val res = executeSetTextNode(targetNode, action.text)
                    if (res.success && action.pressEnterAfter) {
                        executeKeyPress("ENTER")
                    }
                    res
                } else {
                    val res = executeTypeText(action.text)
                    if (res.success && action.pressEnterAfter) {
                        executeKeyPress("ENTER")
                    }
                    res
                }
            }

            is com.example.automation.computeruse.ComputerAction.PressKey -> {
                executeKeyPress(action.keyNameOrCode)
            }

            is com.example.automation.computeruse.ComputerAction.PressEnter -> {
                executeKeyPress("ENTER")
            }

            is com.example.automation.computeruse.ComputerAction.PressBack -> {
                executeGlobalSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }

            is com.example.automation.computeruse.ComputerAction.Scroll -> {
                executeScroll(action.direction, null)
            }

            else -> ActionResult(false, ExecutionTier.NONE, "Action ${action.actionName} not supported in GenericActionExecutor")
        }
    }

    private fun executeClickNode(target: SemanticNode): ActionResult {
        // Tier 1: Accessibility Action API
        val liveNode = LiveNodeResolver.resolveLiveNode(target)
        if (liveNode != null) {
            val clickableTarget = LiveNodeResolver.resolveClickableTarget(liveNode)
            try {
                if (clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "Tier 1: Clicked ${target.id} via Accessibility ACTION_CLICK")
                    return ActionResult(true, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Clicked ${target.id} via ACTION_CLICK")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 performAction failed", e)
            }
        }

        // Tier 2: Gesture Dispatch
        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        if (cx > 0 && cy > 0) {
            if (dispatchTap(cx, cy)) {
                Log.d(TAG, "Tier 2: Tapped bounds center ($cx, $cy) for ${target.id}")
                return ActionResult(true, ExecutionTier.TIER_2_GESTURE, "Tapped ($cx, $cy) via Gesture")
            }
        }

        // Tier 3: Shell Fallback
        if (cx > 0 && cy > 0) {
            if (JarvisAccessibilityService.runShell("input tap $cx $cy")) {
                Log.d(TAG, "Tier 3: Tapped ($cx, $cy) via Shell")
                return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Tapped via Shell")
            }
        }

        return ActionResult(false, ExecutionTier.NONE, "Failed to click ${target.id}")
    }

    private fun executeLongClickNode(target: SemanticNode): ActionResult {
        val liveNode = LiveNodeResolver.resolveLiveNode(target)
        if (liveNode != null) {
            val clickableTarget = LiveNodeResolver.resolveClickableTarget(liveNode)
            try {
                if (clickableTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    return ActionResult(true, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Long clicked ${target.id} via ACTION_LONG_CLICK")
                }
            } catch (_: Exception) {}
        }

        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        if (cx > 0 && cy > 0) {
            if (dispatchLongPress(cx, cy, 1000L)) {
                return ActionResult(true, ExecutionTier.TIER_2_GESTURE, "Long pressed ($cx, $cy) via Gesture")
            }
        }

        return ActionResult(false, ExecutionTier.NONE, "Failed to long click ${target.id}")
    }

    private fun executeSetTextNode(target: SemanticNode, text: String): ActionResult {
        val liveNode = LiveNodeResolver.resolveLiveNode(target)
        if (liveNode != null) {
            try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return ActionResult(true, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Text set via ACTION_SET_TEXT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 set text failed", e)
            }
        }

        // Fallback: click target to focus, then input
        val clickRes = executeClickNode(target)
        if (clickRes.success) {
            val shellEscaped = text.replace(" ", "%s").replace("\"", "\\\"")
            if (JarvisAccessibilityService.runShell("input text \"$shellEscaped\"")) {
                return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Text entered via Shell input")
            }
        }

        return ActionResult(false, ExecutionTier.NONE, "Failed to set text on ${target.id}")
    }

    private fun executeClickCoordinates(x: Int, y: Int): ActionResult {
        if (dispatchTap(x, y)) {
            return ActionResult(true, ExecutionTier.TIER_2_GESTURE, "Tapped ($x, $y) via Gesture")
        }
        if (JarvisAccessibilityService.runShell("input tap $x $y")) {
            return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Tapped ($x, $y) via Shell")
        }
        return ActionResult(false, ExecutionTier.NONE, "Failed to click at ($x, $y)")
    }

    private fun executeSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): ActionResult {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L)))
                .build()
            if (JarvisAccessibilityService.dispatchGestureAction(gesture)) {
                return ActionResult(true, ExecutionTier.TIER_2_GESTURE, "Swiped from ($startX, $startY) to ($endX, $endY)")
            }
        }

        if (JarvisAccessibilityService.runShell("input swipe $startX $startY $endX $endY $durationMs")) {
            return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Swiped via Shell")
        }

        return ActionResult(false, ExecutionTier.NONE, "Failed to swipe")
    }

    private fun executeScroll(direction: String, target: SemanticNode?): ActionResult {
        if (target != null) {
            val liveNode = LiveNodeResolver.resolveLiveNode(target)
            if (liveNode != null) {
                val action = if (direction.equals("UP", ignoreCase = true)) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (liveNode.performAction(action)) {
                    return ActionResult(true, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Scrolled $direction on ${target.id}")
                }
            }
        }

        // Gesture scroll fallback (swipe down to scroll down, swipe up to scroll up)
        val cx = target?.bounds?.centerX() ?: 500
        val cy = target?.bounds?.centerY() ?: 1000
        val deltaY = if (direction.equals("UP", ignoreCase = true)) 400 else -400
        return executeSwipe(cx, cy, cx, cy + deltaY, 300L)
    }

    private fun executeGlobalSystemAction(actionId: Int): ActionResult {
        val testDispatcher = testGlobalActionDispatcher
        if (testDispatcher != null) {
            val success = testDispatcher.invoke(actionId)
            return ActionResult(success, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Global action $actionId executed via test dispatcher")
        }

        val service = JarvisAccessibilityService.instance
        if (service != null) {
            val success = service.performGlobalAction(actionId)
            return ActionResult(success, ExecutionTier.TIER_1_ACCESSIBILITY_API, "Global action $actionId executed")
        }

        // Shell fallback for back / home
        val keyEventCode = when (actionId) {
            AccessibilityService.GLOBAL_ACTION_BACK -> 4
            AccessibilityService.GLOBAL_ACTION_HOME -> 3
            AccessibilityService.GLOBAL_ACTION_RECENTS -> 187
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> 83
            else -> null
        }
        if (keyEventCode != null && JarvisAccessibilityService.runShell("input keyevent $keyEventCode")) {
            return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Global action via keyevent $keyEventCode")
        }

        return ActionResult(false, ExecutionTier.NONE, "Service not running for global action $actionId")
    }

    private fun executeKeyPress(keyName: String): ActionResult {
        val code = KeyboardController.KEY_MAP[keyName.uppercase()] ?: 66
        if (JarvisAccessibilityService.runShell("input keyevent $code")) {
            return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Key $keyName pressed via Shell")
        }
        return ActionResult(false, ExecutionTier.NONE, "Failed to press key $keyName")
    }

    private fun executeTypeText(text: String): ActionResult {
        val shellEscaped = text.replace(" ", "%s").replace("\"", "\\\"")
        if (JarvisAccessibilityService.runShell("input text \"$shellEscaped\"")) {
            return ActionResult(true, ExecutionTier.TIER_3_SHELL, "Typed text via Shell")
        }
        return ActionResult(false, ExecutionTier.NONE, "Failed to type text")
    }

    private fun dispatchTap(x: Int, y: Int): Boolean {
        val testDispatcher = testGestureDispatcher
        if (testDispatcher != null) {
            return testDispatcher.invoke(x, y)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            return JarvisAccessibilityService.dispatchGestureAction(gesture)
        }
        return false
    }

    private fun dispatchLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            return JarvisAccessibilityService.dispatchGestureAction(gesture)
        }
        return false
    }
}
