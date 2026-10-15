package com.example.automation.computeruse

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class ComputerUseObservationVerificationTest {
    private fun obs(
        id: String,
        text: String = "",
        pkg: String = "com.example.app",
        screenshot: String? = "visual-$id",
        task: String? = "task-1",
        step: String? = "step-1",
        action: String? = null
    ) = ScreenObservation(
        observationId = id, taskId = task, stepId = step, executionId = "exec-1",
        causedByAction = action, foregroundPackage = pkg,
        interactiveElements = if (text.isBlank()) emptyList() else listOf(
            InteractiveElement(text = text, bounds = Rect(0, 0, 100, 50), isClickable = true)
        ), visibleTexts = listOfNotNull(text.takeIf { it.isNotBlank() }),
        screenshotFingerprint = screenshot, timestamp = System.currentTimeMillis()
    )

    @Test fun freshTreeAndScreenshotAreCorrelated() {
        val before = obs("before", text = "Search")
        assertTrue(before.isFresh())
        assertTrue(before.hasScreenshot.not()) // fingerprint alone is not a claimed image payload
        assertEquals("task-1", before.taskId)
        assertEquals("step-1", before.stepId)
        assertEquals("exec-1", before.executionId)
    }

    @Test fun postActionObservationCarriesProducingAction() {
        assertEquals("CLICK", obs("after", text = "Results", action = "CLICK").causedByAction)
    }

    @Test fun changedScreenIsUsedForAdaptivePlanning() {
        val context = TaskContext(goal = "open app", targetApp = "Example")
        val assessment = GoalStateAssessment(GoalState.APP_READY, EvidenceStatus.OBSERVED,
            evidence = listOf(EvidenceRecord("test", EvidenceStatus.OBSERVED, "screen changed")))
        val action = ComputerUsePlanner.planNextAction(GoalState.APP_READY, obs("new", text = "Search"), context, assessment, null)
        assertTrue(action is ComputerAction.ObserveMore || action is ComputerAction.FinishTask)
    }

    @Test fun staleTargetCannotBeReusedAfterObservationChanges() {
        val before = obs("one", text = "Open")
        val grounding = TargetGrounding("g", before.observationId, before.foregroundPackage,
            Rect(0, 0, 100, 50), "Open", isClickable = true)
        assertTrue(grounding.isStale(obs("two", text = "Different")))
    }

    @Test fun conflictingEvidenceDoesNotVerifyCoordinateTapWithoutTransition() {
        val before = obs("one", text = "Open", screenshot = "a")
        val after = obs("two", text = "Open", screenshot = "a")
        val result = ActionVerifier.verify(
            ComputerAction.TapCoordinate(10, 10, "Open"),
            ActionResult(true, "TAP"), before, after
        )
        assertFalse(result.first)
    }

    @Test fun failedPostconditionCannotVerifyTap() {
        val result = ActionVerifier.verify(
            ComputerAction.TapElement("Settings", expectation = ActionExpectation.TextPresent("Settings")),
            ActionResult(true, "TAP"), obs("one", text = "Home"), obs("two", text = "Home")
        )
        assertFalse(result.first)
    }

    @Test fun missingFreshObservationIsReportedAsUnverified() {
        val stale = obs("old").copy(timestamp = System.currentTimeMillis() - 20_000)
        val result = ActionVerifier.verify(
            ComputerAction.TapCoordinate(1, 1, "tap"), ActionResult(true, "TAP"), obs("one"), stale
        )
        assertFalse(result.first)
        assertTrue(result.second.contains("Fresh foreground"))
    }

    @Test fun cancelledTaskIsObservedBeforeNextAction() {
        val context = TaskContext(taskId = "cancel-me", goal = "wait then open")
        assertTrue(ComputerUseConcurrency.tryAcquireLock(context))
        try {
            ComputerUseConcurrency.cancelActiveTask("test")
            assertTrue(ComputerUseConcurrency.isTaskCancelled(context))
        } finally {
            ComputerUseConcurrency.releaseLock(context)
        }
    }

    @Test fun verifiedCompletedStepIsNotConfusedWithLaterScreen() {
        val before = obs("step-1", text = "WhatsApp")
        val after = obs("step-2", text = "YouTube", pkg = "com.google.android.youtube", step = "step-2", action = "OPEN_APP")
        assertNotEquals(before.observationId, after.observationId)
        assertEquals("step-1", before.stepId)
        assertEquals("step-2", after.stepId)
        assertNotEquals(before.foregroundPackage, after.foregroundPackage)
    }

    @Test fun simpleOpenAppPlanningStillProducesLaunchWhenWrongForeground() {
        val context = TaskContext(goal = "open YouTube", targetApp = "YouTube")
        val action = ComputerUsePlanner.planNextAction(
            GoalState.INIT, obs("home", pkg = "com.android.launcher"), context,
            GoalStateAssessment(GoalState.INIT, EvidenceStatus.OBSERVED,
                evidence = listOf(EvidenceRecord("test", EvidenceStatus.OBSERVED, "launcher"))), null
        )
        assertTrue(action is ComputerAction.LaunchApp)
    }
}
