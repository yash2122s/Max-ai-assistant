package com.example.automation.coordinators

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
 * High-reliability WhatsApp voice/video call automation coordinator.
 * Locates call action icons, handles confirmation dialogs, and verifies call transition.
 */
object WhatsAppCallCoordinator {

    private const val TAG = "WhatsAppCallCoord"
    private var activeJob: Job? = null

    fun armWhatsAppCall(
        contactName: String,
        isVideo: Boolean = false,
        timeoutMs: Long = 10_000L,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            val success = executeCallFlow(contactName, isVideo, timeoutMs)
            onComplete?.invoke(success)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun executeCallFlow(
        contactName: String,
        isVideo: Boolean,
        timeoutMs: Long
    ): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        Log.d(TAG, "Starting WhatsApp ${if (isVideo) "video" else "voice"} call to '$contactName'")

        // Step 1: Locate call icon
        var callButton: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline && callButton == null) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                callButton = findNode(root) { node ->
                    node.isClickable && if (isVideo) {
                        node.contentDescription?.toString()?.contains("video call", true) == true ||
                        node.viewIdResourceName?.contains("action_video_call", true) == true
                    } else {
                        node.contentDescription?.toString()?.contains("voice call", true) == true ||
                        node.contentDescription?.toString()?.contains("call", true) == true ||
                        node.viewIdResourceName?.contains("action_voice_call", true) == true
                    }
                }
            }
            delay(250)
        }

        if (callButton == null) {
            Log.w(TAG, "Call button not found on screen")
            return false
        }

        val clickRes = service.performClickWithEscalation(callButton)
        val clicked = clickRes == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                clickRes == ClickEscalationResult.GESTURE_CONFIRMED
        if (!clicked) {
            Log.w(TAG, "Failed to click call button: $clickRes")
            return false
        }

        delay(600)

        // Step 2: Handle confirmation dialog ("Start voice call?", "Call" button)
        val dialogDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < dialogDeadline) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val confirmButton = findNode(root) { node ->
                    node.isClickable &&
                            (node.text?.toString()?.equals("Call", true) == true ||
                             node.text?.toString()?.contains("Start", true) == true ||
                             node.viewIdResourceName?.contains("button1", true) == true)
                }
                if (confirmButton != null) {
                    service.performClickWithEscalation(confirmButton)
                    Log.d(TAG, "Confirmed call dialog prompt")
                    break
                }
            }
            delay(200)
        }

        // Step 3: Verify calling state (end call / mute / speaker / connecting visible)
        delay(1000)
        val postRoot = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
        val inCall = if (postRoot != null) {
            findNode(postRoot) { node ->
                val desc = node.contentDescription?.toString().orEmpty().lowercase(Locale.ROOT)
                val text = node.text?.toString().orEmpty().lowercase(Locale.ROOT)
                val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
                desc.contains("end call") || desc.contains("mute") || desc.contains("speaker") ||
                        text.contains("calling") || text.contains("ringing") || id.contains("end_call")
            } != null
        } else false

        Log.d(TAG, "WhatsApp call flow result: inCall=$inCall")
        return inCall
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
