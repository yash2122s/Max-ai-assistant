package com.example.automation.verification

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.AppPackageMatcher
import com.example.automation.resolver.UiTargetResolver
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * High-performance, bounded polling verifier for runtime UI steps.
 * Implements: Immediate Observation -> Short Bounded Polling (100-150ms) -> Stop immediately when matched.
 */
object RuntimeStepVerifier {
    private const val TAG = "RuntimeStepVerifier"
    const val DEFAULT_POLL_INTERVAL_MS = 100L

    /**
     * Verifies an app has entered the foreground within [timeoutMs].
     * Returns true immediately as soon as matching package appears.
     */
    suspend fun verifyAppForeground(
        context: Context,
        expectedPackage: String,
        timeoutMs: Long = 3000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // A cached WorldStateManager package is not sufficient proof that
            // the requested app is still visible. Foreground verification must
            // come from a live accessibility root/window.
            val currentPkg = resolveCurrentForegroundPackage(context, allowCached = false)
            if (isPackageMatch(currentPkg, expectedPackage)) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "✓ App foreground verified for '$expectedPackage' in ${elapsed}ms")
                return true
            }
            delay(DEFAULT_POLL_INTERVAL_MS)
        }
        val finalPkg = resolveCurrentForegroundPackage(context, allowCached = false)
        val success = isPackageMatch(finalPkg, expectedPackage)
        Log.d(TAG, "verifyAppForeground finished: expected='$expectedPackage', got='$finalPkg', success=$success")
        return success
    }

    /**
     * Verifies that a target UI element exists and is visible on screen within [timeoutMs].
     */
    suspend fun verifyTargetVisible(
        context: Context,
        targetQuery: String,
        requireEditable: Boolean = false,
        timeoutMs: Long = 2500L,
        rootNode: AccessibilityNodeInfo? = null,
        expectedPackage: String? = null
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = freshRoot(rootNode)
            if (root != null) {
                val rootPackage = root.packageName?.toString().orEmpty()
                if (!expectedPackage.isNullOrBlank() && !AppPackageMatcher.matches(rootPackage, expectedPackage)) {
                    delay(DEFAULT_POLL_INTERVAL_MS)
                    continue
                }
                val result = UiTargetResolver.resolve(
                    query = targetQuery,
                    requireEditable = requireEditable,
                    rootNode = root
                )
                if (result.success && result.confidence >= UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✓ Target '$targetQuery' verified visible in ${elapsed}ms (confidence=${result.confidence})")
                    return true
                }
            }
            delay(DEFAULT_POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Verifies that an editable field contains the expected text within [timeoutMs].
     */
    suspend fun verifyTextEntered(
        context: Context,
        expectedText: String,
        timeoutMs: Long = 1000L,
        rootNode: AccessibilityNodeInfo? = null,
        expectedPackage: String? = null
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = freshRoot(rootNode)
            if (root != null) {
                val rootPackage = root.packageName?.toString().orEmpty()
                if (!expectedPackage.isNullOrBlank() && !AppPackageMatcher.matches(rootPackage, expectedPackage)) {
                    delay(DEFAULT_POLL_INTERVAL_MS)
                    continue
                }
                val rootText = root.text?.toString() ?: ""
                if (rootText.contains(expectedText, ignoreCase = true)) {
                    Log.d(TAG, "✓ Text verified directly on root node: '$rootText'")
                    return true
                }

                val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
                val focusedText = focused?.text?.toString() ?: ""
                if (focusedText.contains(expectedText, ignoreCase = true)) {
                    Log.d(TAG, "✓ Text verified in focused field: '$focusedText'")
                    return true
                }

                // Check text via UiTargetResolver or direct node scan
                val targetResult = UiTargetResolver.resolve(expectedText, rootNode = root)
                if (targetResult.success) {
                    Log.d(TAG, "✓ Text verified on screen via target resolver: '$expectedText'")
                    return true
                }

                // Fallback check matching nodes by text
                val matchingNodes = try { root.findAccessibilityNodeInfosByText(expectedText) } catch (_: Exception) { emptyList() }
                if (matchingNodes.isNotEmpty()) {
                    Log.d(TAG, "✓ Text verified on screen via text node match")
                    return true
                }
            }
            delay(DEFAULT_POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Verifies that a UI transition occurred after an action (screen signature changed or target appeared).
     */
    suspend fun verifyUiTransition(
        context: Context,
        preSignature: String,
        targetExpected: String? = null,
        timeoutMs: Long = 2500L,
        rootNode: AccessibilityNodeInfo? = null
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val service = JarvisAccessibilityService.instance

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootNode ?: JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                val currentSig = service?.getScreenSignature(root) ?: rootSignature(root)
                if (currentSig.isNotEmpty() && currentSig != preSignature && currentSig != "null_root") {
                    Log.d(TAG, "✓ UI transition verified: screen signature changed")
                    return true
                }

                if (!targetExpected.isNullOrBlank()) {
                    val result = UiTargetResolver.resolve(targetExpected, rootNode = root)
                    if (result.success && result.confidence >= UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD) {
                        Log.d(TAG, "✓ UI transition verified: expected target '$targetExpected' is visible")
                        return true
                    }
                }
            }
            delay(DEFAULT_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun resolveCurrentForegroundPackage(context: Context, allowCached: Boolean): String {
        val root = JarvisAccessibilityService.getActiveRoot()
        val livePkg = root?.packageName?.toString()?.trim().orEmpty()
        if (livePkg.isNotEmpty()) return livePkg

        val windows = JarvisAccessibilityService.getActiveWindows()
        val activeWindowRoot = windows?.firstOrNull { it.isFocused || it.isActive }?.root
        val winPkg = activeWindowRoot?.packageName?.toString()?.trim().orEmpty()
        if (winPkg.isNotEmpty()) return winPkg

        if (allowCached) {
            val statePkg = com.example.automation.state.WorldStateManager.getCurrentState().currentApp.trim()
            if (statePkg.isNotEmpty()) return statePkg
        }

        return ""
    }

    private fun isPackageMatch(currentPackage: String, expectedPackage: String): Boolean {
        if (AppPackageMatcher.matches(currentPackage, expectedPackage)) return true

        // Preserve the existing tolerant matching for legacy package aliases
        // that are not in the shared identity table.
        if (currentPackage.isEmpty() || expectedPackage.isEmpty()) return false
        if (expectedPackage.contains('.')) return false
        return currentPackage.contains(expectedPackage, ignoreCase = true) ||
            expectedPackage.contains(currentPackage, ignoreCase = true)
    }

    private fun freshRoot(previousRoot: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (previousRoot != null) {
            try {
                if (previousRoot.refresh()) return previousRoot
            } catch (_: Exception) {
                // Reacquire below when the snapshot has become obsolete.
            }
        }
        return JarvisAccessibilityService.getActiveRoot()
    }

    private fun rootSignature(root: AccessibilityNodeInfo): String {
        return buildString {
            append(root.packageName ?: "unknown")
            append(':').append(root.windowId)
            append(':').append(root.className ?: "")
            append(':').append(root.text ?: "")
            append(':').append(root.contentDescription ?: "")
            append(':').append(root.childCount)
        }
    }
}
