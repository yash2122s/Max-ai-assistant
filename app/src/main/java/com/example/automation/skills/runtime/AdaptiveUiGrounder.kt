package com.example.automation.skills.runtime

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.resolver.InputTargetResolver
import com.example.automation.resolver.UiTargetResolver
import com.example.service.JarvisAccessibilityService

/**
 * Result of grounding a semantic target to a concrete UI node.
 */
data class GroundedUiTarget(
    val success: Boolean,
    val bounds: Rect? = null,
    val targetNode: AccessibilityNodeInfo? = null,
    val centerX: Int = bounds?.centerX() ?: 0,
    val centerY: Int = bounds?.centerY() ?: 0,
    val matchedText: String = "",
    val resolutionMethod: String = "SEMANTIC",
    val reason: String = ""
)

/**
 * Adaptive UI Grounder for MAX Automation Skills on Android.
 * Bridges abstract semantic targets ("search input", "first_search_result", "profile_message_button")
 * to concrete interactive elements on the live screen hierarchy.
 */
object AdaptiveUiGrounder {

    private const val TAG = "AdaptiveUiGrounder"

    /**
     * Grounds a semantic target query against current live UI hierarchy.
     */
    fun groundTarget(
        semanticTarget: String,
        arguments: Map<String, Any?> = emptyMap(),
        rootNode: AccessibilityNodeInfo? = null
    ): GroundedUiTarget {
        val root = rootNode ?: JarvisAccessibilityService.getActiveRoot()
        if (root == null) {
            return GroundedUiTarget(
                success = false,
                reason = "Accessibility root node is unavailable. Cannot inspect live UI."
            )
        }

        val cleanTarget = semanticTarget.trim()
        val role = (arguments["role"] as? String)?.lowercase() ?: ""
        val avoidList = (arguments["avoid"] as? List<*>)?.mapNotNull { it?.toString()?.lowercase() } ?: emptyList()

        Log.d(TAG, "Grounding target '$cleanTarget' (role='$role', avoid=$avoidList)")

        // 1. Check for Ordinal Queries ("first_search_result", "first account", "top result")
        if (isOrdinalQuery(cleanTarget)) {
            val ordinalResult = groundOrdinalTarget(cleanTarget, avoidList, root)
            if (ordinalResult.success) {
                return ordinalResult
            }
        }

        // 2. Check for Editable / Input targets
        if (role == "edit" || role == "input" || cleanTarget.contains("input") || cleanTarget.contains("search box")) {
            val resolvedInput = InputTargetResolver.resolveInputTarget(root, cleanTarget)
            if (resolvedInput != null && !isNodeAvoided(resolvedInput.node, avoidList)) {
                val rect = Rect()
                resolvedInput.node.getBoundsInScreen(rect)
                return GroundedUiTarget(
                    success = true,
                    bounds = rect,
                    targetNode = resolvedInput.node,
                    matchedText = resolvedInput.node.text?.toString() ?: resolvedInput.node.contentDescription?.toString() ?: cleanTarget,
                    resolutionMethod = "INPUT_RESOLVER",
                    reason = "Grounded editable field via InputTargetResolver"
                )
            }
        }

        // 3. Resolve via UiTargetResolver with semantic scoring & negative filtering
        val resolved = UiTargetResolver.resolve(
            query = cleanTarget,
            requireEditable = (role == "edit" || role == "input"),
            rootNode = root
        )

        if (resolved.success && resolved.target != null) {
            val matchedNode = resolved.matchedNode
            if (matchedNode != null && isNodeAvoided(matchedNode, avoidList)) {
                Log.w(TAG, "Matched node rejected by avoid list: $avoidList")
            } else {
                // Escalate to clickable container if needed
                val clickableNode = matchedNode?.let { escalateToClickableContainer(it, avoidList) } ?: matchedNode
                val bounds = Rect()
                if (clickableNode != null) {
                    clickableNode.getBoundsInScreen(bounds)
                } else {
                    resolved.target.bounds?.let { bounds.set(it) }
                }

                if (bounds.width() > 0 && bounds.height() > 0) {
                    val matchedText = resolved.target.text ?: cleanTarget
                    return GroundedUiTarget(
                        success = true,
                        bounds = bounds,
                        targetNode = clickableNode ?: matchedNode,
                        matchedText = matchedText,
                        resolutionMethod = resolved.method.name,
                        reason = "Resolved via UiTargetResolver (${resolved.reason})"
                    )
                }
            }
        }

        // 4. Fallback: Deep Traversal with Negative Filtering & Container Climbing
        val fallbackNode = findElementWithNegativeFilter(root, cleanTarget, role, avoidList)
        if (fallbackNode != null) {
            val rect = Rect()
            fallbackNode.getBoundsInScreen(rect)
            return GroundedUiTarget(
                success = true,
                bounds = rect,
                targetNode = fallbackNode,
                matchedText = fallbackNode.text?.toString() ?: fallbackNode.contentDescription?.toString() ?: cleanTarget,
                resolutionMethod = "DEEP_HIERARCHY_FALLBACK",
                reason = "Grounded via deep hierarchy scan with negative filtering"
            )
        }

        return GroundedUiTarget(
            success = false,
            reason = "Failed to ground target '$cleanTarget' on current screen hierarchy."
        )
    }

