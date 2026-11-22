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

    fun sanitizeTypeText(rawText: String): String {
        var clean = rawText.trim()
        clean = clean.replace(Regex("^(?:search bar|search icon|search button|సెర్చ్ బార్|సెర్చ్ ఐకాన్|సెర్చ్ బటన్|సెర్చ్|search)\\s*(?:lo|loki|meeda|పై|మీద|లో|లోకి|ని|ను|ni|nu)?\\s*(?:click|tap|open|క్లిక్|ట్యాప్|ఓపెన్)?\\s*(?:chesi|చేసి|cheyyi|చేయి|chey|చేయ్)?\\s*", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("^(?:in|on|into|inside|in the|on the|for|the|a|an|text|query)\\s+", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*(?:ane|ani|ni|nu|kosam|gurinchi|lo|అని|అనే|ని|ను|కోసం|గురించి|లో)$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*(?:ane|ani|ni|nu|అని|అనే)?\\s*(?:type|write|రాయి|రాయండి|టైప్)(?:\\s*(?:chey|cheyi|cheyyi|cheyandi|cheyyandi|చేయి|చేయ్|చేయండి))?$", RegexOption.IGNORE_CASE), "")
        return clean.trim().ifEmpty { rawText.trim() }
    }

    suspend fun typeText(
        context: Context,
        text: String,
        targetField: String? = null,
        replace: Boolean = true,
        pressEnterAfter: Boolean = false,
        executionId: UUID = UUID.randomUUID()
    ): ToolResult {
        val cleanText = sanitizeTypeText(text)
        if (cleanText.isBlank()) {
            return ToolResult(
                success = false,
                toolName = "type_text",
                errorCode = "MISSING_TEXT",
                message = "Text parameter cannot be empty"
            )
        }

        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
        val activeRoot = JarvisAccessibilityService.getActiveRoot()

        Log.d(TAG, "typeText() called: text=\"$cleanText\" (raw=\"$text\") targetField=\"$targetField\" replace=$replace pressEnterAfter=$pressEnterAfter")

        if (service == null && activeRoot == null) {
            if (ShizukuExecutor.isSafeCommand("input text $cleanText") && ShizukuExecutor.sendText(cleanText)) {
                if (pressEnterAfter) {
                    delay(100L)
                    return pressEnter(context, targetField)
                }
                return ToolResult(
                    success = true,
                    toolName = "type_text",
                    verificationRequired = true,
                    message = "Typed \"$cleanText\" via shell input"
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
            val rootNode = JarvisAccessibilityService.getActiveRoot()
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
                            text = cleanText,
                            targetField = targetField ?: "editable_field"
                        )
                    )

                    originalText = try { targetNode.text?.toString() ?: "" } catch (_: Exception) { "" }
                    val finalText = if (replace || originalText.isBlank()) {
                        cleanText
                    } else if (originalText.trim().equals(cleanText, ignoreCase = true) || originalText.endsWith(cleanText)) {
                        // Prevent duplicate appending if the field already has this text
                        originalText
                    } else {
                        "$originalText$cleanText"
                    }

                    // 3. Tier 1: Accessibility ACTION_SET_TEXT
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
                    }
                    val actionSetSuccess = try {
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    } catch (_: Exception) { false }

                    if (actionSetSuccess) {
                        val verified = if (service != null) {
                            InputVerifier.verifyTextEntered(
                                expectedText = cleanText,
                                targetNode = targetNode,
                                service = service,
                                replace = replace,
                                originalText = originalText,
                                targetHint = targetField
                            )
                        } else true

                        if (verified) {
                            Log.d(TAG, "Successfully typed \"${com.example.automation.verification.InputVerifier.maskSensitive(cleanText)}\" via Accessibility ACTION_SET_TEXT")
                            if (pressEnterAfter) {
                                delay(100L)
                                return pressEnter(context, targetField)
                            }
                            return ToolResult(
                                success = true,
                                toolName = "type_text",
                                verificationRequired = true,
                                message = "Typed into input field"
                            )
                        }
                    }
                }
            }
            delay(100L)
        }

        // 4. Tier 2: Generic Accessibility Discovery Fallback
        if (service != null) {
            val genericSuccess = service.genericTypeText(cleanText, targetField, replace)
            if (genericSuccess) {
                val verified = com.example.automation.verification.InputVerifier.verifyTextEntered(
                    expectedText = cleanText,
                    targetNode = lastResolvedNode,
                    service = service,
                    replace = replace,
                    originalText = originalText,
                    targetHint = targetField
                )
                if (verified) {
                    Log.d(TAG, "Successfully typed \"${com.example.automation.verification.InputVerifier.maskSensitive(cleanText)}\" via service genericTypeText")
                    if (pressEnterAfter) {
                        delay(100L)
                        return pressEnter(context, targetField)
                    }
                    return ToolResult(
                        success = true,
                        toolName = "type_text",
                        verificationRequired = true,
                        message = "Typed into active editable field"
                    )
                }
            }
        }

        // 5. Tier 3: Shizuku / ADB Shell Input Fallback (for Canvas, Flutter, React Native, WebViews)
        if (ShizukuExecutor.isSafeCommand("input text $cleanText")) {
            val shizukuSuccess = ShizukuExecutor.sendText(cleanText)
            if (shizukuSuccess) {
                Log.d(TAG, "Dispatched text \"${com.example.automation.verification.InputVerifier.maskSensitive(cleanText)}\" via Shizuku input text fallback")
                if (pressEnterAfter) {
                    delay(100L)
                    return pressEnter(context, targetField)
                }
                return ToolResult(
                    success = true,
                    toolName = "type_text",
                    verificationRequired = true,
                    message = "Typed via shell input"
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
        val keyCode = KEY_MAP[normKey] ?: normKey.toIntOrNull()
        if (keyCode == null) {
            Log.w(TAG, "Unsupported or unknown key: '$keyNameOrCode'")
            return ToolResult(
                success = false,
                toolName = "press_key",
                errorCode = "UNSUPPORTED_KEY",
                message = "Unsupported key: '$keyNameOrCode'. Key must be a recognized key name or integer keyCode."
            )
        }

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
            success = false,
            toolName = "press_key",
            errorCode = "KEY_DISPATCH_FAILED",
            verificationRequired = true,
            message = "Failed to dispatch key $normKey (code=$keyCode)"
        )
    }

    suspend fun pressEnter(
        context: Context,
        targetField: String? = null
    ): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        Log.d(TAG, "pressEnter() called for targetField=\"$targetField\"")

        val initialRoot = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
        val initialSignature = when {
            initialRoot == null -> ""
            service != null -> service.getScreenSignature(initialRoot)
            else -> accessibilityRootSignature(initialRoot)
        }
        var dispatchAccepted = false
        var lastFailure = "No supported Enter/Search action was accepted"

        suspend fun verifiedSubmit(message: String, method: String): ToolResult? {
            dispatchAccepted = true
            val verified = initialSignature.isNotBlank() && verifySubmissionTransition(initialSignature, service)
            if (verified) {
                Log.i(TAG, "[SUBMIT_VERIFY] status=VERIFIED method=$method targetField=\"$targetField\"")
                return ToolResult(
                    success = true,
                    toolName = "press_enter",
                    verificationRequired = true,
                    metadata = org.json.JSONObject().apply {
                        put("submissionVerified", true)
                        put("submissionMethod", method)
                    },
                    message = message
                )
            }

            lastFailure = "$method was dispatched, but no fresh UI transition was observed"
            Log.w(TAG, "[SUBMIT_VERIFY] status=UNVERIFIED method=$method targetField=\"$targetField\"")
            return null
        }

        // Tier 1: Focused field IME action (ACTION_IME_ENTER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rootNode = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            val focusedNode = try {
                rootNode?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) { null }

            if (focusedNode != null) {
                val imeAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                val success = try { focusedNode.performAction(imeAction.id) } catch (_: Exception) { false }
                if (success) {
                    Log.d(TAG, "Triggered ACTION_IME_ENTER on focused node")
                    verifiedSubmit("Triggered IME enter action on focused field", "FOCUSED_IME")?.let { return it }
                }
            }

            // Check all editable nodes
            if (rootNode != null) {
                val editables = InputTargetResolver.findAllEditableNodes(rootNode)
                for (node in editables) {
                    val success = try {
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } catch (_: Exception) { false }
                    if (success) {
                        Log.d(TAG, "Triggered ACTION_IME_ENTER on editable node")
                        verifiedSubmit("Triggered IME enter action on editable node", "EDITABLE_IME")?.let { return it }
                    }
                }
            }
        }

        // Tier 2: Grounded on-screen Search / Go / Done / Submit button
        val buttonRoot = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
        if (buttonRoot != null && service != null) {
            val searchButtons = listOf("Search", "Go", "Done", "Submit", "OK", "Enter", "వెతుకు", "శోధించు")
            for (label in searchButtons) {
                val res = UiTargetResolver.resolve(query = label, rootNode = buttonRoot)
                if (res.success && res.matchedNode != null) {
                    val btnNode = res.matchedNode
                    if (btnNode.isClickable || btnNode.parent?.isClickable == true) {
                        val clickResult = service.performClickWithEscalation(btnNode)
                        if (clickResult == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            clickResult == ClickEscalationResult.GESTURE_CONFIRMED
                        ) {
                            Log.d(TAG, "Clicked grounded submit action '$label'")
                            verifiedSubmit("Clicked grounded submit button: $label", "SUBMIT_BUTTON")?.let { return it }
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
                verifiedSubmit("Executed generic IME enter action", "SERVICE_IME")?.let { return it }
            }
        }

        // Tier 4: Shell Input Keyevent 66 Fallback
        if (JarvisAccessibilityService.runShell("input keyevent 66")) {
            Log.d(TAG, "Dispatched KEYCODE_ENTER via Shell keyevent 66")
            verifiedSubmit("Dispatched Enter key via shell", "SHELL_KEYEVENT")?.let { return it }
        }

        return ToolResult(
            success = false,
            toolName = "press_enter",
            errorCode = if (dispatchAccepted) "SUBMISSION_NOT_VERIFIED" else "ENTER_DISPATCH_FAILED",
            verificationRequired = true,
            message = lastFailure
        )
    }

    private fun isLockScreen(root: AccessibilityNodeInfo): Boolean {
        val pkg = try { root.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        return pkg.contains("keyguard", ignoreCase = true) || pkg.contains("lockscreen", ignoreCase = true)
    }

    private suspend fun verifySubmissionTransition(
        initialSignature: String,
        service: JarvisAccessibilityService?
    ): Boolean {
        if (service != null) {
            return InputVerifier.verifyKeyboardActionExecuted(
                initialSignature = initialSignature,
                service = service,
                timeoutMs = 900L
            )
        }

        // Test/embedded providers may expose a live root without a service
        // instance. They still need the same truth requirement: dispatch is
        // not success until a fresh root signature changes.
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 900L) {
            val root = JarvisAccessibilityService.getActiveRoot()
            if (root != null && accessibilityRootSignature(root) != initialSignature) return true
            delay(100L)
        }
        return false
    }

    private fun accessibilityRootSignature(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        builder.append(root.packageName ?: "unknown")
            .append(':').append(root.windowId)
            .append(':').append(root.className ?: "")
            .append(':').append(root.text ?: "")
            .append(':').append(root.contentDescription ?: "")
            .append(':').append(root.childCount)
        return builder.toString()
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
