package com.example.automation.resolver

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Structured WhatsApp contact target model.
 */
data class WhatsappContactTarget(
    val displayName: String,
    val normalizedName: String,
    val phone: String? = null,
    val rowBounds: Rect = Rect(),
    val resourceId: String? = null,
    val source: String = "ACCESSIBILITY",
    val confidence: Float = 1.0f
)

/**
 * Result of resolving a WhatsApp contact in search results.
 */
sealed class WhatsAppContactMatch {
    data class Found(
        val nodeToClick: AccessibilityNodeInfo,
        val matchedName: String,
        val matchType: String,
        val confidence: Float,
        val target: WhatsappContactTarget? = null
    ) : WhatsAppContactMatch()

    data class Ambiguous(
        val query: String,
        val candidateNames: List<String>
    ) : WhatsAppContactMatch()

    object NotFound : WhatsAppContactMatch()
}

/**
 * High-precision WhatsApp Contact Resolver for MAX.
 *
 * Responsibilities:
 * 1. Contact Name Normalization & Variant Generation.
 * 2. Search Result Contact Identification (isolating contact name nodes).
 * 3. Message Snippet & Header Exclusion (ignoring msg_snippet, conversation_row_last_message, etc.).
 * 4. Group & Collision Filtering (ignoring group chats when matching individuals).
 * 5. Direct-Click Optimization (detecting visible contacts on main chats screen).
 * 6. Clickable Row Ancestor Traversal (finding the safe, clickable row).
 * 7. Ambiguity Detection (detecting multiple conflicting candidates).
 * 8. Top Toolbar Header Verification (verifying chat identity strictly within header).
 */
object WhatsAppContactResolver {
    private const val TAG = "WhatsAppContactResolver"

    // View IDs commonly used for Contact Names in WhatsApp & WhatsApp Business
    val CONTACT_NAME_IDS = listOf(
        "conversations_row_contact_name",
        "contact_name",
        "conversation_contact_name",
        "action_bar_title",
        "title",
        "name"
    )

    // View IDs that represent message content, snippets, timestamps or status — NEVER match as contact names
    val EXCLUDED_MESSAGE_IDS = listOf(
        "conversations_row_message",
        "msg_snippet",
        "last_message",
        "message_text",
        "single_msg_tv",
        "conversations_row_date",
        "msg_status",
        "status",
        "search_src_text",
        "search_input",
        "header_title",
        "section_header",
        "tab",
        "filter"
    )

    // Row container IDs
    val ROW_CONTAINER_IDS = listOf(
        "contact_row_container",
        "conversations_row",
        "search_item_container",
        "row_container"
    )

    // Top Toolbar / Header view IDs
    val TOOLBAR_HEADER_IDS = listOf(
        "conversation_contact_name",
        "conversation_contact_status",
        "action_bar_title",
        "conversation_title",
        "toolbar",
        "header"
    )

    // Words indicating group chats to reject when searching for an individual person
    private val GROUP_KEYWORDS = listOf(
        "group", "family group", "batch", "club", "gang"
    )

    /**
     * Normalizes text for comparison: lowercases, trims, strips non-alphanumeric chars, and collapses spaces.
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Normalizes phone number to raw digits.
     */
    fun normalizeDigits(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        return phone.replace(Regex("[^0-9]"), "")
    }

    /**
     * Generates all matching variants for a target contact name or phone number.
     */
    fun generateTargetVariants(target: String, phone: String? = null): List<String> {
        val variants = mutableListOf<String>()
        val normTarget = normalize(target)
        if (normTarget.isNotBlank()) {
            variants.add(normTarget)
            variants.add(target.trim())
        }

        val allInputs = listOfNotNull(target, phone)
        for (input in allInputs) {
            val digits = normalizeDigits(input)
            if (digits.length >= 7) {
                variants.add(digits)
                variants.add(digits.takeLast(10))
                variants.add("+$digits")
                variants.add("+91${digits.takeLast(10)}")
                if (digits.takeLast(10).length == 10) {
                    val last10 = digits.takeLast(10)
                    variants.add("+91 ${last10.substring(0, 5)} ${last10.substring(5)}")
                    variants.add("${last10.substring(0, 5)} ${last10.substring(5)}")
                }
            }
        }
        return variants.distinct()
    }

