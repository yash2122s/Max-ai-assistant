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
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes structured UI actions on Android with strict safety boundaries.
 * Dual-tier execution: Tier 1 (Accessibility Gestures/APIs), Tier 2 (Shizuku Root/ADB input).
 */
object UiActionExecutor {
    private const val TAG = "UiActionExecutor"

    suspend fun executeAction(context: Context, action: UiAction): Boolean = withContext(Dispatchers.Default) {
        if (!action.validate()) {
            Log.w(TAG, "Action validation failed for ${action.type}")
            return@withContext false
        }

        Log.d(TAG, "Executing UI action: ${action.type} (reason: ${action.reason})")

        return@withContext when (action.type) {
            UiAction.Type.TAP -> executeTap(action.x ?: 0, action.y ?: 0)
            UiAction.Type.LONG_PRESS -> executeLongPress(action.x ?: 0, action.y ?: 0, action.durationMs)
            UiAction.Type.SWIPE -> executeSwipe(action.x ?: 0, action.y ?: 0, action.endX ?: 0, action.endY ?: 0, action.durationMs)
            UiAction.Type.SCROLL -> executeScroll(action.direction ?: "DOWN")
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
            ShizukuShellPlugin.runCommand("input keyevent 66")
            return true
        }

        return false
    }

    private fun executeTap(x: Int, y: Int): Boolean {
        // Tier 1: Accessibility dispatchGesture
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            latch.await(1000, TimeUnit.MILLISECONDS)
            if (success) {
                Log.d(TAG, "Tap at ($x, $y) succeeded via AccessibilityService")
                return true
            }
        }

        // Tier 2: Shizuku / Root ADB
        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            val res = ShizukuShellPlugin.runCommand("input tap $x $y")
            Log.d(TAG, "Tap at ($x, $y) executed via Shizuku: $res")
            return true
        }

        return false
    }

    private fun executeLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(500, 2000)))
                .build()

            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            latch.await(durationMs + 500, TimeUnit.MILLISECONDS)
            if (success) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            ShizukuShellPlugin.runCommand("input swipe $x $y $x $y $durationMs")
            return true
        }

        return false
    }

    private fun executeSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 1000)))
                .build()

            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false
                    latch.countDown()
                }
            }, null)

            latch.await(durationMs + 500, TimeUnit.MILLISECONDS)
            if (success) return true
        }

        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            ShizukuShellPlugin.runCommand("input swipe $startX $startY $endX $endY $durationMs")
            return true
        }

        return false
    }

    private fun executeScroll(direction: String): Boolean {
        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val centerX = width / 2
        val centerY = height / 2

        return when (direction.uppercase()) {
            "UP" -> executeSwipe(centerX, centerY - 300, centerX, centerY + 300, 300)
            "DOWN" -> executeSwipe(centerX, centerY + 300, centerX, centerY - 300, 300)
            "LEFT" -> executeSwipe(centerX + 300, centerY, centerX - 300, centerY, 300)
            "RIGHT" -> executeSwipe(centerX - 300, centerY, centerX + 300, centerY, 300)
            else -> executeSwipe(centerX, centerY + 300, centerX, centerY - 300, 300)
        }
    }

    private fun executeType(text: String): Boolean {
        // Sanitize text for shell execution
        val sanitized = text.replace("'", "\\'").replace("\"", "\\\"").replace(" ", "%s")
        if (ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()) {
            ShizukuShellPlugin.runCommand("input text \"$sanitized\"")
            return true
        }

        // Accessibility set text fallback on focused node
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        val root = service?.rootInActiveWindow
        val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
            ShizukuShellPlugin.runCommand("input keyevent 4")
            return true
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
