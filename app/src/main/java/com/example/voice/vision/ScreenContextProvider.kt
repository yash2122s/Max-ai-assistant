package com.example.voice.vision

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.state.WorldStateManager
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.network.GeminiWebSocketClient
import com.example.service.AccessibilityTreePruner
import com.example.service.IndexedUiElement
import com.example.service.JarvisAccessibilityService
import com.example.service.PrunedNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenContextPayload(
    val packageName: String?,
    val prunedNodes: List<PrunedNode>,
    val visibleText: String,
    val clickableElements: List<String>,
    val screenshotJpeg: ByteArray?,
    val isScreenshotSent: Boolean,
    val hasAccessibility: Boolean,
    val focusMode: String,
    val indexedElements: List<IndexedUiElement> = emptyList(),
    val indexedElementsFormatted: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object ScreenContextProvider {
    private const val TAG = "ScreenContextProvider"
    private const val CAPTURE_TIMEOUT_MS = 5000L
    private const val NODE_RETRY_DELAY_MS = 200L

    suspend fun captureScreenContext(context: Context, focus: String = "general"): ScreenContextPayload {
        val normalizedFocus = focus.lowercase().trim().ifEmpty { "general" }
        Log.d(TAG, "Capturing screen context with focus: '$normalizedFocus'")

        // Wrap entire capture in a hard timeout to prevent indefinite blocking
        val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            captureScreenContextInternal(context, normalizedFocus)
        }

        if (result != null) return result

        // Timeout occurred — return a graceful degraded payload
        Log.w(TAG, "Screen context capture timed out after ${CAPTURE_TIMEOUT_MS}ms")
        return ScreenContextPayload(
            packageName = WorldStateManager.getCurrentState().currentApp.ifEmpty { null },
            prunedNodes = emptyList(),
            visibleText = "",
            clickableElements = emptyList(),
            screenshotJpeg = null,
            isScreenshotSent = false,
            hasAccessibility = false,
            focusMode = normalizedFocus,
            indexedElements = emptyList(),
            indexedElementsFormatted = ""
        )
    }

    /**
     * Attempts to obtain a fresh, non-recycled root node from the accessibility service.
     * Retries once after a short delay if the first attempt returns null or a stale node.
     */
    private suspend fun obtainFreshRootNode(service: JarvisAccessibilityService): AccessibilityNodeInfo? {
        // 1. Direct active root from service or active window
        var rootNode = JarvisAccessibilityService.getActiveRoot()
        if (rootNode != null) {
            try {
                // Try refresh if possible, but keep node if refresh returns false
                rootNode.refresh()
                return rootNode
            } catch (e: Exception) {
                Log.w(TAG, "Root node threw on initial check: ${e.message}")
                rootNode = null
            }
        }

        // 2. Retry after a short delay
        delay(NODE_RETRY_DELAY_MS)
        rootNode = JarvisAccessibilityService.getActiveRoot()
        if (rootNode != null) {
            try {
                rootNode.refresh()
                return rootNode
            } catch (e: Exception) {
                Log.w(TAG, "Root node threw on retry: ${e.message}")
            }
        }

        Log.w(TAG, "Failed to obtain fresh root node after retry")
        return null
    }

    private suspend fun captureScreenContextInternal(context: Context, normalizedFocus: String): ScreenContextPayload {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        val rootNode = if (service != null) obtainFreshRootNode(service) else null
        val hasAccessibility = service != null && rootNode != null

        val packageName = try {
            rootNode?.packageName?.toString()
                ?: WorldStateManager.getCurrentState().currentApp.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading packageName from root node: ${e.message}")
            WorldStateManager.getCurrentState().currentApp.ifEmpty { null }
        }

        if (!packageName.isNullOrBlank()) {
            WorldStateManager.setForegroundApp(packageName)
        }

        val prunedNodes = if (hasAccessibility && rootNode != null) {
            try {
                AccessibilityTreePruner.pruneTree(rootNode)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Accessibility node recycled during pruning: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prune accessibility tree: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val indexedElements = if (hasAccessibility && service != null && rootNode != null) {
            try {
                service.updateIndexedElements(rootNode)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Accessibility node recycled during indexing: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update indexed elements: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val indexedElementsFormatted = if (indexedElements.isNotEmpty()) {
            val sb = StringBuilder("INTERACTIVE ELEMENTS:\n")
            indexedElements.forEach { sb.append(it.toDisplayString()).append("\n") }
            sb.toString().trim()
        } else ""

        // Build combined visible text while protecting password fields
        val visibleTextBuilder = StringBuilder()
        for (node in prunedNodes) {
            val text = node.text?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.takeIf { it.isNotBlank() }
            if (!text.isNullOrBlank()) {
                visibleTextBuilder.append(text).append("\n")
            } else if (!desc.isNullOrBlank()) {
                visibleTextBuilder.append(desc).append("\n")
            }
        }
        val visibleText = visibleTextBuilder.toString().trim()

        // List clickable/interactive elements with details for find_element or general inspection
        val clickableElements = prunedNodes
            .filter { it.isClickable || it.isEditable }
            .map { node ->
                val label = node.text?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.takeIf { it.isNotBlank() }
                    ?: node.resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: node.className
                val bounds = "[${node.boundsInScreen.left},${node.boundsInScreen.top},${node.boundsInScreen.right},${node.boundsInScreen.bottom}]"
                val type = if (node.isEditable) "input" else "button"
                "$type: '$label' (id=${node.nodeId}, bounds=$bounds)"
            }

        // Determine if screenshot capture is required based on focus mode & text availability
        val needScreenshot = when (normalizedFocus) {
            "read_text" -> visibleText.isBlank()
            "find_element" -> clickableElements.isEmpty()
            "summary" -> visibleText.isBlank() && clickableElements.isEmpty()
            "explain_error", "visual", "screenshot" -> true
            "general", "accessibility_only", "planning" -> false
            else -> false
        }

        var screenshotJpeg: ByteArray? = null
        var isScreenshotSent = false

        if (needScreenshot) {
            try {
                screenshotJpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (screenshotJpeg != null && screenshotJpeg.isNotEmpty()) {
                    val wsClient = ServiceRegistry.get<GeminiWebSocketClient>(ServiceType.VOICE)
                    if (wsClient != null) {
                        isScreenshotSent = wsClient.sendVideoFrame(screenshotJpeg)
                        Log.d(TAG, "Screenshot frame (${screenshotJpeg.size} bytes) transmitted to Gemini Live: $isScreenshotSent")
                    } else {
                        Log.w(TAG, "GeminiWebSocketClient not found in ServiceRegistry, video frame not transmitted")
                    }
                } else {
                    Log.w(TAG, "ScreenCaptureProvider returned null or empty JPEG frame")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screenshot frame: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Screenshot skipped for focus mode '$normalizedFocus' because accessibility information is sufficient")
        }

        return ScreenContextPayload(
            packageName = packageName,
            prunedNodes = prunedNodes,
            visibleText = visibleText,
            clickableElements = clickableElements,
            screenshotJpeg = screenshotJpeg,
            isScreenshotSent = isScreenshotSent,
            hasAccessibility = hasAccessibility,
            focusMode = normalizedFocus,
            indexedElements = indexedElements,
            indexedElementsFormatted = indexedElementsFormatted
        )
    }
}