    /**
     * Checks if a node is an excluded message snippet, search input, or section header.
     */
    fun isExcludedNode(node: AccessibilityNodeInfo): Boolean {
        val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        for (excluded in EXCLUDED_MESSAGE_IDS) {
            if (resId.endsWith(":id/$excluded") || resId.endsWith("/$excluded") || resId == excluded) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if a name indicates a group chat collision when searching for an individual person.
     */
    fun isGroupCollision(candidateText: String, targetContact: String): Boolean {
        val normCandidate = normalize(candidateText)
        val normTarget = normalize(targetContact)

        if (normCandidate == normTarget) return false

        // If target doesn't contain group keywords, but candidate does
        val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }
        val candidateTokens = normCandidate.split(" ").filter { it.isNotBlank() }

        val candidateHasGroupWord = candidateTokens.any { it in GROUP_KEYWORDS }
        val targetHasGroupWord = targetTokens.any { it in GROUP_KEYWORDS }

        return candidateHasGroupWord && !targetHasGroupWord
    }

    /**
     * Resolves the target contact in search results or visible chat lists.
     * Returns [WhatsAppContactMatch.Found] with the clickable row node, [WhatsAppContactMatch.Ambiguous], or [WhatsAppContactMatch.NotFound].
     */
    fun resolveSearchRow(
        root: AccessibilityNodeInfo,
        targetContact: String,
        pkgName: String = "com.whatsapp",
        targetPhone: String? = null
    ): WhatsAppContactMatch {
        val normTarget = normalize(targetContact)
        val targetDigits = normalizeDigits(targetPhone.takeIf { !it.isNullOrBlank() } ?: targetContact)
        val variants = generateTargetVariants(targetContact, targetPhone)

        Log.d(TAG, "Resolving WhatsApp contact for target='$targetContact', normTarget='$normTarget', variants=$variants")

        val candidateNodes = mutableListOf<ResolvedCandidate>()

        // Helper to evaluate a node
        fun evaluateNode(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (isExcludedNode(node)) return

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val nodeText = if (text.isNotBlank()) text else desc
            val normNodeText = normalize(nodeText)
            val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)

            if (normNodeText.isBlank()) return

            // Reject group collisions when searching for a person
            if (isGroupCollision(nodeText, targetContact)) {
                Log.d(TAG, "Rejecting group collision candidate: '$nodeText' for target '$targetContact'")
                return
            }

            val isExplicitContactId = CONTACT_NAME_IDS.any {
                resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it
            }

            // Score candidate
            var score = 0f
            var matchType = "NONE"

            if (normNodeText == normTarget) {
                score = if (isExplicitContactId) 1.0f else 0.95f
                matchType = "EXACT_NAME"
            } else if (targetDigits.length >= 7 && normalizeDigits(normNodeText).takeLast(10) == targetDigits.takeLast(10)) {
                score = if (isExplicitContactId) 1.0f else 0.95f
                matchType = "EXACT_PHONE"
            } else if (isExplicitContactId) {
                // Check token matching for multi-word or single-word matches
                val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }
                val nodeTokens = normNodeText.split(" ").filter { it.isNotBlank() }

                if (targetTokens.isNotEmpty() && nodeTokens.containsAll(targetTokens)) {
                    score = 0.88f
                    matchType = "TOKEN_CONTAINED"
                } else if (nodeTokens.isNotEmpty() && targetTokens.containsAll(nodeTokens)) {
                    score = 0.85f
                    matchType = "TOKEN_MATCH"
                } else if (normNodeText.startsWith(normTarget) || normTarget.startsWith(normNodeText)) {
                    score = 0.80f
                    matchType = "PREFIX_MATCH"
                }
            }

            if (score >= 0.80f) {
                val clickableNode = findClickableAncestorOrSelf(node, pkgName)
                if (clickableNode != null) {
                    val bounds = Rect()
                    clickableNode.getBoundsInScreen(bounds)
                    val structuredTarget = WhatsappContactTarget(
                        displayName = nodeText,
                        normalizedName = normNodeText,
                        phone = targetPhone,
                        rowBounds = bounds,
                        resourceId = clickableNode.viewIdResourceName ?: node.viewIdResourceName,
                        confidence = score
                    )
                    candidateNodes.add(
                        ResolvedCandidate(
                            node = node,
                            clickableNode = clickableNode,
                            matchedText = nodeText,
                            score = score,
                            matchType = matchType,
                            structuredTarget = structuredTarget
                        )
                    )
                }
            }
        }

