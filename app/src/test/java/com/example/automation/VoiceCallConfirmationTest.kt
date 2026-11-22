package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.*
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.google.gson.JsonObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCallConfirmationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
    }

    @After
    fun tearDown() {
        PendingCallManager.clear()
        ApprovalCoordinator.clearAll()
    }

    @Test
    fun testDenialAndNegationsNeverApprove() {
        // Denial words and negations must always yield DENY, never APPROVE
        val denyPhrases = listOf(
            "not sure",
            "cheyyaku",
            "చేయకు",
            "చెయ్యకు",
            "చేయొద్దు",
            "ఆపు",
            "ఆపండి",
            "ఆపేయి",
            "ఆగు",
            "ఆగండి",
            "aagu",
            "aapu",
            "aapandi",
            "I don't know",
            "dont call",
            "no don't call",
            "వద్దు",
            "వద్దండి",
            "కాదు",
            "లేదు",
            "never",
            "cancel"
        )
        for (phrase in denyPhrases) {
            val reply = ApprovalCoordinator.parseReply(phrase)
            assertEquals("Phrase '$phrase' must be classified as DENY", ApprovalCoordinator.ReplyIntent.DENY, reply)
            assertFalse("Phrase '$phrase' must not pass isConsentUtterance", ApprovalCoordinator.isConsentUtterance(phrase))
        }
    }

    @Test
    fun testApprovalUtterancesClassifiedCorrectly() {
        val approvePhrases = listOf(
            "yes",
            "yep",
            "yeah",
            "sure",
            "ok",
            "okay",
            "confirm",
            "proceed",
            "sare",
            "haan",
            "avunu",
            "అవును",
            "ఔను",
            "ఓకే",
            "అలాగే",
            "అవునండి",
            "సరేనండి",
            "ఓకేనండి",
            "avunandi",
            "sarenandi",
            "do it",
            "go ahead",
            "pampu",
            "పంపు"
        )
        for (phrase in approvePhrases) {
            val reply = ApprovalCoordinator.parseReply(phrase)
            assertEquals("Phrase '$phrase' must be classified as APPROVE", ApprovalCoordinator.ReplyIntent.APPROVE, reply)
        }

        // Bare verbs approve only if 1 token
        assertEquals(ApprovalCoordinator.ReplyIntent.APPROVE, ApprovalCoordinator.parseReply("చేయి"))
        assertEquals(ApprovalCoordinator.ReplyIntent.APPROVE, ApprovalCoordinator.parseReply("cheyyi"))
        assertEquals(ApprovalCoordinator.ReplyIntent.APPROVE, ApprovalCoordinator.parseReply("cheyandi"))

        // Multi-token sentence with bare verb at end is NOT an approval
        assertNotEquals(ApprovalCoordinator.ReplyIntent.APPROVE, ApprovalCoordinator.parseReply("నాన్నకి కాల్ చేయి"))
        assertNotEquals(ApprovalCoordinator.ReplyIntent.APPROVE, ApprovalCoordinator.parseReply("call cheyyi"))
    }

    @Test
    fun testLeftoverContentRule() {
        // Pure consent + call/filler words -> isConsentUtterance = true
        assertTrue(ApprovalCoordinator.isConsentUtterance("అవును కాల్ చేయి"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes, call please"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes, call her"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("sare phone chey"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("ok"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes"))

        // Utterances containing target names or digits are new requests, NOT pure consent
        assertFalse(ApprovalCoordinator.isConsentUtterance("yes call Dad"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("నాన్నకి కాల్ చేయి"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("call 9640123556"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("call Ravi"))
    }

    @Test
    fun testPendingMomAndExplicitNewCallCancelsPendingMom() {
        // Setup: Mom is pending confirmation
        val now = System.currentTimeMillis()
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "lookup_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            originalUserIntent = "Call Mom"
        )
        assertTrue(PendingCallManager.hasPendingCall())

        // User speaks a new explicit call request: "నాన్నకి కాల్ చేయి"
        val call = FunctionCall("call_contact", JsonObject().apply {
            addProperty("contact", "నాన్న")
        })
        val resultStr = ToolDispatcher.dispatch(call, "నాన్నకి కాల్ చేయి", lastInputAtMs = now + 1000L)
        val json = JSONObject(resultStr)

        // Pending call for Mom must have been cancelled
        assertFalse("Mom must not be in Confirmed state", PendingCallManager.state is CallState.Confirmed)
        // Action was prepared for new target "నాన్న", not Mom
        assertEquals("CALL_PHONE", json.getString("action"))
        assertEquals("నాన్న", json.optString("contact"))
        assertFalse(json.optBoolean("confirmed", false))
    }

    @Test
    fun testPendingMomAndYesCallDadNotApproved() {
        val now = System.currentTimeMillis()
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "lookup_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            originalUserIntent = "Call Mom"
        )

        // User says "yes call Dad": contains "yes" and "call", but leftover has "Dad"
        val call = FunctionCall("call_contact", JsonObject().apply {
            addProperty("contact", "Dad")
        })
        val resultStr = ToolDispatcher.dispatch(call, "yes call Dad", lastInputAtMs = now + 1000L)
        val json = JSONObject(resultStr)

        assertFalse("Mom must not be confirmed when user says 'yes call Dad'", PendingCallManager.state is CallState.Confirmed)
        assertFalse("Confirmed flag must not be true", json.optBoolean("confirmed", false))
    }

    @Test
    fun testPendingMomWithNaturalConsentConfirmedAndBoundToMom() {
        val now = System.currentTimeMillis()
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "lookup_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            originalUserIntent = "Call Mom"
        )

        // User says natural consent: "అవును కాల్ చేయి"
        // Model might hallucinate different contact argument (e.g. "Hacker")
        val call = FunctionCall("call_contact", JsonObject().apply {
            addProperty("contact", "Hacker")
            addProperty("contactId", 999L)
            addProperty("phoneId", 999L)
        })
        val resultStr = ToolDispatcher.dispatch(call, "అవును కాల్ చేయి", lastInputAtMs = now + 1000L)
        val json = JSONObject(resultStr)

        assertTrue("Call must transition to Confirmed", PendingCallManager.state is CallState.Confirmed)
        assertTrue(json.getBoolean("confirmed"))
        // Candidate must be bound to Mom from PendingCallManager, NOT Hacker!
        assertEquals("Mom", json.getString("contact"))
        assertEquals(100L, json.getLong("contactId"))
        assertEquals(10L, json.getLong("phoneId"))
        assertEquals("9876543210", json.getString("phoneNumber"))
    }

    @Test
    fun testStaleTranscriptIgnored() {
        val now = System.currentTimeMillis()
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "lookup_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            originalUserIntent = "Call Mom"
        )
        val pendingSince = PendingCallManager.pendingSinceMs()

        // 1. Timestamp older than pendingSince
        val call1 = FunctionCall("call_contact", JsonObject().apply { addProperty("contact", "Mom") })
        val resultStr1 = ToolDispatcher.dispatch(call1, "yes", lastInputAtMs = pendingSince - 1000L)
        val json1 = JSONObject(resultStr1)
        assertFalse("Stale transcript must not confirm call", PendingCallManager.state is CallState.Confirmed)
        assertFalse(json1.optBoolean("confirmed", false))

        // 2. Timestamp is 0 (fails closed)
        val call2 = FunctionCall("call_contact", JsonObject().apply { addProperty("contact", "Mom") })
        val resultStr2 = ToolDispatcher.dispatch(call2, "yes", lastInputAtMs = 0L)
        val json2 = JSONObject(resultStr2)
        assertFalse("0 timestamp must fail closed and not confirm call", PendingCallManager.state is CallState.Confirmed)
        assertFalse(json2.optBoolean("confirmed", false))
    }

    @Test
    fun testModelSuppliedConfirmedAndOriginalIntentStripped() {
        val now = System.currentTimeMillis()
        // Model attempts to inject confirmed=true and fake original_user_intent
        val call = FunctionCall("call_contact", JsonObject().apply {
            addProperty("contact", "Malicious")
            addProperty("confirmed", true)
            addProperty("user_confirmed", true)
            addProperty("original_user_intent", "Call Malicious")
        })

        // User said something unrelated
        val resultStr = ToolDispatcher.dispatch(call, "what is the weather", lastInputAtMs = now)
        val json = JSONObject(resultStr)

        assertFalse("Model confirmed flag must be stripped", json.optBoolean("confirmed", false))
        assertFalse("Model user_confirmed flag must be stripped", json.optBoolean("user_confirmed", false))
        assertNotEquals("Model original_user_intent must be stripped", "Call Malicious", json.optString("original_user_intent"))
    }

    @Test
    fun testDisambiguationRepliesResolved() {
        val matches = listOf(
            ContactMatch(
                contactId = 10L,
                lookupKey = "sai_mobile_key",
                displayName = "Sai",
                phoneNumbers = listOf(
                    PhoneNumber(id = 1L, label = "mobile", normalizedNumber = "9876543210"),
                    PhoneNumber(id = 2L, label = "home", normalizedNumber = "9123456780")
                )
            )
        )
        PendingCallManager.setAwaitingContactSelection(matches, originalUserIntent = "Call Sai")

        // Reply "2"
        val sel1 = PendingCallManager.resolveAndSelectCandidate("2")
        assertNotNull(sel1)
        assertEquals(2L, sel1?.phoneNumber?.id)
        assertEquals("home", sel1?.phoneNumber?.label)
        assertTrue(PendingCallManager.state is CallState.AwaitingConfirmation)

        // Reset to AwaitingContactSelection
        PendingCallManager.setAwaitingContactSelection(matches, originalUserIntent = "Call Sai")
        // Reply "mobile"
        val sel2 = PendingCallManager.resolveAndSelectCandidate("mobile")
        assertNotNull(sel2)
        assertEquals(1L, sel2?.phoneNumber?.id)
        assertEquals("mobile", sel2?.phoneNumber?.label)

        // Reset to AwaitingContactSelection
        PendingCallManager.setAwaitingContactSelection(matches, originalUserIntent = "Call Sai")
        // Reply "ఒకటి" (Telugu ordinal 1)
        val sel3 = PendingCallManager.resolveAndSelectCandidate("ఒకటి")
        assertNotNull(sel3)
        assertEquals(1L, sel3?.phoneNumber?.id)

        // Verify AssistantEngine authorization intent uses getOriginalIntent() on selection turn
        val authIntent = PendingCallManager.getOriginalIntent()
        assertEquals("Call Sai", authIntent)
        val authResult = ActionAuthorizer.authorizeAction(authIntent!!, "CALL_PHONE", "Sai")
        assertTrue("Selection reply must authorize via original intent", authResult.authorized)
    }

    @Test
    fun testLeftoverContentMatchingPendingCandidateCountsAsConsent() {
        // When Mom is pending, "yes call Mom" and Telugu equivalent "అవును అమ్మకి కాల్ చేయి" match candidate/alias
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes call Mom", "Mom"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes call Mom", "Amma"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("అవును అమ్మకి కాల్ చేయి", "Mom"))
        assertTrue(ApprovalCoordinator.isConsentUtterance("yes call Amma", "Mom"))

        // But "yes call Dad" or "అవును నాన్నకి కాల్ చేయి" when Mom is pending must NOT count as consent
        assertFalse(ApprovalCoordinator.isConsentUtterance("yes call Dad", "Mom"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("అవును నాన్నకి కాల్ చేయి", "Mom"))

        // Without candidate name, leftover tokens fail closed
        assertFalse(ApprovalCoordinator.isConsentUtterance("yes call Mom", null))
        assertFalse(ApprovalCoordinator.isConsentUtterance("yes call Mom", ""))
    }

    @Test
    fun testApostrophesInReplyParsing() {
        // Various forms of apostrophes must be stripped to "dont" rather than separated with spaces
        assertEquals(ApprovalCoordinator.ReplyIntent.DENY, ApprovalCoordinator.parseReply("don't"))
        assertEquals(ApprovalCoordinator.ReplyIntent.DENY, ApprovalCoordinator.parseReply("don’t"))
        assertEquals(ApprovalCoordinator.ReplyIntent.DENY, ApprovalCoordinator.parseReply("don't call"))
        assertEquals(ApprovalCoordinator.ReplyIntent.DENY, ApprovalCoordinator.parseReply("I don't think so"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("don't"))
        assertFalse(ApprovalCoordinator.isConsentUtterance("don't call"))
    }

    @Test
    fun testMultipleFunctionCallsInOneTurnSnapshotBehavior() {
        val now = System.currentTimeMillis()
        PendingCallManager.setAwaitingConfirmation(
            contactId = 100L,
            lookupKey = "lookup_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            originalUserIntent = "Call Mom"
        )

        val turnTranscript = "yes"
        val call1 = FunctionCall("call_contact", JsonObject().apply { addProperty("contact", "Mom") })
        val call2 = FunctionCall("call_contact", JsonObject().apply { addProperty("contact", "Mom") })

        // First call in turn executes with isApprovalConsumed = false
        val res1 = ToolDispatcher.dispatch(call1, turnTranscript, lastInputAtMs = now + 500L, isApprovalConsumed = false)
        val json1 = JSONObject(res1)
        assertTrue("First call must confirm pending call", json1.getBoolean("confirmed"))

        // Second call in the same turn receives the NON-EMPTY turn transcript, but has isApprovalConsumed = true
        val res2 = ToolDispatcher.dispatch(call2, turnTranscript, lastInputAtMs = now + 500L, isApprovalConsumed = true)
        val json2 = JSONObject(res2)
        assertEquals("Transcript must be preserved for second call", turnTranscript, json2.getString("last_input_transcription"))
        assertFalse("Second call must not double-consume confirmation", json2.optBoolean("confirmed", false))
    }

    @Test
    fun testResolveAuthorizationIntentPureFunction() {
        val originalIntent = "Call Mom"

        // Case 1: Confirmed + "yes" -> returns original pending intent ("Call Mom")
        val confirmedState = CallState.Confirmed(
            contactId = 100L,
            lookupKey = "key_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            confirmedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000L,
            originalUserIntent = originalIntent
        )
        val intent1 = com.example.voice.assistant.AssistantEngine.resolveAuthorizationIntent(
            action = "CALL_PHONE",
            userText = "yes",
            callState = confirmedState,
            originalPendingIntent = originalIntent,
            target = "Mom"
        )
        assertEquals("Case 1: Confirmed + 'yes' must resolve to original intent", originalIntent, intent1)

        // Case 2: selection state + "2" (isSelectionMatch = true) -> returns original pending intent
        val selectionState = CallState.AwaitingContactSelection(
            matches = emptyList(),
            createdAt = System.currentTimeMillis(),
            originalUserIntent = "Call Sai"
        )
        val intent2 = com.example.voice.assistant.AssistantEngine.resolveAuthorizationIntent(
            action = "CALL_PHONE",
            userText = "2",
            callState = selectionState,
            originalPendingIntent = "Call Sai",
            target = "Sai",
            isSelectionMatch = true
        )
        assertEquals("Case 2: Selection state + '2' with match must resolve to original intent", "Call Sai", intent2)

        // Case 3: consent utterance (e.g. "yes call Mom") while awaiting confirmation -> returns original pending intent
        val awaitingState = CallState.AwaitingConfirmation(
            contactId = 100L,
            lookupKey = "key_mom",
            phoneId = 10L,
            displayName = "Mom",
            phoneNumber = "9876543210",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000L,
            originalUserIntent = originalIntent
        )
        val intent3 = com.example.voice.assistant.AssistantEngine.resolveAuthorizationIntent(
            action = "CALL_PHONE",
            userText = "yes call Mom",
            callState = awaitingState,
            originalPendingIntent = originalIntent,
            target = "Mom"
        )
        assertEquals("Case 3: Consent utterance must resolve to original intent", originalIntent, intent3)

        // Case 4: no pending (Idle) + "yes" -> returns raw userText ("yes"), which will be blocked by ActionAuthorizer
        val intent4 = com.example.voice.assistant.AssistantEngine.resolveAuthorizationIntent(
            action = "CALL_PHONE",
            userText = "yes",
            callState = CallState.Idle,
            originalPendingIntent = null,
            target = "Mom"
        )
        assertEquals("Case 4: No pending + 'yes' must preserve raw user utterance 'yes'", "yes", intent4)
        val authResult4 = ActionAuthorizer.authorizeAction(intent4, "CALL_PHONE", "Mom")
        assertFalse("Case 4: ActionAuthorizer must block bare 'yes' without pending call", authResult4.authorized)

        // Case 5: pending Mom + new-request utterance "నాన్నకి కాల్ చేయి" -> does NOT count as consent, preserves new request text
        val intent5 = com.example.voice.assistant.AssistantEngine.resolveAuthorizationIntent(
            action = "CALL_PHONE",
            userText = "నాన్నకి కాల్ చేయి",
            callState = awaitingState,
            originalPendingIntent = originalIntent,
            target = "Mom"
        )
        assertEquals("Case 5: Pending Mom + 'నాన్నకి కాల్ చేయి' must preserve new request utterance", "నాన్నకి కాల్ చేయి", intent5)
    }
}
