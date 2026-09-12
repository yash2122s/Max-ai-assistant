package com.example.automation.resolver

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.TargetResolutionMethod
import com.example.automation.models.UiTarget
import com.example.service.JarvisAccessibilityService
import java.util.Locale

/**
 * Result of resolving a UI target query against current screen hierarchy.
 */
data class UiTargetResolutionResult(
    val success: Boolean,
    val target: UiTarget?,
    val matchedNode: AccessibilityNodeInfo? = null,
    val confidence: Float = 0f,
    val method: TargetResolutionMethod = TargetResolutionMethod.TEXT,
    val reason: String = "",
    val candidateCount: Int = 0
)

/**
 * Isolated, deterministic UI Target Resolver for MAX.
 * Resolves runtime target queries into actionable [UiTarget] instances
 * following strict hierarchy priority:
 * 1. Resource ID (0.99 - 1.00)
 * 2. Exact Visible Text (0.95 - 0.98)
 * 3. Content Description (0.92 - 0.95)
 * 4. Accessibility Semantic Role (0.88 - 0.90)
 * 5. Editable Node (0.86 - 0.90)
 * 6. Clickable Node Escalation (0.85 - 0.88)
 * 7. OCR Fallback (0.75 - 0.80)
 * 8. Visual Target (0.70 - 0.75)
 * 9. Coordinate Fallback (0.60 - 0.70)
 */
object UiTargetResolver {
    private const val TAG = "UiTargetResolver"
    const val SAFE_CONFIDENCE_THRESHOLD = 0.85f

    /**
     * Resolves a target against the provided [rootNode] or active accessibility window.
     */
    fun resolve(
        query: String,
        explicitResourceId: String? = null,
        explicitContentDescription: String? = null,
        targetRole: String? = null,
        requireEditable: Boolean = false,
        requireClickable: Boolean = false,
        rootNode: AccessibilityNodeInfo? = null
    ): UiTargetResolutionResult {
        val cleanQuery = query.trim()
        val effectiveRoot = rootNode ?: JarvisAccessibilityService.instance?.rootInActiveWindow

        if (effectiveRoot == null) {
            Log.w(TAG, "Cannot resolve target '$cleanQuery': active window root is null")
            return UiTargetResolutionResult(
                success = false,
                target = null,
                confidence = 0f,
                reason = "Accessibility root node is null or inactive"
            )
        }

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(effectiveRoot, allNodes)

        if (allNodes.isEmpty()) {
            return UiTargetResolutionResult(
                success = false,
                target = null,
                confidence = 0f,
                reason = "No visible UI nodes found in active hierarchy"
            )
        }

        // =========================================================================
        // 1. RESOURCE ID MATCHING (Confidence 0.99 - 1.0)
        // =========================================================================
        val targetResId = explicitResourceId ?: cleanQuery
        if (targetResId.isNotBlank()) {
            val resMatches = allNodes.filter { node ->
                val id = node.viewIdResourceName ?: ""
                val entryName = id.substringAfterLast('/')
                id.equals(targetResId, ignoreCase = true) ||
                (entryName.isNotEmpty() && entryName.equals(targetResId, ignoreCase = true)) ||
                (targetResId.contains('/') && entryName.isNotEmpty() && entryName.equals(targetResId.substringAfterLast('/'), ignoreCase = true))
            }

            if (resMatches.isNotEmpty()) {
                val bestNode = if (requireEditable) {
                    resMatches.firstOrNull { it.isEditable } ?: resMatches.first()
                } else if (requireClickable) {
                    resMatches.firstOrNull { it.isClickable } ?: resMatches.first()
                } else {
                    resMatches.first()
                }

                val target = createUiTarget(bestNode, 0.99f, TargetResolutionMethod.RESOURCE_ID)
                Log.d(TAG, "Resolved by RESOURCE_ID: id=${target.resourceId}, confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.RESOURCE_ID,
                    reason = "Exact resource ID match",
                    candidateCount = resMatches.size
                )
            }
        }

        // =========================================================================
        // 2. EXACT VISIBLE TEXT MATCHING (Confidence 0.95 - 0.98)
        // =========================================================================
        if (cleanQuery.isNotEmpty()) {
            val exactTextMatches = allNodes.filter { node ->
                val text = node.text?.toString()?.trim() ?: ""
                text.equals(cleanQuery, ignoreCase = false)
            }

            if (exactTextMatches.isNotEmpty()) {
                val bestNode = filterBestNode(exactTextMatches, requireEditable, requireClickable)
                val target = createUiTarget(bestNode, 0.98f, TargetResolutionMethod.TEXT)
                Log.d(TAG, "Resolved by EXACT_TEXT: text='${target.text}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.TEXT,
                    reason = "Exact visible text match",
                    candidateCount = exactTextMatches.size
                )
            }

            // Case-insensitive text match
            val ciTextMatches = allNodes.filter { node ->
                val text = node.text?.toString()?.trim() ?: ""
                text.equals(cleanQuery, ignoreCase = true)
            }

            if (ciTextMatches.isNotEmpty()) {
                val bestNode = filterBestNode(ciTextMatches, requireEditable, requireClickable)
                val target = createUiTarget(bestNode, 0.95f, TargetResolutionMethod.TEXT)
                Log.d(TAG, "Resolved by CI_TEXT: text='${target.text}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.TEXT,
                    reason = "Case-insensitive visible text match",
                    candidateCount = ciTextMatches.size
                )
            }
        }

        // =========================================================================
        // 3. CONTENT DESCRIPTION MATCHING (Confidence 0.92 - 0.95)
        // =========================================================================
        val targetDesc = explicitContentDescription ?: cleanQuery
        if (targetDesc.isNotEmpty()) {
            val descMatches = allNodes.filter { node ->
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                desc.equals(targetDesc, ignoreCase = true)
            }

            if (descMatches.isNotEmpty()) {
                val bestNode = filterBestNode(descMatches, requireEditable, requireClickable)
                val target = createUiTarget(bestNode, 0.93f, TargetResolutionMethod.CONTENT_DESCRIPTION)
                Log.d(TAG, "Resolved by CONTENT_DESC: desc='${target.contentDescription}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.CONTENT_DESCRIPTION,
                    reason = "Exact content description match",
                    candidateCount = descMatches.size
                )
            }
        }

        // =========================================================================
        // 4. ACCESSIBILITY SEMANTIC ROLE / KEYWORD MATCHING (Confidence 0.88 - 0.90)
        // =========================================================================
        if (cleanQuery.isNotEmpty()) {
            val semanticKeywords = resolveSemanticAliases(cleanQuery)
            val semanticMatches = allNodes.filter { node ->
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                semanticKeywords.any { kw ->
                    text.contains(kw) || desc.contains(kw) || id.contains(kw)
                }
            }

            if (semanticMatches.isNotEmpty()) {
                val bestNode = filterBestNode(semanticMatches, requireEditable, requireClickable)
                val target = createUiTarget(bestNode, 0.89f, TargetResolutionMethod.SEMANTIC)
                Log.d(TAG, "Resolved by SEMANTIC: label='${target.text ?: target.contentDescription}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.SEMANTIC,
                    reason = "Semantic alias match",
                    candidateCount = semanticMatches.size
                )
            }
        }

        // =========================================================================
        // 5. EDITABLE NODE DETECTION (Confidence 0.86 - 0.90)
        // =========================================================================
        if (requireEditable || cleanQuery.lowercase(Locale.ROOT).contains("search") ||
            cleanQuery.lowercase(Locale.ROOT).contains("type") || cleanQuery.lowercase(Locale.ROOT).contains("input")) {
            val editableNodes = allNodes.filter { it.isEditable || it.className?.toString()?.contains("EditText", ignoreCase = true) == true }
            if (editableNodes.isNotEmpty()) {
                val bestNode = editableNodes.first()
                val target = createUiTarget(bestNode, 0.88f, TargetResolutionMethod.EDITABLE)
                Log.d(TAG, "Resolved by EDITABLE: confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.EDITABLE,
                    reason = "Active editable text field detected",
                    candidateCount = editableNodes.size
                )
            }
        }

        // =========================================================================
        // 6. CLICKABLE ANCESTOR / DESCENDANT ESCALATION (Confidence 0.85 - 0.88)
        // =========================================================================
        if (cleanQuery.isNotEmpty()) {
            val substringMatches = allNodes.filter { node ->
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                (text.isNotEmpty() && text.contains(cleanQuery, ignoreCase = true)) ||
                (desc.isNotEmpty() && desc.contains(cleanQuery, ignoreCase = true))
            }

            if (substringMatches.isNotEmpty()) {
                val baseNode = substringMatches.first()
                val clickableNode = findClickableAncestor(baseNode) ?: baseNode
                val target = createUiTarget(clickableNode, 0.86f, TargetResolutionMethod.TEXT)
                Log.d(TAG, "Resolved by SUBSTRING_CLICKABLE: confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = clickableNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.TEXT,
                    reason = "Partial substring match with clickable escalation",
                    candidateCount = substringMatches.size
                )
            }
        }

        // Target not found within deterministic accessibility tree
        Log.w(TAG, "Target '$cleanQuery' could not be resolved in active hierarchy")
        return UiTargetResolutionResult(
            success = false,
            target = null,
            confidence = 0f,
            reason = "Target '$cleanQuery' not found in accessibility hierarchy"
        )
    }

