package com.example.automation

import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ActionAuthorizer.ApprovalDecision
import com.example.automation.engine.ActionAuthorizer.ApprovalMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-2 regression: unlock must NEVER silently auto-execute in ANY approval
 * mode (MANUAL / SAFE_AUTO / FULL_AUTO), even with a direct-looking command.
 * Offline, voice, and plan paths must all converge on the explicit
 * confirmation boundary. Hard-security hard-blocks must remain intact.
 */
class UnlockSecurityUnitTest {

    @After
    fun tearDown() {
        ActionAuthorizer.setCautionAllowlist(emptySet())
    }

    private fun decisionFor(mode: ApprovalMode, action: String, intent: String? = null): ApprovalDecision {
        return ActionAuthorizer.evaluateApproval(mode, action, intent).decision
    }

    // --- UNLOCK always requires confirmation ---

    @Test
    fun `MANUAL plus UNLOCK requires confirmation`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.MANUAL, "UNLOCK_DEVICE", "unlock my phone"))
    }

    @Test
    fun `SAFE_AUTO plus UNLOCK requires confirmation even with direct command`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "UNLOCK_DEVICE", "unlock my phone"))
    }

    @Test
    fun `FULL_AUTO plus UNLOCK requires confirmation even with direct command`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.FULL_AUTO, "UNLOCK_DEVICE", "unlock my phone"))
    }

    @Test
    fun `UNLOCK with no user intent still requires confirmation`() {
        // LLM direct command without an on-the-record intent must not bypass.
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "UNLOCK_DEVICE", null))
    }

    @Test
    fun `UNLOCK with allowlisted caution entry still requires confirmation`() {
        // CAUTION allowlist must never re-enable silent unlock.
        ActionAuthorizer.setCautionAllowlist(setOf("UNLOCK_DEVICE"))
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "UNLOCK_DEVICE", "unlock my phone"))
    }

    // --- Unrelated behavior preserved ---

    @Test
    fun `unrelated SAFE action remains auto-approved under SAFE_AUTO`() {
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.SAFE_AUTO, "GET_BATTERY_STATUS", "check battery"))
    }

    @Test
    fun `unrelated SAFE action remains auto-approved under FULL_AUTO`() {
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.FULL_AUTO, "FLASHLIGHT_ON", "turn on flashlight"))
    }

    // --- CRITICAL hard-blocks stay intact (UNLOCK exemption did not weaken them) ---

    @Test
    fun `CRITICAL shell action hard-blocked in every mode`() {
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.MANUAL, "RUN_ADB_COMMAND", "run adb command"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.SAFE_AUTO, "RUN_ADB_COMMAND"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "RUN_ADB_COMMAND"))
    }

    @Test
    fun `payment action hard-blocked in every mode`() {
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.MANUAL, "SEND_PAYMENT", "send payment"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.SAFE_AUTO, "SEND_PAYMENT", "send payment"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "SEND_PAYMENT", "send payment"))
    }

    // --- Plan-gate regression: unlock can never be a planned autonomous step ---

    @Test
    fun `validatePlan rejects autonomous unlock step`() {
        val plan = listOf(
            com.example.automation.state.ActionStep(
                stepId = "step_1",
                actionType = "UNLOCK_DEVICE"
            )
        )
        val error = com.example.automation.engine.GoalPlanner.validatePlan(plan, "unlock my phone")
        assertEquals(
            true,
            error != null && error.contains("requires explicit user confirmation")
        )
    }
}