package com.example.automation.computeruse

import android.util.Log

/**
 * Verification outcome for search results state.
 */
data class SearchResultsVerificationResult(
    val verified: Boolean,
    val status: EvidenceStatus,
    val candidateElements: List<InteractiveElement> = emptyList(),
    val evidence: List<EvidenceRecord> = emptyList(),
    val failureReason: String? = null
)

/**
 * Reusable verifier for evaluating whether the current screen displays genuine search results
 * corresponding to a query.
 */
object SearchResultsVerifier {
    private const val TAG = "SearchResultsVerifier"

    fun verify(obs: ScreenObservation, query: String): SearchResultsVerificationResult {
        if (!obs.isAppForeground) {
            return SearchResultsVerificationResult(
                verified = false,
                status = EvidenceStatus.FAILED,
                failureReason = "No target app in foreground"
            )
        }

        val records = mutableListOf<EvidenceRecord>()
        val queryLower = query.lowercase().trim()
        val queryKeywords = queryLower.split(Regex("""\s+""")).filter {
            it.length > 2 && it !in setOf("for", "and", "the", "with", "tutorial", "video", "search")
        }

        // 1. Must not be stuck in typing mode without results
        if (obs.focusedElement?.isEditable == true && obs.interactiveElements.count { it.isEditable } == 1 && obs.interactiveElements.size < 4) {
            records.add(EvidenceRecord("focused_element", EvidenceStatus.FAILED, "Active keyboard typing mode without results list"))
            return SearchResultsVerificationResult(
                verified = false,
                status = EvidenceStatus.FAILED,
                evidence = records,
                failureReason = "Screen is in query typing state, results not rendered"
            )
        }

        // 2. Identify candidate result items (filtering out navigation, search bar, and suggestion arrows)
        val candidateItems = obs.interactiveElements.filter { elem ->
            val id = elem.id?.lowercase() ?: ""
            val text = elem.text ?: ""
            val desc = elem.contentDescription ?: ""

            val isSearchBar = id.contains("search") || id.contains("edit_text") || id.contains("search_box")
            val isNavOrFilter = id.contains("back") || id.contains("filter") || id.contains("chip") || id.contains("mic")
            val isSuggestionArrow = desc.contains("Edit suggestion", ignoreCase = true) ||
                id.contains("arrow", ignoreCase = true) ||
                id.contains("insert", ignoreCase = true) ||
                (elem.bounds.left > 850 && elem.bounds.width() < 250)

            if (isSearchBar || isNavOrFilter || isSuggestionArrow) return@filter false

            val matchesFull = queryLower.isNotBlank() && (text.contains(queryLower, ignoreCase = true) || desc.contains(queryLower, ignoreCase = true))
            val matchesKeyword = queryKeywords.isNotEmpty() && queryKeywords.any { kw ->
                text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)
            }
            val isResultCard = (id.contains("item") || id.contains("row") || id.contains("entry") || id.contains("card") || id.contains("title")) && elem.bounds.top > 200

            matchesFull || matchesKeyword || isResultCard
        }

        // 3. Evidence synthesis
        if (candidateItems.isNotEmpty()) {
            val matchingItems = candidateItems.filter { elem ->
                val t = (elem.text ?: "") + " " + (elem.contentDescription ?: "")
                queryKeywords.any { kw -> t.contains(kw, ignoreCase = true) }
            }

            if (matchingItems.isNotEmpty()) {
                records.add(EvidenceRecord("search_results", EvidenceStatus.VERIFIED, "Found ${matchingItems.size} candidate items matching query keywords"))
                Log.i(TAG, "[VERIFY_RESULTS] PASS: ${matchingItems.size} relevant result cards found for '$query'")
                return SearchResultsVerificationResult(
                    verified = true,
                    status = EvidenceStatus.VERIFIED,
                    candidateElements = matchingItems,
                    evidence = records
                )
            } else {
                records.add(EvidenceRecord("search_results", EvidenceStatus.OBSERVED, "Found ${candidateItems.size} structural result cards"))
                Log.i(TAG, "[VERIFY_RESULTS] PASS (structural): ${candidateItems.size} cards observed")
                return SearchResultsVerificationResult(
                    verified = true,
                    status = EvidenceStatus.OBSERVED,
                    candidateElements = candidateItems,
                    evidence = records
                )
            }
        }

        records.add(EvidenceRecord("search_results", EvidenceStatus.FAILED, "No candidate result items found on screen"))
        return SearchResultsVerificationResult(
            verified = false,
            status = EvidenceStatus.FAILED,
            evidence = records,
            failureReason = "No matching search result cards found"
        )
    }
}
