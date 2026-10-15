package com.example.automation

import android.content.Context
import android.media.AudioManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.automation.actions.CallPhoneAction
import com.example.automation.actions.SearchContactAction
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.*
import com.example.automation.tools.CallTool
import com.example.automation.tools.SearchContactTool
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.*
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
class OutgoingCallLifecycleUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
        com.example.automation.state.WorldStateManager.reset()
    }

    @After
    fun tearDown() {
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
        com.example.automation.state.WorldStateManager.reset()
    }

    @Test
    fun testDirectNumberAndContactResolution() = runBlocking {
        val searchContactTool = SearchContactTool(SearchContactAction())

        // 1. Direct number (national)
        val directReq1 = ExecutionRequest(
            action = "SEARCH_CONTACT",
            arguments = JsonObject().apply { addProperty("query", "9876543210") },
            source = ExecutionSource.GEMINI_LIVE
        )
        val result1 = searchContactTool.execute(context, directReq1)
        assertTrue(result1.success)
        val msgJson1 = JSONObject(result1.message ?: "{}")
        assertTrue(msgJson1.getBoolean("found"))
        val matches1 = msgJson1.getJSONArray("matches")
        assertEquals(1, matches1.length())
        val match1 = matches1.getJSONObject(0)
        assertEquals("Raw Number", match1.getString("displayName"))
        val phones1 = match1.getJSONArray("phoneNumbers")
        assertEquals(1, phones1.length())
        val phone1 = phones1.getJSONObject(0)
        assertEquals("9876543210", phone1.getString("normalizedNumber"))
        assertEquals("9876543210", phone1.getString("phoneNumber"))

        // State machine must have prepared confirmation target
        assertTrue(PendingCallManager.hasPendingConfirmation())
        val pendingConf1 = PendingCallManager.getPendingConfirmation()
        assertNotNull(pendingConf1)
        assertEquals("9876543210", pendingConf1?.phoneNumber)
        assertEquals(CallTargetKind.DIRECT_NUMBER, pendingConf1?.preparedTarget?.targetKind)

        PendingCallManager.clear()

        // 2. Direct number with country code (+91)
        val directReq2 = ExecutionRequest(
            action = "SEARCH_CONTACT",
            arguments = JsonObject().apply { addProperty("query", "+919876543210") },
            source = ExecutionSource.GEMINI_LIVE
        )
        val result2 = searchContactTool.execute(context, directReq2)
        assertTrue(result2.success)
        val msgJson2 = JSONObject(result2.message ?: "{}")
        val phone2 = msgJson2.getJSONArray("matches").getJSONObject(0).getJSONArray("phoneNumbers").getJSONObject(0)
        assertEquals("+919876543210", phone2.getString("normalizedNumber"))
    }

    @Test
    fun testConfirmationLifecycleAndVoiceAuthorization() = runBlocking {
        // Setup initial candidate selection in PendingCallManager
        val contactMatch = ContactMatch(
            contactId = 42L,
            lookupKey = "lookup_ravi_42",
            displayName = "Ravi",
            phoneNumbers = listOf(
                PhoneNumber(id = 101L, label = "Mobile", normalizedNumber = "+919876543210")
            )
        )
        PendingCallManager.setAwaitingContactSelection(
            matches = listOf(contactMatch),
            originalUserIntent = "Call Ravi",
            taskId = "task_call_1",
            stepId = "step_1"
        )

        // Verify that candidate selection transitions to AwaitingConfirmation
        val selected = PendingCallManager.selectCandidate(contactId = 42L, phoneId = 101L)
        assertTrue(selected)
        assertTrue(PendingCallManager.hasPendingConfirmation())
        val pending = PendingCallManager.getPendingConfirmation()
        assertNotNull(pending)
        assertEquals(42L, pending?.contactId)
        assertEquals(101L, pending?.phoneId)
        assertEquals("+919876543210", pending?.phoneNumber)

        // Verify call is NOT permitted until confirmed
        assertFalse(PendingCallManager.isCallPermitted(42L, 101L))

        // Confirm pending call
        val confirmed = PendingCallManager.confirmPendingCall(42L, 101L)
        assertTrue(confirmed)
        assertTrue(PendingCallManager.isCallPermitted(42L, 101L))
        assertTrue(PendingCallManager.isCallPermitted(42L, 101L, "+919876543210"))
        // Number mismatch check
        assertFalse(PendingCallManager.isCallPermitted(42L, 101L, "+911111111111"))

        // Voice Authorization Check:
        // 1. Unsolicited "Yes" without pending call is blocked
        PendingCallManager.clear()
        val authBareYes = ActionAuthorizer.authorizeAction("Yes", "CALL_PHONE", "")
        assertFalse("Bare Yes without call context must be rejected", authBareYes.authorized)

        // 2. "Yes" with pending call retains original intent and succeeds
        PendingCallManager.setAwaitingConfirmation(
            contactId = 42L,
            lookupKey = "lookup_ravi_42",
            phoneId = 101L,
            displayName = "Ravi",
            phoneNumber = "+919876543210",
            originalUserIntent = "Call Ravi"
        )
        val origIntent = PendingCallManager.getOriginalIntent()
        assertNotNull(origIntent)
        val authWithPending = ActionAuthorizer.authorizeAction(origIntent ?: "Call Ravi", "CALL_PHONE", "Ravi")
        assertTrue("Authorization using original call intent must succeed", authWithPending.authorized)

        // 3. User denial cancels pending call
        val userDenyReply = ApprovalCoordinator.parseReply("no don't call")
        assertEquals(ApprovalCoordinator.ReplyIntent.DENY, userDenyReply)
        PendingCallManager.cancel()
        assertEquals(CallState.Cancelled, PendingCallManager.state)
        assertFalse(PendingCallManager.hasPendingConfirmation())
    }

    @Test
    fun testOfflineCallConfirmationAndResume() = runBlocking {
        val callPhoneAction = CallPhoneAction()
        val callTool = CallTool(callPhoneAction)

        // Request a call to raw number without confirmation
        val callReq = ExecutionRequest(
            action = "CALL_PHONE",
            arguments = JsonObject().apply { addProperty("contact", "9876543210") },
            source = ExecutionSource.MANUAL
        )

        // Execution returns NEEDS_INPUT asking user confirmation
        val result = callTool.execute(context, callReq)
        assertEquals(ToolResultStatus.NEEDS_INPUT, result.status)
        assertEquals("CONFIRMATION_REQUIRED", result.errorCode)
        assertTrue(PendingCallManager.hasPendingConfirmation())

        val pending = PendingCallManager.getPendingConfirmation()
        assertNotNull(pending)
        assertEquals("9876543210", pending?.phoneNumber)

        // User replies "Yes"
        val userReply = ApprovalCoordinator.parseReply("Yes, call please")
        assertEquals(ApprovalCoordinator.ReplyIntent.APPROVE, userReply)

        val confirmSuccess = PendingCallManager.confirmPendingCall(pending?.contactId, pending?.phoneId)
        assertTrue(confirmSuccess)
        assertTrue(PendingCallManager.isCallPermitted(pending!!.contactId, pending.phoneId))
    }

    @Test
    fun testAsynchronousVerificationAndResultMapping() = runBlocking {
        val verifier = CallVerifier()
        val req = ExecutionRequest(
            action = "CALL_PHONE",
            arguments = JsonObject().apply {
                addProperty("contactId", 42L)
                addProperty("phoneId", 101L)
            }
        )

        // 1. Tool result with NEEDS_INPUT is preserved as confirmation required
        val needsInputResult = ToolResult.needsInput("call_contact", "Confirm?", listOf("Yes", "No"))
        val dummySnapshot = DeviceContext(ui = UiSnapshot("com.example.app"))
        val vNeedsInput = verifier.verify(context, req, needsInputResult, dummySnapshot)
        assertFalse(vNeedsInput.success)
        assertTrue(vNeedsInput.reason?.contains("CONFIRMATION_REQUIRED") == true)

        // 2. Successful launch with InCallUI evidence returns CALL_PLACED
        val inCallSnapshot = DeviceContext(ui = UiSnapshot("com.android.incallui"))
        val launchSuccessResult = ToolResult(
            success = true,
            toolName = "call_contact",
            verificationRequired = true,
            message = "Call placed successfully"
        )
        val vInCallSuccess = verifier.verify(context, req, launchSuccessResult, inCallSnapshot)
        assertTrue(vInCallSuccess.success)
        assertEquals("CALL_PLACED", vInCallSuccess.reason)

        // 3. Successful launch without telecom yet returns non-destructive CALL_REQUESTED
        val vPendingSuccess = verifier.verify(context, req, launchSuccessResult, dummySnapshot)
        assertFalse(vPendingSuccess.success)
        assertTrue(vPendingSuccess.reason?.contains("CALL_REQUESTED") == true)
        assertFalse(vPendingSuccess.retryRecommended)

        // 4. Dialpad-only snapshot triggers DIALER_OPENED failure
        val dialpadSnapshot = DeviceContext(
            ui = UiSnapshot(
                packageName = "com.android.dialer",
                activityName = "com.android.dialer.DialtactsActivity"
            )
        )
        val vDialpad = verifier.verify(context, req, launchSuccessResult, dialpadSnapshot)
        assertFalse(vDialpad.success)
        assertTrue(vDialpad.reason?.contains("DIALER_OPENED") == true)

        // 5. RetryEngine consistency: verify status is not SUCCESS when success is false
        val failedToolResult = ToolResult(
            success = true,
            status = ToolResultStatus.SUCCESS,
            toolName = "call_contact",
            message = "Initial success"
        )
        val updated = RetryEngine.executeWithRetry(
            context = context,
            tool = CallTool(CallPhoneAction()),
            request = req,
            executeBlock = { failedToolResult },
            verifyBlock = {
                VerificationResult(
                    success = false,
                    reason = "Explicit failure",
                    retryRecommended = false,
                    snapshot = dummySnapshot
                )
            }
        )
        assertFalse(updated.success)
        assertEquals(ToolResultStatus.FAILURE, updated.status)
        assertEquals("VERIFICATION_FAILED", updated.errorCode)

        // 4. ActionDispatcher mapping preserves WAITING_FOR_INPUT on needsInput
        val dispatchJson = JSONObject().apply {
            put("action", "CALL_PHONE")
            put("contact", "9876543210")
        }
        val rawJsonResult = ActionDispatcher.dispatchWithResult(context, dispatchJson)
        val parsedJson = JSONObject(rawJsonResult)
        assertEquals("needs_input", parsedJson.optString("status"))
        assertEquals("WAITING_FOR_INPUT", parsedJson.optString("executionState"))
        assertFalse(parsedJson.optBoolean("success"))
        assertTrue(parsedJson.has("question"))
    }
}
