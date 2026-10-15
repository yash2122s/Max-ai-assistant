package com.example.automation.verification

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.resolver.InputTargetResolver
import com.example.automation.resolver.UiTargetResolver
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Adaptive Input & Keyboard Verification Engine.
 * Verifies focus, text insertion, clear text, and keyboard action transitions with 100ms adaptive bounded polling.
 */
object InputVerifier {
    private const val TAG = "InputVerifier"

    suspend fun verifyFocusAcquired(
        targetNode: AccessibilityNodeInfo,
        service: JarvisAccessibilityService,
        timeoutMs: Long = 1000L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                if (targetNode.isFocused) return true
                if (targetNode.refresh() && targetNode.isFocused) return true
            } catch (_: Exception) {}

            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                val focused = try {
                    freshRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) { null }
                if (focused != null) return true
            }
            delay(80L)
        }
        return false
    }

    suspend fun verifyTextEntered(
        expectedText: String,
        targetNode: AccessibilityNodeInfo?,
        service: JarvisAccessibilityService,
        replace: Boolean = true,
        originalText: String = "",
        targetHint: String? = null,
        timeoutMs: Long = 1000L
    ): Boolean {
        val start = System.currentTimeMillis()
        val normExpected = normalizeText(expectedText)
        if (normExpected.isEmpty()) return true

        val targetId = try { targetNode?.viewIdResourceName } catch (_: Exception) { null }
        val targetBounds = Rect().also { try { targetNode?.getBoundsInScreen(it) } catch (_: Exception) {} }

        while (System.currentTimeMillis() - start < timeoutMs) {
            // 1. Try checking targetNode directly
            try {
                if (targetNode != null && targetNode.refresh()) {
                    val updatedText = targetNode.text?.toString() ?: ""
                    if (isTextMatch(updatedText, normExpected, replace, originalText)) {
                        Log.d(TAG, "Text verified on targetNode: '${maskSensitive(updatedText)}'")
                        return true
                    }
                }
            } catch (_: Exception) {}

            // 2. Query fresh active window
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                // Ground via target hint if provided
                if (!targetHint.isNullOrBlank()) {
                    val res = UiTargetResolver.resolve(query = targetHint, rootNode = freshRoot)
                    if (res.success && res.matchedNode != null) {
                        val node = res.matchedNode
                        val text = node.text?.toString() ?: ""
                        if (isTextMatch(text, normExpected, replace, originalText)) {
                            Log.d(TAG, "Text verified via fresh target hint '$targetHint'")
                            return true
                        }
                    }
                }

                // Check focused node in fresh root
                val focused = try {
                    freshRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) { null }
                if (focused != null) {
                    val focusedText = focused.text?.toString() ?: ""
                    if (isTextMatch(focusedText, normExpected, replace, originalText)) {
                        Log.d(TAG, "Text verified via fresh focused node: '${maskSensitive(focusedText)}'")
                        return true
                    }
                }

                // Check editable node matching ID or bounds
                val allEditables = InputTargetResolver.findAllEditableNodes(freshRoot)
                for (editable in allEditables) {
                    val editableId = try { editable.viewIdResourceName } catch (_: Exception) { null }
                    val editableBounds = Rect().also { try { editable.getBoundsInScreen(it) } catch (_: Exception) {} }
                    val isMatch = (!targetId.isNullOrBlank() && targetId == editableId) ||
                            (!targetBounds.isEmpty && targetBounds == editableBounds)
                    if (isMatch) {
                        val text = editable.text?.toString() ?: ""
                        if (isTextMatch(text, normExpected, replace, originalText)) {
                            Log.d(TAG, "Text verified on matched editable node")
                            return true
                        }
                    }
                }
            }

            delay(80L)
        }

        Log.w(TAG, "Text verification timed out for expected='${maskSensitive(expectedText)}'")
        return false
    }

    suspend fun verifyClearText(
        targetNode: AccessibilityNodeInfo?,
        service: JarvisAccessibilityService,
        timeoutMs: Long = 800L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                if (targetNode != null && targetNode.refresh()) {
                    val text = targetNode.text?.toString() ?: ""
                    if (text.isBlank()) return true
                }
            } catch (_: Exception) {}

            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                val focused = try {
                    freshRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) { null }
                if (focused != null) {
                    val text = focused.text?.toString() ?: ""
                    if (text.isBlank()) return true
                }
            }
            delay(80L)
        }
        return false
    }

    suspend fun verifyKeyboardActionExecuted(
        initialSignature: String,
        service: JarvisAccessibilityService,
        timeoutMs: Long = 1500L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                val currentSignature = service.getScreenSignature(freshRoot)
                if (currentSignature.isNotBlank() && currentSignature != initialSignature) {
                    Log.d(TAG, "Keyboard action transition verified (signature changed)")
                    return true
                }
            }
            delay(100L)
        }
        // Timeout without observed signature change: truthfully report failure
        Log.w(TAG, "Keyboard action transition timed out without observable signature change")
        return false
    }

    internal fun isTextMatch(actual: String, expectedNormalized: String, replace: Boolean, originalText: String): Boolean {
        val normActual = normalizeText(actual)
        if (normActual.isEmpty() && expectedNormalized.isEmpty()) return true
        if (normActual.isEmpty()) return false

        return if (replace) {
            // Strict field-bound matching: field must equal expected text (modulo whitespace/case normalization)
            normActual == expectedNormalized
        } else {
            // Append mode: field must match original + expected
            val normOriginal = normalizeText(originalText)
            val combined = "$normOriginal$expectedNormalized"
            normActual == combined || (normActual.startsWith(normOriginal) && normActual.endsWith(expectedNormalized))
        }
    }

    internal fun normalizeText(text: String): String {
        return text.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
    }

    internal fun maskSensitive(text: String): String {
        if (text.length <= 4) return "***"
        return "${text.first()}***${text.last()}"
    }
}
