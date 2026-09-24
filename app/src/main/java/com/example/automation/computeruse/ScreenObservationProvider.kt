package com.example.automation.computeruse

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.ShizukuManager
import com.example.automation.ShizukuShellPlugin
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

object ScreenObservationProvider {
    private const val TAG = "ScreenObservation"
    private const val MAX_TRAVERSAL_NODES = 150
    private const val MAX_TREE_DEPTH = 30
    private const val SCREENSHOT_PATH = "/sdcard/max_cu_obs.png"

    suspend fun captureObservation(context: Context? = null, captureScreenshot: Boolean = true): ScreenObservation = withContext(Dispatchers.Default) {
        val observation = withTimeoutOrNull(3500L) {
            val service = JarvisAccessibilityService.instance
            val rootNode = JarvisAccessibilityService.getActiveRoot()
            val currentPackage = rootNode?.packageName?.toString()
                ?: service?.rootInActiveWindow?.packageName?.toString()
                ?: service?.windows?.firstOrNull { it.isActive }?.root?.packageName?.toString()
                ?: service?.windows?.firstOrNull { it.isFocused }?.root?.packageName?.toString()
                ?: com.example.automation.state.WorldStateManager.getCurrentState().currentApp
                ?: ""
            val currentActivity = rootNode?.className?.toString()?.takeIf { it.contains("Activity") }

            // 1. Capture real physical screenshot
            var savedScreenshotPath: String? = null
            var screenshotFp: String? = null
            if (captureScreenshot) {
                try {
                    val screenshotPath = context?.cacheDir?.absolutePath?.let { "$it/max_cu_obs.png" } ?: SCREENSHOT_PATH
                    if (JarvisAccessibilityService.runShell("screencap -p $screenshotPath && chmod 666 $screenshotPath")) {
                        val f = File(screenshotPath)
                        if (f.exists() && f.length() > 0) {
                            savedScreenshotPath = screenshotPath
                            screenshotFp = computeScreenshotHash(f)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Screenshot capture failed: ${e.message}")
                }
            }

            val obsId = "obs_${System.currentTimeMillis()}"

            if (rootNode == null) {
                Log.i(TAG, "[OBSERVE] app=$currentPackage activity=${currentActivity ?: "none"} screenshot=${if (savedScreenshotPath != null) "yes" else "no"} treeNodes=0")
                return@withTimeoutOrNull ScreenObservation(
                    observationId = obsId,
                    foregroundPackage = currentPackage,
                    currentActivity = currentActivity,
                    screenshotPath = savedScreenshotPath,
                    screenshotFingerprint = screenshotFp,
                    timestamp = System.currentTimeMillis()
                )
            }

            val interactiveList = mutableListOf<InteractiveElement>()
            val editableList = mutableListOf<InteractiveElement>()
            val scrollableList = mutableListOf<InteractiveElement>()
            val visibleTexts = mutableListOf<String>()
            var focusedElem: InteractiveElement? = null
            var nodeCount = 0

            fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_TREE_DEPTH || nodeCount >= MAX_TRAVERSAL_NODES) return
                nodeCount++

                val bounds = Rect()
                try {
                    node.getBoundsInScreen(bounds)
                } catch (_: Exception) {}

                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val id = node.viewIdResourceName
                val className = node.className?.toString()
                val isClickable = node.isClickable
                val isEditable = node.isEditable
                val isScrollable = node.isScrollable
                val isFocused = node.isFocused
                val isEnabled = node.isEnabled
                val isSelected = node.isSelected

                if (!text.isNullOrBlank() && !visibleTexts.contains(text)) {
                    visibleTexts.add(text)
                }

                val isInteractive = isClickable || isEditable || isScrollable || isFocused || !desc.isNullOrBlank() || !id.isNullOrBlank()
                if (isInteractive && (bounds.width() > 0 && bounds.height() > 0)) {
                    val element = InteractiveElement(
                        id = id,
                        text = text,
                        contentDescription = desc,
                        className = className,
                        bounds = bounds,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isScrollable = isScrollable,
                        isFocused = isFocused,
                        isEnabled = isEnabled,
                        isSelected = isSelected
                    )
                    interactiveList.add(element)
                    if (isEditable) editableList.add(element)
                    if (isScrollable) scrollableList.add(element)
                    if (isFocused && isEditable) focusedElem = element
                }

                val childCount = try { node.childCount } catch (_: Exception) { 0 }
                for (i in 0 until childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                }
            }

            try {
                traverse(rootNode, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error traversing accessibility tree: ${e.message}")
            }

            Log.i(TAG, "[OBSERVE] app=$currentPackage activity=${currentActivity ?: "none"} screenshot=${if (savedScreenshotPath != null) "yes" else "no"} treeNodes=$nodeCount")

            ScreenObservation(
                observationId = obsId,
                foregroundPackage = currentPackage,
                currentActivity = currentActivity,
                interactiveElements = interactiveList,
                editableElements = editableList,
                scrollableElements = scrollableList,
                visibleTexts = visibleTexts,
                focusedElement = focusedElem,
                screenshotPath = savedScreenshotPath,
                screenshotFingerprint = screenshotFp,
                timestamp = System.currentTimeMillis()
            )
        }

        observation ?: ScreenObservation(
            foregroundPackage = "",
            timestamp = System.currentTimeMillis()
        )
    }

    private fun computeScreenshotHash(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed computing screenshot hash: ${e.message}")
            null
        }
    }
}