        // 1. First search via explicit contact name IDs (if supported by active Accessibility window)
        for (idName in CONTACT_NAME_IDS) {
            val nodesWithId = runCatching { root.findAccessibilityNodeInfosByViewId("$pkgName:id/$idName") }.getOrNull() ?: emptyList()
            for (node in nodesWithId) {
                evaluateNode(node)
            }
        }

        // 2. If no candidate found from IDs, search by text variants
        if (candidateNodes.isEmpty()) {
            for (variant in variants) {
                val textNodes = runCatching { root.findAccessibilityNodeInfosByText(variant) }.getOrNull() ?: emptyList()
                for (node in textNodes) {
                    evaluateNode(node)
                }
            }
        }

        // 3. Fallback: recursive tree scan
        if (candidateNodes.isEmpty()) {
            fun scanTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null || depth > 15) return
                evaluateNode(node)
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    scanTree(child, depth + 1)
                }
            }
            scanTree(root)
        }

        if (candidateNodes.isEmpty()) {
            Log.w(TAG, "No valid contact candidates found for '$targetContact'.")
            return WhatsAppContactMatch.NotFound
        }

        // Deduplicate candidates that point to the same clickable node
        val uniqueCandidates = candidateNodes
            .sortedByDescending { it.score }
            .distinctBy { it.clickableNode }

        Log.d(TAG, "Found ${uniqueCandidates.size} unique candidates for '$targetContact': ${uniqueCandidates.map { "${it.matchedText} (${it.matchType}, score=${it.score})" }}")

        // Exact match check
        val exactMatches = uniqueCandidates.filter { it.score >= 0.95f }
        if (exactMatches.size == 1) {
            val best = exactMatches.first()
            return WhatsAppContactMatch.Found(
                nodeToClick = best.clickableNode,
                matchedName = best.matchedText,
                matchType = best.matchType,
                confidence = best.score,
                target = best.structuredTarget
            )
        } else if (exactMatches.size > 1) {
            // Check if they are distinct names or duplicates of the exact same name
            val distinctNames = exactMatches.map { it.matchedText }.distinct()
            if (distinctNames.size == 1) {
                val best = exactMatches.first()
                return WhatsAppContactMatch.Found(
                    nodeToClick = best.clickableNode,
                    matchedName = best.matchedText,
                    matchType = best.matchType,
                    confidence = best.score,
                    target = best.structuredTarget
                )
            }
            return WhatsAppContactMatch.Ambiguous(
                query = targetContact,
                candidateNames = distinctNames
            )
        }

        // If best candidate is significantly higher than second
        val best = uniqueCandidates.first()
        if (uniqueCandidates.size == 1 || (uniqueCandidates.size > 1 && best.score - uniqueCandidates[1].score >= 0.08f)) {
            return WhatsAppContactMatch.Found(
                nodeToClick = best.clickableNode,
                matchedName = best.matchedText,
                matchType = best.matchType,
                confidence = best.score,
                target = best.structuredTarget
            )
        }

        // Ambiguous candidates
        return WhatsAppContactMatch.Ambiguous(
            query = targetContact,
            candidateNames = uniqueCandidates.take(4).map { it.matchedText }
        )
    }

    /**
     * Direct-Click Optimization: Resolves contact row if visible in the current chat list.
     */
    fun resolveVisibleChatRow(
        root: AccessibilityNodeInfo,
        targetContact: String,
        pkgName: String = "com.whatsapp",
        targetPhone: String? = null
    ): WhatsAppContactMatch {
        return resolveSearchRow(root, targetContact, pkgName, targetPhone)
    }

    /**
     * Climbs up the hierarchy to find the clickable row container or ancestor.
     */
    fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        if (node.isClickable) return node

        var current: AccessibilityNodeInfo? = node
        var count = 0
        while (current != null && count < 6) {
            val resId = (current.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isRowContainer = ROW_CONTAINER_IDS.any {
                resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it
            }

            if (current.isClickable || isRowContainer) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
            count++
        }
        return if (node.isClickable) node else runCatching { node.parent }.getOrNull() ?: node
    }

    /**
     * Strictly verifies that the currently opened chat's top toolbar header matches the target contact.
     * Prevents typing or sending messages to wrong chats.
     */
    fun isCorrectChatOpen(
        root: AccessibilityNodeInfo,
        targetContact: String,
        pkgName: String = "com.whatsapp",
        targetPhone: String? = null
    ): Boolean {
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collectNodes(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 15) return
            allNodes.add(node)
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                collectNodes(child, depth + 1)
            }
        }
        collectNodes(root)

        // 1. Ensure message input field exists (confirming we are on a chat screen)
        val hasEntryField = allNodes.any { node ->
            val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val text = node.text?.toString() ?: ""
            resId.endsWith(":id/entry") || resId.endsWith(":id/text_entry") ||
                    text.contains("Type a message", ignoreCase = true) ||
                    text.equals("Message", ignoreCase = true)
        }

        if (!hasEntryField) {
            Log.d(TAG, "isCorrectChatOpen: Message entry field not found.")
            return false
        }

        val variants = generateTargetVariants(targetContact, targetPhone)
        val normTarget = normalize(targetContact)
        val targetDigits = normalizeDigits(targetPhone.takeIf { !it.isNullOrBlank() } ?: targetContact)

        Log.d(TAG, "isCorrectChatOpen: Verifying header for '$targetContact', variants=$variants")

        // 2. Check header nodes via known toolbar title view IDs
        for (node in allNodes) {
            if (!node.isVisibleToUser) continue
            val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isHeaderId = TOOLBAR_HEADER_IDS.any { resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it }
            if (isHeaderId) {
                val text = node.text?.toString()?.trim() ?: ""
                val normText = normalize(text)
                if (normText.isNotBlank()) {
                    if (normText == normTarget || (targetDigits.length >= 7 && normalizeDigits(normText).takeLast(10) == targetDigits.takeLast(10))) {
                        Log.d(TAG, "isCorrectChatOpen: Header verified via ID '$resId' -> '$text'")
                        return true
                    }
                    if (normTarget.isNotBlank() && normText.contains(normTarget)) {
                        Log.d(TAG, "isCorrectChatOpen: Header verified via partial token in ID '$resId' -> '$text'")
                        return true
                    }
                }
            }
        }

        // 3. Check text in the top area of screen bounds to strictly restrict to toolbar area
        for (node in allNodes) {
            if (node.packageName != null && node.packageName != pkgName) continue
            if (!node.isVisibleToUser || isExcludedNode(node)) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)

            // The top toolbar in Android is at the top of the display (typically top <= 500px)
            if (rect.top <= 500) {
                val nodeText = normalize(node.text?.toString())
                if (nodeText.isNotBlank()) {
                    if (nodeText == normTarget || nodeText.contains(normTarget) || (targetDigits.length >= 7 && normalizeDigits(nodeText).takeLast(10) == targetDigits.takeLast(10))) {
                        Log.d(TAG, "isCorrectChatOpen: Header verified in top screen area (top=${rect.top}) -> '${node.text}'")
                        return true
                    }
                }
            }
        }

        Log.w(TAG, "isCorrectChatOpen: Verification FAILED for target '$targetContact'. Header text did not match.")
        return false
    }

    private data class ResolvedCandidate(
        val node: AccessibilityNodeInfo,
        val clickableNode: AccessibilityNodeInfo,
        val matchedText: String,
        val score: Float,
        val matchType: String,
        val structuredTarget: WhatsappContactTarget? = null
    )
}
