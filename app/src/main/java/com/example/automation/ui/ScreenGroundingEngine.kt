package com.example.automation.ui

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.core.actions.GenericAction
import com.example.automation.core.actions.GenericActionType
import com.example.service.AccessibilityTreePruner
import com.example.service.IndexedUiElement
import com.example.service.JarvisAccessibilityService
import java.util.Locale

/**
 * ScreenGroundingResult status enum representing element search outcomes.
 */
enum class GroundingStatus {
    FOUND,
    AMBIGUOUS,
    NOT_FOUND
}

/**
 * Detailed metadata of a candidate node evaluated during grounding.
 */
data class GroundedNodeMetadata(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true
)

/**
 * Structured result returned by ScreenGroundingEngine.
 */
data class ScreenGroundingResult(
    val status: GroundingStatus,
    val matchedNode: AccessibilityNodeInfo? = null,
    val metadata: GroundedNodeMetadata? = null,
    val confidence: Float = 0f,
    val matchPriority: Int = 0,
    val reason: String = "",
    val candidateCount: Int = 0,
    val target: String = ""
)

/**
 * ScreenGroundingEngine - Local Accessibility Intelligence for MAX.
 * Resolves generic action targets (e.g. CLICK_ELEMENT("5"), CLICK_ELEMENT("Search"))
 * dynamically against the live Android Accessibility Tree without app-specific hardcoding.
 */
object ScreenGroundingEngine {
    private const val TAG = "ScreenGroundingEngine"

    /**
     * Resolves an arbitrary GenericAction target against the current screen hierarchy.
     */
    fun groundAction(
        action: GenericAction,
        rootNode: AccessibilityNodeInfo? = JarvisAccessibilityService.instance?.rootInActiveWindow
    ): ScreenGroundingResult {
        val target = action.target ?: action.text ?: action.resourceId ?: action.contentDescription ?: ""
        val targetIndex = action.index
        val explicitResId = action.resourceId
        val explicitDesc = action.contentDescription

        Log.d("UI_GROUND", "[UI_GROUND] target=\"$target\" type=${action.type} index=$targetIndex")

        if (rootNode == null) {
            Log.w(TAG, "[UI_GROUND] rootNode is null. Accessibility tree unavailable.")
            return ScreenGroundingResult(
                status = GroundingStatus.NOT_FOUND,
                reason = "Accessibility root node is null or inactive",
                target = target
            )
        }

        // Handle explicit index match if requested
        if (targetIndex != null && targetIndex > 0) {
            val service = JarvisAccessibilityService.instance
            val node = service?.resolveIndexedNode(targetIndex)
            if (node != null && node.isVisibleToUser && node.isEnabled) {
                val meta = extractMetadata(node)
                Log.d("UI_MATCH", "[UI_MATCH] target=\"$target\" by=INDEX index=$targetIndex confidence=1.0")
                return ScreenGroundingResult(
                    status = GroundingStatus.FOUND,
                    matchedNode = node,
                    metadata = meta,
                    confidence = 1.0f,
                    matchPriority = 7,
                    reason = "Exact indexed UI element match #$targetIndex",
                    candidateCount = 1,
                    target = target
                )
            }
        }

        return groundTarget(
            target = target,
            rootNode = rootNode,
            explicitResourceId = explicitResId,
            explicitContentDescription = explicitDesc
        )
    }

