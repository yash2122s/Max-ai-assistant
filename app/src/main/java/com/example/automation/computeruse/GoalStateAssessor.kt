package com.example.automation.computeruse

import android.util.Log

/**
 * Assesses the current screen observation against the task goal,
 * establishing evidence-grounded goal states and enabling smart current-state attachment.
 */
object GoalStateAssessor {
    private const val TAG = "GoalStateAssessor"

    fun assessCurrentState(
        obs: ScreenObservation,
        context: TaskContext,
        currentPhase: GoalState,
        selectedCandidate: TargetGrounding? = null
    ): GoalStateAssessment {
        val records = mutableListOf<EvidenceRecord>()
        val query = context.searchQuery
        val targetApp = context.targetApp
        val targetPkg = targetApp?.lowercase() ?: ""
        val currentPkg = obs.foregroundPackage.lowercase()

        // 1. App Foreground Check
        val isTargetAppForeground = if (targetPkg.isNotBlank()) {
            currentPkg.contains(targetPkg) || 
            (targetPkg.contains("youtube") && currentPkg.contains("youtube")) ||
            (targetPkg.contains("instagram") && currentPkg.contains("instagram")) ||
            (targetPkg.contains("whatsapp") && currentPkg.contains("whatsapp")) ||
            (targetPkg.contains("telegram") && currentPkg.contains("telegram")) ||
            (targetPkg.contains("maps") && currentPkg.contains("maps")) ||
            (targetPkg.contains("settings") && currentPkg.contains("settings"))
        } else {
            obs.isAppForeground
        }

        if (!isTargetAppForeground) {
            records.add(EvidenceRecord("foreground_check", EvidenceStatus.FAILED, "Target app '$targetApp' not in foreground (actual: '$currentPkg')"))
            return GoalStateAssessment(
                assessedState = GoalState.INIT,
                status = EvidenceStatus.FAILED,
                evidence = records
            )
        }

        records.add(EvidenceRecord("foreground_check", EvidenceStatus.VERIFIED, "Target app in foreground: $currentPkg"))

        // For OPEN_APP_ONLY goals (e.g. "Open Instagram", "Open WhatsApp")
        if (context.intentType == TaskIntentType.OPEN_APP_ONLY) {
            val hasObservedContent = obs.isFresh() && (obs.interactiveElements.isNotEmpty() || obs.visibleTexts.isNotEmpty() || obs.hasScreenshot)
            if (hasObservedContent) {
                records.add(EvidenceRecord("screen_content_check", EvidenceStatus.VERIFIED, "Screen content observed: ${obs.interactiveElements.size} elements, ${obs.visibleTexts.size} texts"))
                return GoalStateAssessment(
                    assessedState = GoalState.DESTINATION_VERIFIED,
                    status = EvidenceStatus.VERIFIED,
                    evidence = records,
                    isAlreadySatisfied = true
                )
            } else {
                records.add(EvidenceRecord("screen_content_check", EvidenceStatus.INFERRED, "Screen content not observed"))
                return GoalStateAssessment(
                    assessedState = GoalState.APP_READY,
                    status = EvidenceStatus.INFERRED,
                    evidence = records,
                    isAlreadySatisfied = false
                )
            }
        }

        // For COMPOUND_UI_NAVIGATION / UI_ACTION
        if (context.intentType == TaskIntentType.COMPOUND_UI_NAVIGATION || context.intentType == TaskIntentType.UI_ACTION) {
            if (context.remainingSteps.isEmpty() && context.completedSteps.isNotEmpty()) {
                records.add(EvidenceRecord("navigation_check", EvidenceStatus.VERIFIED, "All navigation steps completed: ${context.completedSteps}"))
                return GoalStateAssessment(
                    assessedState = GoalState.DESTINATION_VERIFIED,
                    status = EvidenceStatus.VERIFIED,
                    evidence = records,
                    isAlreadySatisfied = true
                )
            } else {
                records.add(EvidenceRecord("navigation_check", EvidenceStatus.OBSERVED, "Navigation in progress: remaining ${context.remainingSteps}"))
                return GoalStateAssessment(
                    assessedState = GoalState.NAVIGATION_IN_PROGRESS,
                    status = EvidenceStatus.OBSERVED,
                    evidence = records,
                    isAlreadySatisfied = false
                )
            }
        }

        // 2. Smart Already-Complete Check (DESTINATION_VERIFIED)
        if (context.isSearchGoal) {
            val destResult = DestinationVerifier.verify(obs, query, targetApp, selectedCandidate)
            if (destResult.verified && (currentPhase == GoalState.RESULT_SELECTED || currentPhase == GoalState.INIT || currentPhase == GoalState.APP_READY)) {
                records.addAll(destResult.evidence)
                Log.i(TAG, "[ASSESS] Smart state attachment: Destination already active on screen!")
                return GoalStateAssessment(
                    assessedState = GoalState.DESTINATION_VERIFIED,
                    status = EvidenceStatus.VERIFIED,
                    evidence = records,
                    isAlreadySatisfied = true
                )
            }
        }

        // 3. Search Results Check (SEARCH_RESULTS_OBSERVED)
        if (context.isSearchGoal) {
            val resultsResult = SearchResultsVerifier.verify(obs, query)
            if (resultsResult.verified) {
                records.addAll(resultsResult.evidence)
                val groundedCandidate = resultsResult.candidateElements.firstOrNull()?.let { elem ->
                    TargetGrounding(
                        observationId = obs.observationId,
                        packageName = obs.foregroundPackage,
                        bounds = elem.bounds,
                        semanticLabel = elem.displayLabel,
                        resourceId = elem.id,
                        contentDescription = elem.contentDescription,
                        isClickable = elem.isClickable
                    )
                }

                // If results are already visible on screen, attach to SEARCH_RESULTS_OBSERVED
                val canAttachToResults = currentPhase >= GoalState.SEARCH_SUBMITTED ||
                    (obs.focusedElement?.isEditable != true && resultsResult.status == EvidenceStatus.VERIFIED)

                if (canAttachToResults) {
                    Log.i(TAG, "[ASSESS] Search results active on screen: candidate='${groundedCandidate?.semanticLabel}'")
                    return GoalStateAssessment(
                        assessedState = GoalState.SEARCH_RESULTS_OBSERVED,
                        status = resultsResult.status,
                        evidence = records,
                        isAlreadySatisfied = currentPhase < GoalState.SEARCH_RESULTS_OBSERVED,
                        groundedTarget = groundedCandidate
                    )
                }
            }
        }

        // 4. Query Entered Check (QUERY_ENTERED)
        if (context.isSearchGoal) {
            val editable = obs.findEditableElement()
            val textInEditable = editable?.text?.trim() ?: ""
            if (textInEditable.contains(query, ignoreCase = true) || (query.isNotBlank() && obs.visibleTexts.any { it.equals(query, ignoreCase = true) })) {
                records.add(EvidenceRecord("query_check", EvidenceStatus.VERIFIED, "Query '$query' present in search field"))
                val targetGrounding = editable?.let {
                    TargetGrounding(
                        observationId = obs.observationId,
                        packageName = obs.foregroundPackage,
                        bounds = it.bounds,
                        semanticLabel = it.displayLabel,
                        resourceId = it.id,
                        isEditable = true
                    )
                }
                return GoalStateAssessment(
                    assessedState = GoalState.QUERY_ENTERED,
                    status = EvidenceStatus.VERIFIED,
                    evidence = records,
                    isAlreadySatisfied = currentPhase < GoalState.QUERY_ENTERED,
                    groundedTarget = targetGrounding
                )
            }
        }

        // 5. Search UI Open Check (SEARCH_UI_OPEN)
        if (context.isSearchGoal) {
            val editable = obs.findEditableElement()
            val isSearchSpecific = editable != null && (
                editable.id?.contains("search", ignoreCase = true) == true ||
                editable.contentDescription?.contains("search", ignoreCase = true) == true ||
                obs.interactiveElements.any { it.id?.contains("search_box", ignoreCase = true) == true }
            )

            if (isSearchSpecific) {
                records.add(EvidenceRecord("search_ui_check", EvidenceStatus.VERIFIED, "Search input field is open"))
                val targetGrounding = editable?.let {
                    TargetGrounding(
                        observationId = obs.observationId,
                        packageName = obs.foregroundPackage,
                        bounds = it.bounds,
                        semanticLabel = it.displayLabel,
                        resourceId = it.id,
                        isEditable = true
                    )
                }
                return GoalStateAssessment(
                    assessedState = GoalState.SEARCH_UI_OPEN,
                    status = EvidenceStatus.VERIFIED,
                    evidence = records,
                    isAlreadySatisfied = currentPhase < GoalState.SEARCH_UI_OPEN,
                    groundedTarget = targetGrounding
                )
            }
        }

        // 6. Base App Ready Check (APP_READY)
        return GoalStateAssessment(
            assessedState = GoalState.APP_READY,
            status = EvidenceStatus.VERIFIED,
            evidence = records
        )
    }
}
