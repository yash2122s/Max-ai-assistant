package com.example.automation

import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ActionAuthorizer.ApprovalDecision
import com.example.automation.engine.ActionAuthorizer.ApprovalMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.2 — Approval policy matrix. Verifies the exact spec table and the
 * invariant that CRITICAL actions are never bypassed in any mode.
 */
class ApprovalPolicyUnitTest {

    @After
    fun tearDown() {
        ActionAuthorizer.setCautionAllowlist(emptySet())
    }

    private fun decisionFor(mode: ApprovalMode, action: String, intent: String? = null): ApprovalDecision {
        return ActionAuthorizer.evaluateApproval(mode, action, intent).decision
    }

    // --- MANUAL ---
    @Test
    fun `MANUAL plus SAFE requires confirmation`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.MANUAL, "OPEN_APP", "open whatsapp"))
    }

    @Test
    fun `MANUAL plus CAUTION requires confirmation`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.MANUAL, "SEND_SMS", "send sms to mom"))
    }

    @Test
    fun `MANUAL plus CRITICAL is blocked without a direct command`() {
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.MANUAL, "RUN_ADB_COMMAND"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.MANUAL, "SEND_PAYMENT", "send payment"))
    }

    // --- SAFE_AUTO ---
    @Test
    fun `SAFE_AUTO plus SAFE auto-approves`() {
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.SAFE_AUTO, "OPEN_APP", "open whatsapp"))
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.SAFE_AUTO, "GET_BATTERY_STATUS", "check battery"))
    }

    @Test
    fun `SAFE_AUTO plus CAUTION requires confirmation`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "SEND_SMS", "send sms"))
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "SEND_WHATSAPP_MESSAGE", "send whatsapp"))
    }

    @Test
    fun `SAFE_AUTO plus allowlisted external side effect still confirms`() {
        ActionAuthorizer.setCautionAllowlist(setOf("SEND_SMS"))
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.SAFE_AUTO, "SEND_SMS", "send sms"))
    }

    @Test
    fun `SAFE_AUTO plus CRITICAL is blocked without a direct command`() {
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.SAFE_AUTO, "RUN_ADB_COMMAND"))
        // Chatter that happens to mention the action name is NOT a direct command.
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.SAFE_AUTO, "RUN_ADB_COMMAND", "tell me a joke"))
    }

    @Test
    fun `CRITICAL remains blocked even under an explicit direct command`() {
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.SAFE_AUTO, "RUN_ADB_COMMAND", "run adb command pm list packages"))
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.FULL_AUTO, "WINDOWS_AGENT", "open chrome on my pc"))
    }

    // --- FULL_AUTO ---
    @Test
    fun `FULL_AUTO plus SAFE auto-approves`() {
        assertEquals(ApprovalDecision.APPROVED, decisionFor(ApprovalMode.FULL_AUTO, "FLASHLIGHT_ON", "flashlight on"))
    }

    @Test
    fun `FULL_AUTO plus CAUTION requires confirmation when policy disallows`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, decisionFor(ApprovalMode.FULL_AUTO, "SEND_SMS", "send sms"))
    }

    @Test
    fun `FULL_AUTO cannot bypass confirmation for external side effects`() {
        assertEquals(ApprovalDecision.NEEDS_CONFIRMATION, ActionAuthorizer.evaluateApproval(
            ApprovalMode.FULL_AUTO, "SEND_SMS", "send sms", allowCautionAutoInFullAuto = true
        ).decision)
    }

    @Test
    fun `FULL_AUTO never bypasses CRITICAL or hard security domains`() {
        // No user intent at all → blocked.
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "RUN_ADB_COMMAND"))
        // Hard security domains are ALWAYS blocked, even with an explicit command.
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "SEND_PAYMENT", "send payment of 500"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "UPI_PIN_ENTRY", "enter upi pin 1234"))
        assertEquals(ApprovalDecision.BLOCKED, decisionFor(ApprovalMode.FULL_AUTO, "FACTORY_RESET", "factory reset the phone"))
    }

    // --- Security invariants ---
    @Test
    fun `never-auto security domain actions are blocked in every mode`() {
        // Autonomous context (no user intent) must never auto-approve CRITICAL actions.
        for (mode in ApprovalMode.values()) {
            assertEquals("mode $mode", ApprovalDecision.BLOCKED, decisionFor(mode, "SEND_PAYMENT"))
            assertEquals("mode $mode", ApprovalDecision.BLOCKED, decisionFor(mode, "RUN_ADB_COMMAND"))
            assertEquals("mode $mode", ApprovalDecision.BLOCKED, decisionFor(mode, "CREDENTIAL_STORE"))
        }
    }

    @Test
    fun `existing authorization still enforced separately`() {
        // evaluateApproval only decides auto-run; authorizeAction remains the gate.
        val denied = ActionAuthorizer.authorizeAction("tell me a joke", "OPEN_APP", "WhatsApp")
        assertTrue(!denied.authorized)
        val allowed = ActionAuthorizer.authorizeAction("open whatsapp", "OPEN_APP", "WhatsApp")
        assertTrue(allowed.authorized)
    }
}
