package com.example.automation.computeruse

import android.util.Log

/**
 * Verification outcome for target destination state.
 */
data class DestinationVerificationResult(
    val verified: Boolean,
    val status: EvidenceStatus,
    val destinationType: String = "generic_destination",
    val evidence: List<EvidenceRecord> = emptyList(),
    val message: String = ""
)

/**
 * Independent Destination Verifier that confirms whether the target destination screen
 * (video player, user profile, detail page, settings, webpage) is actively displayed.
 */
object DestinationVerifier {
    private const val TAG = "DestinationVerifier"

    fun verify(
        obs: ScreenObservation,
        query: String,
        targetApp: String? = null,
        selectedCandidate: TargetGrounding? = null
    ): DestinationVerificationResult {
        if (!obs.isAppForeground) {
            return DestinationVerificationResult(
                verified = false,
                status = EvidenceStatus.FAILED,
                message = "Target app is not in foreground"
            )
        }

        val records = mutableListOf<EvidenceRecord>()
        val pkg = obs.foregroundPackage.lowercase()
        val queryLower = query.lowercase().trim()
        val queryKeywords = queryLower.split(Regex("""\s+""")).filter {
            it.length > 2 && it !in setOf("for", "and", "the", "with", "tutorial", "video", "search")
        }

        // 1. Check YouTube destination (Video Player / Detail view)
        if (pkg.contains("youtube")) {
            val hasPlayerControls = obs.interactiveElements.any { elem ->
                elem.id?.contains("player", ignoreCase = true) == true ||
                elem.id?.contains("watch", ignoreCase = true) == true ||
                elem.contentDescription?.contains("Play", ignoreCase = true) == true ||
                elem.contentDescription?.contains("Pause", ignoreCase = true) == true
            }
            val hasVideoIndicators = obs.visibleTexts.any { text ->
                text.equals("Subscribe", ignoreCase = true) ||
                text.contains("Subscribers", ignoreCase = true) ||
                text.contains("Comments", ignoreCase = true) ||
                text.contains("Views", ignoreCase = true) ||
                text.equals("Share", ignoreCase = true) ||
                text.equals("Like", ignoreCase = true) ||
                text.equals("Dislike", ignoreCase = true)
            }
            val matchesQuery = queryKeywords.any { kw ->
                obs.visibleTexts.any { it.contains(kw, ignoreCase = true) }
            }

            val candidateFresh = selectedCandidate?.let { !it.isStale(obs) } == true
            if ((hasPlayerControls || hasVideoIndicators) && (matchesQuery || candidateFresh)) {
                records.add(EvidenceRecord("youtube_verifier", EvidenceStatus.VERIFIED, "YouTube video player/detail indicators active"))
                Log.i(TAG, "[DESTINATION_VERIFIED] PASS: YouTube video page verified for '$query'")
                return DestinationVerificationResult(
                    verified = true,
                    status = EvidenceStatus.VERIFIED,
                    destinationType = "youtube_video",
                    evidence = records,
                    message = "Target video for '$query' is open and active."
                )
            }
        }

        // 2. Check Instagram destination (Profile / Post view)
        if (pkg.contains("instagram")) {
            val hasProfileIndicators = obs.visibleTexts.any { text ->
                text.equals("Follow", ignoreCase = true) ||
                text.equals("Following", ignoreCase = true) ||
                text.equals("Posts", ignoreCase = true) ||
                text.equals("Followers", ignoreCase = true) ||
                text.equals("Message", ignoreCase = true)
            }
            val matchesQuery = queryKeywords.any { kw ->
                obs.visibleTexts.any { it.contains(kw, ignoreCase = true) }
            }

            if (hasProfileIndicators && matchesQuery) {
                records.add(EvidenceRecord("instagram_verifier", EvidenceStatus.VERIFIED, "Instagram profile indicators active"))
                Log.i(TAG, "[DESTINATION_VERIFIED] PASS: Instagram profile verified for '$query'")
                return DestinationVerificationResult(
                    verified = true,
                    status = EvidenceStatus.VERIFIED,
                    destinationType = "instagram_profile",
                    evidence = records,
                    message = "Profile for '$query' is open and active."
                )
            }
        }

        // 3. Generic destination verification
        val matchesQuery = queryKeywords.isNotEmpty() && queryKeywords.any { kw ->
            obs.visibleTexts.any { it.contains(kw, ignoreCase = true) }
        }
        val hasDetailIndicators = obs.interactiveElements.any { elem ->
            elem.id?.contains("detail", ignoreCase = true) == true ||
            elem.id?.contains("container", ignoreCase = true) == true ||
            elem.id?.contains("content", ignoreCase = true) == true
        }

        if (matchesQuery && hasDetailIndicators) {
            records.add(EvidenceRecord("generic_verifier", EvidenceStatus.VERIFIED, "Generic destination content and query match verified"))
            Log.i(TAG, "[DESTINATION_VERIFIED] PASS: Generic destination verified for '$query'")
            return DestinationVerificationResult(
                verified = true,
                status = EvidenceStatus.VERIFIED,
                destinationType = "generic_detail",
                evidence = records,
                message = "Target destination for '$query' is verified on screen."
            )
        }

        records.add(EvidenceRecord("destination_verifier", EvidenceStatus.FAILED, "Destination indicators not satisfied on current screen"))
        return DestinationVerificationResult(
            verified = false,
            status = EvidenceStatus.FAILED,
            evidence = records,
            message = "Destination screen not verified"
        )
    }
}
