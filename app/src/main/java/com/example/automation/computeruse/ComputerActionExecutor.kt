package com.example.automation.computeruse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.ShizukuManager
import com.example.automation.ShizukuShellPlugin
import com.example.automation.keyboard.KeyboardController
import com.example.automation.resolver.UiTargetResolver
import com.example.knowledge.apps.InstalledAppsRepository
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ComputerActionExecutor {
    private const val TAG = "ComputerActionExecutor"

    suspend fun execute(context: Context, action: ComputerAction): ActionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val result = withTimeoutOrNull(6000L) {
            when (action) {
                is ComputerAction.LaunchApp -> executeLaunchApp(context, action)
                is ComputerAction.TapCoordinate -> executeTapCoordinate(action)
                is ComputerAction.LongPress -> executeLongPress(action)
                is ComputerAction.Swipe -> executeSwipe(action)
                is ComputerAction.Drag -> executeDrag(action)
                is ComputerAction.TapElement -> executeTapElement(action)
                is ComputerAction.TypeText -> executeTypeText(context, action)
                is ComputerAction.PressKey -> executePressKey(context, action)
                is ComputerAction.PressEnter -> executePressEnter(context)
                is ComputerAction.PressBack -> executePressBack()
                is ComputerAction.Scroll -> executeScroll(action)
                is ComputerAction.Wait -> executeWait(action)
                is ComputerAction.ObserveMore -> ActionResult(
                    success = true,
                    actionType = action.actionName,
                    message = action.reason,
                    methodUsed = "NOOP"
                )
                is ComputerAction.FinishTask -> ActionResult(
                    success = action.success,
                    actionType = action.actionName,
                    message = action.message,
                    methodUsed = "NOOP"
                )
            }
        } ?: ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "TIMEOUT",
            message = "Action '${action.actionName}' timed out after 6000ms",
            methodUsed = "TIMEOUT"
        )

        val duration = System.currentTimeMillis() - startTime
        val finalResult = result.copy(durationMs = duration)
        Log.i(TAG, "[EXECUTE] action=${action.actionName} method=${finalResult.methodUsed} success=${finalResult.success} duration=${duration}ms")
        finalResult
    }

    private fun executeLaunchApp(context: Context, action: ComputerAction.LaunchApp): ActionResult {
        val appName = action.appName.trim()
        val pkg = action.packageName
            ?: if (appName.contains(".") && context.packageManager.getLaunchIntentForPackage(appName) != null) {
                appName
            } else {
                InstalledAppsRepository.findApp(context, appName)
            }

        if (pkg.isNullOrBlank()) {
            return ActionResult(
                success = false,
                actionType = action.actionName,
                errorCode = "APP_NOT_FOUND",
                message = "Could not find installed package for '$appName'",
                methodUsed = "PACKAGE_MANAGER"
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            context.startActivity(launchIntent)
            return ActionResult(
                success = true,
                actionType = action.actionName,
                targetApp = pkg,
                message = "Launched $appName ($pkg)",
                methodUsed = "INTENT_LAUNCH"
            )
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "INTENT_LAUNCH_FAILED",
            message = "No launchable intent found for package '$pkg'",
            methodUsed = "INTENT_LAUNCH"
        )
    }

    private fun executeTapCoordinate(action: ComputerAction.TapCoordinate): ActionResult {
        if (action.x < 0 || action.y < 0) {
            return ActionResult(
                success = false,
                actionType = action.actionName,
                errorCode = ComputerUseErrorCode.STALE_TARGET.name,
                message = "Invalid out-of-bounds coordinate: (${action.x}, ${action.y})",
                methodUsed = "FAILED"
            )
        }

        // Priority 1: Accessibility dispatchGesture
        val service = JarvisAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
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
                return ActionResult(
                    success = true,
                    actionType = action.actionName,
                    message = "Tapped coordinate (${action.x}, ${action.y})",
                    methodUsed = "GESTURE_DISPATCH"
                )
            }
        }

        // Priority 2: Shell fallback (Shizuku / test shell)
        if (JarvisAccessibilityService.runShell("input tap ${action.x} ${action.y}")) {
            return ActionResult(
                success = true,
                actionType = action.actionName,
                message = "Tapped coordinate via Shell (${action.x}, ${action.y})",
                methodUsed = "SHELL_TAP"
            )
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "TAP_FAILED",
            message = "Accessibility service / Shell unavailable for tap gesture",
            methodUsed = "FAILED"
        )
    }

    private fun executeLongPress(action: ComputerAction.LongPress): ActionResult {
        val service = JarvisAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
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

            latch.await(action.durationMs + 500, TimeUnit.MILLISECONDS)
            if (success) {
                return ActionResult(
                    success = true,
                    actionType = action.actionName,
                    message = "Long pressed coordinate (${action.x}, ${action.y})",
                    methodUsed = "GESTURE_LONG_PRESS"
                )
            }
        }

        if (JarvisAccessibilityService.runShell("input swipe ${action.x} ${action.y} ${action.x} ${action.y} ${action.durationMs}")) {
            return ActionResult(
                success = true,
                actionType = action.actionName,
                message = "Long pressed coordinate via Shell (${action.x}, ${action.y})",
                methodUsed = "SHELL_SWIPE"
            )
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "LONG_PRESS_FAILED",
            message = "Accessibility / Shell unavailable for long press",
            methodUsed = "FAILED"
        )
    }

    private fun executeSwipe(action: ComputerAction.Swipe): ActionResult {
        val service = JarvisAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val latch = CountDownLatch(1)
            var success = false

            val path = Path().apply {
                moveTo(action.x1.toFloat(), action.y1.toFloat())
                lineTo(action.x2.toFloat(), action.y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
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

            latch.await(action.durationMs + 500, TimeUnit.MILLISECONDS)
            if (success) {
                return ActionResult(
                    success = true,
                    actionType = action.actionName,
                    message = "Swiped from (${action.x1}, ${action.y1}) to (${action.x2}, ${action.y2})",
                    methodUsed = "GESTURE_SWIPE"
                )
            }
        }

        if (JarvisAccessibilityService.runShell("input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}")) {
            return ActionResult(
                success = true,
                actionType = action.actionName,
                message = "Swiped via Shell",
                methodUsed = "SHELL_SWIPE"
            )
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "SWIPE_FAILED",
            message = "Accessibility / Shell unavailable for swipe gesture",
            methodUsed = "FAILED"
        )
    }

    private fun executeDrag(action: ComputerAction.Drag): ActionResult {
        return executeSwipe(ComputerAction.Swipe(action.x1, action.y1, action.x2, action.y2, durationMs = 800L))
    }

    private fun executeTapElement(action: ComputerAction.TapElement): ActionResult {
        val targetQuery = action.targetText.trim()
        val resolution = UiTargetResolver.resolve(
            query = targetQuery,
            explicitResourceId = action.resourceId,
            explicitContentDescription = action.contentDescription,
            targetRole = action.targetRole
        )

        if (resolution.success && resolution.target != null) {
            val node = resolution.matchedNode
            var methodUsed = "ACTION_CLICK"

            // Priority 1: Accessibility ACTION_CLICK
            var clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

            if (!clicked) {
                // Try clicking parents
                var parent = node?.parent
                var depth = 0
                while (parent != null && depth < 4) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true
                        methodUsed = "ACTION_CLICK_PARENT"
                        break
                    }
                    parent = parent.parent
                    depth++
                }
            }

            // Priority 2: Gesture Tap on node bounds center
            if (!clicked) {
                val bounds = resolution.target.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    val coordResult = executeTapCoordinate(ComputerAction.TapCoordinate(centerX, centerY, targetQuery))
                    clicked = coordResult.success
                    methodUsed = "BOUNDS_TAP_GESTURE"
                }
            }

            // Priority 3: Coordinate tap fallback via Shell
            if (!clicked) {
                val bounds = resolution.target.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    if (JarvisAccessibilityService.runShell("input tap ${bounds.centerX()} ${bounds.centerY()}")) {
                        clicked = true
                        methodUsed = "SHELL_TAP_BOUNDS"
                    }
                }
            }

            if (clicked) {
                return ActionResult(
                    success = true,
                    actionType = action.actionName,
                    message = "Clicked '$targetQuery'",
                    methodUsed = methodUsed
                )
            }
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "ELEMENT_NOT_FOUND",
            message = "Could not ground or click element for '$targetQuery'",
            methodUsed = "FAILED"
        )
    }

    private suspend fun executeTypeText(context: Context, action: ComputerAction.TypeText): ActionResult {
        val field = action.targetField?.lowercase() ?: ""
        if (field.contains("password") || field.contains("pin") || field.contains("otp") || field.contains("secret")) {
            return ActionResult(
                success = false,
                actionType = action.actionName,
                errorCode = ComputerUseErrorCode.SECURITY_BLOCKED.name,
                message = "Automated typing into secure/password fields is restricted by security policy",
                methodUsed = "SECURITY_GATE"
            )
        }

        val typeRes = KeyboardController.typeText(
            context = context,
            text = action.text,
            targetField = action.targetField,
            replace = action.clearExisting,
            pressEnterAfter = action.pressEnterAfter
        )

        return ActionResult(
            success = typeRes.success,
            actionType = action.actionName,
            errorCode = if (!typeRes.success) "TYPE_FAILED" else null,
            message = if (typeRes.success) "Typed '${action.text}'" else typeRes.message,
            methodUsed = if (typeRes.success) "KEYBOARD_CONTROLLER" else "FAILED"
        )
    }

    private suspend fun executePressKey(context: Context, action: ComputerAction.PressKey): ActionResult {
        val res = KeyboardController.pressKey(context, action.keyNameOrCode)
        return ActionResult(
            success = res.success,
            actionType = action.actionName,
            errorCode = if (!res.success) "KEY_FAILED" else null,
            message = if (res.success) "Pressed key '${action.keyNameOrCode}'" else res.message,
            methodUsed = "KEY_DISPATCH"
        )
    }

    private suspend fun executePressEnter(context: Context): ActionResult {
        val res = KeyboardController.pressEnter(context)
        return ActionResult(
            success = res.success,
            actionType = ComputerAction.PressEnter.actionName,
            errorCode = if (!res.success) "ENTER_FAILED" else null,
            message = if (res.success) "Pressed Enter" else res.message,
            methodUsed = "IME_ENTER"
        )
    }

    private fun executePressBack(): ActionResult {
        val service = JarvisAccessibilityService.instance
        val ok = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
        if (!ok && JarvisAccessibilityService.runShell("input keyevent 4")) {
            return ActionResult(
                success = true,
                actionType = ComputerAction.PressBack.actionName,
                message = "Pressed Back via Shell",
                methodUsed = "SHELL_BACK"
            )
        }
        return ActionResult(
            success = ok,
            actionType = ComputerAction.PressBack.actionName,
            errorCode = if (!ok) "BACK_FAILED" else null,
            message = if (ok) "Pressed Back" else "Accessibility / Shell unavailable",
            methodUsed = if (ok) "ACCESSIBILITY_GLOBAL" else "FAILED"
        )
    }

    private suspend fun executeScroll(action: ComputerAction.Scroll): ActionResult {
        val service = JarvisAccessibilityService.instance
        val (ok, msg) = service?.scrollScreen(action.direction) ?: Pair(false, "Accessibility unavailable")
        if (ok) {
            return ActionResult(
                success = true,
                actionType = action.actionName,
                message = msg,
                methodUsed = "ACCESSIBILITY_SCROLL"
            )
        }

        val swipeCmd = if (action.direction.equals("up", ignoreCase = true)) {
            "input swipe 540 600 540 1600 400"
        } else {
            "input swipe 540 1600 540 600 400"
        }

        if (JarvisAccessibilityService.runShell(swipeCmd)) {
            return ActionResult(
                success = true,
                actionType = action.actionName,
                message = "Scrolled ${action.direction} via Shell",
                methodUsed = "SHELL_SCROLL"
            )
        }

        return ActionResult(
            success = false,
            actionType = action.actionName,
            errorCode = "SCROLL_FAILED",
            message = msg,
            methodUsed = "FAILED"
        )
    }

    private suspend fun executeWait(action: ComputerAction.Wait): ActionResult {
        delay(action.durationMs.coerceIn(100L, 10000L))
        return ActionResult(
            success = true,
            actionType = action.actionName,
            message = "Waited ${action.durationMs}ms",
            methodUsed = "NOOP"
        )
    }
}

