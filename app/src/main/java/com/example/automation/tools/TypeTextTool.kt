package com.example.automation.tools

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ExecutionRequest
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Universal text typing tool for MAX.
 *
 * Execution Hierarchy:
 * 1. Target node resolution & specific target security validation.
 *    (Detect secure fields directly on the target node; never globally block normal fields).
 * 2. Focused editable node (active field from prior click/focus) -> ACTION_SET_TEXT -> verify text.
 * 3. Explicit grounded target field (if field name provided) -> ACTION_SET_TEXT -> verify text.
 * 4. Single visible editable field -> ACTION_SET_TEXT -> verify text.
 * 5. Multiple visible editable fields without focus -> NEEDS_INPUT (never randomly guess).
 * 6. Safe keypad character typing (Calculator / dialer digit buttons) only when no editable field accepts text.
 */
class TypeTextTool : Tool {
    private val TAG = "TypeTextTool"

    override val name: String = "type_text"
    override val supportedActions: Set<String> = setOf("TYPE_TEXT", "ENTER_TEXT", "INPUT_TEXT", "TYPE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val args = request.arguments
        val raw = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val text = com.example.automation.keyboard.KeyboardController.sanitizeTypeText(raw)
        return text.isNotBlank()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val raw = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val text = com.example.automation.keyboard.KeyboardController.sanitizeTypeText(raw)
        val targetField = args.get("field")?.asString
            ?: args.get("hint")?.asString
            ?: args.get("description")?.asString

        val replaceParam = if (args.has("replace")) {
            args.get("replace").asBoolean
        } else if (args.has("append")) {
            !args.get("append").asBoolean
        } else {
            true
        }
        val pressEnter = args.get("press_enter")?.asBoolean ?: args.get("enter")?.asBoolean ?: false

        val result = com.example.automation.engine.SerializedActionExecutor.execute("type:${text.take(20)}") {
            com.example.automation.keyboard.KeyboardController.typeText(
                context = context,
                text = text,
                targetField = targetField,
                replace = replaceParam,
                pressEnterAfter = pressEnter,
                executionId = request.executionId
            )
        }

        return result ?: ToolResult(
            success = false,
            toolName = name,
            errorCode = "EXECUTION_LOCKED",
            message = "Typing action could not acquire UI lock or was cancelled"
        )
    }

    /**
     * Strict Target-Specific Text Verification.
     * Polls up to 1000ms:
     * 1. Refreshes targetNode to check if text was updated in-place.
     * 2. Re-acquires fresh rootInActiveWindow and re-resolves the target node (via hint, id, or focus).
     * 3. Compares actual text against expected text using exact or positive containment semantics.
     * 4. Rejects blank/unrelated text without positive evidence.
     */
    suspend fun verifyTargetFieldText(
        targetNode: AccessibilityNodeInfo,
        expectedText: String,
        replaceParam: Boolean,
        originalText: String = "",
        targetFieldHint: String? = null,
        service: JarvisAccessibilityService,
        maxWaitMs: Long = 1000L
    ): Boolean {
        val start = System.currentTimeMillis()
        val targetId = try { targetNode.viewIdResourceName } catch (_: Exception) { null }
        val targetBounds = Rect().also { try { targetNode.getBoundsInScreen(it) } catch (_: Exception) {} }

        while (System.currentTimeMillis() - start < maxWaitMs) {
            // 1. Try refreshing original node
            try {
                if (targetNode.refresh()) {
                    val updatedText = targetNode.text?.toString() ?: ""
                    if (isTextMatch(updatedText, expectedText, replaceParam, originalText)) {
                        return true
                    }
                }
            } catch (_: Exception) {}

            // 2. Query fresh active window
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                // If targetFieldHint provided, re-ground explicitly
                if (!targetFieldHint.isNullOrBlank()) {
                    val freshGrounding = ScreenGroundingEngine.groundTarget(targetFieldHint, freshRoot)
                    if (freshGrounding.status == com.example.automation.ui.GroundingStatus.FOUND && freshGrounding.matchedNode != null) {
                        val freshNodeText = freshGrounding.matchedNode.text?.toString() ?: ""
                        if (isTextMatch(freshNodeText, expectedText, replaceParam, originalText)) {
                            return true
                        }
                    }
                }

                // Check focused editable node in fresh root
                val freshFocused = freshRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findFocusedNodeRecursive(freshRoot)
                if (freshFocused != null && (freshFocused.isEditable || isEditTextClass(freshFocused))) {
                    val focusedText = freshFocused.text?.toString() ?: ""
                    if (isTextMatch(focusedText, expectedText, replaceParam, originalText)) {
                        return true
                    }
                }

                // Check editable node matching targetId or bounds
                val allEditables = findAllEditableNodes(freshRoot)
                for (editable in allEditables) {
                    val editableId = editable.viewIdResourceName
                    val editableBounds = Rect().also { editable.getBoundsInScreen(it) }
                    val isSameField = (!targetId.isNullOrBlank() && targetId == editableId) ||
                            (!targetBounds.isEmpty && targetBounds == editableBounds)
                    if (isSameField) {
                        val editText = editable.text?.toString() ?: ""
                        if (isTextMatch(editText, expectedText, replaceParam, originalText)) {
                            return true
                        }
                    }
                }
            }

            kotlinx.coroutines.delay(60L)
        }

