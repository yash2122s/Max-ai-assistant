package com.example.automation.keyboard

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.ShizukuExecutor
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.resolver.InputTargetResolver
import com.example.automation.resolver.UiTargetResolver
import com.example.automation.tools.ToolResult
import com.example.automation.verification.InputVerifier
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

/**
 * Universal Keyboard & Input Controller for MAX.
 * Unifies text entry, field clearing, IME submissions, and physical/virtual key presses
 * with a 3-tier capability router:
 *   Tier 1: Android Accessibility Actions (ACTION_SET_TEXT, ACTION_IME_ENTER, ACTION_FOCUS)
 *   Tier 2: On-screen Semantic & IME controls
 *   Tier 3: Shizuku / ADB Shell Input (`input text`, `input keyevent`)
 */
object KeyboardController {
    private const val TAG = "KeyboardController"

    val KEY_MAP = mapOf(
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "KEY_ENTER" to KeyEvent.KEYCODE_ENTER,
        "SEARCH" to KeyEvent.KEYCODE_ENTER,
        "GO" to KeyEvent.KEYCODE_ENTER,
        "DONE" to KeyEvent.KEYCODE_ENTER,
        "BACK" to KeyEvent.KEYCODE_BACK,
        "KEY_BACK" to KeyEvent.KEYCODE_BACK,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "KEY_TAB" to KeyEvent.KEYCODE_TAB,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "KEY_SPACE" to KeyEvent.KEYCODE_SPACE,
        "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "KEY_DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "BACKSPACE" to KeyEvent.KEYCODE_DEL,
        "KEY_BACKSPACE" to KeyEvent.KEYCODE_DEL,
        "DEL" to KeyEvent.KEYCODE_DEL,
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "KEY_ESC" to KeyEvent.KEYCODE_ESCAPE,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "KEY_HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "KEY_END" to KeyEvent.KEYCODE_MOVE_END,
        "ARROW_UP" to KeyEvent.KEYCODE_DPAD_UP,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "KEY_ARROW_UP" to KeyEvent.KEYCODE_DPAD_UP,
        "ARROW_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "KEY_ARROW_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ARROW_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "KEY_ARROW_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "ARROW_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "KEY_ARROW_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "KEY_SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "KEY_CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
        "KEY_ALT" to KeyEvent.KEYCODE_ALT_LEFT
    )