    private fun filterBestNode(
        nodes: List<AccessibilityNodeInfo>,
        requireEditable: Boolean,
        requireClickable: Boolean
    ): AccessibilityNodeInfo {
        if (requireEditable) {
            val editable = nodes.firstOrNull { it.isEditable }
            if (editable != null) return editable
        }
        if (requireClickable) {
            val clickable = nodes.firstOrNull { it.isClickable }
            if (clickable != null) return clickable
        }
        // Prefer interactive nodes
        return nodes.firstOrNull { it.isClickable || it.isEditable } ?: nodes.first()
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun createUiTarget(
        node: AccessibilityNodeInfo,
        confidence: Float,
        method: TargetResolutionMethod
    ): UiTarget {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return UiTarget(
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            role = node.className?.toString(),
            bounds = bounds,
            confidence = confidence,
            method = method
        )
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 50) return
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            collectNodes(child, list, depth + 1)
        }
    }

    private fun resolveSemanticAliases(query: String): List<String> {
        val q = query.lowercase(Locale.ROOT)
        return when {
            q.contains("search") || q.contains("find") -> listOf("search", "search_bar", "search_box", "search_button", "query", "omnibox")
            q.contains("direction") || q.contains("route") || q.contains("navigate") -> listOf("directions", "direction", "route", "start", "navigate")
            q.contains("menu") || q.contains("drawer") -> listOf("menu", "drawer", "navigation_drawer", "more options", "options")
            q.contains("home") -> listOf("home", "home_screen", "dashboard")
            q.contains("back") -> listOf("back", "navigate up", "arrow_back")
            q.contains("close") || q.contains("cancel") -> listOf("close", "cancel", "dismiss", "clear")
            q.contains("send") -> listOf("send", "submit", "confirm")
            q.contains("setting") -> listOf("settings", "preferences", "config")
            else -> listOf(q)
        }
    }
}
