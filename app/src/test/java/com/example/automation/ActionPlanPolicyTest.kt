package com.example.automation

import com.example.automation.engine.planning.ActionPlanPolicy
import com.example.automation.state.ActionStep
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlanPolicyTest {
    @Test
    fun rejectsUnknownAction() {
        val error = ActionPlanPolicy.validate(listOf(ActionStep("s1", "RUN_SHELL")))
        assertTrue(error?.contains("Unsupported actionType") == true)
    }

    @Test
    fun rejectsDuplicateIds() {
        val error = ActionPlanPolicy.validate(listOf(
            ActionStep("s1", "OPEN_APP", targetText = "Settings"),
            ActionStep("s1", "PRESS_BACK")
        ))
        assertTrue(error?.contains("Duplicate stepId") == true)
    }

    @Test
    fun rejectsMissingDependency() {
        val error = ActionPlanPolicy.validate(listOf(
            ActionStep("s1", "OPEN_APP", targetText = "Settings", dependsOn = listOf("missing"))
        ))
        assertTrue(error?.contains("unknown stepId") == true)
    }

    @Test
    fun rejectsOversizedPlanAndUnsafeArguments() {
        val oversized = (1..19).map { ActionStep("s$it", "PRESS_BACK") }
        assertTrue(ActionPlanPolicy.validate(oversized)?.contains("exceeds maximum") == true)

        val invalidPackage = ActionPlanPolicy.validate(listOf(
            ActionStep("s1", "OPEN_APP", targetText = "not a package", params = mapOf("package" to "not a package"))
        ))
        assertTrue(invalidPackage?.contains("Invalid package") == true)

        val invalidCoordinates = ActionPlanPolicy.validate(listOf(
            ActionStep("s1", "TAP", fallbackX = 2f, fallbackY = 0.5f)
        ))
        assertTrue(invalidCoordinates?.contains("coordinate") == true)
    }
}