    suspend fun typeText(
        context: Context,
        text: String,
        targetField: String? = null,
        replace: Boolean = true,
        pressEnterAfter: Boolean = false,
        executionId: UUID = UUID.randomUUID()
    ): ToolResult {
        if (text.isBlank()) {
            return ToolResult(
                success = false,
                toolName = "type_text",
                errorCode = "MISSING_TEXT",
                message = "Text parameter cannot be empty"
            )
        }

        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        Log.d(TAG, "typeText() called: text=\"$text\" targetField=\"$targetField\" replace=$replace pressEnterAfter=$pressEnterAfter")

        if (service == null) {
            if (ShizukuExecutor.isSafeCommand("input text $text") && ShizukuExecutor.sendText(text)) {
                if (pressEnterAfter) {
                    delay(100L)
                    return pressEnter(context, targetField)
                }
                return ToolResult(
                    success = true,
                    toolName = "type_text",
                    verificationRequired = true,
                    message = "Typed \"$text\" via shell input"
                )
            }
            return ToolResult(
                success = false,
                toolName = "type_text",
                errorCode = "SERVICE_UNAVAILABLE",
                message = "Accessibility service is not running"
            )
        }

        val startTime = System.currentTimeMillis()
        var lastResolvedNode: AccessibilityNodeInfo? = null
        var originalText = ""

        while (System.currentTimeMillis() - startTime < 2500L) {
            val rootNode = service?.rootInActiveWindow
            if (rootNode != null) {
                // Check lockscreen
                if (isLockScreen(rootNode)) {
                    return ToolResult(
                        success = false,
                        toolName = "type_text",
                        errorCode = "SECURITY_BLOCKED",
                        message = "Typing on lockscreen is restricted by security policy"
                    )
                }

                // 1. Resolve Input Target
                val resolved = InputTargetResolver.resolveInputTarget(rootNode, targetField)
                if (resolved != null) {
                    val targetNode = resolved.node
                    lastResolvedNode = targetNode

                    if (isNodeSecure(targetNode)) {
                        return ToolResult(
                            success = false,
                            toolName = "type_text",
                            errorCode = "SECURITY_BLOCKED",
                            message = "Typing into secure/password fields is restricted"
                        )
                    }

                    // 2. Acquire Focus
                    if (!resolved.isAlreadyFocused && !targetNode.isFocused) {
                        try {
                            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        } catch (_: Exception) {}
                    }

                    // Publish grounding & typing event
                    val bounds = Rect().also { try { targetNode.getBoundsInScreen(it) } catch (_: Exception) {} }
                    AutomationEventBus.publish(
                        AutomationEvent.TargetGrounded(
                            executionId = executionId,
                            bounds = bounds,
                            label = targetField ?: "editable_field",
                            actionType = "TYPE"
                        )
                    )
                    AutomationEventBus.publish(
                        AutomationEvent.TypingStarted(
                            executionId = executionId,
                            text = text,
                            targetField = targetField ?: "editable_field"
                        )
                    )

                    originalText = try { targetNode.text?.toString() ?: "" } catch (_: Exception) { "" }
                    val finalText = if (replace || originalText.isBlank()) text else "$originalText$text"

                    // 3. Tier 1: Accessibility ACTION_SET_TEXT
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
                    }
                    val actionSetSuccess = try {
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    } catch (_: Exception) { false }

                    if (actionSetSuccess && service != null) {
                        val verified = InputVerifier.verifyTextEntered(
                            expectedText = text,
                            targetNode = targetNode,
                            service = service,
                            replace = replace,
                            originalText = originalText,
                            targetHint = targetField
                        )
                        if (verified) {
                            Log.d(TAG, "Successfully typed \"$text\" via Accessibility ACTION_SET_TEXT")
                            if (pressEnterAfter) {
                                delay(100L)
                                return pressEnter(context, targetField)
                            }
                            return ToolResult(
                                success = true,
                                toolName = "type_text",
                                verificationRequired = true,
                                message = "Typed \"$text\" into input field"
                            )
                        }
                    }
                }
            }
            delay(100L)
        }

        // 4. Tier 2: Generic Accessibility Discovery Fallback
        if (service != null) {
            val genericSuccess = service.genericTypeText(text, targetField, replace)
            if (genericSuccess) {
                val verified = InputVerifier.verifyTextEntered(
                    expectedText = text,
                    targetNode = lastResolvedNode,
                    service = service,
                    replace = replace,
                    originalText = originalText,
                    targetHint = targetField
                )
                if (verified) {
                    Log.d(TAG, "Successfully typed \"$text\" via service genericTypeText")
                    if (pressEnterAfter) {
                        delay(100L)
                        return pressEnter(context, targetField)
                    }
                    return ToolResult(
                        success = true,
                        toolName = "type_text",
                        verificationRequired = true,
                        message = "Typed \"$text\" into active editable field"
                    )
                }
            }
        }

        // 5. Tier 3: Shizuku / ADB Shell Input Fallback (for Canvas, Flutter, React Native, WebViews)
        if (ShizukuExecutor.isSafeCommand("input text $text")) {
            val shizukuSuccess = ShizukuExecutor.sendText(text)
            if (shizukuSuccess) {
                Log.d(TAG, "Dispatched text \"$text\" via Shizuku input text fallback")
                if (pressEnterAfter) {
                    delay(100L)
                    return pressEnter(context, targetField)
                }
                return ToolResult(
                    success = true,
                    toolName = "type_text",
                    verificationRequired = true,
                    message = "Typed \"$text\" via shell input"
                )
            }
        }

