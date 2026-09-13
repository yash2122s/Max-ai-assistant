package com.example.automation.resolver

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.TargetResolutionMethod
import com.example.automation.models.UiTarget
import com.example.service.JarvisAccessibilityService
import java.util.Locale
import kotlin.math.max

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
 * following strictly ranked semantic scoring and negative filtering:
 * 1. Exact Requested Text (1.00)
 * 2. Exact Semantic Alias (0.95)
 * 3. Content Description Semantic Match (0.93 - 0.95)
 * 4. Resource ID Semantic Match (0.92 - 0.94)
 * 5. Partial / Substring Semantic Match (0.70 - 0.88)
 *
 * Interactive controls (clickable, checkable, editable) receive score boosts,
 * while passive text/image nodes and negative semantic matches (e.g. Street View / Preview / Playlists)
 * are strictly penalized or rejected.
 */
object UiTargetResolver {
    private const val TAG = "UiTargetResolver"
    const val SAFE_CONFIDENCE_THRESHOLD = 0.85f

    private val CONTEXTUAL_SEMANTIC_ALIASES: Map<String, List<String>> = mapOf(
        "directions" to listOf("directions", "get directions", "route", "navigate", "direction"),
        "direction" to listOf("directions", "get directions", "route", "navigate", "direction"),
        "route" to listOf("directions", "get directions", "route", "navigate", "direction"),
        "navigate" to listOf("directions", "get directions", "route", "navigate", "direction"),
        "play" to listOf("play", "play video", "watch", "start playback"),
        "video" to listOf("video", "watch video", "play video", "videos"),
        "send" to listOf("send", "submit"),
        "search" to listOf("search", "search here", "search or type url", "omnibox", "search_box", "search_button"),
        "find" to listOf("search", "search here", "search or type url", "omnibox", "search_box", "search_button"),
        "menu" to listOf("menu", "drawer", "navigation_drawer", "more options", "options"),
        "drawer" to listOf("menu", "drawer", "navigation_drawer", "more options", "options"),
        "home" to listOf("home", "home_screen", "dashboard"),
        "back" to listOf("back", "navigate up", "arrow_back"),
        "close" to listOf("close", "cancel", "dismiss", "clear"),
        "cancel" to listOf("close", "cancel", "dismiss", "clear"),
        "settings" to listOf("settings", "preferences", "config"),
        "setting" to listOf("settings", "preferences", "config"),
        "account" to listOf("account", "profile", "user", "username"),
        "message" to listOf("message", "send message", "chat", "direct_message")
    )

    private val NEGATIVE_SEMANTIC_FILTERS: Map<String, List<String>> = mapOf(
        "directions" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "direction" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "route" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "navigate" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "play" to listOf("playlist", "playlists", "play queue", "channel", "comments", "share", "download", "save"),
        "send" to listOf("share", "post", "cancel", "dismiss", "delete", "clear"),
        "search" to listOf("history", "clear search", "voice search", "recent", "clear", "cancel")
    )

    private data class ScoredCandidate(
        val node: AccessibilityNodeInfo,
        val score: Float,
        val matchType: String,
        val text: String?,
        val desc: String?,
        val resId: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean
    )

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
        // 1. EXPLICIT RESOURCE ID MATCHING (Confidence 0.99 - 1.0)
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
                val bestNode = filterBestNode(resMatches, requireEditable, requireClickable)
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
        // 2. EXPLICIT CONTENT DESCRIPTION MATCHING (Confidence 0.95)
        // =========================================================================
        if (!explicitContentDescription.isNullOrBlank()) {
            val descMatches = allNodes.filter { node ->
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                desc.equals(explicitContentDescription.trim(), ignoreCase = true)
            }

            if (descMatches.isNotEmpty()) {
                val bestNode = filterBestNode(descMatches, requireEditable, requireClickable)
                val target = createUiTarget(bestNode, 0.95f, TargetResolutionMethod.CONTENT_DESCRIPTION)
                Log.d(TAG, "Resolved by EXPLICIT_CONTENT_DESC: desc='${target.contentDescription}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = bestNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.CONTENT_DESCRIPTION,
                    reason = "Exact explicit content description match",
                    candidateCount = descMatches.size
                )
            }
        }

