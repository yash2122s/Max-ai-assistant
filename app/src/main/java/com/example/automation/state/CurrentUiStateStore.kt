package com.example.automation.state

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

enum class UiStateSource {
    ACCESSIBILITY,
    VISION,
    REHYDRATED
}

data class SemanticNode(
    val id: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isFocusable: Boolean = false,
    val isFocused: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val hierarchyPath: String = ""
) {
    val primaryText: String
        get() = text ?: contentDescription ?: ""
}

data class UiScreenSnapshot(
    val packageName: String = "",
    val activityName: String? = null,
    val windowId: Int = -1,
    val stateVersion: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val fingerprint: String = "",
    val nodes: List<SemanticNode> = emptyList(),
    val focusedNode: SemanticNode? = null,
    val allVisibleTexts: List<String> = emptyList(),
    val isStale: Boolean = false,
    val source: UiStateSource = UiStateSource.ACCESSIBILITY
) {
    fun isFresh(maxAgeMs: Long = 1500L, expectedPackage: String? = null): Boolean {
        if (isStale) return false
        if (System.currentTimeMillis() - timestamp > maxAgeMs) return false
        if (expectedPackage != null && !packageName.equals(expectedPackage, ignoreCase = true)) return false
        return true
    }

    val isSparseOrObscured: Boolean
        get() {
            if (packageName.isBlank() || nodes.isEmpty()) {
                return true
            }

            val visibleSemanticNodes = nodes.count { node ->
                node.isVisibleToUser &&
                    (
                        !node.text.isNullOrBlank() ||
                        !node.contentDescription.isNullOrBlank() ||
                        !node.resourceId.isNullOrBlank()
                    )
            }

            val visibleInteractiveNodes = nodes.count { node ->
                node.isVisibleToUser &&
                    node.isEnabled &&
                    (
                        node.isClickable ||
                        node.isEditable ||
                        node.isFocusable
                    )
            }

            val hasSurfaceLikeNode = nodes.any { node ->
                val cls = node.className?.lowercase() ?: ""
                cls.contains("webview") ||
                cls.contains("surfaceview") ||
                cls.contains("textureview")
            }

            // Accessibility tree is considered sparse when it exposes
            // almost no meaningful semantic content and no usable control.
            if (visibleSemanticNodes < 2 && visibleInteractiveNodes == 0) {
                return true
            }

            // Surface/WebView screens may visually contain information
            // that is not fully represented in the accessibility tree.
            if (hasSurfaceLikeNode &&
                visibleInteractiveNodes == 0 &&
                visibleSemanticNodes < 4
            ) {
                return true
            }

            return false
        }
}

object CurrentUiStateStore {
    private const val TAG = "CurrentUiStateStore"
    private var versionCounter = 0L

    private val _snapshotFlow = MutableStateFlow(UiScreenSnapshot())
    val snapshotFlow: StateFlow<UiScreenSnapshot> = _snapshotFlow.asStateFlow()

    @Synchronized
    fun updateSnapshot(snapshot: UiScreenSnapshot) {
        val nextVersion = ++versionCounter
        val fp = if (snapshot.fingerprint.isNotBlank()) snapshot.fingerprint else computeFingerprint(snapshot)
        val visibleTexts = if (snapshot.allVisibleTexts.isNotEmpty()) {
            snapshot.allVisibleTexts
        } else {
            snapshot.nodes.mapNotNull { it.text?.trim() }.filter { it.isNotBlank() }
        }
        val focused = snapshot.focusedNode ?: snapshot.nodes.firstOrNull { it.isFocused }

        val finalized = snapshot.copy(
            stateVersion = nextVersion,
            timestamp = if (snapshot.timestamp > 0) snapshot.timestamp else System.currentTimeMillis(),
            fingerprint = fp,
            allVisibleTexts = visibleTexts,
            focusedNode = focused,
            isStale = false
        )
        _snapshotFlow.value = finalized

        // Synchronize with WorldStateManager for single authoritative package/screen truth
        WorldStateManager.setForegroundApp(finalized.packageName, finalized.activityName ?: "")
        WorldStateManager.setScreenSignature(finalized.fingerprint)
    }

    fun getCurrentSnapshot(): UiScreenSnapshot {
        return _snapshotFlow.value
    }

    @Synchronized
    fun markStale() {
        val current = _snapshotFlow.value
        if (!current.isStale) {
            _snapshotFlow.value = current.copy(isStale = true)
        }
    }

    @Synchronized
    fun reset() {
        versionCounter = 0L
        _snapshotFlow.value = UiScreenSnapshot()
    }

    suspend fun waitForStateChange(
        timeoutMs: Long = 1500L,
        predicate: (UiScreenSnapshot) -> Boolean
    ): Boolean {
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                _snapshotFlow.first { snapshot ->
                    predicate(snapshot)
                }
            }
            result != null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun waitForPredicate(
        predicate: ExpectedPredicate,
        timeoutMs: Long = 1500L,
        maxStateAgeMs: Long = 2000L,
        minStateVersion: Long = 0L
    ): Boolean {
        val current = _snapshotFlow.value
        if (current.stateVersion >= minStateVersion && current.isFresh(maxStateAgeMs) && predicate.evaluate(current)) {
            return true
        }
        val baselineVersion = if (minStateVersion > 0L) minStateVersion else current.stateVersion
        return waitForStateChange(timeoutMs) { snapshot ->
            (snapshot.stateVersion >= baselineVersion) &&
            snapshot.isFresh(maxStateAgeMs) &&
            predicate.evaluate(snapshot)
        }
    }

    fun computeFingerprint(snapshot: UiScreenSnapshot): String {
        val raw = StringBuilder()
        raw.append(snapshot.packageName).append("|")
            .append(snapshot.activityName ?: "").append("|")
            .append("focused=").append(snapshot.focusedNode?.id ?: "none").append("|")
        for (node in snapshot.nodes) {
            raw.append(node.id)
                .append(":t=").append(node.text ?: "")
                .append(":d=").append(node.contentDescription ?: "")
                .append(":f=").append(if (node.isFocused) "1" else "0")
                .append(":s=").append(if (node.isSelected) "1" else "0")
                .append(":c=").append(if (node.isChecked) "1" else "0")
                .append(":e=").append(if (node.isEnabled) "1" else "0")
                .append(":b=").append(node.bounds.toShortString())
                .append(";")
        }
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(raw.toString().toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            raw.toString().hashCode().toString()
        }
    }
}
