package com.example.automation.grounding

import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenSnapshot
import java.util.Locale

data class ScoredCandidate(
    val node: SemanticNode,
    val score: Float,
    val matchReasons: List<String>
)

data class GroundingResult(
    val bestCandidate: SemanticNode?,
    val confidence: Float,
    val rankedCandidates: List<ScoredCandidate>,
    val isAmbiguous: Boolean = false,
    val reason: String = ""
)

object ScoredTargetGroundingEngine {
    const val CONFIDENCE_THRESHOLD = 0.70f
    const val SAFE_DIRECT_ACTION_THRESHOLD = 0.85f

    private val SYNONYM_MAP: Map<String, List<String>> = mapOf(
        "search" to listOf("search", "search here", "search or type url", "omnibox", "ask meta ai or search", "query", "find"),
        "send" to listOf("send", "submit", "post"),
        "directions" to listOf("directions", "get directions", "route", "navigate"),
        "play" to listOf("play", "play video", "watch"),
        "chats" to listOf("chats", "conversations", "messages", "inbox"),
        "calls" to listOf("calls", "phone calls", "call history"),
        "status" to listOf("status", "updates", "stories")
    )

    fun groundTarget(snapshot: UiScreenSnapshot, query: String): GroundingResult {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank() || snapshot.nodes.isEmpty()) {
            return GroundingResult(
                bestCandidate = null,
                confidence = 0f,
                rankedCandidates = emptyList(),
                reason = "Empty query or no nodes in snapshot"
            )
        }

        val candidates = mutableListOf<ScoredCandidate>()

        for (node in snapshot.nodes) {
            val (score, reasons) = evaluateNode(node, cleanQuery)
            if (score > 0f) {
                candidates.add(ScoredCandidate(node, score, reasons))
            }
        }

        candidates.sortByDescending { it.score }

        if (candidates.isEmpty()) {
            return GroundingResult(
                bestCandidate = null,
                confidence = 0f,
                rankedCandidates = emptyList(),
                reason = "No candidate matched query '$query'"
            )
        }

        val best = candidates.first()
        val normalizedConfidence = (best.score / 115f).coerceIn(0f, 1.0f)

        // Ambiguity check: if top 2 candidates are within 5 points of each other
        val isAmbiguous = candidates.size > 1 &&
            (best.score - candidates[1].score) < 5f &&
            best.score >= 50f

        return GroundingResult(
            bestCandidate = best.node,
            confidence = normalizedConfidence,
            rankedCandidates = candidates,
            isAmbiguous = isAmbiguous,
            reason = best.matchReasons.joinToString(", ")
        )
    }

    private fun evaluateNode(node: SemanticNode, query: String): Pair<Float, List<String>> {
        var score = 0f
        val reasons = mutableListOf<String>()

        val nodeText = node.text?.trim()?.lowercase(Locale.ROOT) ?: ""
        val nodeDesc = node.contentDescription?.trim()?.lowercase(Locale.ROOT) ?: ""
        val nodeResId = node.resourceId?.trim()?.lowercase(Locale.ROOT) ?: ""

        // 1. Exact ResourceId match (+100)
        if (nodeResId.isNotEmpty() && (nodeResId == query || nodeResId.endsWith("/$query") || nodeResId.endsWith(":$query"))) {
            score += 100f
            reasons.add("Exact resourceId")
        }

        // 2. Exact ContentDescription (+95)
        if (nodeDesc.isNotEmpty() && nodeDesc == query) {
            score += 95f
            reasons.add("Exact contentDescription")
        }

        // 3. Exact Text (+95)
        if (nodeText.isNotEmpty() && nodeText == query) {
            score += 95f
            reasons.add("Exact text")
        }

        // 4. Normalized Text (+75)
        if (score < 85f && nodeText.isNotEmpty() && normalize(nodeText) == normalize(query)) {
            score += 75f
            reasons.add("Normalized text match")
        }

        // 5. Contextual Synonym / Alias match (+60)
        val synonyms = SYNONYM_MAP[query] ?: emptyList()
        if (synonyms.isNotEmpty()) {
            if (synonyms.any { s -> nodeText == s || nodeDesc == s }) {
                score += 60f
                reasons.add("Exact synonym match")
            } else if (synonyms.any { s -> nodeText.contains(s) || nodeDesc.contains(s) || nodeResId.contains(s) }) {
                score += 40f
                reasons.add("Partial synonym match")
            }
        }

        // 6. Substring / Partial match (+30)
        if (score < 60f) {
            if (nodeText.contains(query)) {
                score += 35f
                reasons.add("Text substring match")
            } else if (nodeDesc.contains(query)) {
                score += 30f
                reasons.add("Desc substring match")
            } else if (nodeResId.contains(query)) {
                score += 25f
                reasons.add("ResourceId substring match")
            }
        }

        // 7. Levenshtein Fuzzy Similarity Match (for typos, speech-to-text slips, pluralizations)
        if (score < 60f) {
            val textSim = if (nodeText.isNotEmpty()) fuzzySimilarity(query, nodeText) else 0f
            val descSim = if (nodeDesc.isNotEmpty()) fuzzySimilarity(query, nodeDesc) else 0f
            val bestSim = maxOf(textSim, descSim)
            if (bestSim >= 0.70f) {
                val fuzzyScore = 40f + (bestSim - 0.70f) * 83.3f
                score = maxOf(score, fuzzyScore)
                reasons.add("Fuzzy match (${(bestSim * 100).toInt()}%)")
            }
        }

        // 8. Interactive Boost (+15)
        if (score > 0f) {
            if (node.isClickable) {
                score += 15f
                reasons.add("Clickable boost")
            }
            if (node.isEditable) {
                score += 15f
                reasons.add("Editable boost")
            }
            if (node.isFocusable) {
                score += 5f
            }
        }

        return Pair(score, reasons)
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun fuzzySimilarity(s1: String, s2: String): Float {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0f
        val dist = levenshteinDistance(s1.lowercase(Locale.ROOT), s2.lowercase(Locale.ROOT))
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun normalize(str: String): String {
        return str.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.ROOT)
    }
}