        // =========================================================================
        // 3. ORDINAL & RELATIVE TARGET MATCHING (Confidence 0.91)
        // e.g. "first result", "second video", "top link", "first account"
        // =========================================================================
        val ordinalIndex = parseOrdinal(cleanQuery)
        if (ordinalIndex != null) {
            val ordinalNode = resolveOrdinalTarget(ordinalIndex, cleanQuery, allNodes, requireClickable)
            if (ordinalNode != null) {
                val target = createUiTarget(ordinalNode, 0.91f, TargetResolutionMethod.SEMANTIC)
                Log.d(TAG, "Resolved by ORDINAL: index=$ordinalIndex, text='${target.text}', confidence=${target.confidence}")
                return UiTargetResolutionResult(
                    success = true,
                    target = target,
                    matchedNode = ordinalNode,
                    confidence = target.confidence,
                    method = TargetResolutionMethod.SEMANTIC,
                    reason = "Ordinal target match (index #$ordinalIndex)",
                    candidateCount = 1
                )
            }
        }

        // =========================================================================
        // 4. STRICTLY RANKED SEMANTIC CANDIDATE SCORING & RESOLUTION
        // =========================================================================
        val qLower = cleanQuery.lowercase(Locale.ROOT)
        val semanticAliases = resolveSemanticAliases(cleanQuery)
        val negativeTerms = resolveNegativeFilters(cleanQuery)

        val scoredCandidates = mutableListOf<ScoredCandidate>()
        for (node in allNodes) {
            val candidate = scoreCandidate(node, cleanQuery, semanticAliases, negativeTerms, requireEditable, requireClickable)
            if (candidate.score > 0f) {
                scoredCandidates.add(candidate)
            }
        }

