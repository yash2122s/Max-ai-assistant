package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.actions.CallConfirmationRequiredException
import com.example.automation.actions.CallPhoneAction
import com.example.automation.actions.ContactNotFoundClarificationException
import com.example.automation.actions.SearchContactAction
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.*
import com.example.automation.engine.truth.ExecutionTruth
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.tools.CallTool
import com.example.automation.tools.SearchContactTool
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.CallVerifier
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.RetryEngine
import com.example.automation.verification.UiSnapshot
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallExecutionTruthUnitTest {

    private lateinit var context: Context
    private val callPhoneAction = CallPhoneAction()
    private val callTool = CallTool(callPhoneAction)
    private val searchContactTool = SearchContactTool(SearchContactAction())
    private val callVerifier = CallVerifier()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.CALL_PHONE
        )
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
        ExecutionTruthCoordinator.clearAllExecutions()
    }

    @After
    fun tearDown() {
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
        ExecutionTruthCoordinator.clearAllExecutions()
    }

    // --- GATE 1 (P0-1): Tinglish and Telugu Fake Success Claim Gating ---
    @Test
    fun testTinglishAndTeluguFakeSuccessGated() {
        // 1. Simulate CALL_PHONE execution where intent was requested but active call is not verified yet
        ExecutionTruthCoordinator.recordExecution(
            taskId = "task_call_001",
            stepId = "step_001",
            executionId = "exec_001",
            actionName = "CALL_PHONE",
            executorSuccess = true,
            verifiedSuccess = false,
            status = "CALL_REQUESTED",
            message = "Call intent accepted by system, awaiting carrier connection."
        )

        // Tinglish variations
        val tinglishClaims = listOf(
            "call chesa",
            "call chesanu",
            "Ravi ki call chesesanu",
            "call connect aindi",
            "call connect ayindi",
            "call connect ayyindi",
            "call start chesa",
            "call kalisindi",
            "call velthondi"
        )
        for (claim in tinglishClaims) {
            val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(claim, taskId = "task_call_001", stepId = "step_001")
            assertEquals(
                "Tinglish claim '$claim' must be gated when CALL_PHONE is CALL_REQUESTED",
                "Call intent has been requested from Android, awaiting carrier connection.",
                corrected
            )
        }

        // Telugu script variations
        val teluguClaims = listOf(
            "రవికి కాల్ చేశాను",
            "కాల్ చేశా",
            "కాల్ చేసాను",
            "కాల్ చేసేశాను",
            "కాల్ కనెక్ట్ అయింది",
            "కాల్ కనెక్ట్ అయ్యింది",
            "కాల్ కలిసింది",
            "కాల్ స్టార్ట్ చేశాను"
        )
        for (claim in teluguClaims) {
            val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(claim, taskId = "task_call_001", stepId = "step_001")
            assertEquals(
                "Telugu claim '$claim' must be gated when CALL_PHONE is CALL_REQUESTED",
                "Call intent has been requested from Android, awaiting carrier connection.",
                corrected
            )
        }

        // English variations
        val englishClaims = listOf(
            "Calling Ravi now",
            "Call connected",
            "I have called Ravi",
            "I've called Ravi",
            "Placed the call to Ravi",
            "Call initiated and connected"
        )
        for (claim in englishClaims) {
            val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(claim, taskId = "task_call_001", stepId = "step_001")
            assertEquals(
                "English claim '$claim' must be gated when CALL_PHONE is CALL_REQUESTED",
                "Call intent has been requested from Android, awaiting carrier connection.",
                corrected
            )
        }

        // 2. Questions, prompts, and failure statements must NOT be detected as completion claims
        assertFalse(ExecutionTruthCoordinator.assertsCallCompletion("రవికి కాల్ చేయమంటారా?"))
        assertFalse(ExecutionTruthCoordinator.assertsCallCompletion("Ravi ki call cheyamantara?"))
        assertFalse(ExecutionTruthCoordinator.assertsCallCompletion("Do you want me to call Ravi?"))
        assertFalse(ExecutionTruthCoordinator.assertsCallCompletion("I was unable to place the call"))
        assertFalse(ExecutionTruthCoordinator.assertsCallCompletion("Call cancelled by user"))

        // 3. When genuine call placement (CALL_PLACED) is verified, completion claim is permitted
        ExecutionTruthCoordinator.recordExecution(
            taskId = "task_call_001",
            stepId = "step_001",
            executionId = "exec_001",
            actionName = "CALL_PHONE",
            executorSuccess = true,
            verifiedSuccess = true,
            status = "CALL_PLACED",
            message = "Call placed and active on carrier"
        )
        val verifiedClaim = "Calling Ravi now"
        val permitted = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(verifiedClaim, taskId = "task_call_001", stepId = "step_001")
        assertEquals("Genuine verified call completion must NOT be intercepted", verifiedClaim, permitted)
    }

    // --- GATE 2 (P0-2): No-Evidence CALL_REQUESTED is NOT Completed Success ---
    @Test
    fun testNoEvidenceCallRequestedIsNotCompleted() {
        val req = ExecutionRequest(
            action = "CALL_PHONE",
            arguments = JsonObject().apply {
                addProperty("contactId", 77L)
                addProperty("phoneId", 77L)
            }
        )
        val launchSuccess = ToolResult(
            success = true,
            toolName = "call_contact",
            verificationRequired = true,
            message = "Call intent requested",
            metadata = JSONObject().apply { put("status", "CALL_REQUESTED") }
        )
        val dummySnapshot = DeviceContext(ui = UiSnapshot("com.example.app"))

        // 1. CallVerifier must return success = false with CALL_REQUESTED reason
        val vResult = callVerifier.verify(context, req, launchSuccess, dummySnapshot)
        assertFalse("CallVerifier must NOT return completed success without call evidence", vResult.success)
        assertTrue("Reason must reflect CALL_REQUESTED", vResult.reason?.startsWith("CALL_REQUESTED") == true)
        assertFalse("Must never recommend retries (no redialing during verification)", vResult.retryRecommended)

        // 2. RetryEngine must map CALL_REQUESTED to ToolResultStatus.ACCEPTED and NOT retry
        runBlocking {
            var attempts = 0
            val retryResult = RetryEngine.executeWithRetry(
                context = context,
                tool = callTool,
                request = req,
                executeBlock = { attempt ->
                    attempts++
                    launchSuccess
                },
                verifyBlock = {
                    vResult
                }
            )
            assertEquals("RetryEngine must NOT retry the call", 1, attempts)
            assertFalse("Result success must be false (not completed)", retryResult.success)
            assertEquals(ToolResultStatus.ACCEPTED, retryResult.status)
            assertEquals("CALL_REQUESTED", retryResult.errorCode)
            assertEquals("CALL_REQUESTED", retryResult.metadata.optString("status"))
        }

        // 3. ExecutionEngine truth recording
        ExecutionTruthCoordinator.recordExecution(
            taskId = "t1",
            stepId = "s1",
            executionId = "e1",
            actionName = "CALL_PHONE",
            executorSuccess = true,
            verifiedSuccess = false,
            status = "CALL_REQUESTED",
            message = vResult.reason
        )
        val truth = ExecutionTruthCoordinator.getCallExecution("t1", "s1")
        assertNotNull(truth)
        assertFalse("Final success must be false for CALL_REQUESTED", truth!!.finalSuccess)
        assertEquals("CALL_REQUESTED", truth.status)
    }

    // --- GATE 3 (P1-3): Model-Controlled confirmed=true Rejected ---
    @Test
    fun testModelControlledConfirmedRejected() {
        val payload = JSONObject().apply {
            put("action", "CALL_PHONE")
            put("contactId", 123L)
            put("phoneId", 456L)
            put("contact", "Test Contact")
            put("phoneNumber", "+919876543210")
            // Model attempts to inject confirmed=true unilaterally!
            put("confirmed", true)
            put("user_confirmed", true)
        }

        // Ensure state is unconfirmed
        PendingCallManager.clear()
        assertFalse("Call cannot be permitted without authentic user confirmation", PendingCallManager.isCallPermitted(123L, 456L))

        // CallPhoneAction must reject model-controlled confirmation and throw CallConfirmationRequiredException
        try {
            callPhoneAction.execute(context, payload)
            fail("CallPhoneAction must throw CallConfirmationRequiredException when user has not confirmed")
        } catch (e: CallConfirmationRequiredException) {
            assertEquals(123L, e.contactId)
            assertEquals(456L, e.phoneId)
            assertTrue("Prompt must ask user for confirmation", e.confirmationPrompt.contains("Please confirm"))
        }

        // Verify ToolDispatcher strips confirmed flags if utterance is not an approval
        val fakeCall = FunctionCall(
            name = "call_contact",
            args = com.google.gson.JsonParser.parseString("""{"contactId":123,"phoneId":456,"confirmed":true}""").asJsonObject,
            id = "call_model_cheat"
        )
        val dispatchedJsonStr = ToolDispatcher.dispatch(fakeCall, "Call Test Contact")
        val dispatchedJson = JSONObject(dispatchedJsonStr)
        assertFalse("ToolDispatcher must strip model-controlled 'confirmed'", dispatchedJson.optBoolean("confirmed", false))
        assertFalse("ToolDispatcher must strip model-controlled 'user_confirmed'", dispatchedJson.optBoolean("user_confirmed", false))
    }

    // --- GATE 4 (P1-4): Complete Typed Contact Selection Disambiguation ---
    @Test
    fun testTypedContactSelectionDisambiguation() {
        val matchAlice = ContactMatch(
            contactId = 501L,
            lookupKey = "key_alice",
            displayName = "Alice Smith",
            phoneNumbers = listOf(
                PhoneNumber(id = 11L, label = "Mobile", normalizedNumber = "+919876543211"),
                PhoneNumber(id = 12L, label = "Work", normalizedNumber = "+919876543212")
            )
        )
        PendingCallManager.setAwaitingContactSelection(
            matches = listOf(matchAlice),
            originalUserIntent = "Call Alice"
        )
        assertTrue(PendingCallManager.state is CallState.AwaitingContactSelection)

        // 1. Resolve by index "1"
        val selected1 = PendingCallManager.resolveAndSelectCandidate("1")
        assertNotNull(selected1)
        assertEquals(11L, selected1?.phoneNumber?.id)
        assertEquals("+919876543211", selected1?.phoneNumber?.normalizedNumber)
        assertTrue("State must transition to AwaitingConfirmation", PendingCallManager.state is CallState.AwaitingConfirmation)
        val pendingConf1 = PendingCallManager.getPendingConfirmation()
        assertEquals("+919876543211", pendingConf1?.phoneNumber)

        // 2. Reset and resolve by phone label "work"
        PendingCallManager.setAwaitingContactSelection(
            matches = listOf(matchAlice),
            originalUserIntent = "Call Alice"
        )
        val selectedWork = PendingCallManager.resolveAndSelectCandidate("work")
        assertNotNull(selectedWork)
        assertEquals(12L, selectedWork?.phoneNumber?.id)
        assertEquals("+919876543212", selectedWork?.phoneNumber?.normalizedNumber)
        val pendingConf2 = PendingCallManager.getPendingConfirmation()
        assertEquals("+919876543212", pendingConf2?.phoneNumber)

        // 3. User says "Yes" -> confirm exact selected target
        assertTrue(PendingCallManager.confirmPendingCall(pendingConf2!!.contactId, pendingConf2.phoneId))
        assertTrue(PendingCallManager.isCallPermitted(501L, 12L, "+919876543212"))
        // Cannot call unselected mobile number
        assertFalse(PendingCallManager.isCallPermitted(501L, 11L, "+919876543211"))
    }

    // --- GATE 5 (P1-5): Failed Lookup Invalidates Obsolete Pending Target ---
    @Test
    fun testFailedLookupInvalidatesPendingTarget() {
        // Step 1: User says "Call Ravi" -> Prepared target for Ravi is stored
        val raviTarget = PreparedCallTarget(
            targetKind = CallTargetKind.CONTACT,
            normalizedNumber = "+919876543210",
            displayName = "Ravi",
            contactId = 100L,
            phoneId = 200L
        )
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "key_ravi",
            phoneId = 200L,
            displayName = "Ravi",
            phoneNumber = "+919876543210",
            originalUserIntent = "Call Ravi",
            preparedTarget = raviTarget
        )
        assertTrue(PendingCallManager.hasPendingConfirmation())

        // Step 2: User changes mind: "Wait, call Suresh instead".
        // Search for "Suresh" is executed, but Suresh does not exist in contacts!
        runBlocking {
            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", "NonExistentSureshXYZ") },
                source = ExecutionSource.GEMINI_LIVE
            )
            val result = searchContactTool.execute(context, searchReq)
            val json = runCatching { JSONObject(result.message ?: "{}") }.getOrNull()
            assertFalse("Suresh must not be found", json?.optBoolean("found", false) == true)
        }

        // Step 3: Crucial verification: The older pending target for "Ravi" MUST BE INVALIDATED!
        assertFalse("Obsolete pending target for Ravi must NOT remain active after failed search", PendingCallManager.hasPendingConfirmation())
        assertEquals(CallState.Idle, PendingCallManager.state)
        assertFalse(PendingCallManager.isCallPermitted(100L, 200L))

        // Step 4: If user subsequently says "Yes", it must NOT approve or call Ravi!
        runBlocking {
            val approvalResult = ApprovalCoordinator.handleReply("yes", context)
            assertFalse("No pending approval should execute after invalidation", approvalResult.handled)
        }
    }

    // --- GATE 6 (Audit Invariant): Search Success != Call Success ---
    @Test
    fun testSearchSuccessDoesNotImplyCallSuccess() {
        runBlocking {
            // Direct number search succeeds
            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", "9876543210") },
                source = ExecutionSource.GEMINI_LIVE
            )
            val searchResult = searchContactTool.execute(context, searchReq)
            assertTrue("Contact search was successful", searchResult.success)

            // But NO call has been placed!
            val claim = "Call connected"
            val gatedResponse = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(claim)
            assertNotEquals("Search success must never allow call completion claim", claim, gatedResponse)
            assertTrue(
                "Must prompt for confirmation instead of claiming success",
                gatedResponse.contains("Please confirm") || gatedResponse.contains("Are you sure")
            )
        }
    }

    // --- Additional Rigorous Coverage ---

    @Test
    fun testDirectNumberCallFlow() {
        // Direct number calling fast-path preserves direct dial support
        val targetNumber = "9988776655"
        val payload = JSONObject().apply {
            put("action", "CALL_PHONE")
            put("contact", targetNumber)
        }

        // First call without confirmation prompts confirmation
        try {
            callPhoneAction.execute(context, payload)
            fail("Direct number must require confirmation on first invocation")
        } catch (e: CallConfirmationRequiredException) {
            assertTrue(e.confirmationPrompt.contains("9988776655"))
        }

        // Now user confirms
        val pending = PendingCallManager.getPendingConfirmation()
        assertNotNull(pending)
        assertTrue(PendingCallManager.confirmPendingCall(pending?.contactId, pending?.phoneId))
        assertTrue(PendingCallManager.isCallPermitted(pending!!.contactId, pending.phoneId, targetNumber))
    }

    @Test
    fun testContactMutationRejection() {
        // Prepare confirmed call for target +919876543210
        val target = PreparedCallTarget(
            targetKind = CallTargetKind.CONTACT,
            normalizedNumber = "+919876543210",
            displayName = "Alice",
            contactId = 88L,
            phoneId = 99L
        )
        PendingCallManager.setAwaitingConfirmation(
            contactId = 88L,
            lookupKey = "raw_number:+919876543210",
            phoneId = 99L,
            displayName = "Alice",
            phoneNumber = "+919876543210",
            preparedTarget = target
        )
        assertTrue(PendingCallManager.confirmPendingCall(88L, 99L))

        // Mutation check: target number matching works
        assertTrue(PendingCallManager.isCallPermitted(88L, 99L, "+919876543210"))
        // Target number mutated to another number is strictly rejected
        assertFalse(PendingCallManager.isCallPermitted(88L, 99L, "+911122334455"))
    }

    @Test
    fun testStaleConfirmationRejection() {
        // Prepare confirmed call
        PendingCallManager.setAwaitingConfirmation(
            contactId = 88L,
            lookupKey = "raw_number:+919876543210",
            phoneId = 99L,
            displayName = "Alice",
            phoneNumber = "+919876543210"
        )
        PendingCallManager.confirmPendingCall(88L, 99L)
        assertTrue(PendingCallManager.isCallPermitted(88L, 99L))

        // Clearing or expiring invalidates confirmation
        PendingCallManager.clear()
        assertFalse("Stale confirmation must be rejected", PendingCallManager.isCallPermitted(88L, 99L))
    }
}
