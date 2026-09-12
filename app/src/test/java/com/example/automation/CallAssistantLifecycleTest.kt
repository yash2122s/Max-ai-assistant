package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallAssistantLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        IncomingCallManager.terminateCallAssistantSession(context)
    }

    @Test
    fun testDecisionParser_AcceptCommands() {
        val acceptPhrases = listOf(
            "accept",
            "answer",
            "pick up",
            "pickup",
            "lift",
            "take call",
            "take the call",
            "yes",
            "yes please",
            "please answer the call",
            "pick up the phone",
            "accept it"
        )

        for (phrase in acceptPhrases) {
            val decision = IncomingCallManager.parseCallDecision(phrase)
            assertEquals("Expected ACCEPT for phrase: '$phrase'", CallDecision.ACCEPT, decision)
        }
    }

    @Test
    fun testDecisionParser_RejectCommands() {
        val rejectPhrases = listOf(
            "reject",
            "decline",
            "don't answer",
            "dont answer",
            "do not answer",
            "no",
            "no don't answer",
            "decline the call",
            "reject it please",
            "no thanks"
        )

        for (phrase in rejectPhrases) {
            val decision = IncomingCallManager.parseCallDecision(phrase)
            assertEquals("Expected REJECT for phrase: '$phrase'", CallDecision.REJECT, decision)
        }
    }

    @Test
    fun testDecisionParser_RejectPrecedenceOverAccept() {
        // Phrases containing "don't answer" or "do not answer" should be REJECT, not ACCEPT
        assertEquals(CallDecision.REJECT, IncomingCallManager.parseCallDecision("don't answer"))
        assertEquals(CallDecision.REJECT, IncomingCallManager.parseCallDecision("do not answer"))
        assertEquals(CallDecision.REJECT, IncomingCallManager.parseCallDecision("dont answer"))
        assertEquals(CallDecision.REJECT, IncomingCallManager.parseCallDecision("no do not answer"))
    }

    @Test
    fun testDecisionParser_NonMatchingQueries() {
        val neutralPhrases = listOf(
            "hello",
            "what's the weather",
            "turn on flashlight",
            "open camera",
            "who is calling",
            ""
        )

        for (phrase in neutralPhrases) {
            val decision = IncomingCallManager.parseCallDecision(phrase)
            assertNull("Expected null for neutral phrase: '$phrase'", decision)
        }
    }

    @Test
    fun testCallAssistant_AcceptFlowAndCleanup() = runBlocking {
        // Start listening
        IncomingCallManager.onListeningStarted(context, wasConnectedBeforeCall = false)
        assertTrue(IncomingCallManager.isListeningForCallDecision())
        assertEquals(IncomingCallFlowState.INCOMING_CALL_LISTENING, IncomingCallManager.currentCallState)

        // User speaks ACCEPT
        IncomingCallManager.executeCallDecision(context, CallDecision.ACCEPT)

        // Verify transition and cleanup to IDLE
        assertEquals(IncomingCallFlowState.IDLE, IncomingCallManager.currentCallState)
        assertFalse(IncomingCallManager.isListeningForCallDecision())
    }

    @Test
    fun testCallAssistant_RejectFlowAndCleanup() = runBlocking {
        // Start listening
        IncomingCallManager.onListeningStarted(context, wasConnectedBeforeCall = false)
        assertTrue(IncomingCallManager.isListeningForCallDecision())

        // User speaks REJECT
        IncomingCallManager.executeCallDecision(context, CallDecision.REJECT)

        // Verify transition and cleanup to IDLE
        assertEquals(IncomingCallFlowState.IDLE, IncomingCallManager.currentCallState)
        assertFalse(IncomingCallManager.isListeningForCallDecision())
    }

    @Test
    fun testCallAssistant_TimeoutFlowAndCleanup() = runBlocking {
        // Start listening
        IncomingCallManager.onListeningStarted(context, wasConnectedBeforeCall = false)
        assertTrue(IncomingCallManager.isListeningForCallDecision())

        // 10s timeout triggers
        IncomingCallManager.executeCallDecision(context, CallDecision.TIMEOUT)

        // Verify transition and cleanup to IDLE
        assertEquals(IncomingCallFlowState.IDLE, IncomingCallManager.currentCallState)
        assertFalse(IncomingCallManager.isListeningForCallDecision())
    }

    @Test
    fun testCallAssistant_ExternalHangup_CleansUpToIdle() {
        IncomingCallManager.onListeningStarted(context, wasConnectedBeforeCall = false)
        assertTrue(IncomingCallManager.isListeningForCallDecision())

        // External hangup received
        IncomingCallManager.handleCallEnded(context)

        assertEquals(IncomingCallFlowState.IDLE, IncomingCallManager.currentCallState)
        assertFalse(IncomingCallManager.isListeningForCallDecision())
    }
}
