package com.example.automation.coordinators

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.SignatureCalculator
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * High-reliability automation coordinator for YouTube flows.
 * Handles search-and-play, subscription, and fullscreen transitions with closed-loop verification.
 */
object YouTubeAutomationCoordinator {

    private const val TAG = "YouTubeCoordinator"
    private var activeJob: Job? = null

    /**
     * Arms autonomous YouTube playback for a given search query.
     */
    fun armYouTubePlay(query: String, timeoutMs: Long = 15_000L, onComplete: ((Boolean) -> Unit)? = null) {
        cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            val success = executePlay(query, timeoutMs)
            onComplete?.invoke(success)
        }
    }

    /**
     * Arms autonomous subscription toggle on the current video channel.
     */
    fun armYouTubeSubscribe(timeoutMs: Long = 8_000L, onComplete: ((Boolean) -> Unit)? = null) {
        cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            val success = executeSubscribe(timeoutMs)
            onComplete?.invoke(success)
        }
    }

    /**
     * Arms autonomous fullscreen toggle on active playback.
     */
    fun armYouTubeFullscreen(timeoutMs: Long = 6_000L, onComplete: ((Boolean) -> Unit)? = null) {
        cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            val success = executeFullscreen(timeoutMs)
            onComplete?.invoke(success)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun executePlay(query: String, timeoutMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        Log.d(TAG, "Starting YouTube play flow for '$query'")

        // Step 1: Find search button or search bar
        var root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
        var searchInput: AccessibilityNodeInfo? = null

        while (System.currentTimeMillis() < deadline && searchInput == null) {
            root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                searchInput = findNode(root) { node ->
                    node.isEditable ||
                            node.viewIdResourceName?.contains("search_edit_text", true) == true ||
                            node.contentDescription?.toString()?.contains("search", true) == true
                }
                if (searchInput == null) {
                    val searchIcon = findNode(root) { node ->
                        node.isClickable &&
                                (node.contentDescription?.toString()?.contains("search", true) == true ||
                                 node.viewIdResourceName?.contains("menu_item_1", true) == true)
                    }
                    if (searchIcon != null) {
                        service.performClickWithEscalation(searchIcon)
                        delay(600)
                    }
                }
            }
            delay(250)
        }

        // Step 2: Type query and submit
        if (searchInput != null) {
            val bounds = Rect().also { searchInput.getBoundsInScreen(it) }
            service.smartTypeText(query, bounds, submit = true)
            delay(1200)
        }

        // Step 3: Click first result video
        val preSig = SignatureCalculator.compute(service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot())
        var videoClicked = false

        while (System.currentTimeMillis() < deadline && !videoClicked) {
            root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val candidate = findNode(root) { node ->
                    node.isClickable &&
                            (node.viewIdResourceName?.contains("thumbnail", true) == true ||
                             node.viewIdResourceName?.contains("title", true) == true ||
                             node.contentDescription?.toString()?.contains("minute", true) == true ||
                             node.contentDescription?.toString()?.contains("second", true) == true)
                }
                if (candidate != null) {
                    val res = service.performClickWithEscalation(candidate)
                    videoClicked = res == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            res == ClickEscalationResult.GESTURE_CONFIRMED
                }
            }
            delay(300)
        }

        if (!videoClicked) return false

        // Step 4: Verify playback controls or title
        delay(1500)
        root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
        val targets = ScreenGroundingEngine.scanTargets(root)
        val isPlaying = targets.any {
            val l = it.primaryLabel.lowercase(Locale.ROOT)
            l.contains("pause") || l.contains("player") || l.contains("now playing")
        }
        val postSig = SignatureCalculator.compute(root)

        return isPlaying || (postSig != null && postSig != preSig)
    }

    private suspend fun executeSubscribe(timeoutMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val btn = findNode(root) { node ->
                    node.isClickable &&
                            (node.text?.toString()?.equals("Subscribe", true) == true ||
                             node.contentDescription?.toString()?.contains("Subscribe", true) == true)
                }
                if (btn != null) {
                    val res = service.performClickWithEscalation(btn)
                    if (res == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                        res == ClickEscalationResult.GESTURE_CONFIRMED) {
                        delay(1000)
                        val postRoot = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
                        val postTargets = ScreenGroundingEngine.scanTargets(postRoot)
                        return postTargets.any { it.primaryLabel.contains("Subscribed", true) }
                    }
                }
            }
            delay(300)
        }
        return false
    }

    private suspend fun executeFullscreen(timeoutMs: Long): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val btn = findNode(root) { node ->
                    node.isClickable &&
                            (node.contentDescription?.toString()?.contains("full screen", true) == true ||
                             node.viewIdResourceName?.contains("fullscreen", true) == true)
                }
                if (btn != null) {
                    val res = service.performClickWithEscalation(btn)
                    return res == ClickEscalationResult.ACCESSIBILITY_CONFIRMED ||
                            res == ClickEscalationResult.GESTURE_CONFIRMED
                }
            }
            delay(300)
        }
        return false
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