    private fun isOrdinalQuery(target: String): Boolean {
        val lower = target.lowercase()
        return lower.startsWith("first_") || lower.startsWith("first ") ||
                lower.startsWith("second_") || lower.startsWith("second ") ||
                lower.startsWith("top ") || lower.startsWith("latest ")
    }

    private fun groundOrdinalTarget(
        target: String,
        avoidList: List<String>,
        root: AccessibilityNodeInfo
    ): GroundedUiTarget {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val defaultAvoid = listOf("avatar", "story", "ring", "bottom_nav", "tab", "search_box", "search_edit_text", "action_bar")
        val combinedAvoid = (avoidList + defaultAvoid).distinct()

        findInteractiveRowCandidates(root, combinedAvoid, candidates)

        if (candidates.isNotEmpty()) {
            val selected = candidates[0]
            val rect = Rect()
            selected.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                Log.d(TAG, "✓ Grounded ordinal target '$target' to row at $rect")
                return GroundedUiTarget(
                    success = true,
                    bounds = rect,
                    targetNode = selected,
                    matchedText = selected.text?.toString() ?: selected.contentDescription?.toString() ?: target,
                    resolutionMethod = "ORDINAL_ROW_CONTAINER",
                    reason = "Selected top valid row container excluding decoys"
                )
            }
        }

        return GroundedUiTarget(success = false, reason = "No valid ordinal row candidate found")
    }

    private fun findInteractiveRowCandidates(
        node: AccessibilityNodeInfo,
        avoidList: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (isNodeAvoided(node, avoidList)) return

        val className = node.className?.toString() ?: ""
        val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
        val isClickable = node.isClickable

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val isRowShape = rect.width() > 300 && rect.height() in 60..400
        val isRowRes = resId.contains("row") || resId.contains("item") || resId.contains("container") || resId.contains("user")

        if (isClickable && (isRowShape || isRowRes) && rect.top > 150) {
            results.add(node)
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findInteractiveRowCandidates(child, avoidList, results)
        }
    }

    private fun findElementWithNegativeFilter(
        node: AccessibilityNodeInfo,
        targetQuery: String,
        targetRole: String,
        avoidList: List<String>
    ): AccessibilityNodeInfo? {
        if (isNodeAvoided(node, avoidList)) return null

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
        val query = targetQuery.lowercase()

        val textMatches = text.contains(query) || desc.contains(query) || resId.contains(query)
        if (textMatches) {
            return escalateToClickableContainer(node, avoidList)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findElementWithNegativeFilter(child, targetQuery, targetRole, avoidList)
            if (found != null) return found
        }
        return null
    }

    fun escalateToClickableContainer(
        node: AccessibilityNodeInfo,
        avoidList: List<String>
    ): AccessibilityNodeInfo {
        if (node.isClickable) return node

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (isNodeAvoided(current, avoidList)) break
            if (current.isClickable) {
                Log.d(TAG, "Escalated passive node to clickable container at depth $depth (${current.className})")
                return current
            }
            current = current.parent
            depth++
        }
        return node
    }

    private fun isNodeAvoided(node: AccessibilityNodeInfo, avoidList: List<String>): Boolean {
        if (avoidList.isEmpty()) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""

        for (avoid in avoidList) {
            if (text.contains(avoid) || desc.contains(avoid) || resId.contains(avoid)) {
                return true
            }
        }
        return false
    }
}