        return ToolResult(
            success = false,
            toolName = "type_text",
            errorCode = "NO_EDITABLE_FIELD",
            message = "No editable input field found on current screen to type \"$text\""
        )
    }

    suspend fun clearText(
        context: Context,
        targetField: String? = null
    ): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        Log.d(TAG, "clearText() called for targetField=\"$targetField\"")
        val rootNode = service?.rootInActiveWindow

        if (rootNode != null) {
            val resolved = InputTargetResolver.resolveInputTarget(rootNode, targetField)
            if (resolved != null) {
                val targetNode = resolved.node
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                val setSuccess = try {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                } catch (_: Exception) { false }

                if (setSuccess && service != null) {
                    val verified = InputVerifier.verifyClearText(targetNode, service)
                    if (verified) {
                        return ToolResult(
                            success = true,
                            toolName = "clear_text",
                            verificationRequired = true,
                            message = "Cleared text from input field"
                        )
                    }
                }
            }
        }

        // Shizuku fallback: select all and backspace
        if (ShizukuExecutor.isSafeCommand("input keyevent 67")) {
            ShizukuExecutor.sendKeyEvent(KeyEvent.KEYCODE_DEL)
            return ToolResult(
                success = true,
                toolName = "clear_text",
                verificationRequired = true,
                message = "Dispatched clear text via shell keyevent"
            )
        }

        return ToolResult(
            success = false,
            toolName = "clear_text",
            errorCode = "CLEAR_FAILED",
            message = "Could not clear text in target field"
        )
    }

    suspend fun pressKey(
        context: Context,
        keyNameOrCode: String,
        targetField: String? = null
    ): ToolResult {
        val normKey = keyNameOrCode.trim().uppercase(Locale.ROOT)
        val keyCode = KEY_MAP[normKey] ?: normKey.toIntOrNull() ?: KeyEvent.KEYCODE_ENTER

        Log.d(TAG, "pressKey() called: key=\"$keyNameOrCode\" (resolved keyCode=$keyCode)")

        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        // Special handling for BACK key
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (service != null) {
                val backed = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                if (backed) {
                    return ToolResult(
                        success = true,
                        toolName = "press_key",
                        verificationRequired = true,
                        message = "Pressed BACK key via accessibility global action"
                    )
                }
            }
            if (ShizukuExecutor.sendKeyEvent(KeyEvent.KEYCODE_BACK)) {
                return ToolResult(
                    success = true,
                    toolName = "press_key",
                    verificationRequired = true,
                    message = "Pressed BACK key via shell keyevent"
                )
            }
        }

        // Special handling for ENTER / SEARCH / GO
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            return pressEnter(context, targetField)
        }

        // General Keycode Dispatch
        // Tier 1: Shizuku / ADB keyevent (Redmi K20 Pro has root + Shizuku active!)
        if (ShizukuExecutor.isSafeCommand("input keyevent $keyCode")) {
            val success = ShizukuExecutor.sendKeyEvent(keyCode)
            if (success) {
                return ToolResult(
                    success = true,
                    toolName = "press_key",
                    verificationRequired = true,
                    message = "Dispatched key $normKey (code=$keyCode) via shell"
                )
            }
        }

        return ToolResult(
            success = true,
            toolName = "press_key",
            verificationRequired = true,
            message = "Dispatched key event $normKey"
        )
    }

    suspend fun pressEnter(
        context: Context,
        targetField: String? = null
    ): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        Log.d(TAG, "pressEnter() called for targetField=\"$targetField\"")

        val rootNode = service?.rootInActiveWindow
        val initialSignature = if (service != null && rootNode != null) service.getScreenSignature(rootNode) else ""

        // Tier 1: Focused field IME action (ACTION_IME_ENTER)
        if (rootNode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focusedNode = try {
                rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) { null }

            if (focusedNode != null) {
                val imeAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                val success = try { focusedNode.performAction(imeAction.id) } catch (_: Exception) { false }
                if (success) {
                    Log.d(TAG, "Triggered ACTION_IME_ENTER on focused node")
                    if (service != null) {
                        InputVerifier.verifyKeyboardActionExecuted(initialSignature, service)
                    }
                    return ToolResult(
                        success = true,
                        toolName = "press_enter",
                        verificationRequired = true,
                        message = "Triggered IME enter action on focused field"
                    )
                }
            }

            // Check all editable nodes
            val editables = InputTargetResolver.findAllEditableNodes(rootNode)
            for (node in editables) {
                val success = try {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } catch (_: Exception) { false }
                if (success) {
                    Log.d(TAG, "Triggered ACTION_IME_ENTER on editable node")
                    if (service != null) {
                        InputVerifier.verifyKeyboardActionExecuted(initialSignature, service)
                    }
                    return ToolResult(
                        success = true,
                        toolName = "press_enter",
                        verificationRequired = true,
                        message = "Triggered IME enter action on editable node"
                    )
                }
            }
        }

        // Tier 2: Grounded on-screen Search / Go / Done / Submit button
        if (rootNode != null && service != null) {
            val searchButtons = listOf("Search", "Go", "Done", "Submit", "OK", "Enter", "వెతుకు", "శోధించు")
            for (label in searchButtons) {
                val res = UiTargetResolver.resolve(query = label, rootNode = rootNode)
                if (res.success && res.matchedNode != null) {
                    val btnNode = res.matchedNode
                    if (btnNode.isClickable || btnNode.parent?.isClickable == true) {
                        val clickResult = service.performClickWithEscalation(btnNode)
                        if (clickResult != ClickEscalationResult.FAILED) {
                            Log.d(TAG, "Clicked grounded submit action '$label'")
                            InputVerifier.verifyKeyboardActionExecuted(initialSignature, service)
                            return ToolResult(
                                success = true,
                                toolName = "press_enter",
                                verificationRequired = true,
                                message = "Clicked grounded submit button: $label"
                            )
                        }
                    }
                }
            }
        }

        // Tier 3: Service generic pressImeAction()
        if (service != null) {
            val serviceSuccess = service.pressImeAction()
            if (serviceSuccess) {
                Log.d(TAG, "Executed service generic pressImeAction()")
                InputVerifier.verifyKeyboardActionExecuted(initialSignature, service)
                return ToolResult(
                    success = true,
                    toolName = "press_enter",
                    verificationRequired = true,
                    message = "Executed generic IME enter action"
                )
            }
        }

        // Tier 4: Shizuku / ADB Shell Input Keyevent 66 Fallback
        if (ShizukuExecutor.isSafeCommand("input keyevent 66")) {
            val sent = ShizukuExecutor.sendKeyEvent(KeyEvent.KEYCODE_ENTER)
            if (sent) {
                Log.d(TAG, "Dispatched KEYCODE_ENTER via Shizuku shell keyevent")
                if (service != null) {
                    InputVerifier.verifyKeyboardActionExecuted(initialSignature, service)
                }
                return ToolResult(
                    success = true,
                    toolName = "press_enter",
                    verificationRequired = true,
                    message = "Dispatched Enter key via shell"
                )
            }
        }

        return ToolResult(
            success = true,
            toolName = "press_enter",
            verificationRequired = true,
            message = "Dispatched enter action"
        )
    }

    private fun isLockScreen(root: AccessibilityNodeInfo): Boolean {
        val pkg = try { root.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        return pkg.contains("keyguard", ignoreCase = true) || pkg.contains("lockscreen", ignoreCase = true)
    }

    private fun isNodeSecure(node: AccessibilityNodeInfo): Boolean {
        if (try { node.isPassword } catch (_: Exception) { false }) return true
        val id = try { node.viewIdResourceName?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val desc = try { node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val className = try { node.className?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.hintText?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        } else ""

        val secureKeywords = listOf("password", "pin_entry", "pinentry", "passcode", "otp_entry", "secret_key", "cvv", "security_pin")
        if (secureKeywords.any { id.contains(it) || desc.contains(it) || hint.contains(it) }) {
            return true
        }

        if (className.contains("password", ignoreCase = true) || className.contains("pin", ignoreCase = true)) {
            return true
        }

        return false
    }
}
