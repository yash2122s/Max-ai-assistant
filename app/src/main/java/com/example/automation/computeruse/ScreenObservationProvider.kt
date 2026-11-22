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
import kotlinx.coroutines.delay

object ScreenObservationProvider {
    private const val TAG = "ScreenObservation"
    private const val MAX_TRAVERSAL_NODES = 150
    private const val MAX_TREE_DEPTH = 30

    suspend fun awaitStableObservation(
        context: Context,
        timeoutMs: Long = 5_000L,
        pollMs: Long = 200L,
        expectedPackage: String? = null,
        expectedText: String? = null,
        taskId: String? = null
    ): ScreenObservation {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(pollMs)
        var previous: ScreenObservation? = null
        var stablePolls = 0
        var latest = captureObservation(context, false, taskId = taskId)
        while (System.currentTimeMillis() < deadline) {
            val packageReady = expectedPackage.isNullOrBlank() || latest.foregroundPackage == expectedPackage
            val textReady = expectedText.isNullOrBlank() || latest.visibleTexts.any { it == expectedText || it.contains(expectedText, true) }
            if (previous != null && !latest.hasScreenChanged(previous) && packageReady && textReady) {
                stablePolls++
                if (stablePolls >= 2) return latest
            } else {
                stablePolls = 0
            }
            previous = latest
            delay(pollMs.coerceIn(150L, 300L))
            latest = captureObservation(context, false, taskId = taskId)
        }
        return latest
    }

    suspend fun captureObservation(
        context: Context? = null,
        captureScreenshot: Boolean = true,
        taskId: String? = null,
        stepId: String? = null,
        executionId: String? = null,
        causedByAction: String? = null,
        expectedPostcondition: String? = null
    ): ScreenObservation = withContext(Dispatchers.Default) {
        val observation = withTimeoutOrNull(3500L) {
            val service = JarvisAccessibilityService.instance
            val rootNode = JarvisAccessibilityService.getActiveRoot() ?: com.example.automation.engine.LiveNodeResolver.rootProvider.invoke()
            val currentPackage = rootNode?.packageName?.toString()
                ?: service?.rootInActiveWindow?.packageName?.toString()
                ?: service?.windows?.firstOrNull { it.isActive }?.root?.packageName?.toString()
                ?: service?.windows?.firstOrNull { it.isFocused }?.root?.packageName?.toString()
                ?: com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot().packageName.takeIf { it.isNotBlank() }
                ?: com.example.automation.state.WorldStateManager.getCurrentState().currentApp.takeIf { it.contains('.') && !it.contains(' ') }
                ?: ""
            val currentActivity = rootNode?.className?.toString()?.takeIf { it.contains("Activity") }
                ?: com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot().activityName

            val obsId = "obs_${java.util.UUID.randomUUID()}"

            if (rootNode == null) {
                val snapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
                if (!snapshot.isFresh(2000L) || snapshot.packageName.isBlank()) {
                    val safePkg = com.example.automation.state.WorldStateManager.getCurrentState().currentApp.takeIf { it.contains('.') && !it.contains(' ') } ?: ""
                    return@withTimeoutOrNull ScreenObservation(observationId = obsId, timestamp = snapshot.timestamp,
                        taskId = taskId, stepId = stepId, executionId = executionId,
                        causedByAction = causedByAction, expectedPostcondition = expectedPostcondition,
                        foregroundPackage = safePkg)
                }
                val snapshotInteractive = snapshot.nodes.filter { it.isVisibleToUser && (it.isClickable || it.isEditable || it.isFocusable || it.isScrollable) }.map {
                    InteractiveElement(
                        id = it.resourceId,
                        text = it.text,
                        contentDescription = it.contentDescription,
                        className = it.className,
                        bounds = Rect(it.bounds),
                        isClickable = it.isClickable,
                        isEditable = it.isEditable,
                        isScrollable = it.isScrollable,
                        isFocused = it.isFocused,
                        isEnabled = it.isEnabled,
                        isSelected = it.isSelected
                    )
                }
                val snapshotTexts = snapshot.allVisibleTexts.ifEmpty { snapshot.nodes.mapNotNull { it.text } }
                Log.i(TAG, "[OBSERVE] app=${snapshot.packageName} treeNodes=${snapshot.nodes.size} source=state_store")
                return@withTimeoutOrNull ScreenObservation(
                    observationId = obsId,
                    foregroundPackage = snapshot.packageName,
                    currentActivity = snapshot.activityName,
                    interactiveElements = snapshotInteractive,
                    editableElements = snapshotInteractive.filter { it.isEditable },
                    scrollableElements = snapshotInteractive.filter { it.isScrollable },
                    focusedElement = snapshotInteractive.firstOrNull { it.isFocused },
                    visibleTexts = snapshotTexts,
                    timestamp = snapshot.timestamp
                    ,taskId = taskId, stepId = stepId, executionId = executionId,
                    causedByAction = causedByAction, expectedPostcondition = expectedPostcondition
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
                val isPassword = try { node.isPassword } catch (_: Exception) { false }

                if (node.isVisibleToUser && !text.isNullOrBlank() && !visibleTexts.contains(text)) {
                    visibleTexts.add(text)
                }

                val isInteractive = isClickable || isEditable || isScrollable || isFocused || !desc.isNullOrBlank() || !id.isNullOrBlank() || isPassword
                if (node.isVisibleToUser && isInteractive && (bounds.width() > 0 && bounds.height() > 0)) {
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
                        isSelected = isSelected,
                        isPassword = isPassword
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

            Log.i(TAG, "[OBSERVE] app=$currentPackage treeNodes=$nodeCount source=accessibility")

            ScreenObservation(
                observationId = obsId,
                foregroundPackage = currentPackage,
                currentActivity = currentActivity,
                interactiveElements = interactiveList,
                editableElements = editableList,
                scrollableElements = scrollableList,
                visibleTexts = visibleTexts,
                focusedElement = focusedElem,
                timestamp = System.currentTimeMillis()
                ,taskId = taskId, stepId = stepId, executionId = executionId,
                causedByAction = causedByAction, expectedPostcondition = expectedPostcondition
            )
        }

        val semantic = observation ?: ScreenObservation(
            foregroundPackage = "",
            timestamp = System.currentTimeMillis(), taskId = taskId, stepId = stepId,
            executionId = executionId, causedByAction = causedByAction,
            expectedPostcondition = expectedPostcondition
        )
        // Node traversal is finished before a potentially suspending screenshot capture.
        if (captureScreenshot) {
            val bytes = withContext(Dispatchers.IO) {
                com.example.voice.vision.ScreenCaptureProvider.captureCompressedJpeg()
            }
            semantic.copy(screenshotJpeg = bytes, screenshotFingerprint = bytes?.let {
                java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
            })
        } else {
            semantic
        }
    }
}
