package com.example.automation

import android.graphics.Rect
import com.example.automation.computeruse.ActionResult
import com.example.automation.computeruse.ActionVerifier
import com.example.automation.computeruse.ComputerAction
import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.computeruse.ComputerUsePlanner
import com.example.automation.computeruse.EvidenceStatus
import com.example.automation.computeruse.GoalState
import com.example.automation.computeruse.GoalStateAssessment
import com.example.automation.computeruse.InteractiveElement
import com.example.automation.computeruse.ScreenObservation
import com.example.automation.computeruse.TaskContext
import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.models.UiStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleMapsAutomationRegressionTest {
    private val mapsPackage = "com.google.android.apps.maps"

    @Test
    fun displayNameAndPackageAreEquivalentWithoutRelaunchingMaps() {
        val searchControl = InteractiveElement(
            id = "$mapsPackage:id/search_omnibox_text_box",
            contentDescription = "Search here",
            bounds = Rect(10, 20, 600, 100),
            isClickable = true
        )
        val observation = ScreenObservation(
            foregroundPackage = mapsPackage,
            interactiveElements = listOf(searchControl),
            timestamp = System.currentTimeMillis()
        )
        val context = TaskContext(
            goal = "Open Google Maps and search Palakollu to Hyderabad",
            targetApp = "Google Maps",
            searchQuery = "Palakollu to Hyderabad",
            isSearchGoal = true
        )
        assertTrue(context.isSearchGoal)
        assertTrue(observation.interactiveElements.first().id!!.contains("search"))
        assertTrue(observation.interactiveElements.first().bounds.width() > 0)

        val action = ComputerUsePlanner.planNextAction(
            currentPhase = GoalState.APP_READY,
            obs = observation,
            context = context,
            assessment = GoalStateAssessment(GoalState.APP_READY, EvidenceStatus.VERIFIED, emptyList()),
            selectedCandidate = null
        )

        assertFalse("Maps must not be relaunched when its package is foreground", action is ComputerAction.LaunchApp)
        assertTrue("Expected search tap, got $action", action is ComputerAction.TapCoordinate)
    }

    @Test
    fun enterDispatchWithoutObservedSubmissionCannotVerify() {
        val before = ScreenObservation(foregroundPackage = mapsPackage, visibleTexts = listOf("Palakollu"))
        val after = before.copy(visibleTexts = listOf("Directions"), observationId = "after")

        val unverified = ActionResult(success = true, actionType = "PRESS_ENTER")
        val verified = unverified.copy(submissionVerified = true)

        assertFalse(ActionVerifier.verify(ComputerAction.PressEnter, unverified, before, after).first)
        assertTrue(ActionVerifier.verify(ComputerAction.PressEnter, verified, before, after).first)
    }

    @Test
    fun typeAndSubmitDoesNotRequireEditableFieldAfterResultsRender() {
        val before = ScreenObservation(
            foregroundPackage = mapsPackage,
            editableElements = listOf(
                InteractiveElement(id = "$mapsPackage:id/search_omnibox_text_box", text = "Palakollu", isEditable = true)
            )
        )
        val after = before.copy(
            observationId = "results",
            editableElements = emptyList(),
            visibleTexts = listOf("Directions", "Start")
        )
        val action = ComputerAction.TypeText("Palakollu", pressEnterAfter = true)

        assertTrue(
            ActionVerifier.verify(
                action,
                ActionResult(success = true, actionType = action.actionName, submissionVerified = true),
                before,
                after
            ).first
        )
        assertFalse(
            ActionVerifier.verify(
                action,
                ActionResult(success = true, actionType = action.actionName),
                before,
                after
            ).first
        )
    }

    @Test
    fun locationPermissionPromptIsTerminalUserActionRequiredState() {
        val observation = ScreenObservation(
            foregroundPackage = "com.google.android.permissioncontroller",
            visibleTexts = listOf(
                "Allow MAX to access this device's location?",
                "While using the app",
                "Precise"
            )
        )

        assertEquals(
            "while using the app",
            ComputerUseEngine.findBlockingPermissionPrompt(observation)
        )
    }

    @Test
    fun mapsPlanScopesEveryUiOutcomeToMapsPackage() {
        val task = TaskPlanner().createPlan(
            originalUtterance = "Search Palakollu to Hyderabad in Google Maps",
            taskIntent = TaskIntent(
                goal = "Search Palakollu to Hyderabad in Google Maps",
                parameters = mapOf("destination" to "Palakollu to Hyderabad")
            )
        )

        assertEquals(5, task.steps.size)
        task.steps.drop(1).mapNotNull { it.expectedOutcome }.forEach { outcome ->
            when (outcome) {
                is ExpectedOutcome.ElementPresent -> assertEquals(mapsPackage, outcome.expectedPackage)
                is ExpectedOutcome.TextContentEquals -> assertEquals(mapsPackage, outcome.expectedPackage)
                else -> Unit
            }
        }
    }

    @Test
    fun threeSearchPlansKeepQueriesIndependentAndOrdered() {
        val queries = listOf(
            "Palakollu to Bhimavaram",
            "Palakollu to Hyderabad",
            "Narsapur to Hyderabad"
        )
        val planner = TaskPlanner()

        val typedQueries = queries.map { query ->
            val task = planner.createPlan(
                originalUtterance = "Search $query in Google Maps",
                taskIntent = TaskIntent(
                    goal = "Search $query in Google Maps",
                    parameters = mapOf("destination" to query)
                )
            )
            (task.steps[2].action as UiStep.TypeText).text
        }

        assertEquals(queries, typedQueries)
    }

    @Test
    fun packageMatcherIsStrictForMapsAndSupportsDisplayLabel() {
        assertTrue(AppPackageMatcher.matches(mapsPackage, "Google Maps"))
        assertTrue(AppPackageMatcher.matches(mapsPackage, mapsPackage))
        assertFalse(AppPackageMatcher.matches("com.google.android.apps.maps.fake", "Google Maps"))
        assertFalse(AppPackageMatcher.matches("com.android.chrome", "Google Maps"))
    }
}
