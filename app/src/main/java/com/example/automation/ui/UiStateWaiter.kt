package com.example.automation.ui

import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.UiTarget
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class UiWaitResult(
    val ready: Boolean,
    val root: AccessibilityNodeInfo?,
    val signature: String?,
    val changedFromPrevious: Boolean,
    val reason: String
)

object UiStateWaiter {

    private val LOADING_MARKERS = listOf(
        "loading", "buffering", "please wait", "working…", "working..."
    )

    /**
     * JARVIS migration — waits for a stable, non-loading accessibility tree.
     * Polls until two consecutive samples have identical signatures and no
     * "loading…" markers are present.
     */
    suspend fun waitForUiState(
        previousSignature: String? = null,
        expectedPackage: String? = null,
        timeoutMs: Long = 10_000L
    ): UiWaitResult = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
        if (service == null && JarvisAccessibilityService.testRootProvider == null) {
            return@withContext UiWaitResult(false, null, null, false, "Accessibility Service is unavailable")
        }

        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 20_000L)
        var lastSignature: String? = null
        var stableSamples = 0
        var latestRoot: AccessibilityNodeInfo? = null
        var latestSignature: String? = null

        while (System.currentTimeMillis() < deadline) {
            latestRoot = service?.rootInActiveWindow ?: JarvisAccessibilityService.getActiveRoot()
            val latestElements: List<UiTarget> = ScreenGroundingEngine.scanTargets(latestRoot)
            latestSignature = SignatureCalculator.compute(latestRoot)

            val pkg = latestRoot?.packageName?.toString().orEmpty()
            val packageReady = expectedPackage.isNullOrBlank() ||
                    pkg.contains(expectedPackage, ignoreCase = true)
            val loading = latestElements.any { element ->
                val label = element.primaryLabel.lowercase(Locale.ROOT)
                LOADING_MARKERS.any(label::contains)
            }

            if (packageReady && !loading && latestRoot != null) {
                if (latestSignature != null && latestSignature == lastSignature) {
                    stableSamples++
                } else {
                    stableSamples = 0
                }

                if (stableSamples >= 1) {
                    return@withContext UiWaitResult(
                        ready = true,
                        root = latestRoot,
                        signature = latestSignature,
                        changedFromPrevious = previousSignature != null &&
                                latestSignature != null && latestSignature != previousSignature,
                        reason = "UI tree stable across fresh accessibility observations"
                    )
                }
            } else {
                stableSamples = 0
            }
            lastSignature = latestSignature
            delay(100L)
        }

        UiWaitResult(
            ready = false,
            root = latestRoot,
            signature = latestSignature,
            changedFromPrevious = previousSignature != null &&
                    latestSignature != null && latestSignature != previousSignature,
            reason = "Timed out waiting for a stable non-loading accessibility tree"
        )
    }
}

/**
 * JARVIS migration — lightweight screen signature.
 * Two signatures are equal when their element count + top-level labels match.
 */
object SignatureCalculator {
    fun compute(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val sb = StringBuilder()
        walk(root, sb, depth = 0, maxDepth = 8)
        return sb.toString().hashCode().toString()
    }

    private fun walk(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val text = node.text?.toString()?.take(40) ?: ""
        val desc = node.contentDescription?.toString()?.take(40) ?: ""
        val id = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        sb.append(text).append('|').append(desc).append('|').append(id).append(';')
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            walk(child, sb, depth + 1, maxDepth)
        }
    }
}