        return false
    }

    /**
     * Keypad Result State Verification.
     * After clicking keypad buttons (e.g. "25"), verifies that the typed string
     * actually appears in a visible value/formula/display node on screen.
     * Does NOT use hardcoded app IDs.
     */
    suspend fun verifyKeypadResultState(
        expectedText: String,
        service: JarvisAccessibilityService,
        maxWaitMs: Long = 1000L
    ): Boolean {
        val start = System.currentTimeMillis()
        val normExpected = normalizeForComparison(expectedText)
        if (normExpected.isEmpty()) return true

        while (System.currentTimeMillis() - start < maxWaitMs) {
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                val allVisibleTexts = collectAllVisibleNodeTexts(freshRoot)
                if (allVisibleTexts.any { normalizeForComparison(it).contains(normExpected) }) {
                    return true
                }
            }
            kotlinx.coroutines.delay(60L)
        }
        return false
    }

    private suspend fun verifyAnyVisibleTextContains(
        expectedText: String,
        service: JarvisAccessibilityService,
        maxWaitMs: Long = 800L
    ): Boolean {
        val start = System.currentTimeMillis()
        val normExpected = normalizeForComparison(expectedText)
        if (normExpected.isEmpty()) return true

        while (System.currentTimeMillis() - start < maxWaitMs) {
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                val allVisibleTexts = collectAllVisibleNodeTexts(freshRoot)
                if (allVisibleTexts.any { normalizeForComparison(it).contains(normExpected) }) {
                    return true
                }
            }
            kotlinx.coroutines.delay(60L)
        }
        return false
    }

    private fun collectAllVisibleNodeTexts(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 25 || !node.isVisibleToUser) return emptyList()
        val texts = mutableListOf<String>()
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) {
            texts.add(t)
        }
        val d = node.contentDescription?.toString()
        if (!d.isNullOrBlank()) {
            texts.add(d)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            texts.addAll(collectAllVisibleNodeTexts(child, depth + 1))
        }
        return texts
    }

    fun isTextMatch(actual: String, expected: String, replace: Boolean, originalText: String = ""): Boolean {
        val normActual = normalizeForComparison(actual)
        val normExpected = normalizeForComparison(expected)
        if (normExpected.isEmpty()) return normActual.isEmpty()
        if (normActual.isEmpty()) return false

        if (replace) {
            // For replace=true:
            // 1. Exact equality (e.g. "palakollu" == "palakollu")
            // 2. Positive containment (e.g. search bar affix "search palakollu" or currency "$25")
            // BUT must contain normExpected
            return normActual == normExpected || normActual.contains(normExpected)
        } else {
            // For replace=false (append):
            // 1. Contains appended text
            // 2. Exact match of combined original + expected
            val normOriginal = normalizeForComparison(originalText)
            val combined = "$normOriginal$normExpected"
            return normActual == combined || normActual.contains(normExpected)
        }
    }

    private fun normalizeForComparison(text: String): String {
        return text.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
    }

    private fun isLockScreen(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        return pkg.contains("keyguard", ignoreCase = true) || pkg.contains("lockscreen", ignoreCase = true)
    }

    private fun isNodeSecure(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase(Locale.ROOT) ?: ""
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

    private fun isEditTextClass(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        return cls.contains("EditText", ignoreCase = true) || cls.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun findFocusedNodeRecursive(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNodeRecursive(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findAllEditableNodes(node: AccessibilityNodeInfo, depth: Int = 0): List<AccessibilityNodeInfo> {
        if (depth > 25) return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (node.isEditable || isEditTextClass(node)) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            list.addAll(findAllEditableNodes(child, depth + 1))
        }
        return list
    }

    private fun trySafeKeypadCharacterTyping(
        text: String,
        service: JarvisAccessibilityService,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        if (text.isBlank()) return false

        if (isLockScreen(rootNode)) {
            Log.w(TAG, "Safe keypad typing aborted: Lockscreen detected.")
            return false
        }

        // Verify that EVERY character in text exists as an on-screen button
        for (ch in text) {
            val chStr = ch.toString()
            val grounding = ScreenGroundingEngine.groundTarget(target = chStr, rootNode = rootNode)
            if (grounding.status != com.example.automation.ui.GroundingStatus.FOUND || grounding.matchedNode == null) {
                return false
            }
            if (isNodeSecure(grounding.matchedNode)) {
                return false
            }
        }

        // Safe keypad matching confirmed! Perform clicks sequentially and verify each character
        Log.d("UI_TYPE", "[UI_TYPE] Safe keypad matching succeeded for text=\"$text\" (${text.length} buttons)")
        for (ch in text) {
            val chStr = ch.toString()
            val liveRoot = service.rootInActiveWindow ?: return false
            val grounding = ScreenGroundingEngine.groundTarget(target = chStr, rootNode = liveRoot)
            if (grounding.status != com.example.automation.ui.GroundingStatus.FOUND || grounding.matchedNode == null) {
                return false
            }
            val escalationResult = service.performClickWithEscalation(grounding.matchedNode)
            Log.d("UI_TYPE", "[UI_TYPE] Keypad tapped char='$chStr' result=$escalationResult")
            val clickOk = escalationResult == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                    escalationResult == ClickEscalationResult.GESTURE_CONFIRMED
            if (!clickOk) {
                Log.w(TAG, "Keypad click for char '$chStr' failed: $escalationResult")
                return false
            }
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {}
        }
        return true
    }
}

