package com.example.automation.monitor

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenSnapshot
import com.example.automation.state.UiStateSource
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

object AccessibilityStateMonitor {
    private const val TAG = "AccessibilityStateMonitor"
    private const val DEBOUNCE_MS = 100L
    private const val MAX_DEPTH = 30
    private const val MAX_NODES = 500

    var rootProvider: () -> AccessibilityNodeInfo? = {
        JarvisAccessibilityService.getActiveRoot()
    }

    var monitorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var debounceJob: Job? = null

    private val debounceMutex = Mutex()
    private val refreshMutex = Mutex()

    private val requestCounter = AtomicLong(0L)

    @Volatile
    private var latestRequestId: Long = 0L

    private var lastFingerprint: String = ""

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val pkgName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Structural/focus/click changes must be observed immediately.
                requestImmediateRefresh(pkgName, className)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Content churn is coalesced to avoid rebuilding the tree
                // repeatedly during rapid UI updates.
                requestDebouncedRefresh(pkgName, className)
            }
        }
    }

    private fun requestImmediateRefresh(
        pkgName: String,
        className: String
    ) {
        val requestId = requestCounter.incrementAndGet()
        latestRequestId = requestId

        monitorScope.launch {
            // Cancel any pending debounced refresh because an immediate
            // observation has higher priority.
            debounceMutex.withLock {
                debounceJob?.cancel()
                debounceJob = null
            }

            refreshMutex.withLock {
                extractAndPublish(
                    pkgHint = pkgName,
                    classHint = className,
                    requestId = requestId
                )
            }
        }
    }

    private fun requestDebouncedRefresh(
        pkgName: String,
        className: String
    ) {
        val requestId = requestCounter.incrementAndGet()
        latestRequestId = requestId

        monitorScope.launch {
            debounceMutex.withLock {
                debounceJob?.cancel()

                debounceJob = monitorScope.launch {
                    delay(DEBOUNCE_MS)

                    refreshMutex.withLock {
                        extractAndPublish(
                            pkgHint = pkgName,
                            classHint = className,
                            requestId = requestId
                        )
                    }
                }
            }
        }
    }

    suspend fun refreshNow(
        pkgHint: String = "",
        classHint: String = ""
    ): UiScreenSnapshot {
        val requestId = requestCounter.incrementAndGet()
        latestRequestId = requestId

        debounceMutex.withLock {
            debounceJob?.cancel()
            debounceJob = null
        }

        return refreshMutex.withLock {
            extractAndPublish(
                pkgHint = pkgHint,
                classHint = classHint,
                requestId = requestId
            )
        }
    }

    private suspend fun extractAndPublish(
        pkgHint: String,
        classHint: String,
        requestId: Long
    ): UiScreenSnapshot {
        val root = try {
            rootProvider.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain root node", e)
            null
        }

        val snapshot = extractSnapshot(
            root = root,
            pkgHint = pkgHint,
            classHint = classHint
        )

        val fp = computeFingerprint(snapshot)

        val finalized = snapshot.copy(
            fingerprint = fp
        )

        synchronized(this) {
            // A newer accessibility event arrived while this tree was
            // being extracted. Never allow this older request to overwrite
            // the state produced by the newer request.
            if (requestId < latestRequestId) {
                Log.d(
                    TAG,
                    "Skipping stale refresh request=$requestId latest=$latestRequestId"
                )
                return finalized
            }

            if (fp != lastFingerprint) {
                lastFingerprint = fp

                CurrentUiStateStore.updateSnapshot(finalized)

                Log.d(
                    TAG,
                    "UI state published request=$requestId version=${CurrentUiStateStore.getCurrentSnapshot().stateVersion}"
                )
            }
        }

        return finalized
    }

    fun extractSnapshot(
        root: AccessibilityNodeInfo?,
        pkgHint: String = "",
        classHint: String = ""
    ): UiScreenSnapshot {
        if (root == null) {
            return UiScreenSnapshot(
                packageName = pkgHint,
                activityName = classHint,
                nodes = emptyList(),
                source = UiStateSource.ACCESSIBILITY
            )
        }

        val effectivePackage = root.packageName?.toString()?.ifBlank { null } ?: pkgHint
        val effectiveClass = classHint.ifBlank { null } ?: root.className?.toString()?.ifBlank { null }
        val collectedNodes = mutableListOf<SemanticNode>()
        var focusedNode: SemanticNode? = null
        var nodeCounter = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int, path: String) {
            if (depth > MAX_DEPTH || collectedNodes.size >= MAX_NODES) return

            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Exception) {}

            val resId = node.viewIdResourceName
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val className = node.className?.toString()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isFocusable = node.isFocusable
            val isFocused = node.isFocused
            val isScrollable = node.isScrollable
            val isEnabled = node.isEnabled
            val isVisible = node.isVisibleToUser
            val isSelected = try { node.isSelected } catch (_: Exception) { false }
            val isChecked = try { node.isChecked } catch (_: Exception) { false }

            val semanticId = if (!resId.isNullOrBlank()) {
                resId
            } else {
                "node_${nodeCounter++}"
            }

            val semantic = SemanticNode(
                id = semanticId,
                text = text,
                contentDescription = desc,
                resourceId = resId,
                className = className,
                bounds = bounds,
                isClickable = isClickable,
                isEditable = isEditable,
                isFocusable = isFocusable,
                isFocused = isFocused,
                isScrollable = isScrollable,
                isEnabled = isEnabled,
                isVisibleToUser = isVisible,
                isSelected = isSelected,
                isChecked = isChecked,
                hierarchyPath = path
            )
            collectedNodes.add(semantic)

            if (isFocused && focusedNode == null) {
                focusedNode = semantic
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    traverse(child, depth + 1, "$path/$i")
                }
            }
        }

        try {
            traverse(root, 0, "root")
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree", e)
        }

        val visibleTexts = collectedNodes
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotBlank() }

        return UiScreenSnapshot(
            packageName = effectivePackage,
            activityName = effectiveClass,
            windowId = root.windowId,
            nodes = collectedNodes,
            focusedNode = focusedNode,
            allVisibleTexts = visibleTexts,
            source = UiStateSource.ACCESSIBILITY
        )
    }

    private fun computeFingerprint(snapshot: UiScreenSnapshot): String {
        return CurrentUiStateStore.computeFingerprint(snapshot)
    }

    @Synchronized
    fun reset() {
        debounceJob?.cancel()
        debounceJob = null

        lastFingerprint = ""

        val next = requestCounter.incrementAndGet()
        latestRequestId = next
    }
}