    /**
     * Searches the live accessibility tree according to strict matching priority:
     * 1. Exact resourceId
     * 2. Exact contentDescription
     * 3. Exact visible text
     * 4. Case-insensitive text
     * 5. Normalized text
     * 6. Conservative fuzzy text matching
     * 7. Existing indexed UI element mapping
     */
    fun groundTarget(
        target: String,
        rootNode: AccessibilityNodeInfo,
        explicitResourceId: String? = null,
        explicitContentDescription: String? = null
    ): ScreenGroundingResult {
        val trimmedTarget = java.text.Normalizer.normalize(target.trim(), java.text.Normalizer.Form.NFKC).trim()
        if (trimmedTarget.isEmpty() && explicitResourceId.isNullOrBlank() && explicitContentDescription.isNullOrBlank()) {
            return ScreenGroundingResult(
                status = GroundingStatus.NOT_FOUND,
                reason = "Target query is empty",
                target = target
            )
        }

        // Collect all visible, enabled interactive/informative nodes
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectActiveNodes(rootNode, allNodes)

        if (allNodes.isEmpty()) {
            Log.w(TAG, "[UI_GROUND] No active visible nodes found in tree.")
            return ScreenGroundingResult(
                status = GroundingStatus.NOT_FOUND,
                reason = "No visible interactive nodes found in active screen",
                target = target
            )
        }

        // 1. Priority 1: Exact resourceId match
        val resTarget = explicitResourceId ?: trimmedTarget
        if (resTarget.isNotEmpty()) {
            val aliases = getTargetAliases(resTarget)
            val resMatches = allNodes.filter { node ->
                val id = node.viewIdResourceName ?: ""
                val entryName = id.substringAfterLast('/')
                id.equals(resTarget, ignoreCase = true) ||
                entryName.equals(resTarget, ignoreCase = true) ||
                (explicitResourceId != null && aliases.any { alias ->
                    id.equals(alias, ignoreCase = true) || entryName.equals(alias, ignoreCase = true)
                })
            }
            if (resMatches.size == 1) {
                val node = resMatches.first()
                Log.d("UI_MATCH", "[UI_MATCH] resourceId=\"${node.viewIdResourceName}\" priority=1 confidence=1.0")
                return ScreenGroundingResult(
                    status = GroundingStatus.FOUND,
                    matchedNode = node,
                    metadata = extractMetadata(node),
                    confidence = 1.0f,
                    matchPriority = 1,
                    reason = "Exact resourceId match",
                    candidateCount = 1,
                    target = target
                )
            } else if (resMatches.size > 1) {
                val interactive = resMatches.filter { it.isClickable || it.isEditable }
                if (interactive.size == 1) {
                    val node = interactive.first()
                    Log.d("UI_MATCH", "[UI_MATCH] resourceId=\"${node.viewIdResourceName}\" (single interactive) priority=1 confidence=0.95")
                    return ScreenGroundingResult(
                        status = GroundingStatus.FOUND,
                        matchedNode = node,
                        metadata = extractMetadata(node),
                        confidence = 0.95f,
                        matchPriority = 1,
                        reason = "Exact resourceId match among multiple duplicates",
                        candidateCount = resMatches.size,
                        target = target
                    )
                }
            }
        }

        // 2. Priority 2: Exact contentDescription match
        val descTarget = explicitContentDescription ?: trimmedTarget
        if (descTarget.isNotEmpty()) {
            val aliases = getTargetAliases(descTarget)
            val descMatches = allNodes.filter { node ->
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                desc == descTarget || aliases.any { alias -> desc.equals(alias, ignoreCase = true) }
            }
            if (descMatches.size == 1) {
                val node = descMatches.first()
                Log.d("UI_MATCH", "[UI_MATCH] desc=\"${node.contentDescription}\" priority=2 confidence=1.0")
                return ScreenGroundingResult(
                    status = GroundingStatus.FOUND,
                    matchedNode = node,
                    metadata = extractMetadata(node),
                    confidence = 1.0f,
                    matchPriority = 2,
                    reason = "Exact contentDescription match",
                    candidateCount = 1,
                    target = target
                )
            } else if (descMatches.size > 1) {
                return disambiguateCandidates(descMatches, target, priority = 2, matchType = "contentDescription")
            }
        }

        // 3. Priority 3: Exact visible text match (case-sensitive)
        if (trimmedTarget.isNotEmpty()) {
            val aliases = getTargetAliases(trimmedTarget)
            val exactTextMatches = allNodes.filter { node ->
                val txt = node.text?.toString()?.trim() ?: ""
                txt == trimmedTarget || aliases.any { alias -> txt == alias }
            }
            if (exactTextMatches.size == 1) {
                val node = exactTextMatches.first()
                Log.d("UI_MATCH", "[UI_MATCH] text=\"${node.text}\" priority=3 confidence=1.0")
                return ScreenGroundingResult(
                    status = GroundingStatus.FOUND,
                    matchedNode = node,
                    metadata = extractMetadata(node),
                    confidence = 1.0f,
                    matchPriority = 3,
                    reason = "Exact visible text match",
                    candidateCount = 1,
                    target = target
                )
            } else if (exactTextMatches.size > 1) {
                return disambiguateCandidates(exactTextMatches, target, priority = 3, matchType = "exact text")
            }
        }

        // 4. Priority 4: Case-insensitive text match (or case-insensitive description)
        if (trimmedTarget.isNotEmpty()) {
            val ciTarget = trimmedTarget.lowercase(Locale.ROOT)
            val ciMatches = allNodes.filter { node ->
                val txt = node.text?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                txt == ciTarget || desc == ciTarget
            }
            if (ciMatches.size == 1) {
                val node = ciMatches.first()
                Log.d("UI_MATCH", "[UI_MATCH] text=\"${node.text ?: node.contentDescription}\" priority=4 confidence=0.95")
                return ScreenGroundingResult(
                    status = GroundingStatus.FOUND,
                    matchedNode = node,
                    metadata = extractMetadata(node),
                    confidence = 0.95f,
                    matchPriority = 4,
                    reason = "Case-insensitive text/description match",
                    candidateCount = 1,
                    target = target
                )
            } else if (ciMatches.size > 1) {
                return disambiguateCandidates(ciMatches, target, priority = 4, matchType = "case-insensitive text")
            }
        }

        // 5. Priority 5: Normalized text match (strip punctuation, extra spaces, special chars)
        if (trimmedTarget.isNotEmpty()) {
            val normTarget = normalizeText(trimmedTarget)
            if (normTarget.isNotEmpty()) {
                val normMatches = allNodes.filter { node ->
                    val normTxt = normalizeText(node.text?.toString() ?: "")
                    val normDesc = normalizeText(node.contentDescription?.toString() ?: "")
                    normTxt == normTarget || normDesc == normTarget
                }
                if (normMatches.size == 1) {
                    val node = normMatches.first()
                    Log.d("UI_MATCH", "[UI_MATCH] text=\"${node.text ?: node.contentDescription}\" priority=5 confidence=0.90")
                    return ScreenGroundingResult(
                        status = GroundingStatus.FOUND,
                        matchedNode = node,
                        metadata = extractMetadata(node),
                        confidence = 0.90f,
                        matchPriority = 5,
                        reason = "Normalized text match",
                        candidateCount = 1,
                        target = target
                    )
                } else if (normMatches.size > 1) {
                    return disambiguateCandidates(normMatches, target, priority = 5, matchType = "normalized text")
                }
            }
        }

        // 6. Priority 6: Conservative fuzzy text matching (prefix / word boundary / token containment)
        if (trimmedTarget.length >= 3) {
            val fuzzyTarget = normalizeText(trimmedTarget)
            val fuzzyCandidates = mutableListOf<Pair<AccessibilityNodeInfo, Float>>()

            for (node in allNodes) {
                val nodeTxt = normalizeText(node.text?.toString() ?: "")
                val nodeDesc = normalizeText(node.contentDescription?.toString() ?: "")
                val combined = if (nodeTxt.isNotEmpty() && nodeDesc.isNotEmpty()) "$nodeTxt $nodeDesc" else (nodeTxt.ifEmpty { nodeDesc })

                if (combined.isEmpty()) continue

                val score = calculateFuzzyScore(fuzzyTarget, combined)
                if (score >= 0.75f) {
                    fuzzyCandidates.add(node to score)
                }
            }

            if (fuzzyCandidates.isNotEmpty()) {
                fuzzyCandidates.sortByDescending { it.second }
                val bestScore = fuzzyCandidates.first().second
                val topTier = fuzzyCandidates.filter { it.second >= (bestScore - 0.05f) }

                if (topTier.size == 1) {
                    val node = topTier.first().first
                    Log.d("UI_MATCH", "[UI_MATCH] fuzzy=\"${node.text ?: node.contentDescription}\" priority=6 confidence=$bestScore")
                    return ScreenGroundingResult(
                        status = GroundingStatus.FOUND,
                        matchedNode = node,
                        metadata = extractMetadata(node),
                        confidence = bestScore,
                        matchPriority = 6,
                        reason = "Conservative fuzzy text match (score: $bestScore)",
                        candidateCount = 1,
                        target = target
                    )
                } else {
                    return disambiguateCandidates(
                        topTier.map { it.first },
                        target,
                        priority = 6,
                        matchType = "fuzzy text"
                    )
                }
            }
        }

        // 7. Priority 7: Existing indexed UI element mapping fallback
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            val indexedElements = service.getIndexedElements()
            val targetLower = trimmedTarget.lowercase(Locale.ROOT)
            val matchedIndexed = indexedElements.filter { el ->
                val label = (el.text ?: el.contentDescription ?: "").lowercase(Locale.ROOT)
                label.contains(targetLower) || targetLower.contains(label)
            }
            if (matchedIndexed.size == 1) {
                val matched = matchedIndexed.first()
                val liveNode = service.resolveIndexedNode(matched.index)
                if (liveNode != null && liveNode.isVisibleToUser && liveNode.isEnabled) {
                    Log.d("UI_MATCH", "[UI_MATCH] indexed_element=\"${matched.toDisplayString()}\" priority=7 confidence=0.80")
                    return ScreenGroundingResult(
                        status = GroundingStatus.FOUND,
                        matchedNode = liveNode,
                        metadata = extractMetadata(liveNode),
                        confidence = 0.80f,
                        matchPriority = 7,
                        reason = "Indexed element tree mapping",
                        candidateCount = 1,
                        target = target
                    )
                }
            }
        }

