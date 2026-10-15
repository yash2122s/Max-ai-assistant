package com.example.automation.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.state.SemanticNode
import com.example.service.JarvisAccessibilityService

object LiveNodeResolver {
    private const val TAG = "LiveNodeResolver"
    private const val MAX_DEPTH = 30
    private const val MAX_ANCESTOR_WALK = 4

    var rootProvider: () -> AccessibilityNodeInfo? = {
        JarvisAccessibilityService.getActiveRoot()
    }

    /**
     * Re-resolves a live AccessibilityNodeInfo corresponding to the immutable SemanticNode snapshot.
     */
    fun resolveLiveNode(
        target: SemanticNode,
        root: AccessibilityNodeInfo? = rootProvider.invoke()
    ): AccessibilityNodeInfo? {
        if (root == null) {
            Log.w(TAG, "Cannot resolve live node: root is null")
            return null
        }

        // 1. Try by Resource ID
        if (!target.resourceId.isNullOrBlank()) {
            val byId = try {
                root.findAccessibilityNodeInfosByViewId(target.resourceId)
            } catch (_: Exception) {
                emptyList()
            }
            if (!byId.isNullOrEmpty()) {
                val matched = findBestMatchingNode(byId, target)
                if (matched != null) return matched
            }
        }

        // 2. Try by Exact Text
        if (!target.text.isNullOrBlank()) {
            val byText = try {
                root.findAccessibilityNodeInfosByText(target.text)
            } catch (_: Exception) {
                emptyList()
            }
            if (!byText.isNullOrEmpty()) {
                val matched = findBestMatchingNode(byText, target)
                if (matched != null) return matched
            }
        }

        // 3. Try by Hierarchy Path (deterministic positional fallback with signature validation)
        if (target.hierarchyPath.isNotBlank() && target.hierarchyPath != "root") {
            val byPath = resolveByHierarchyPath(root, target.hierarchyPath)
            if (byPath != null && validatePathCandidate(byPath, target)) {
                return byPath
            }
        }

        // 4. Fallback to tree traversal
        return searchTree(root, target, 0)
    }

    /**
     * Traverses down child indices along hierarchyPath (e.g. "root/0/1/2") to reacquire live node.
     */
    fun resolveByHierarchyPath(root: AccessibilityNodeInfo, hierarchyPath: String): AccessibilityNodeInfo? {
        val segments = hierarchyPath.removePrefix("root/").removePrefix("root").split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return root

        var current: AccessibilityNodeInfo = root
        for (seg in segments) {
            val childIndex = seg.toIntOrNull() ?: return null
            if (childIndex < 0 || childIndex >= current.childCount) return null
            val child = try {
                current.getChild(childIndex)
            } catch (_: Exception) {
                null
            } ?: return null
            current = child
        }
        return current
    }

    /**
     * Validates that candidate resolved via hierarchyPath matches target's structural signature.
     */
    fun validatePathCandidate(candidate: AccessibilityNodeInfo, target: SemanticNode): Boolean {
        // 1. Validate class name if specified
        if (!target.className.isNullOrBlank()) {
            val candClass = candidate.className?.toString()
            if (!candClass.isNullOrBlank() && candClass != target.className) {
                return false
            }
        }

        // 2. Validate resource ID if specified
        if (!target.resourceId.isNullOrBlank()) {
            val candId = candidate.viewIdResourceName
            if (!candId.isNullOrBlank() && candId != target.resourceId) {
                return false
            }
        }

        // 3. Validate text / contentDescription if specified
        if (!target.text.isNullOrBlank()) {
            val candText = candidate.text?.toString()
            if (!candText.isNullOrBlank() && candText != target.text) {
                return false
            }
        }
        if (!target.contentDescription.isNullOrBlank()) {
            val candDesc = candidate.contentDescription?.toString()
            if (!candDesc.isNullOrBlank() && candDesc != target.contentDescription) {
                return false
            }
        }

        // 4. Validate bounds proximity if target has valid bounds
        if (target.bounds.width() > 0 && target.bounds.height() > 0) {
            val candBounds = Rect()
            try {
                candidate.getBoundsInScreen(candBounds)
            } catch (_: Exception) {}
            if (candBounds.width() > 0 && candBounds.height() > 0) {
                val dx = Math.abs(candBounds.centerX() - target.bounds.centerX())
                val dy = Math.abs(candBounds.centerY() - target.bounds.centerY())
                if (dx > 300 || dy > 300) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * If the resolved node is not clickable, traverses up to [MAX_ANCESTOR_WALK] parents
     * to find the nearest clickable container (e.g. TextView inside a clickable Button/Row).
     */
    fun resolveClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node

        var current: AccessibilityNodeInfo = node
        for (i in 0 until MAX_ANCESTOR_WALK) {
            val parent = try {
                current.parent
            } catch (_: Exception) {
                null
            } ?: break

            if (parent.isClickable) {
                return parent
            }
            current = parent
        }
        return node
    }

    private fun findBestMatchingNode(
        candidates: List<AccessibilityNodeInfo>,
        target: SemanticNode
    ): AccessibilityNodeInfo? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val targetBounds = target.bounds
        var bestNode: AccessibilityNodeInfo? = null
        var minDistance = Float.MAX_VALUE

        for (candidate in candidates) {
            val bounds = Rect()
            try {
                candidate.getBoundsInScreen(bounds)
            } catch (_: Exception) {
                continue
            }

            // Exact bounds match
            if (bounds == targetBounds) {
                return candidate
            }

            // Center proximity distance
            val dx = (bounds.centerX() - targetBounds.centerX()).toFloat()
            val dy = (bounds.centerY() - targetBounds.centerY()).toFloat()
            val dist = dx * dx + dy * dy

            if (dist < minDistance) {
                minDistance = dist
                bestNode = candidate
            }
        }

        return bestNode ?: candidates.first()
    }

    private fun searchTree(
        node: AccessibilityNodeInfo,
        target: SemanticNode,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null

        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Exception) {}

        val resId = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        val idMatch = !target.resourceId.isNullOrBlank() && resId == target.resourceId
        val textMatch = !target.text.isNullOrBlank() && text == target.text
        val descMatch = !target.contentDescription.isNullOrBlank() && desc == target.contentDescription

        if (idMatch || textMatch || descMatch) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                val found = searchTree(child, target, depth + 1)
                if (found != null) return found
            }
        }

        return null
    }
}
