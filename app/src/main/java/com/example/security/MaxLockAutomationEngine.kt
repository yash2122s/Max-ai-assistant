package com.example.security

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ScreenType {
    PIN, PATTERN, SWIPE, UNKNOWN
}

class MaxLockAutomationEngine(
    private val service: AccessibilityService
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
    private val screenWidth = displayMetrics.widthPixels.toFloat()
    private val screenHeight = displayMetrics.heightPixels.toFloat()
    
    private val scaleX = if (screenWidth > 0) screenWidth / 1080f else 1f
    private val scaleY = if (screenHeight > 0) screenHeight / 1920f else 1f

    private val pinPadMap = mapOf(
        '1' to Pair(180f * scaleX, 820f * scaleY), '2' to Pair(540f * scaleX, 820f * scaleY), '3' to Pair(900f * scaleX, 820f * scaleY),
        '4' to Pair(180f * scaleX, 980f * scaleY), '5' to Pair(540f * scaleX, 980f * scaleY), '6' to Pair(900f * scaleX, 980f * scaleY),
        '7' to Pair(180f * scaleX, 1140f * scaleY), '8' to Pair(540f * scaleX, 1140f * scaleY), '9' to Pair(900f * scaleX, 1140f * scaleY),
        '0' to Pair(540f * scaleX, 1300f * scaleY)
    )

    fun start(pin: String, onComplete: (() -> Unit)? = null) {
        scope.launch {
            Log.d("MaxLockEngine", "Starting MAX UNIVERSAL LOCK AUTOMATION ENGINE with PIN of length ${pin.length}")
            
            val pm = service.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenAlreadyOn = pm.isInteractive
            
            if (!isScreenAlreadyOn) {
                // Screen is OFF — wake it first, then swipe
                Log.d("MaxLockEngine", "Screen is OFF. Waking screen first...")
                com.example.security.UnlockManager(service).wakeScreen()
                delay(1000) // Wait for screen to turn on
            } else {
                Log.d("MaxLockEngine", "Screen is already ON.")
            }
            
            // Step 1: Collapse any expanded notification shade first
            // This handles the case where notifications are visible on lock screen
            try {
                @Suppress("DEPRECATION")
                val statusBarService = service.getSystemService("statusbar")
                val statusBarClass = Class.forName("android.app.StatusBarManager")
                val collapseMethod = statusBarClass.getMethod("collapsePanels")
                collapseMethod.invoke(statusBarService)
                Log.d("MaxLockEngine", "Collapsed notification panels")
            } catch (e: Exception) {
                Log.w("MaxLockEngine", "Could not collapse panels: ${e.message}")
            }
            delay(300)
            
            // Step 2: Dismiss keyguard via status bar (helps clear notification overlay)
            // On MIUI/HyperOS, this helps get past the notification layer on lock screen
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                Log.d("MaxLockEngine", "Dismissed notification shade via global action")
            } catch (e: Exception) {
                Log.w("MaxLockEngine", "Could not dismiss notification shade: ${e.message}")
            }
            delay(300)
            
            // Step 3: First swipe up to dismiss lock screen / reveal PIN pad
            swipeUp()
            delay(1500) // Wait for transition/animation
            
            var root = service.rootInActiveWindow
            
            // If root is null or we're still on the lock screen, try another swipe
            if (root == null) {
                Log.w("MaxLockEngine", "Root window is null after first swipe. Retrying swipe...")
                swipeUp()
                delay(1500)
                root = service.rootInActiveWindow
            }
            
            // Check if we're still seeing notifications or lock screen overlay
            // If so, do an aggressive center-screen swipe to get past them
            if (root != null) {
                val hasNotification = hasNotificationOnScreen(root)
                if (hasNotification) {
                    Log.d("MaxLockEngine", "Notifications detected on lock screen. Doing aggressive swipe...")
                    swipeUpAggressive()
                    delay(1000)
                    swipeUp()
                    delay(1500)
                    root = service.rootInActiveWindow
                }
            }
            
            if (root == null) {
                Log.w("MaxLockEngine", "Root still null. Falling back to classic coordinates.")
                executeClassicCoordinates(pin)
                delay(1000)
                onComplete?.invoke()
                return@launch
            }

            val screenType = detectScreenType(root)
            Log.d("MaxLockEngine", "Detected screen type: $screenType")

            when (screenType) {
                ScreenType.PIN -> {
                    handlePinScreenSmart(root, pin)
                }
                ScreenType.PATTERN -> {
                    Log.w("MaxLockEngine", "Pattern screen detected. Classic gesture fallback.")
                    fallbackGesture()
                }
                ScreenType.SWIPE, ScreenType.UNKNOWN -> {
                    // Might still be on lock screen, try one more swipe then PIN
                    Log.d("MaxLockEngine", "Swipe or unknown screen. Swiping up again and retrying...")
                    swipeUp()
                    delay(1500)
                    val newRoot = service.rootInActiveWindow
                    if (newRoot != null && detectScreenType(newRoot) == ScreenType.PIN) {
                        handlePinScreenSmart(newRoot, pin)
                    } else {
                        executeClassicCoordinates(pin)
                    }
                }
            }
            delay(1500) // General delay to ensure device is fully unlocked before callback runs
            onComplete?.invoke()
        }
    }
    
    /**
     * Checks if there are notification-like elements visible on the lock screen
     */
    private fun hasNotificationOnScreen(root: AccessibilityNodeInfo): Boolean {
        // Check for common notification-related UI elements
        val notificationIndicators = listOf(
            "com.android.systemui:id/notification_stack_scroller",
            "com.android.systemui:id/notification_panel",
            "com.android.systemui:id/notification_container",
            "com.android.systemui:id/expanded_notification"
        )
        for (id in notificationIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                Log.d("MaxLockEngine", "Found notification element: $id")
                return true
            }
        }
        return false
    }

    private fun detectScreenType(root: AccessibilityNodeInfo): ScreenType {
        // Look for digit text nodes or content descriptions
        var foundDigits = 0
        for (digit in 0..9) {
            val textNodes = root.findAccessibilityNodeInfosByText(digit.toString())
            if (textNodes.isNotEmpty()) {
                foundDigits++
            }
        }

        val drawPatternNodes = root.findAccessibilityNodeInfosByText("Draw pattern") +
                root.findAccessibilityNodeInfosByText("pattern") +
                root.findAccessibilityNodeInfosByText("Pattern")

        return when {
            foundDigits >= 3 -> ScreenType.PIN
            drawPatternNodes.isNotEmpty() -> ScreenType.PATTERN
            else -> ScreenType.SWIPE
        }
    }

    private suspend fun handlePinScreenSmart(root: AccessibilityNodeInfo, pin: String) {
        Log.d("MaxLockEngine", "Executing handlePinScreenSmart for PIN")
        for (digit in pin) {
            val clicked = clickDigitSmart(root, digit.toString())
            if (!clicked) {
                Log.w("MaxLockEngine", "Smart click failed for digit $digit. Tapping fallback coordinates.")
                // Fallback to tap screen coordinates for this digit
                val coord = pinPadMap[digit]
                if (coord != null) {
                    tapScreen(coord.first, coord.second)
                }
            } else {
                Log.d("MaxLockEngine", "Smart clicked digit $digit successfully.")
            }
            delay(300) // Timing delay between typing digits
        }

        // Try to click enter or okay button if it exists
        delay(300)
        val entered = clickEnterButtonSmart(root)
        if (!entered) {
            Log.d("MaxLockEngine", "No explicit Enter button clicked. Tapping fallback enter coordinate.")
            tapScreen(900f * scaleX, 1300f * scaleY) // Classic OK/Enter key
        }
    }

    private fun clickDigitSmart(root: AccessibilityNodeInfo, value: String): Boolean {
        // Layer 1: Precise Text match
        val textNodes = root.findAccessibilityNodeInfosByText(value)
        for (node in textNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Layer 2: Content description match or ViewId match
        val searchKeywords = listOf(value, "key_$value", "button_$value", "pin_$value")
        for (keyword in searchKeywords) {
            val found = findNodeByTextOrDescRecursive(root, keyword)
            if (found != null && found.isClickable) {
                found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Layer 3: Recursive search in full hierarchy
        return findAndClickRecursive(root, value)
    }

    private fun clickEnterButtonSmart(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf("enter", "ok", "done", "confirm", "✓", "tick", "next", "search")
        for (kw in keywords) {
            val node = findNodeByTextOrDescRecursive(root, kw)
            if (node != null && node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    private fun findNodeByTextOrDescRecursive(node: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(keyword, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(keyword, ignoreCase = true) == true ||
            node.viewIdResourceName?.contains(keyword, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextOrDescRecursive(child, keyword)
            if (found != null) return found
        }
        return null
    }

    private fun findAndClickRecursive(node: AccessibilityNodeInfo?, value: String): Boolean {
        if (node == null) return false

        if (node.text?.toString() == value || node.contentDescription?.toString() == value) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                // If the node isn't clickable, try to click its clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndClickRecursive(child, value)) return true
        }

        return false
    }

    private suspend fun executeClassicCoordinates(pin: String) {
        Log.d("MaxLockEngine", "Executing classic coordinate unlock sequence.")
        for (digit in pin) {
            val coord = pinPadMap[digit] ?: continue
            tapScreen(coord.first, coord.second)
            delay(300)
        }
        delay(300)
        tapScreen(900f * scaleX, 1300f * scaleY) // Enter key tap
    }

    private fun swipeUp() {
        val path = Path().apply {
            val midX = screenWidth / 2f
            val startY = screenHeight * 0.85f
            val endY = screenHeight * 0.2f
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
    }
    
    /**
     * More aggressive swipe that starts from the very bottom of the screen
     * and goes all the way to the top. This is needed when notifications
     * are covering the lock screen, as a normal swipe from 85% might get
     * intercepted by the notification panel.
     */
    private fun swipeUpAggressive() {
        val path = Path().apply {
            val midX = screenWidth / 2f
            val startY = screenHeight * 0.95f  // Start from very bottom
            val endY = screenHeight * 0.05f     // Go to very top
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200)) // Faster swipe
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun fallbackGesture() {
        swipeUp()
    }

    private fun tapScreen(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