        Log.w(TAG, "[UI_GROUND] Target \"$target\" NOT FOUND in current accessibility tree.")
        return ScreenGroundingResult(
            status = GroundingStatus.NOT_FOUND,
            reason = "No matching UI element found for target '$target'",
            candidateCount = 0,
            target = target
        )
    }

    /**
     * Resolves multiple matching candidates without guessing.
     * Prioritizes clickable, focusable, editable elements or returns AMBIGUOUS.
     */
    private fun disambiguateCandidates(
        candidates: List<AccessibilityNodeInfo>,
        target: String,
        priority: Int,
        matchType: String
    ): ScreenGroundingResult {
        // Step 1: Filter to interactive nodes
        val interactive = candidates.filter { it.isClickable || it.isFocusable || it.isEditable }
        if (interactive.size == 1) {
            val node = interactive.first()
            Log.d("UI_MATCH", "[UI_MATCH] Disambiguated single interactive node for target=\"$target\" (from ${candidates.size} $matchType candidates)")
            return ScreenGroundingResult(
                status = GroundingStatus.FOUND,
                matchedNode = node,
                metadata = extractMetadata(node),
                confidence = 0.90f,
                matchPriority = priority,
                reason = "Resolved single interactive element among $matchType matches",
                candidateCount = candidates.size,
                target = target
            )
        }

        // Step 2: Check if one candidate has an exact text match while others are partial
        val exactMatches = candidates.filter { it.text?.toString()?.trim() == target || it.contentDescription?.toString()?.trim() == target }
        if (exactMatches.size == 1) {
            val node = exactMatches.first()
            Log.d("UI_MATCH", "[UI_MATCH] Disambiguated exact target=\"$target\" over partial $matchType candidates")
            return ScreenGroundingResult(
                status = GroundingStatus.FOUND,
                matchedNode = node,
                metadata = extractMetadata(node),
                confidence = 0.95f,
                matchPriority = priority,
                reason = "Exact target match prioritized over partial matches",
                candidateCount = candidates.size,
                target = target
            )
        }

        // Otherwise report AMBIGUOUS to prevent blind misclicks
        Log.w(TAG, "[UI_GROUND] Target \"$target\" is AMBIGUOUS (${candidates.size} conflicting candidates found).")
        return ScreenGroundingResult(
            status = GroundingStatus.AMBIGUOUS,
            reason = "Multiple matching elements found for '$target' (${candidates.size} candidates)",
            confidence = 0.5f,
            matchPriority = priority,
            candidateCount = candidates.size,
            target = target
        )
    }

    /**
     * Traverses the Accessibility tree recursively, filtering out invisible, disabled, or zero-bound noise.
     */
    private fun collectActiveNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > 50) return

        if (!node.isVisibleToUser || !node.isEnabled) {
            return
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Ignore non-visible or off-screen noise
        if (rect.width() > 0 && rect.height() > 0) {
            val hasText = !node.text.isNullOrBlank()
            val hasDesc = !node.contentDescription.isNullOrBlank()
            val hasResId = !node.viewIdResourceName.isNullOrBlank()
            val isInteractive = node.isClickable || node.isFocusable || node.isEditable || node.isScrollable || node.isCheckable

            if (hasText || hasDesc || hasResId || isInteractive) {
                result.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectActiveNodes(child, result, depth + 1)
        }
    }

    private fun getTargetAliases(target: String): Set<String> {
        val normalized = java.text.Normalizer.normalize(target.trim(), java.text.Normalizer.Form.NFKC)
        val trimmed = normalized.trim()
        val result = mutableSetOf(target.trim(), trimmed, trimmed.lowercase(Locale.ROOT))
        when (trimmed.lowercase(Locale.ROOT)) {
            "+", "plus", "add" -> result.addAll(listOf("+", "＋", "plus", "add", "op_add"))
            "-", "−", "minus", "sub", "subtract" -> result.addAll(listOf("-", "−", "－", "minus", "sub", "subtract", "op_sub"))
            "*", "×", "x", "multiply", "mul", "times" -> result.addAll(listOf("*", "×", "x", "＊", "multiply", "mul", "times", "op_mul"))
            "/", "÷", "divide", "div" -> result.addAll(listOf("/", "÷", "／", "divide", "div", "op_div"))
            "=", "equals", "eq", "equal" -> result.addAll(listOf("=", "＝", "equals", "eq", "equal", "op_eq"))
            "%", "percent", "pct" -> result.addAll(listOf("%", "％", "percent", "pct", "op_pct"))
            ".", "point", "dot", "decimal" -> result.addAll(listOf(".", "．", "point", "dot", "decimal", "dec_point"))
            "c", "clr", "clear" -> result.addAll(listOf("c", "clr", "clear"))
            "del", "delete", "backspace" -> result.addAll(listOf("del", "delete", "backspace"))
        }
        return result
    }

    private fun normalizeText(raw: String): String {
        return raw
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateFuzzyScore(target: String, candidate: String): Float {
        if (target == candidate) return 1.0f
        if (candidate.startsWith(target)) return 0.92f
        if (candidate.contains(" $target ") || candidate.startsWith("$target ") || candidate.endsWith(" $target")) return 0.88f
        if (candidate.contains(target)) return 0.80f

        val targetTokens = target.split(" ").filter { it.isNotBlank() }
        val candidateTokens = candidate.split(" ").filter { it.isNotBlank() }

        if (targetTokens.isNotEmpty() && candidateTokens.containsAll(targetTokens)) {
            return 0.85f
        }

        return 0f
    }

    fun extractMetadata(node: AccessibilityNodeInfo): GroundedNodeMetadata {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return GroundedNodeMetadata(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            bounds = rect,
            isClickable = node.isClickable,
            isFocusable = node.isFocusable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isEnabled = node.isEnabled,
            isVisibleToUser = node.isVisibleToUser
        )
    }
}
