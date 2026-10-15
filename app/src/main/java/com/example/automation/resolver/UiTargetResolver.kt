package com.example.automation.resolver

import android.graphics.Rect
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
        "search" to listOf(
            "search", "search here", "search or type url", "omnibox", "search_box", "search_button",
            "search and explore", "explore", "search_tab", "ask meta ai or search", "ask meta ai",
            "search_edit_text", "action_bar_search_edit_text", "search_src_text", "query"
        ),
        "find" to listOf(
            "search", "search here", "search or type url", "omnibox", "search_box", "search_button",
            "search and explore", "explore", "search_tab", "ask meta ai or search", "ask meta ai",
            "search_edit_text", "action_bar_search_edit_text"
        ),
        "explore" to listOf("search and explore", "explore", "search_tab", "search"),
        "reels" to listOf("reels", "clips", "clips_tab", "short videos"),
        "reel" to listOf("reels", "clips", "clips_tab", "short videos"),
        "clips" to listOf("clips", "reels", "clips_tab"),
        "dm" to listOf("message", "messages", "direct", "direct_tab", "inbox", "chats"),
        "dms" to listOf("message", "messages", "direct", "direct_tab", "inbox", "chats"),
        "direct" to listOf("direct", "direct_tab", "message", "messages", "inbox", "chats"),
        "inbox" to listOf("inbox", "messages", "direct", "direct_tab", "chats"),
        "messages" to listOf("message", "messages", "direct", "direct_tab", "inbox", "chats", "send message"),
        "menu" to listOf("menu", "drawer", "navigation_drawer", "more options", "options"),
        "drawer" to listOf("menu", "drawer", "navigation_drawer", "more options", "options"),
        "home" to listOf("home", "feed_tab", "home_screen", "dashboard"),
        "back" to listOf("back", "navigate up", "arrow_back"),
        "close" to listOf("close", "cancel", "dismiss", "clear", "not now"),
        "dismiss" to listOf("dismiss", "not now", "cancel", "close"),
        "cancel" to listOf("close", "cancel", "dismiss", "clear", "not now"),
        "settings" to listOf("settings", "preferences", "config"),
        "setting" to listOf("settings", "preferences", "config"),
        "account" to listOf("account", "profile", "user", "username", "row_search_user_container"),
        "contact" to listOf("contact", "chat", "friend", "conversation"),
        "chat" to listOf("chat", "contact", "conversation", "friend", "message", "direct_tab", "inbox"),
        "friend" to listOf("friend", "contact", "chat", "person"),
        "card" to listOf("card", "action", "shortcut", "item"),
        "message" to listOf("message", "send message", "chat", "direct_message", "direct_tab", "inbox"),
        "composer" to listOf("message…", "message", "row_thread_composer_edittext", "type a message", "write a message", "chat_input"),
        "message composer" to listOf("message…", "message", "row_thread_composer_edittext", "type a message", "write a message", "chat_input"),
        "search input" to listOf("search", "search here", "search or type url", "search_edit_text", "action_bar_search_edit_text", "search_src_text", "query", "ask meta ai or search")
    )

    private val NEGATIVE_SEMANTIC_FILTERS: Map<String, List<String>> = mapOf(
        "directions" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "direction" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "route" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "navigate" to listOf("street view", "preview", "photo", "photos", "thumbnail", "reviews", "review", "share", "save", "nearby", "options", "preference", "preferences", "settings"),
        "play" to listOf("playlist", "playlists", "play queue", "channel", "comments", "share", "download", "save"),
        "send" to listOf("share", "post", "cancel", "dismiss", "delete", "clear"),
        "search" to listOf("history", "clear search", "voice search", "recent", "clear", "cancel"),
        "account" to listOf("avatar", "story", "stories", "ring", "seen_state", "profile_picture", "contact_photo"),
        "user" to listOf("avatar", "story", "stories", "ring", "seen_state", "profile_picture", "contact_photo"),
        "profile" to listOf("avatar", "story", "stories", "ring", "seen_state", "profile_picture", "contact_photo"),
        "contact" to listOf("avatar", "story", "stories", "ring", "seen_state", "profile_picture", "contact_photo")
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
        val effectiveRoot = rootNode ?: JarvisAccessibilityService.getActiveRoot()

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
        // 2.5 DUAL-ANCHOR SWITCH & TOGGLE SNAPPING (Confidence 0.96)
        // If user intent is toggling/switching an item (e.g. "Wireless Debugging", "Dark Theme", "Bluetooth"),
        // find the label first and snap directly to the adjacent switch/checkbox on the same row!
        // =========================================================================
        val qLower = cleanQuery.lowercase(Locale.ROOT)
        val isToggleIntent = targetRole == "switch" || targetRole == "checkbox" ||
                qLower.startsWith("turn on ") || qLower.startsWith("turn off ") ||
                qLower.startsWith("enable ") || qLower.startsWith("disable ") ||
                qLower.startsWith("toggle ") || qLower.endsWith(" toggle") ||
                qLower.endsWith(" switch") || qLower.contains("switch") || qLower.contains("toggle")

        if (isToggleIntent) {
            val strippedToggleQuery = qLower
                .removePrefix("turn on ")
                .removePrefix("turn off ")
                .removePrefix("enable ")
                .removePrefix("disable ")
                .removePrefix("toggle ")
                .removeSuffix(" toggle")
                .removeSuffix(" switch")
                .trim()

            if (strippedToggleQuery.length >= 3) {
                val labelCandidate = allNodes.filter { node ->
                    val t = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                    val d = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                    t.contains(strippedToggleQuery) || d.contains(strippedToggleQuery) ||
                    strippedToggleQuery.contains(t.takeIf { it.length >= 3 } ?: "____")
                }.minByOrNull { node ->
                    val t = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    kotlin.math.abs(t.length - strippedToggleQuery.length)
                }

                if (labelCandidate != null) {
                    val labelBounds = Rect()
                    labelCandidate.getBoundsInScreen(labelBounds)

                    val adjacentSwitch = allNodes.filter { candidate ->
                        val isSwitch = candidate.isCheckable ||
                                (candidate.className?.toString()?.contains("Switch", ignoreCase = true) == true) ||
                                (candidate.className?.toString()?.contains("CheckBox", ignoreCase = true) == true) ||
                                (candidate.className?.toString()?.contains("ToggleButton", ignoreCase = true) == true)
                        if (!isSwitch) return@filter false

                        val candBounds = Rect()
                        candidate.getBoundsInScreen(candBounds)
                        val dy = kotlin.math.abs(candBounds.centerY() - labelBounds.centerY())
                        dy < 150 && candBounds.centerX() >= labelBounds.centerX()
                    }.minByOrNull { candidate ->
                        val candBounds = Rect()
                        candidate.getBoundsInScreen(candBounds)
                        kotlin.math.abs(candBounds.centerY() - labelBounds.centerY())
                    }

                    if (adjacentSwitch != null) {
                        val target = createUiTarget(adjacentSwitch, 0.96f, TargetResolutionMethod.SEMANTIC)
                        Log.i(TAG, "Grounded adjacent toggle switch for label '${labelCandidate.text ?: labelCandidate.contentDescription}': confidence=${target.confidence}")
                        return UiTargetResolutionResult(
                            success = true,
                            target = target,
                            matchedNode = adjacentSwitch,
                            confidence = target.confidence,
                            method = TargetResolutionMethod.SEMANTIC,
                            reason = "Dual-anchor adjacent switch for '${labelCandidate.text ?: labelCandidate.contentDescription}'",
                            candidateCount = 1
                        )
                    }
                }
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
        var nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

        // JARVIS migration: coalesce descendant text for clickable rows
        if (nodeText.isBlank() && nodeDesc.isBlank() && node.isClickable && node.childCount > 0) {
            val childTexts = collectNodeText(node)
            if (childTexts.isNotEmpty()) {
                nodeText = childTexts.joinToString(" • ")
            }
        }

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
        val isSearchEdit = isSearchInputNode(node)
        val isSearchQuery = q.contains("search") || q.contains("input") || q.contains("box") || q.contains("edit") || q.contains("bar")
        if (nodeText.equals(query, ignoreCase = false)) {
            baseScore = if (isSearchEdit && !isSearchQuery) 0.60f else 1.00f
            matchType = "EXACT_TEXT_CASE_SENSITIVE"
        } else if (nodeText.equals(query, ignoreCase = true)) {
            baseScore = if (isSearchEdit && !isSearchQuery) 0.58f else 0.98f
            matchType = "EXACT_TEXT"
        } else if (semanticAliases.any { it.equals(nodeText, ignoreCase = true) }) {
            baseScore = if (isSearchEdit && !isSearchQuery) 0.55f else 0.95f
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

        // 5. Navigation Tab Penalty for content actions (Disambiguate Content "Message" vs Bottom direct_tab)
        val isBottomNavNode = isLikelyNavigationNode(node) || nodeId.endsWith("_tab") ||
                              node.parent?.viewIdResourceName?.lowercase(Locale.ROOT)?.contains("tab_bar") == true ||
                              bounds.top >= 2100
        val isContentActionQuery = q.equals("message", ignoreCase = true) ||
                                   q.equals("messages", ignoreCase = true) ||
                                   q.equals("send message", ignoreCase = true) ||
                                   q.equals("chat", ignoreCase = true) ||
                                   q.equals("follow", ignoreCase = true) ||
                                   q.equals("share", ignoreCase = true)
        val isExplicitTabQuery = q.contains("tab") || q.contains("nav") || q.contains("direct tab") || q.contains("inbox")

        if (isBottomNavNode && isContentActionQuery && !isExplicitTabQuery) {
            finalScore -= 0.20f
        }

        // Boost for profile / content action buttons
        if ((nodeId.contains("button") || nodeId.contains("action") || nodeId.contains("profile") ||
             nodeText.equals("Message", ignoreCase = true)) && !isBottomNavNode) {
            finalScore += 0.03f
        }

        // Explicit button query penalty for input fields (JARVIS Pattern)
        val isExplicitButtonQuery = q.contains("send button") || q.contains("generate button") ||
                q.contains("submit button") || q.contains("enter button") ||
                q.contains("create button") || q.contains("go button") ||
                q == "send" || q == "generate" || q == "submit" ||
                q == "enter" || q == "done" || q == "run" ||
                q.contains("click send") || q.contains("press send") ||
                q.contains("press enter") || q.contains("press generate")
        if (isExplicitButtonQuery && (isSearchEdit || node.isEditable)) {
            finalScore -= 0.60f
        }

        // Web search results priority: avoid clicking the search / URL bar when targeting content links
        val isExplicitSearchQuery = q.contains("search") || q.contains("url") || q.contains("address") || q.contains("omnibox")
        if (!isExplicitSearchQuery && (nodeId.contains("url_bar") || nodeId.contains("omnibox") || (bounds.top < 200 && (isSearchEdit || node.isEditable)))) {
            finalScore -= 0.40f
        }

        // 6. Generic decorative / avatar / story node penalty:
        // When user intent is finding or opening an item/account, avoid selecting avatar/story/ring nodes
        val isExplicitAvatar = q.contains("avatar") || q.contains("story") || q.contains("stories") ||
                               q.contains("photo") || q.contains("picture") || q.contains("ring")
        if (isAvatarOrStoryNode(node) && !isExplicitAvatar) {
            finalScore -= 0.50f
        }

        // 7. Off-screen or invisible node penalty
        val isOffScreen = bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0 ||
                          bounds.left >= 1080 || bounds.top >= 2400 || bounds.right <= 0 || bounds.bottom <= 0 || !node.isVisibleToUser
        if (isOffScreen) {
            finalScore -= 0.60f
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
            for (node in nodes) {
                val ancestor = findClickableAncestor(node)
                if (ancestor != null) return ancestor
            }
        }
        // Prefer interactive nodes
        for (node in nodes) {
            if (node.isClickable || node.isEditable) return node
            val ancestor = findClickableAncestor(node)
            if (ancestor != null) return ancestor
        }
        return nodes.first()
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
        var text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (text.isNullOrBlank() && desc.isNullOrBlank() && node.isClickable && node.childCount > 0) {
            val childTexts = collectNodeText(node)
            if (childTexts.isNotEmpty()) {
                text = childTexts.joinToString(" • ")
            }
        }
        return UiTarget(
            resourceId = node.viewIdResourceName,
            text = text,
            contentDescription = desc,
            role = node.className?.toString(),
            bounds = bounds,
            confidence = confidence,
            method = method,
            className = node.className?.toString() ?: "",
            viewId = node.viewIdResourceName ?: "",
            isClickable = node.isClickable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable
        )
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 3) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val ct = child.text?.toString()?.trim()
                ?: child.contentDescription?.toString()?.trim()
                ?: ""
            if (ct.isNotBlank() && !list.contains(ct)) list.add(ct)
            if (child.childCount > 0) list.addAll(collectNodeText(child, depth + 1))
        }
        return list
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
        if (ordinalIndex == 0) return null

        val q = query.lowercase(Locale.ROOT)

        val isAccountTarget = q.contains("account") ||
                q.contains("user") ||
                q.contains("profile") ||
                q.contains("person") ||
                q.contains("contact") ||
                q.contains("chat") ||
                q.contains("friend")

        val isCardTarget = q.contains("card") ||
                q.contains("action") ||
                q.contains("shortcut")

        val isResultTarget = q.contains("result") ||
                q.contains("item") ||
                q.contains("link") ||
                q.contains("post") ||
                q.contains("video")

        val embeddedFilter = extractEmbeddedFilter(q)

        // ---------------------------------------------------------------------
        // 1. Account / contact / chat / friend targets
        // ---------------------------------------------------------------------
        if (isAccountTarget) {
            val accountCandidates = allNodes
                .asSequence()
                .filter { node ->
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    b.width() > 0 && b.height() > 0 && node.isVisibleToUser && node.isEnabled &&
                            (node.isClickable || findClickableAncestor(node) != null)
                }
                .filterNot { isScrollableContainer(it) }
                .filterNot { isSearchInputNode(it) }
                .filterNot { isActionBarOrToolbar(it) }
                .filterNot { isLikelyNavigationNode(it) }
                .filterNot { isAvatarOrStoryNode(it) }
                .map { node ->
                    val clickable = if (node.isClickable) node else (findClickableAncestor(node) ?: node)
                    clickable to getNodeBounds(clickable)
                }
                .filterNot { isSearchInputNode(it.first) }
                .filterNot { isActionBarOrToolbar(it.first) }
                .filterNot { isLikelyNavigationNode(it.first) }
                .filterNot { isAvatarOrStoryNode(it.first) }
                .filter { (_, b) -> b.width() > 0 && b.height() > 0 }
                .toList()

            val userRowCandidates = accountCandidates.filter { (node, _) ->
                val id = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                val desc = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
                (id.contains("row_search_user_container") || id.contains("user_container") || id.contains("row_search") ||
                 id.contains("user") || id.contains("account") || id.contains("profile") || id.contains("contact") ||
                 desc.contains("profile") || hasAccountChild(node)) && !isSearchInputNode(node)
            }
            val candidatesToUse = if (userRowCandidates.isNotEmpty()) userRowCandidates else accountCandidates

            val deduped = dedupeOrdinalCandidates(candidatesToUse)
            val filtered = if (embeddedFilter != null) {
                val matched = deduped.filter { nodeMatchesFilter(it, embeddedFilter) }
                if (matched.isNotEmpty()) matched else deduped
            } else deduped

            if (filtered.isNotEmpty()) {
                val target = selectOrdinalCandidate(filtered, ordinalIndex, requireClickable)
                return target
            }
        }

        // ---------------------------------------------------------------------
        // 2. Generic cards / results / items
        // ---------------------------------------------------------------------
        if (isCardTarget || isResultTarget || isAccountTarget) {
            val candidates = allNodes
                .asSequence()
                .filter { node ->
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    b.width() > 0 && b.height() > 0 && node.isVisibleToUser && node.isEnabled && node.isClickable
                }
                .filterNot { isScrollableContainer(it) }
                .filterNot { isLikelyNavigationNode(it) }
                .filterNot { isSearchInputNode(it) }
                .filterNot { isActionBarOrToolbar(it) }
                .filterNot { isAvatarOrStoryNode(it) }
                .filter { node ->
                    !isCardTarget || hasMeaningfulContent(node)
                }
                .filter { node ->
                    !isResultTarget || hasMeaningfulContent(node)
                }
                .map { it to getNodeBounds(it) }
                .toList()

            val deduped = dedupeOrdinalCandidates(candidates)
            val filtered = if (embeddedFilter != null) {
                val matched = deduped.filter { nodeMatchesFilter(it, embeddedFilter) }
                if (matched.isNotEmpty()) matched else deduped
            } else deduped

            if (filtered.isNotEmpty()) {
                return selectOrdinalCandidate(filtered, ordinalIndex, requireClickable)
            }
        }

        // ---------------------------------------------------------------------
        // 3. Final generic fallback: clickable, visible UI elements only.
        //    Passive containers/text nodes are never ordinal candidates.
        // ---------------------------------------------------------------------
        val genericCandidates = allNodes
            .asSequence()
            .filter { node ->
                val b = Rect()
                node.getBoundsInScreen(b)
                b.width() > 0 && b.height() > 0 && node.isVisibleToUser && node.isEnabled && node.isClickable
            }
            .filterNot { isLikelyNavigationNode(it) }
            .filterNot { isSearchInputNode(it) }
            .filterNot { isActionBarOrToolbar(it) }
            .map { it to getNodeBounds(it) }
            .toList()

        val dedupedGeneric = dedupeOrdinalCandidates(genericCandidates)
        return selectOrdinalCandidate(dedupedGeneric, ordinalIndex, requireClickable)
    }

    private fun getNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun dedupeOrdinalCandidates(
        candidates: List<Pair<AccessibilityNodeInfo, Rect>>
    ): List<AccessibilityNodeInfo> {
        if (candidates.isEmpty()) return emptyList()

        // Keep one node per identical visual bounds. When multiple clickable
        // nodes share the same rectangle, prefer the node with more semantic
        // information and then the larger visual container.
        val grouped = candidates.groupBy { (_, b) ->
            "${b.left}:${b.top}:${b.right}:${b.bottom}"
        }

        val unique = grouped.values.mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<Pair<AccessibilityNodeInfo, Rect>> { semanticWeight(it.first) }
                    .thenBy { it.second.width() * it.second.height() }
            )?.first
        }

        // Remove nested clickable descendants when an enclosing clickable
        // container represents the same visual item. This prevents icon/button
        // descendants from becoming separate ordinal items.
        return unique
            .filter { candidate ->
                val cb = getNodeBounds(candidate)
                unique.none { other ->
                    if (other === candidate) return@none false
                    val ob = getNodeBounds(other)
                    val otherArea = ob.width().toLong() * ob.height().toLong()
                    val candidateArea = cb.width().toLong() * cb.height().toLong()
                    if (otherArea <= candidateArea) return@none false
                    ob.contains(cb) && otherArea >= (candidateArea * 2L)
                }
            }
            .sortedWith(
                compareBy<AccessibilityNodeInfo> { getNodeBounds(it).top }
                    .thenBy { getNodeBounds(it).left }
            )
    }

    private fun semanticWeight(node: AccessibilityNodeInfo): Int {
        var weight = 0
        if (!node.text.isNullOrBlank()) weight += 2
        if (!node.contentDescription.isNullOrBlank()) weight += 2
        if (!node.viewIdResourceName.isNullOrBlank()) weight += 1
        if (hasMeaningfulContent(node)) weight += 2
        return weight
    }

    private fun hasMeaningfulContent(node: AccessibilityNodeInfo): Boolean {
        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
            return true
        }

        fun scan(current: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (current == null || depth > 8) return false
            for (i in 0 until current.childCount) {
                val child = try { current.getChild(i) } catch (_: Throwable) { null } ?: continue
                if (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank()) {
                    return true
                }
                if (scan(child, depth + 1)) return true
            }
            return false
        }

        return scan(node, 0)
    }

    private fun isLikelyNavigationNode(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$text $desc $id $cls"

        val navigationTerms = listOf(
            "open drawer",
            "navigation drawer",
            "more options",
            "options",
            "navigate up",
            "arrow_back",
            "toolbar",
            "top_bar",
            "status_bar",
            "navigationbar",
            "nav_bar",
            "tab_bar",
            "bottom_bar",
            "bottom_nav",
            "_tab"
        )

        return navigationTerms.any { combined.contains(it) } || id.endsWith("_tab")
    }

    private fun isSearchInputNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (cls.contains("edittext")) return true
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return id.contains("search_edit_text") ||
               id.contains("action_bar_search") ||
               id.contains("search_src_text") ||
               id.contains("search_bar") ||
               id.contains("search_input") ||
               id.contains("omnibox") ||
               desc.contains("search query") ||
               desc.contains("search edit text")
    }

    private fun isActionBarOrToolbar(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return id.contains("action_bar") || id.contains("toolbar") || cls.contains("toolbar") || cls.contains("actionbar")
    }

    private fun hasAccountChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val id = child.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            if (id.contains("username") || id.contains("user") || id.contains("account")) return true
        }
        return false
    }

    private fun isAvatarOrStoryNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$id $desc $cls"

        val avatarSignatures = listOf(
            "avatar",
            "story",
            "stories",
            "ring",
            "seen_state",
            "profile_picture",
            "contact_photo",
            "row_search_avatar",
            "pog_avatar"
        )
        return avatarSignatures.any { combined.contains(it) }
    }

    private fun isScrollableContainer(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return cls.contains("RecyclerView", ignoreCase = true) ||
               cls.contains("ListView", ignoreCase = true) ||
               cls.contains("ScrollView", ignoreCase = true) ||
               cls.contains("ViewPager", ignoreCase = true) ||
               cls.contains("AbsListView", ignoreCase = true)
    }

    private fun extractEmbeddedFilter(query: String): String? {
        val pattern = Regex("""(?i)match(?:ing)?(?:\s+for|:|\s+)\s*([a-zA-Z0-9._]+)""")
        val m = pattern.find(query)
        if (m != null) {
            val candidate = m.groupValues[1].trim().lowercase(Locale.ROOT)
            val ignoredWords = setOf("account", "result", "item", "video", "post", "link", "card", "user", "profile", "first", "second", "third", "one", "two")
            if (candidate.isNotEmpty() && candidate !in ignoredWords) {
                return candidate
            }
        }
        return null
    }

    private fun nodeMatchesFilter(node: AccessibilityNodeInfo, filter: String): Boolean {
        val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        if (text.contains(filter) || desc.contains(filter) || id.contains(filter)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            if (nodeMatchesFilter(child, filter)) return true
        }
        return false
    }

    private fun selectOrdinalCandidate(
        candidates: List<AccessibilityNodeInfo>,
        ordinalIndex: Int,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        if (candidates.isEmpty()) return null

        // NEVER fall back to the first candidate for an out-of-range ordinal.
        // This is critical for autonomous UI safety.
        if (ordinalIndex == -1) {
            return candidates.lastOrNull()
        }

        val zeroBased = ordinalIndex - 1
        if (zeroBased !in candidates.indices) {
            Log.w(
                TAG,
                "Ordinal target out of range: requested=$ordinalIndex available=${candidates.size}"
            )
            return null
        }

        val selected = candidates[zeroBased]

        return if (requireClickable && !selected.isClickable) {
            findClickableAncestor(selected)
        } else {
            if (selected.isClickable) selected else findClickableAncestor(selected)
        }
    }
}
