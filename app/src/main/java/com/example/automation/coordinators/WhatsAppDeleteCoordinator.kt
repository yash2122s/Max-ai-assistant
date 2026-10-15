package com.example.automation.coordinators

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 4-Stage WhatsApp Message Deletion Coordinator.
 * Safely executes: long-press message → tap delete action → select delete scope → confirm.
 */
object WhatsAppDeleteCoordinator {

    private const val TAG = "WhatsAppDeleteCoord"
    private var activeJob: Job? = null

    fun armWhatsAppDelete(
        targetMessageText: String? = null,
        deleteForEveryone: Boolean = true,
        timeoutMs: Long = 12_000L,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            val success = executeDeleteFlow(targetMessageText, deleteForEveryone, timeoutMs)
            onComplete?.invoke(success)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun executeDeleteFlow(
        targetMessageText: String?,
        deleteForEveryone: Boolean,
        timeoutMs: Long
    ): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        Log.d(TAG, "Starting WhatsApp message delete flow")

        // Stage 1: Locate message and long press
        var messageNode: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline && messageNode == null) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                messageNode = if (!targetMessageText.isNullOrBlank()) {
                    findNode(root) { node ->
                        val t = node.text?.toString().orEmpty()
                        t.contains(targetMessageText, ignoreCase = true)
                    }
                } else {
                    // Fall back to most recent message row in chat
                    findNode(root) { node ->
                        node.viewIdResourceName?.contains("message_text", true) == true ||
                        node.viewIdResourceName?.contains("conversation_row", true) == true
                    }
                }
            }
            delay(250)
        }

        if (messageNode == null) {
            Log.w(TAG, "Stage 1 failed: Target message not found")
            return false
        }

        val bounds = Rect().also { messageNode.getBoundsInScreen(it) }
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // Perform long-press gesture
        val path = android.graphics.Path().apply { moveTo(cx, cy) }
        val longPressGesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        service.dispatchGesture(longPressGesture, null, null)
        delay(700)

        // Stage 2: Tap delete (trash) action in top bar
        var trashButton: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline && trashButton == null) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                trashButton = findNode(root) { node ->
                    node.isClickable &&
                            (node.contentDescription?.toString()?.contains("delete", true) == true ||
                             node.viewIdResourceName?.contains("menuitem_conversations_delete", true) == true ||
                             node.viewIdResourceName?.contains("delete", true) == true)
                }
            }
            delay(200)
        }

        if (trashButton == null) {
            Log.w(TAG, "Stage 2 failed: Delete action icon not visible")
            return false
        }

        service.performClickWithEscalation(trashButton)
        delay(600)

        // Stage 3 & 4: Confirmation dialog handling
        var dialogConfirmed = false
        while (System.currentTimeMillis() < deadline && !dialogConfirmed) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                // Check for "Delete for everyone" vs "Delete for me"
                val scopeTarget = if (deleteForEveryone) "delete for everyone" else "delete for me"
                val optionBtn = findNode(root) { node ->
                    node.isClickable && node.text?.toString()?.contains(scopeTarget, ignoreCase = true) == true
                }
                if (optionBtn != null) {
                    val res = service.performClickWithEscalation(optionBtn)
                    dialogConfirmed = res == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            res == ClickEscalationResult.GESTURE_CONFIRMED
                    break
                }

                // Generic "Delete" confirm button
                val genericDelete = findNode(root) { node ->
                    node.isClickable && node.text?.toString()?.equals("Delete", ignoreCase = true) == true
                }
                if (genericDelete != null) {
                    val res = service.performClickWithEscalation(genericDelete)
                    dialogConfirmed = res == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            res == ClickEscalationResult.GESTURE_CONFIRMED
                    break
                }
            }
            delay(200)
        }

        Log.d(TAG, "WhatsApp delete flow completed: success=$dialogConfirmed")
        return dialogConfirmed
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
