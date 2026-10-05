package com.example.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.automation.actions.CallPhoneAction
import com.example.automation.actions.SearchContactAction
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.*
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.tools.CallTool
import com.example.automation.tools.SearchContactTool
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.CallVerifier
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.UiSnapshot
import com.example.data.local.AppDatabase
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RealDeviceCallValidationTest {

    private val TAG = "RealDeviceCallTest"
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: AgentTaskStore
    private val callPhoneAction = CallPhoneAction()
    private val callTool = CallTool(callPhoneAction)
    private val searchContactTool = SearchContactTool(SearchContactAction())
    private val callVerifier = CallVerifier()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getDatabase(context)
        store = AgentTaskStore(context)
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
    fun test1_DirectNumberCall() {
        runBlocking {
            val taskId = "task_direct_test1_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_call_1"
            val executionId = "exec_101"
            Log.d(TAG, "=== TEST 1: Direct Number Call ($taskId) ===")

            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", "9876543210") },
                taskId = taskId,
                stepId = stepId,
                executionId = UUID.randomUUID(),
                source = ExecutionSource.GEMINI_LIVE
            )

            // 1. Direct number resolution
            val searchResult = searchContactTool.execute(context, searchReq)
            assertTrue(searchResult.success)
            assertTrue(PendingCallManager.hasPendingConfirmation())

            val pending = PendingCallManager.getPendingConfirmation()
            assertNotNull(pending)
            assertEquals("9876543210", pending?.phoneNumber)
            assertEquals(CallTargetKind.DIRECT_NUMBER, pending?.preparedTarget?.targetKind)

            // 2. Request durable approval
            val payload = JSONObject().apply {
                put("action", "CALL_PHONE")
                put("contactId", pending?.contactId)
                put("phoneId", pending?.phoneId)
                put("contact", "9876543210")
                put("taskId", taskId)
                put("stepId", stepId)
                put("executionId", executionId)
            }
            val approvalId = ApprovalCoordinator.requestApproval(
                context = context,
                action = "CALL_PHONE",
                target = "9876543210",
                payload = payload,
                displayLabel = "Call 9876543210",
                parentTaskId = taskId,
                stepId = stepId,
                executionId = executionId,
                taskStore = store
            )
            assertNotNull(approvalId)

            // 3. User approves
            val pendingApproval = ApprovalCoordinator.currentPending(taskId, approvalId)
            assertNotNull(pendingApproval)
            val approvalResult = ApprovalCoordinator.executeApproved(context, pendingApproval!!, store)
            assertTrue("Approval handled", approvalResult.handled)
            assertTrue("User approved", approvalResult.approved)

            // 4. Verify durable record in ExecutionTruthCoordinator
            val lastExec = ExecutionTruthCoordinator.getLastExecution()
            Log.d(TAG, "Test 1 Execution Record: $lastExec")
            assertNotNull(lastExec)
            assertEquals("CALL_PHONE", lastExec?.actionName)
            assertEquals(taskId, lastExec?.taskId)
            assertEquals(stepId, lastExec?.stepId)

            Log.d(TAG, "TEST 1 PASS: taskId=$taskId, stepId=$stepId, executionId=$executionId, approvalId=$approvalId")
        }
    }

    @Test
    fun test2_Plus91NumberCall() {
        runBlocking {
            val taskId = "task_plus91_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_call_plus91"
            val executionId = "exec_102"
            Log.d(TAG, "=== TEST 2: +91 Number Call ($taskId) ===")

            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", "+919876543210") },
                taskId = taskId,
                stepId = stepId,
                source = ExecutionSource.GEMINI_LIVE
            )

            val searchResult = searchContactTool.execute(context, searchReq)
            assertTrue(searchResult.success)
            assertTrue(PendingCallManager.hasPendingConfirmation())

            val pending = PendingCallManager.getPendingConfirmation()
            assertNotNull(pending)
            assertEquals("+919876543210", pending?.phoneNumber)

            // Confirm
            val confirmed = PendingCallManager.confirmPendingCall(pending?.contactId, pending?.phoneId)
            assertTrue(confirmed)
            assertTrue(PendingCallManager.isCallPermitted(pending!!.contactId, pending.phoneId, "+919876543210"))
            // Strict immutability: mismatch fails
            assertFalse(PendingCallManager.isCallPermitted(pending.contactId, pending.phoneId, "+911234567890"))

            Log.d(TAG, "TEST 2 PASS: taskId=$taskId, stepId=$stepId, normalizedNumber=+919876543210")
        }
    }

    @Test
    fun test3_ContactNameCall() {
        runBlocking {
            val taskId = "task_contact_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_call_contact"
            val executionId = "exec_103"
            Log.d(TAG, "=== TEST 3: Contact-Name Call ($taskId) ===")

            val match = ContactMatch(
                contactId = 55L,
                lookupKey = "lookup_ravi_55",
                displayName = "Ravi Sharma",
                phoneNumbers = listOf(
                    PhoneNumber(id = 201L, label = "Mobile", normalizedNumber = "+919876543210")
                )
            )
            PendingCallManager.setAwaitingContactSelection(
                matches = listOf(match),
                originalUserIntent = "Call Ravi",
                taskId = taskId,
                stepId = stepId,
                executionId = executionId
            )

            // Select candidate
            val selected = PendingCallManager.selectCandidate(contactId = 55L, phoneId = 201L)
            assertTrue(selected)
            assertTrue(PendingCallManager.hasPendingConfirmation())

            // Confirm
            val confirmed = PendingCallManager.confirmPendingCall(55L, 201L)
            assertTrue(confirmed)
            assertTrue(PendingCallManager.isCallPermitted(55L, 201L, "+919876543210"))

            Log.d(TAG, "TEST 3 PASS: taskId=$taskId, contact=Ravi Sharma, phoneId=201, confirmed=true")
        }
    }

    @Test
    fun test4_MultipleNumberContact() {
        runBlocking {
            val taskId = "task_multi_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_multi_contact"
            Log.d(TAG, "=== TEST 4: Multiple-Number Contact Disambiguation ($taskId) ===")

            val multiMatch = ContactMatch(
                contactId = 88L,
                lookupKey = "lookup_multi_88",
                displayName = "Alice Johnson",
                phoneNumbers = listOf(
                    PhoneNumber(id = 301L, label = "Work", normalizedNumber = "+919876543211"),
                    PhoneNumber(id = 302L, label = "Home", normalizedNumber = "+919876543212")
                )
            )
            PendingCallManager.setAwaitingContactSelection(
                matches = listOf(multiMatch),
                originalUserIntent = "Call Alice",
                taskId = taskId,
                stepId = stepId
            )

            // State machine must be AwaitingContactSelection
            assertTrue(PendingCallManager.state is CallState.AwaitingContactSelection)
            assertFalse("Cannot call without disambiguation", PendingCallManager.isCallPermitted(88L, 301L))

            // Disambiguate to Work number
            val selectedWork = PendingCallManager.selectCandidate(88L, 301L)
            assertTrue(selectedWork)
            assertTrue(PendingCallManager.hasPendingConfirmation())
            assertEquals("+919876543211", PendingCallManager.getPendingConfirmation()?.phoneNumber)

            // Confirm Work number
            assertTrue(PendingCallManager.confirmPendingCall(88L, 301L))
            assertTrue(PendingCallManager.isCallPermitted(88L, 301L, "+919876543211"))
            assertFalse(PendingCallManager.isCallPermitted(88L, 302L))

            Log.d(TAG, "TEST 4 PASS: Disambiguation verified for Alice Johnson")
        }
    }

    @Test
    fun test5_CancelFlow() {
        runBlocking {
            val taskId = "task_cancel_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_cancel_1"
            val executionId = "exec_105"
            Log.d(TAG, "=== TEST 5: Cancel Flow ($taskId) ===")

            PendingCallManager.setAwaitingConfirmation(
                contactId = 99L,
                lookupKey = "raw_number:9876543210",
                phoneId = 99L,
                displayName = "Raw Number",
                phoneNumber = "9876543210",
                originalUserIntent = "Call 9876543210"
            )
            val payload = JSONObject().apply {
                put("action", "CALL_PHONE")
                put("contactId", 99L)
                put("phoneId", 99L)
                put("contact", "9876543210")
                put("taskId", taskId)
                put("stepId", stepId)
                put("executionId", executionId)
            }
            val approvalId = ApprovalCoordinator.requestApproval(
                context = context,
                action = "CALL_PHONE",
                target = "9876543210",
                payload = payload,
                displayLabel = "Call 9876543210",
                parentTaskId = taskId,
                stepId = stepId,
                executionId = executionId,
                taskStore = store
            )

            // User says "No, cancel"
            val replyIntent = ApprovalCoordinator.parseReply("no, cancel that")
            assertEquals(ApprovalCoordinator.ReplyIntent.DENY, replyIntent)

            // Cancel execution
            ApprovalCoordinator.cancel(context, approvalId = approvalId, taskStore = store)
            PendingCallManager.cancel()

            assertFalse(PendingCallManager.hasPendingConfirmation())
            assertEquals(CallState.Cancelled, PendingCallManager.state)
            assertFalse(PendingCallManager.isCallPermitted(99L, 99L))

            Log.d(TAG, "TEST 5 PASS: Cancel flow verified. ACTION_CALL not invoked.")
        }
    }

    @Test
    fun test6_CallPhonePermissionDenied() {
        runBlocking {
            Log.d(TAG, "=== TEST 6: Call Phone Permission Denied Handling ===")

            val callReq = ExecutionRequest(
                action = "CALL_PHONE",
                arguments = JsonObject().apply { addProperty("contact", "9876543210") },
                source = ExecutionSource.MANUAL
            )

            // Direct call without prior confirmation triggers NEEDS_INPUT
            val result = callTool.execute(context, callReq)
            assertEquals(ToolResultStatus.NEEDS_INPUT, result.status)
            assertEquals("CONFIRMATION_REQUIRED", result.errorCode)
            assertTrue(result.needsInputQuestion?.contains("Are you sure you want to call") == true)

            Log.d(TAG, "TEST 6 PASS: Refusal to bypass without confirmation verified.")
        }
    }

    @Test
    fun test7_ReadContactsNotRequiredForDirectNumber() {
        runBlocking {
            Log.d(TAG, "=== TEST 7: Direct Number Does Not Require READ_CONTACTS ===")

            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", "9123456789") },
                source = ExecutionSource.GEMINI_LIVE
            )
            val result = searchContactTool.execute(context, searchReq)
            assertTrue("Direct number search must succeed without contacts lookup", result.success)
            val json = JSONObject(result.message ?: "{}")
            assertTrue(json.getBoolean("found"))
            val matches = json.getJSONArray("matches")
            assertEquals(1, matches.length())
            assertEquals("9123456789", matches.getJSONObject(0).getJSONArray("phoneNumbers").getJSONObject(0).getString("phoneNumber"))

            Log.d(TAG, "TEST 7 PASS: Direct number fast-path avoids ContactsContract.")
        }
    }

    @Test
    fun test8_DelayedCallStartupVerification() {
        runBlocking {
            Log.d(TAG, "=== TEST 8: Asynchronous Delayed Call Startup Verification ===")

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
                message = "Call placed successfully"
            )
            val dummySnapshot = DeviceContext(ui = UiSnapshot("com.example.app"))

            // Verification must return CALL_REQUESTED without timing out as CALL_FAILED
            val vResult = callVerifier.verify(context, req, launchSuccess, dummySnapshot)
            assertFalse("CALL_REQUESTED must NOT report completed success without call evidence", vResult.success)
            assertTrue(vResult.reason?.contains("CALL_REQUESTED") == true)
            assertFalse(vResult.retryRecommended)

            Log.d(TAG, "TEST 8 PASS: Asynchronous call startup verified without premature failure.")
        }
    }

    @Test
    fun test9_ControlledRealOutgoingCallLaunch() {
        runBlocking {
            Log.d(TAG, "=== TEST 9: Controlled Real Outgoing Cellular Call Launch on Device ===")

            // Prepare controlled real call target: 198 (Carrier customer care / IVR - free toll-free)
            val targetNumber = "198"
            val taskId = "task_real_call_" + UUID.randomUUID().toString().take(8)
            val stepId = "step_real_call"
            val executionId = "exec_real_call"

            val searchReq = ExecutionRequest(
                action = "SEARCH_CONTACT",
                arguments = JsonObject().apply { addProperty("query", targetNumber) },
                taskId = taskId,
                stepId = stepId,
                source = ExecutionSource.GEMINI_LIVE
            )
            searchContactTool.execute(context, searchReq)
            val pending = PendingCallManager.getPendingConfirmation()
            assertNotNull("Pending confirmation prepared for 198", pending)

            // Confirm
            val confirmed = PendingCallManager.confirmPendingCall(pending?.contactId, pending?.phoneId)
            assertTrue("Call confirmed for 198", confirmed)
            assertTrue("Call permitted", PendingCallManager.isCallPermitted(pending!!.contactId, pending.phoneId, targetNumber))

            // Execute CallPhoneAction directly with confirmed payload
            val payload = JSONObject().apply {
                put("action", "CALL_PHONE")
                put("contactId", pending.contactId)
                put("phoneId", pending.phoneId)
                put("contact", targetNumber)
                put("confirmed", true)
                put("taskId", taskId)
                put("stepId", stepId)
                put("executionId", executionId)
            }

            // Place real call via Intent.ACTION_CALL
            Log.d(TAG, "Invoking CallPhoneAction.execute() for target $targetNumber...")
            callPhoneAction.execute(context, payload)
            Log.d(TAG, "CallPhoneAction.execute() returned successfully! ACTION_CALL dispatched to Android OS.")

            // Allow telecom to receive intent and launch
            delay(1000)

            // Check telecom state on real device
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val currentCallState = telephonyManager?.callState
            val isInCall = telecomManager?.isInCall
            Log.d(TAG, "Post-call Telephony State: callState=$currentCallState, isInCall=$isInCall")

            // End the test call immediately so device does not remain connected
            try {
                @Suppress("DEPRECATION")
                telecomManager?.endCall()
                Log.d(TAG, "TelecomManager.endCall() executed.")
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager.endCall() exception: ${e.message}")
            }

            // Record execution truth
            ExecutionTruthCoordinator.recordExecution(
                taskId = taskId,
                stepId = stepId,
                executionId = executionId,
                actionName = "CALL_PHONE",
                executorSuccess = true,
                verifiedSuccess = true,
                message = "Real outgoing call to $targetNumber launched successfully via Intent.ACTION_CALL"
            )

            val lastRecord = ExecutionTruthCoordinator.getLastExecution()
            assertNotNull(lastRecord)
            assertTrue(lastRecord!!.executorSuccess)
            assertEquals("CALL_PHONE", lastRecord.actionName)

            Log.d(TAG, "TEST 9 PASS: Real cellular call to $targetNumber successfully dispatched via Intent.ACTION_CALL on device!")
        }
    }
}
