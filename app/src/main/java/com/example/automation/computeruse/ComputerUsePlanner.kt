package com.example.automation.computeruse

import android.util.Log
import com.example.automation.AppPackageMatcher

/**
 * Deterministic single-step action planner for the Computer-Use state machine.
 * Serves as the architecture boundary for future Gemini multimodal planner integration.
 */
object ComputerUsePlanner {
    private const val TAG = "ComputerUsePlanner"

    fun planNextAction(
        currentPhase: GoalState,
        obs: ScreenObservation,
        context: TaskContext,
        assessment: GoalStateAssessment,
        selectedCandidate: TargetGrounding?
    ): ComputerAction {
        val query = context.searchQuery
        val queryKeywords = query.lowercase().split(Regex("""\s+""")).filter {
            it.length > 2 && it !in setOf("for", "and", "the", "with", "tutorial", "video", "search")
        }
        val targetApp = context.targetApp
        val targetAppLower = targetApp?.lowercase() ?: ""

        // 1. If target app is not in foreground, launch it
        if (targetAppLower.isNotBlank() && !AppPackageMatcher.matches(obs.foregroundPackage, targetAppLower)) {
            Log.i(
                TAG,
                "[DECIDE] action=LAUNCH_APP reason='Target app not in foreground' " +
                    "expected='$targetAppLower' observed='${obs.foregroundPackage}'"
            )
            return ComputerAction.LaunchApp(appName = targetApp ?: "Target App")
        }

        // 2. Terminal verification check
        if (currentPhase == GoalState.DESTINATION_VERIFIED) {
            Log.i(TAG, "[DECIDE] action=FINISH_TASK reason='Destination verified on screen'")
            val isTelugu = isTeluguGoal(context.goal)
            val displayName = targetApp ?: "App"
            val successMsg = if (isTelugu) {
                if (context.isSearchGoal) {
                    "'$query' kosam search chesi, result open chesi verify chesanu."
                } else {
                    "$displayName open ayyindi. Verify chesanu."
                }
            } else {
                if (context.isSearchGoal) {
                    "Target content for '$query' successfully opened and verified on screen."
                } else {
                    "$displayName opened and verified."
                }
            }
            return ComputerAction.FinishTask(
                success = true,
                message = successMsg
            )
        }

        // 3. Compound navigation or UI action handling
        if (context.intentType == TaskIntentType.COMPOUND_UI_NAVIGATION || context.intentType == TaskIntentType.UI_ACTION) {
            if (context.remainingSteps.isEmpty()) {
                val lastStep = context.completedSteps.lastOrNull() ?: targetApp ?: "Target"
                val isTelugu = isTeluguGoal(context.goal)
                val msg = if (isTelugu) "$lastStep open chesi verify chesanu." else "Navigated to '$lastStep' and verified."
                Log.i(TAG, "[DECIDE] action=FINISH_TASK reason='All navigation steps completed'")
                return ComputerAction.FinishTask(success = true, message = msg)
            }

            val nextTarget = context.currentTarget!!
            val stepNumber = context.currentStepIndex + (if (context.targetApp != null) 2 else 1)
            val totalSteps = context.navigationSteps.size + (if (context.targetApp != null) 1 else 0)

            Log.i(TAG, "[TASK_PROGRESS] step=$stepNumber/$totalSteps action=TapElement target=$nextTarget")

            val snapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
            val groundedNode = if (snapshot.nodes.isNotEmpty()) {
                com.example.automation.grounding.ScoredTargetGroundingEngine.groundTarget(snapshot, nextTarget).bestCandidate
            } else null

            val targetRole = groundedNode?.className
            val resId = groundedNode?.resourceId
            val desc = groundedNode?.contentDescription

            return ComputerAction.TapElement(
                targetText = nextTarget,
                resourceId = resId,
                contentDescription = desc,
                targetRole = targetRole,
                expectation = ActionExpectation.TargetVerified(nextTarget)
            )
        }

        // 4. Phase-driven action planning
        return when (currentPhase) {
            GoalState.INIT -> {
                if (obs.isAppForeground) {
                    ComputerAction.ObserveMore("App is foreground; observing UI hierarchy")
                } else if (!targetApp.isNullOrBlank()) {
                    ComputerAction.LaunchApp(appName = targetApp)
                } else {
                    ComputerAction.ObserveMore("Awaiting foreground app; observing UI hierarchy")
                }
            }

            GoalState.APP_READY -> {
                if (context.isSearchGoal) {
                    // Ground the search button
                    val searchBtn = obs.interactiveElements.firstOrNull { elem ->
                        val id = elem.id?.lowercase() ?: ""
                        val desc = elem.contentDescription?.lowercase() ?: ""
                        val text = elem.text?.lowercase() ?: ""
                        id.contains("search") || desc.contains("search") || text == "search"
                    }

                    if (searchBtn != null && searchBtn.bounds.width() > 0 && searchBtn.bounds.height() > 0) {
                        val grounding = TargetGrounding(
                            observationId = obs.observationId,
                            packageName = obs.foregroundPackage,
                            bounds = searchBtn.bounds,
                            semanticLabel = searchBtn.displayLabel,
                            resourceId = searchBtn.id,
                            contentDescription = searchBtn.contentDescription,
                            isClickable = true
                        )
                        Log.i(TAG, "[DECIDE] action=TAP_COORDINATE target='Search Button' bounds=${searchBtn.bounds}")
                        ComputerAction.TapCoordinate(
                            x = searchBtn.bounds.centerX(),
                            y = searchBtn.bounds.centerY(),
                            description = "Search Button",
                            grounding = grounding
                        )
                    } else {
                        Log.w(TAG, "[DECIDE] Search button not grounded on screen; observing more")
                        ComputerAction.ObserveMore("Searching for search icon in app UI")
                    }
                } else {
                    val hasContent = obs.isFresh() && (obs.interactiveElements.isNotEmpty() || obs.visibleTexts.isNotEmpty() || obs.hasScreenshot)
                    val isTelugu = isTeluguGoal(context.goal)
                    val displayName = targetApp ?: "App"
                    if (hasContent) {
                        ComputerAction.FinishTask(
                            success = true,
                            message = if (isTelugu) "$displayName open ayyindi. Verify chesanu." else "$displayName opened and verified."
                        )
                    } else {
                        ComputerAction.ObserveMore("Observing app UI content for verification")
                    }
                }
            }

            GoalState.SEARCH_UI_OPEN -> {
                val editable = obs.findEditableElement()
                if (editable != null && !SecureFieldProtector.isSecureElement(editable)) {
                    val grounding = TargetGrounding(
                        observationId = obs.observationId,
                        packageName = obs.foregroundPackage,
                        bounds = editable.bounds,
                        semanticLabel = editable.displayLabel,
                        resourceId = editable.id,
                        isEditable = true
                    )
                    Log.i(TAG, "[DECIDE] action=TYPE_TEXT query='$query' targetField='${editable.id}'")
                    ComputerAction.TypeText(
                        text = query,
                        targetField = editable.id ?: "search_edit_text",
                        pressEnterAfter = true,
                        clearExisting = true,
                        grounding = grounding
                    )
                } else {
                    Log.w(TAG, "[DECIDE] Editable search field not found; requesting more observation")
                    ComputerAction.ObserveMore("Searching for active search edit text")
                }
            }

            GoalState.QUERY_ENTERED -> {
                Log.i(TAG, "[DECIDE] action=PRESS_ENTER reason='Submitting entered query'")
                ComputerAction.PressEnter
            }

            GoalState.SEARCH_SUBMITTED -> {
                // Wait for results to render or scroll down if needed
                Log.i(TAG, "[DECIDE] action=SCROLL reason='Discovering search results list'")
                ComputerAction.Scroll(direction = "down")
            }

            GoalState.SEARCH_RESULTS_OBSERVED -> {
                // Candidate selection from grounded assessment or observation
                val candidate = assessment.groundedTarget ?: run {
                    val resResult = SearchResultsVerifier.verify(obs, query)
                    resResult.candidateElements.firstOrNull()?.let { elem ->
                        TargetGrounding(
                            observationId = obs.observationId,
                            packageName = obs.foregroundPackage,
                            bounds = elem.bounds,
                            semanticLabel = elem.displayLabel,
                            resourceId = elem.id,
                            contentDescription = elem.contentDescription,
                            isClickable = true
                        )
                    }
                }

                if (candidate != null && !candidate.isStale(obs)) {
                    Log.i(TAG, "[DECIDE] action=TAP_COORDINATE candidate='${candidate.semanticLabel}' bounds=${candidate.bounds}")
                    ComputerAction.TapCoordinate(
                        x = candidate.bounds.centerX(),
                        y = candidate.bounds.centerY(),
                        description = "Search Result: ${candidate.semanticLabel}",
                        grounding = candidate
                    )
                } else {
                    Log.i(TAG, "[DECIDE] action=SCROLL reason='No fresh candidate grounded; scrolling down'")
                    ComputerAction.Scroll(direction = "down")
                }
            }

            GoalState.RESULT_SELECTED -> {
                Log.i(TAG, "[DECIDE] action=OBSERVE_MORE reason='Awaiting destination video/profile screen settlement'")
                ComputerAction.Wait(durationMs = 800L, reason = "Waiting for destination screen to settle")
            }

            GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED -> {
                ComputerAction.FinishTask(
                    success = currentPhase == GoalState.COMPLETED,
                    message = "Task reached terminal state: $currentPhase"
                )
            }

            else -> {
                ComputerAction.ObserveMore("Observing screen in phase: $currentPhase")
            }
        }
    }
}
