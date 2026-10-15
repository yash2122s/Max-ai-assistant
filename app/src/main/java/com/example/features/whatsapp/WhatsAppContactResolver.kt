package com.example.features.whatsapp

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
     * Normalizes text phonetically for common Indian/regional English transliterations.
     * E.g., 'yaswanth' <-> 'yashwanth', 'suresh' <-> 'sures', 'deepak' <-> 'dipak'.
     */
    fun normalizePhonetic(text: String?): String {
        val norm = normalize(text)
        if (norm.isBlank()) return ""
        return norm
            .replace("sh", "s")
            .replace("th", "t")
            .replace("ph", "f")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("c", "k")
    }

    /**
     * Normalizes phone number to raw digits.
     */
    fun normalizeDigits(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        return phone.replace(Regex("[^0-9]"), "")
    }

    /**
     * Checks if two single-word names are fuzzy-matched within Levenshtein distance 2.
     */
    fun isSingleWordFuzzyMatch(s1: String, s2: String): Boolean {
        if (s1.length < 4 || s2.length < 4) return false
        if (Math.abs(s1.length - s2.length) > 2) return false
        return levenshteinDistance(s1, s2) <= 2
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var prev = IntArray(rhs.length + 1) { it }
        var curr = IntArray(rhs.length + 1)
        for (i in lhs.indices) {
            curr[0] = i + 1
            for (j in rhs.indices) {
                val cost = if (lhs[i] == rhs[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[rhs.length]
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
            val phonetic = normalizePhonetic(target)
            if (phonetic.isNotBlank() && phonetic != normTarget) {
                variants.add(phonetic)
            }
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

            val isExact = normNodeText == normTarget
            val isPhoneticExact = normalizePhonetic(normNodeText) == normalizePhonetic(normTarget)

            if (isExact || isPhoneticExact) {
                score = if (isExplicitContactId) 1.0f else 0.95f
                matchType = if (isExact) "EXACT_NAME" else "PHONETIC_EXACT"
            } else if (targetDigits.length >= 7 && normalizeDigits(normNodeText).takeLast(10) == targetDigits.takeLast(10)) {
                score = if (isExplicitContactId) 1.0f else 0.95f
                matchType = "EXACT_PHONE"
            } else {
                // Check token matching for multi-word or single-word matches
                val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }
                val nodeTokens = normNodeText.split(" ").filter { it.isNotBlank() }
                val targetPhoneticTokens = normalizePhonetic(normTarget).split(" ").filter { it.isNotBlank() }
                val nodePhoneticTokens = normalizePhonetic(normNodeText).split(" ").filter { it.isNotBlank() }

                if (targetTokens.isNotEmpty() && (nodeTokens.containsAll(targetTokens) || nodePhoneticTokens.containsAll(targetPhoneticTokens))) {
                    score = if (isExplicitContactId) 0.88f else 0.85f
                    matchType = "TOKEN_CONTAINED"
                } else if (nodeTokens.isNotEmpty() && (targetTokens.containsAll(nodeTokens) || targetPhoneticTokens.containsAll(nodePhoneticTokens))) {
                    score = if (isExplicitContactId) 0.85f else 0.82f
                    matchType = "TOKEN_MATCH"
                } else if (normNodeText.startsWith(normTarget) || normTarget.startsWith(normNodeText) ||
                    normalizePhonetic(normNodeText).startsWith(normalizePhonetic(normTarget)) ||
                    normalizePhonetic(normTarget).startsWith(normalizePhonetic(normNodeText))) {
                    score = if (isExplicitContactId) 0.80f else 0.78f
                    matchType = "PREFIX_MATCH"
                } else if (isSingleWordFuzzyMatch(normNodeText, normTarget) || isSingleWordFuzzyMatch(normalizePhonetic(normNodeText), normalizePhonetic(normTarget))) {
                    score = if (isExplicitContactId) 0.82f else 0.78f
                    matchType = "FUZZY_MATCH"
                }
            }

            if (score >= 0.78f) {
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
     * Climbs up the hierarchy to find the clickable row container or ancestor,
     * explicitly bypassing avatar / profile picture sub-nodes so the chat opens rather than the DP preview.
     */
    fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo, pkgName: String): AccessibilityNodeInfo? {
        val initialId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        // If the matched node is an avatar/contact_photo, climb to parent immediately
        val isAvatar = initialId.contains("contact_photo") || initialId.contains("avatar") ||
                       initialId.contains("profile_picture") || initialId.contains("picture")
        var current: AccessibilityNodeInfo? = if (isAvatar) runCatching { node.parent }.getOrNull() else node
        var count = 0
        var bestRow: AccessibilityNodeInfo? = null

        while (current != null && count < 8) {
            val resId = (current.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isRowContainer = ROW_CONTAINER_IDS.any {
                resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it
            }

            if (isRowContainer) {
                return current
            }
            if (current.isClickable && bestRow == null && !isAvatarNode(current)) {
                bestRow = current
            }
            current = runCatching { current.parent }.getOrNull()
            count++
        }
        return bestRow ?: (if (node.isClickable && !isAvatarNode(node)) node else runCatching { node.parent }.getOrNull() ?: node)
    }

    private fun isAvatarNode(node: AccessibilityNodeInfo): Boolean {
        val id = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        val desc = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
        return id.contains("contact_photo") || id.contains("avatar") || id.contains("profile_picture") ||
               id.contains("picture") || desc.contains("profile photo") || desc.contains("view profile")
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
            val desc = node.contentDescription?.toString() ?: ""
            resId.endsWith(":id/entry") || resId.endsWith(":id/text_entry") ||
                    text.contains("Type a message", ignoreCase = true) ||
                    text.equals("Message", ignoreCase = true) ||
                    desc.contains("Type a message", ignoreCase = true) ||
                    desc.equals("Message", ignoreCase = true)
        }

        if (!hasEntryField) {
            Log.d(TAG, "isCorrectChatOpen: Message entry field not found.")
            return false
        }

        val variants = generateTargetVariants(targetContact, targetPhone)
        val normTarget = normalize(targetContact)
        val targetDigits = normalizeDigits(targetPhone.takeIf { !it.isNullOrBlank() } ?: targetContact)
        val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }

        Log.d(TAG, "isCorrectChatOpen: Verifying header for '$targetContact', variants=$variants")

        fun matchesTarget(normText: String): Boolean {
            if (normText.isBlank()) return false
            if (normText == normTarget) return true
            if (normalizePhonetic(normText) == normalizePhonetic(normTarget)) return true
            if (targetDigits.length >= 7 && normalizeDigits(normText).takeLast(10) == targetDigits.takeLast(10)) return true
            if (normTarget.isNotBlank() && (normText.contains(normTarget) || normTarget.contains(normText))) return true
            val normPhoneticText = normalizePhonetic(normText)
            val normPhoneticTarget = normalizePhonetic(normTarget)
            if (normPhoneticTarget.isNotBlank() && (normPhoneticText.contains(normPhoneticTarget) || normPhoneticTarget.contains(normPhoneticText))) return true
            val textTokens = normText.split(" ").filter { it.isNotBlank() }
            if (targetTokens.isNotEmpty() && textTokens.isNotEmpty()) {
                if (textTokens.containsAll(targetTokens) || targetTokens.containsAll(textTokens)) return true
            }
            val textPhoneticTokens = normPhoneticText.split(" ").filter { it.isNotBlank() }
            val targetPhoneticTokens = normPhoneticTarget.split(" ").filter { it.isNotBlank() }
            if (targetPhoneticTokens.isNotEmpty() && textPhoneticTokens.isNotEmpty()) {
                if (textPhoneticTokens.containsAll(targetPhoneticTokens) || targetPhoneticTokens.containsAll(textPhoneticTokens)) return true
            }
            if (isSingleWordFuzzyMatch(normText, normTarget) || isSingleWordFuzzyMatch(normPhoneticText, normPhoneticTarget)) return true
            return false
        }

        // 2. Check header nodes via known toolbar title view IDs
        for (node in allNodes) {
            if (!node.isVisibleToUser) continue
            val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isHeaderId = TOOLBAR_HEADER_IDS.any { resId.endsWith(":id/$it") || resId.endsWith("/$it") || resId == it }
            if (isHeaderId) {
                val text = node.text?.toString()?.trim() ?: ""
                val normText = normalize(text)
                if (matchesTarget(normText)) {
                    Log.d(TAG, "isCorrectChatOpen: Header verified via ID '$resId' -> '$text'")
                    return true
                }
            }
        }

        // 3. Check text in the top area of screen bounds to strictly restrict to toolbar area
        for (node in allNodes) {
            if (node.packageName != null && node.packageName != pkgName) continue
            if (!node.isVisibleToUser || isExcludedNode(node)) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)

            // The top toolbar in Android is at the top of the display (typically top <= 750px across display densities)
            if (rect.top <= 750) {
                val nodeText = normalize(node.text?.toString())
                if (matchesTarget(nodeText)) {
                    Log.d(TAG, "isCorrectChatOpen: Header verified in top screen area (top=${rect.top}) -> '${node.text}'")
                    return true
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
