package com.example.automation

import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ActionAuthorizer.ApprovalDecision
import com.example.automation.engine.ActionAuthorizer.ApprovalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiSafetyUnitTest {

    @Test
    fun testNeverAutoCriticalKeywords() {
        assertTrue(ActionAuthorizer.isNeverAutoAction("MAKE_UPI_PAYMENT"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("ENTER_PIN"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("ENTER_PASSWORD"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("CONFIRM_PAYMENT"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("FACTORY_RESET"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("WIPE_DATA"))
        assertTrue(ActionAuthorizer.isNeverAutoAction("UNLOCK_DEVICE"))

        // Standard actions must NOT be never-auto
        assertFalse(ActionAuthorizer.isNeverAutoAction("CLICK_ELEMENT"))
        assertFalse(ActionAuthorizer.isNeverAutoAction("TYPE_TEXT"))
        assertFalse(ActionAuthorizer.isNeverAutoAction("PRESS_ENTER"))
        assertFalse(ActionAuthorizer.isNeverAutoAction("OPEN_APP"))
    }

    @Test
    fun testUnlockRequiresConfirmationInEveryMode() {
        assertTrue(ActionAuthorizer.requiresEveryModeConfirmation("UNLOCK_DEVICE"))
        assertTrue(ActionAuthorizer.requiresEveryModeConfirmation("UNLOCK"))

        val evaluation = ActionAuthorizer.evaluateApproval(
            mode = ApprovalMode.FULL_AUTO,
            actionType = "UNLOCK_DEVICE",
            userIntent = "unlock the phone"
        )
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, evaluation.decision)
    }

    @Test
    fun testPaymentBlockedFromAutonomousExecution() {
        val eval = ActionAuthorizer.evaluateApproval(
            mode = ApprovalMode.FULL_AUTO,
            actionType = "CONFIRM_PAYMENT",
            userIntent = null
        )
        assertEquals(ApprovalDecision.BLOCKED, eval.decision)
    }

    @Test
    fun testSafeActionsAutoApprovedInSafeAuto() {
        val eval = ActionAuthorizer.evaluateApproval(
            mode = ApprovalMode.SAFE_AUTO,
            actionType = "CLICK_ELEMENT",
            userIntent = "click search bar",
            target = "search bar"
        )
        assertEquals(ApprovalDecision.APPROVED, eval.decision)
    }

    @Test
    fun testDestructiveActionsNotAuthorizedWithoutIntent() {
        val authResult = ActionAuthorizer.authorizeAction(
            userIntent = "",
            actionType = "DELETE_CONTACT"
        )
        assertFalse(authResult.authorized)
    }
}