        // Sort strictly by score descending; if tied, prefer clickable/editable then smaller bounds (more specific)
        scoredCandidates.sortWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenByDescending { it.isClickable || it.isEditable }
                .thenBy { it.bounds.width() * it.bounds.height() }
        )

        // Log candidate list and scores for full diagnostics
        Log.d(TAG, "Resolving target query='$cleanQuery' across ${allNodes.size} UI nodes")
        if (scoredCandidates.isNotEmpty()) {
            val topCount = scoredCandidates.take(5).size
            Log.d(TAG, "Ranked candidates for '$cleanQuery' (Top $topCount):")
            scoredCandidates.take(5).forEachIndexed { index, c ->
                val boundsStr = "[${c.bounds.left},${c.bounds.top}][${c.bounds.right},${c.bounds.bottom}]"
                Log.d(TAG, "  [$index] score=%.2f | type=%s | resId=%s | text='%s' | desc='%s' | bounds=%s | clickable=%b".format(
                    c.score,
                    c.matchType,
                    c.resId,
                    c.text ?: "",
                    c.desc ?: "",
                    boundsStr,
                    c.isClickable
                ))
            }
        }

        val topCandidate = scoredCandidates.firstOrNull()
        if (topCandidate != null && topCandidate.score >= SAFE_CONFIDENCE_THRESHOLD) {
            val effectiveNode = if (requireClickable && !topCandidate.node.isClickable) {
                findClickableAncestor(topCandidate.node) ?: topCandidate.node
            } else {
                if (topCandidate.node.isClickable) topCandidate.node else (findClickableAncestor(topCandidate.node) ?: topCandidate.node)
            }

            val resolutionMethod = when {
                topCandidate.matchType.startsWith("EXACT_TEXT") || topCandidate.matchType.startsWith("CI_TEXT") -> TargetResolutionMethod.TEXT
                topCandidate.matchType.startsWith("EXACT_DESC") || topCandidate.matchType.startsWith("EXACT_ALIAS_DESC") -> TargetResolutionMethod.CONTENT_DESCRIPTION
                topCandidate.matchType.startsWith("EXACT_RES_ID") || topCandidate.matchType.startsWith("ALIAS_RES_ID") -> TargetResolutionMethod.RESOURCE_ID
                topCandidate.matchType.startsWith("EDITABLE") -> TargetResolutionMethod.EDITABLE
                else -> TargetResolutionMethod.SEMANTIC
            }

            val target = createUiTarget(effectiveNode, topCandidate.score, resolutionMethod)
            val boundsStr = target.bounds?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "[]"
            Log.d(TAG, "Selected target: resId=${target.resourceId}, text='${target.text}', desc='${target.contentDescription}', bounds=$boundsStr, confidence=${target.confidence}, method=${target.method}")

            return UiTargetResolutionResult(
                success = true,
                target = target,
                matchedNode = effectiveNode,
                confidence = target.confidence,
                method = target.method,
                reason = "Ranked semantic match: ${topCandidate.matchType}",
                candidateCount = scoredCandidates.size
            )
        }

        // =========================================================================
        // 5. EDITABLE FALLBACK DETECTION (Confidence 0.86 - 0.88)
        // =========================================================================
        if (requireEditable || qLower.contains("search") || qLower.contains("type") || qLower.contains("input")) {
            val editableNodes = allNodes.filter { it.isEditable || it.className?.toString()?.contains("EditText", ignoreCase = true) == true }
            if (editableNodes.isNotEmpty()) {
                val bestNode = editableNodes.first()
                val target = createUiTarget(bestNode, 0.88f, TargetResolutionMethod.EDITABLE)
                Log.d(TAG, "Resolved by EDITABLE fallback: confidence=${target.confidence}")
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
        // 6. TARGET NOT RESOLVED OR BELOW SAFE CONFIDENCE THRESHOLD
        // =========================================================================
        val bestScore = topCandidate?.score ?: 0f
        Log.w(TAG, "Target '$cleanQuery' could not be resolved with confidence >= $SAFE_CONFIDENCE_THRESHOLD (Best score: $bestScore)")
        return UiTargetResolutionResult(
            success = false,
            target = null,
            confidence = bestScore,
            reason = "Target '$cleanQuery' below safe confidence threshold (best: $bestScore)"
        )
    }

    private fun scoreCandidate(
        node: AccessibilityNodeInfo,
        query: String,
        semanticAliases: List<String>,
        negativeTerms: List<String>,
        requireEditable: Boolean,
        requireClickable: Boolean
    ): ScoredCandidate {
        val nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
        val rawId = node.viewIdResourceName ?: ""
        val nodeId = rawId.substringAfterLast('/').lowercase(Locale.ROOT)
        val q = query.lowercase(Locale.ROOT).trim()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 1. Negative semantic filtering check
        val textLower = nodeText.lowercase(Locale.ROOT)
        val descLower = nodeDesc.lowercase(Locale.ROOT)
        val isNegativeMatched = negativeTerms.isNotEmpty() && negativeTerms.any { neg ->
            // Only trigger negative penalty if the query itself did NOT explicitly request the negative term
            !q.contains(neg) && (textLower.contains(neg) || descLower.contains(neg) || nodeId.contains(neg))
        }

        var baseScore = 0f
        var matchType = "NONE"

        val isSubordinateId = nodeId.contains("preference") || nodeId.contains("options") || nodeId.contains("setting") ||
                              nodeId.contains("clear") || nodeId.contains("close") || nodeId.contains("header") ||
                              nodeId.contains("container") || nodeId.contains("layout") || nodeId.contains("background")

        // 2. Exact match evaluations
        if (nodeText.equals(query, ignoreCase = false)) {
            baseScore = 1.00f
            matchType = "EXACT_TEXT_CASE_SENSITIVE"
        } else if (nodeText.equals(query, ignoreCase = true)) {
            baseScore = 0.98f
            matchType = "EXACT_TEXT"
        } else if (semanticAliases.any { it.equals(nodeText, ignoreCase = true) }) {
            baseScore = 0.95f
            matchType = "EXACT_ALIAS_TEXT"
        } else if (nodeDesc.equals(query, ignoreCase = true)) {
            baseScore = 0.95f
            matchType = "EXACT_DESC"
        } else if (semanticAliases.any { it.equals(nodeDesc, ignoreCase = true) }) {
            baseScore = 0.93f
            matchType = "EXACT_ALIAS_DESC"
        } else if (nodeId.equals(q, ignoreCase = true)) {
            baseScore = 0.94f
            matchType = "EXACT_RES_ID"
        } else if (!isSubordinateId && semanticAliases.any { nodeId.equals(it, ignoreCase = true) || nodeId.startsWith(it + "_") || nodeId.endsWith("_" + it) }) {
            baseScore = 0.92f
            matchType = "ALIAS_RES_ID"
        } else if (!isSubordinateId && semanticAliases.any { nodeId.contains(it) }) {
            baseScore = 0.90f
            matchType = "PARTIAL_RES_ID"
        } else {
            // Partial substring matches
            if (nodeText.isNotBlank()) {
                if (textLower.contains(q)) {
                    val isConcise = nodeText.length <= q.length + 10
                    baseScore = if (isConcise) 0.88f else 0.76f
                    matchType = if (isConcise) "CONCISE_SUBSTRING_TEXT" else "LONG_SUBSTRING_TEXT"
                } else {
                    val matchedAlias = semanticAliases.firstOrNull { textLower.contains(it) }
                    if (matchedAlias != null) {
                        val isConcise = nodeText.length <= matchedAlias.length + 10
                        baseScore = if (isConcise) 0.86f else 0.72f
                        matchType = if (isConcise) "CONCISE_ALIAS_SUBSTRING_TEXT" else "LONG_ALIAS_SUBSTRING_TEXT"
                    }
                }
            }

            if (nodeDesc.isNotBlank() && baseScore < 0.87f) {
                if (descLower.contains(q)) {
                    val isConcise = nodeDesc.length <= q.length + 10
                    val descScore = if (isConcise) 0.87f else 0.75f
                    if (descScore > baseScore) {
                        baseScore = descScore
                        matchType = if (isConcise) "CONCISE_SUBSTRING_DESC" else "LONG_SUBSTRING_DESC"
                    }
                } else {
                    val matchedAlias = semanticAliases.firstOrNull { descLower.contains(it) }
                    if (matchedAlias != null) {
                        val isConcise = nodeDesc.length <= matchedAlias.length + 10
                        val descScore = if (isConcise) 0.85f else 0.70f
                        if (descScore > baseScore) {
                            baseScore = descScore
                            matchType = if (isConcise) "CONCISE_ALIAS_SUBSTRING_DESC" else "LONG_ALIAS_SUBSTRING_DESC"
                        }
                    }
                }
            }
        }

        if (baseScore <= 0f) {
            return ScoredCandidate(
                node = node,
                score = 0f,
                matchType = "NONE",
                text = node.text?.toString(),
                desc = node.contentDescription?.toString(),
                resId = node.viewIdResourceName,
                bounds = bounds,
                isClickable = node.isClickable,
                isEditable = node.isEditable
            )
        }

        var finalScore = baseScore

        // 3. Interactivity and role adjustments
        if (node.isClickable || node.isCheckable || node.isEditable) {
            finalScore += 0.02f
        } else if (findClickableAncestor(node) != null) {
            finalScore += 0.01f
        } else {
            finalScore -= 0.05f
        }

        val className = node.className?.toString() ?: ""
        if (className.contains("Button", ignoreCase = true) || className.contains("EditText", ignoreCase = true)) {
            finalScore += 0.01f
        }

        if (requireEditable) {
            if (node.isEditable || className.contains("EditText", ignoreCase = true)) {
                finalScore += 0.05f
            } else {
                finalScore -= 0.30f
            }
        }

        if (requireClickable) {
            if (node.isClickable || findClickableAncestor(node) != null) {
                finalScore += 0.02f
            } else {
                finalScore -= 0.20f
            }
        }

        // 4. Negative filter penalty
        if (isNegativeMatched) {
            finalScore -= 0.35f
        }

        finalScore = finalScore.coerceIn(0.0f, 1.0f)

        return ScoredCandidate(
            node = node,
            score = finalScore,
            matchType = matchType,
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            resId = node.viewIdResourceName,
            bounds = bounds,
            isClickable = node.isClickable,
            isEditable = node.isEditable
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
        val q = query.lowercase(Locale.ROOT).trim()
        for ((key, aliases) in CONTEXTUAL_SEMANTIC_ALIASES) {
            if (q.contains(key)) {
                return aliases
            }
        }
        return listOf(q)
    }

    private fun resolveNegativeFilters(query: String): List<String> {
        val q = query.lowercase(Locale.ROOT).trim()
        for ((key, filters) in NEGATIVE_SEMANTIC_FILTERS) {
            if (q.contains(key)) {
                return filters
            }
        }
        return emptyList()
    }

    /**
     * Parses ordinal index (1-based) from natural language query, e.g. "first result" -> 1, "second" -> 2, "last" -> -1
     */
    fun parseOrdinal(query: String): Int? {
        val q = query.lowercase(Locale.ROOT)
        return when {
            q.contains("first") || q.contains("1st") || q.contains("top result") || q.contains("top link") || q.contains("top item") || q.contains("initial") -> 1
            q.contains("second") || q.contains("2nd") -> 2
            q.contains("third") || q.contains("3rd") -> 3
            q.contains("fourth") || q.contains("4th") -> 4
            q.contains("fifth") || q.contains("5th") -> 5
            q.contains("last") -> -1
            else -> null
        }
    }

    private fun resolveOrdinalTarget(
        ordinalIndex: Int,
        query: String,
        allNodes: List<AccessibilityNodeInfo>,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        val q = query.lowercase(Locale.ROOT)
        val isAccountTarget = q.contains("account") || q.contains("user") || q.contains("profile") || q.contains("person")
        val isResultTarget = q.contains("result") || q.contains("item") || q.contains("link") || q.contains("post") || q.contains("video")

        // 1. If targeting accounts/users explicitly (e.g. in Instagram / social search), search for profile/row containers or username elements
        if (isAccountTarget) {
            val accountNodes = allNodes.filter { node ->
                val b = Rect()
                node.getBoundsInScreen(b)
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val isAccountRow = id.contains("row_search_user") || id.contains("user_row") || id.contains("search_row") ||
                                   id.contains("row_user") || id.contains("account") || id.contains("profile") ||
                                   id.contains("row_inbox") || id.contains("direct_user")
                val isClickableText = (node.isClickable || node.parent?.isClickable == true) && (text.isNotBlank() || desc.isNotBlank())
                b.top > 120 && (isAccountRow || isClickableText)
            }.sortedBy {
                val b = Rect()
                it.getBoundsInScreen(b)
                b.top
            }

            if (accountNodes.isNotEmpty()) {
                val target = if (ordinalIndex == -1) accountNodes.last() else accountNodes.getOrNull(ordinalIndex - 1) ?: accountNodes.first()
                return if (target.isClickable) target else (findClickableAncestor(target) ?: target)
            }
        }

        // 2. Look for explicit collection/list containers, excluding top navigation bar / filter tabs
        val containers = allNodes.filter { node ->
            val cls = node.className?.toString() ?: ""
            val isNavContainer = cls.contains("NavigationView", ignoreCase = true) || cls.contains("TabLayout", ignoreCase = true)
            (node.isScrollable || cls.contains("RecyclerView", ignoreCase = true) ||
            cls.contains("ListView", ignoreCase = true) || cls.contains("ScrollView", ignoreCase = true) ||
            cls.contains("ViewPager", ignoreCase = true)) && !isNavContainer
        }

        val containerCandidates = mutableListOf<AccessibilityNodeInfo>()
        for (container in containers) {
            for (i in 0 until container.childCount) {
                val child = try { container.getChild(i) } catch (_: Throwable) { null } ?: continue
                containerCandidates.add(child)
            }
            if (containerCandidates.isNotEmpty()) break
        }

        // 3. If container candidates exist, choose candidate; otherwise filter candidate content nodes on screen
        val candidateList = if (containerCandidates.isNotEmpty()) {
            containerCandidates
        } else {
            allNodes.filter { node ->
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val cls = node.className?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val isNavTab = cls.contains("NavigationView", ignoreCase = true) || cls.contains("TabLayout", ignoreCase = true) || id.contains("nav_bar") || id.contains("tab_bar")
                val hasContent = text.isNotBlank() || desc.isNotBlank()
                val isInteractive = node.isClickable || node.isCheckable || hasContent
                isInteractive && !isNavTab
            }.sortedBy {
                val b = Rect()
                it.getBoundsInScreen(b)
                b.top
            }
        }

        if (candidateList.isEmpty()) return null

        val targetNode: AccessibilityNodeInfo = (if (ordinalIndex == -1) {
            candidateList.lastOrNull()
        } else if (ordinalIndex in 1..candidateList.size) {
            candidateList[ordinalIndex - 1]
        } else {
            candidateList.firstOrNull()
        }) ?: return null

        return if (requireClickable && !targetNode.isClickable) {
            findClickableAncestor(targetNode) ?: targetNode
        } else {
            if (targetNode.isClickable) targetNode else (findClickableAncestor(targetNode) ?: targetNode)
        }
    }
}
