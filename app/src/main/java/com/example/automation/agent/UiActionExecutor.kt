package com.example.automation.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.example.automation.ShizukuManager
import com.example.automation.ShizukuShellPlugin
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Executes structured UI actions on Android with strict safety boundaries.
 * Dual-tier execution: Tier 1 (Accessibility Gestures/APIs), Tier 2 (Shizuku Root/ADB input).
 */
object UiActionExecutor {
    private const val TAG = "UiActionExecutor"
    private const val GESTURE_TIMEOUT_MS = 2_500L

    suspend fun executeAction(context: Context, action: UiAction): Boolean = withContext(Dispatchers.Default) {
        val displayMetrics = context.resources.displayMetrics
        if (!action.validate(displayMetrics.widthPixels, displayMetrics.heightPixels)) {
            Log.w(TAG, "Action validation failed for ${action.type}")
            return@withContext false
        }

        Log.d(TAG, "Executing UI action: ${action.type} (reason: ${action.reason})")

        return@withContext when (action.type) {
            UiAction.Type.TAP -> executeTap(requireNotNull(action.x), requireNotNull(action.y))
            UiAction.Type.LONG_PRESS -> executeLongPress(requireNotNull(action.x), requireNotNull(action.y), action.durationMs)
            UiAction.Type.SWIPE -> executeSwipe(
                requireNotNull(action.x),
                requireNotNull(action.y),
                requireNotNull(action.endX),
                requireNotNull(action.endY),
                action.durationMs
            )
            UiAction.Type.SCROLL -> executeScroll(context, action.direction ?: "DOWN")
            UiAction.Type.TYPE -> executeType(action.text ?: "")
            UiAction.Type.KEY_ENTER -> executeEnter()
            UiAction.Type.KEY_BACK -> executeBack()
            UiAction.Type.KEY_HOME -> executeHome(context)
            UiAction.Type.LAUNCH_APP -> executeLaunchApp(context, action.packageName ?: "")
            UiAction.Type.SCREENSHOT -> true
            UiAction.Type.FINISH -> true
        }
    }

    private fun executeEnter(): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null) {
            val success = service.pressImeAction()
            if (success) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input keyevent 66")
            return !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
        }

        return false
    }

    private suspend fun executeTap(x: Int, y: Int): Boolean {
        // Tier 1: Accessibility dispatchGesture
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val success = awaitGesture(service, gesture)
            if (success) {
                Log.d(TAG, "Tap at ($x, $y) succeeded via AccessibilityService")
                return true
            }
        }

        // Tier 2: Shizuku / Root ADB
        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input tap $x $y")
            val ok = !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
            Log.d(TAG, "Tap at ($x, $y) executed via Shizuku: $res (ok=$ok)")
            return ok
        }

        return false
    }

    private suspend fun executeLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(500, 2000)))
                .build()

            val success = awaitGesture(service, gesture)
            if (success) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input swipe $x $y $x $y $durationMs")
            val ok = !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
            return ok
        }

        return false
    }

    private suspend fun executeSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 1000)))
                .build()

            val success = awaitGesture(service, gesture)
            if (success) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input swipe $startX $startY $endX $endY $durationMs")
            val ok = !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
            return ok
        }

        return false
    }

    private suspend fun awaitGesture(
        service: JarvisAccessibilityService,
        gesture: GestureDescription
    ): Boolean {
        return try {
            withTimeout(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val accepted = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                        null
                    )
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Accessibility gesture timed out")
            false
        }
    }

    private suspend fun executeScroll(context: Context, direction: String): Boolean {
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false

        val centerX = width / 2
        val centerY = height / 2
        val horizontalDelta = minOf(300, (width - 1) / 2)
        val verticalDelta = minOf(300, (height - 1) / 2)
        fun x(value: Int): Int = value.coerceIn(0, width - 1)
        fun y(value: Int): Int = value.coerceIn(0, height - 1)

        return when (direction.uppercase()) {
            "UP" -> executeSwipe(x(centerX), y(centerY - verticalDelta), x(centerX), y(centerY + verticalDelta), 300)
            "DOWN" -> executeSwipe(x(centerX), y(centerY + verticalDelta), x(centerX), y(centerY - verticalDelta), 300)
            "LEFT" -> executeSwipe(x(centerX + horizontalDelta), y(centerY), x(centerX - horizontalDelta), y(centerY), 300)
            "RIGHT" -> executeSwipe(x(centerX - horizontalDelta), y(centerY), x(centerX + horizontalDelta), y(centerY), 300)
            else -> executeSwipe(x(centerX), y(centerY + verticalDelta), x(centerX), y(centerY - verticalDelta), 300)
        }
    }

    private fun executeType(text: String): Boolean {
        // 1. Tier 1: Accessibility set text on focused node (preferred, safe)
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        val root = service?.rootInActiveWindow
        val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            try {
                if (focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Log.d(TAG, "Typed text via Accessibility ACTION_SET_TEXT")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility ACTION_SET_TEXT failed", e)
            }
        }

        // 2. Tier 2: Shell input with strict character validation
        // Reject any text with shell metacharacters: ;, &, |, `, $, \, ", ', <, >, newlines
        val hasShellMetachars = text.any { it in ";|`$\\<>\n\r\"'" }
        if (hasShellMetachars) {
            Log.w(TAG, "[SECURITY_REJECT] Text contains prohibited shell metacharacters; rejected for shell input")
            return false
        }

        val safeText = text.replace(" ", "%s")
        val cmd = "input text $safeText"
        if (com.example.automation.computeruse.ShellCommandPolicy.isCommandAllowed(cmd)) {
            if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
                val res = ShizukuShellPlugin.runCommand(cmd)
                val ok = !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
                Log.d(TAG, "Typed text via Shizuku shell (cmd='$cmd', ok=$ok, res='$res')")
                return ok
            }
        } else {
            Log.w(TAG, "[SECURITY_REJECT] Shell input command rejected by ShellCommandPolicy: '$cmd'")
        }

        return false
    }

    private fun executeBack(): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null) {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (ok) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input keyevent 4")
            val ok = !res.startsWith("Error") && !res.startsWith("Failed") && !res.startsWith("Remote error")
            return ok
        }

        return false
    }

    private fun executeHome(context: Context): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null) {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (ok) return true
        }

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch home intent", e)
        }

        return false
    }

    private fun executeLaunchApp(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                true
            } else {
                Log.w(TAG, "Launch intent not found for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName", e)
            false
        }
    }
}
