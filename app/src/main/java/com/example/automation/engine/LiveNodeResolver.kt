package com.example.automation.engine

import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.state.SemanticNode
import com.example.service.JarvisAccessibilityService

object LiveNodeResolver {
    private const val TAG = "LiveNodeResolver"
    private const val MAX_DEPTH = 30
    private const val MAX_NODES = 500
    private const val MAX_TRAVERSAL_MS = 250L
    private const val MAX_ANCESTOR_WALK = 4

    var rootProvider: () -> AccessibilityNodeInfo? = {
        JarvisAccessibilityService.getActiveRoot()
    }

    /** Resolve against the active tree; a duplicate semantic signature is not a safe target. */
    fun resolveLiveNode(
        target: SemanticNode,
        root: AccessibilityNodeInfo? = rootProvider.invoke()
    ): AccessibilityNodeInfo? {
        if (root == null) {
            Log.w(TAG, "Cannot resolve live node: root is null")
            return null
        }
        if (!target.isEnabled || !target.isVisibleToUser) return null

        val expectedPackage = target.resourceId?.substringBefore(":id/", "")?.takeIf { it.isNotBlank() }
        val rootPackage = root.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (expectedPackage != null && rootPackage != null && rootPackage != expectedPackage) return null

        val hasSemantics = !target.resourceId.isNullOrBlank() || !target.text.isNullOrBlank() ||
            !target.contentDescription.isNullOrBlank()
        // An anonymous target needs *exact* geometry, and a path or sole root as structural evidence.
        if (!hasSemantics && (target.bounds.isEmpty ||
                    (target.hierarchyPath.isBlank() && root.childCount != 0))) return null

        val pending = ArrayDeque<Triple<AccessibilityNodeInfo, Int, String>>()
        pending.add(Triple(root, 0, "root"))
        val deadline = SystemClock.elapsedRealtime() + MAX_TRAVERSAL_MS
        var visited = 0
        var match: AccessibilityNodeInfo? = null
        while (pending.isNotEmpty()) {
            val (node, depth, path) = pending.removeFirst()
            if (++visited > MAX_NODES || SystemClock.elapsedRealtime() >= deadline) return null
            if ((hasSemantics || target.hierarchyPath.isBlank() || target.hierarchyPath == path) &&
                matches(node, target, expectedPackage, hasSemantics)) {
                if (match != null) return null
                match = node
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    if (visited + pending.size >= MAX_NODES || SystemClock.elapsedRealtime() >= deadline) return null
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null) pending.add(Triple(child, depth + 1, "$path/$i"))
                }
            } else if (node.childCount > 0) {
                return null // Deeper unseen matches could make the target ambiguous.
            }
        }
        return match
    }

    /** Walk a bounded, well-formed path; reject malformed and excessive paths. */
    fun resolveByHierarchyPath(root: AccessibilityNodeInfo, hierarchyPath: String): AccessibilityNodeInfo? {
        if (hierarchyPath == "root") return root
        if (!hierarchyPath.startsWith("root/")) return null
        val segments = hierarchyPath.removePrefix("root/").split("/")
        if (segments.isEmpty() || segments.size > MAX_DEPTH || segments.any { it.isEmpty() }) return null

        var current = root
        for (segment in segments) {
            val index = segment.toIntOrNull() ?: return null
            if (index < 0 || index >= current.childCount) return null
            current = try { current.getChild(index) } catch (_: Exception) { null } ?: return null
        }
        return current
    }

    /** All supplied signatures must be present and equal, not merely non-conflicting. */
    fun validatePathCandidate(candidate: AccessibilityNodeInfo, target: SemanticNode): Boolean =
        matches(candidate, target, target.resourceId?.substringBefore(":id/", "")?.takeIf { it.isNotBlank() },
            !target.resourceId.isNullOrBlank() || !target.text.isNullOrBlank() || !target.contentDescription.isNullOrBlank())

    private fun matches(
        node: AccessibilityNodeInfo,
        target: SemanticNode,
        expectedPackage: String?,
        hasSemantics: Boolean
    ): Boolean {
        try {
            if (!node.isVisibleToUser || !node.isEnabled) return false
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return false
            val nodePackage = node.packageName?.toString()?.takeIf { it.isNotBlank() }
            if (expectedPackage != null && nodePackage != null && expectedPackage != nodePackage) return false
            if (!target.resourceId.isNullOrBlank() && node.viewIdResourceName != target.resourceId) return false
            if (!target.text.isNullOrBlank() && node.text?.toString() != target.text) return false
            if (!target.contentDescription.isNullOrBlank() && node.contentDescription?.toString() != target.contentDescription) return false
            if (!target.className.isNullOrBlank() && node.className?.toString() != target.className) return false
            if (!hasSemantics) {
                if (bounds != target.bounds) return false
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** Return only a visible, enabled ancestor which supports the requested action. */
    fun resolveClickableTarget(node: AccessibilityNodeInfo, action: Int = AccessibilityNodeInfo.ACTION_CLICK): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTOR_WALK + 1) {
            val candidate = current ?: return null
            if (!candidate.isVisibleToUser || !candidate.isEnabled) return null
            val suitable = if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) candidate.isLongClickable else candidate.isClickable
            if (suitable && try { candidate.actionList.any { it.id == action } } catch (_: Exception) { false }) return candidate
            val parent = try { candidate.parent } catch (_: Exception) { null } ?: return null
            val childBounds = Rect()
            val parentBounds = Rect()
            try {
                candidate.getBoundsInScreen(childBounds)
                parent.getBoundsInScreen(parentBounds)
            } catch (_: Exception) { return null }
            if (childBounds.isEmpty || !parentBounds.contains(childBounds.centerX(), childBounds.centerY())) return null
            current = parent
        }
        return null
    }
}
