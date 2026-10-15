package com.example.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiWebSocketClientUnitTest {

    @Test
    fun testSanitizeModelName_DefaultsToGemini38Live() {
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName(""))
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName("   "))
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName("Auto"))
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName("auto"))
    }

    @Test
    fun testSanitizeModelName_StripsModelsPrefix() {
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName("models/gemini-3.8-live"))
        assertEquals("gemini-3.1-flash-live-preview", GeminiWebSocketClient.sanitizeModelName("models/gemini-3.1-flash-live-preview"))
    }

    @Test
    fun testSanitizeModelName_PreservesExplicitModel() {
        assertEquals("gemini-3.8-live", GeminiWebSocketClient.sanitizeModelName("gemini-3.8-live"))
        assertEquals("gemini-3.1-flash-live-preview", GeminiWebSocketClient.sanitizeModelName("gemini-3.1-flash-live-preview"))
        assertEquals("custom-experimental-model", GeminiWebSocketClient.sanitizeModelName("custom-experimental-model"))
    }

    @Test
    fun testClientInitialization_DefaultModelAndState() {
        val client = GeminiWebSocketClient(
            apiKey = "",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        assertEquals("gemini-3.8-live", GeminiWebSocketClient.DEFAULT_LIVE_MODEL)
        assertEquals("gemini-3.1-flash-live-preview", GeminiWebSocketClient.FALLBACK_LIVE_MODEL)
        assertEquals(ConnectionState.DISCONNECTED, client.currentState)
        assertFalse(client.isConnected())
    }

    @Test
    fun testExtendedThinking_IntermediateTurnCompleteDoesNotTriggerOnTurnCompleteUntilIdle() {
        var turnCompleteCount = 0
        var lastStatus = ""
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" },
            onTurnComplete = { turnCompleteCount++ },
            onInteractionStatusChanged = { lastStatus = it }
        )

        // 1. Initial message with model text and interactionStatus = "IN_PROGRESS"
        val inProgressJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "Let me check that for you..."}]
                    },
                    "turnComplete": true,
                    "interactionStatus": "IN_PROGRESS"
                }
            }
        """.trimIndent()

        client.handleIncomingMessage(inProgressJson)

        assertEquals("IN_PROGRESS", lastStatus)
        // turnComplete was true, but interactionStatus was IN_PROGRESS -> must NOT fire onTurnComplete
        assertEquals(0, turnCompleteCount)

        // 2. Intermediate reasoning/thought occurs
        val reasoningJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "Analyzing the device screen...", "thought": true}]
                    },
                    "interactionStatus": "IN_PROGRESS"
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(reasoningJson)
        assertEquals(0, turnCompleteCount)

        // 3. Final answer received and status changes to "IDLE"
        val idleJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "Here is the final answer."}]
                    },
                    "turnComplete": true,
                    "interactionStatus": "IDLE"
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(idleJson)

        assertEquals("IDLE", lastStatus)
        assertEquals(1, turnCompleteCount)
    }

    @Test
    fun testLegacyFallback_TurnCompleteFiresWhenInteractionStatusIsAbsent() {
        var turnCompleteCount = 0
        var receivedText = ""
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            preferredModel = "gemini-3.8-live",
            onMessageReceived = { receivedText += it },
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" },
            onTurnComplete = { turnCompleteCount++ }
        )

        // Legacy message: has modelTurn and turnComplete, but NO interactionStatus field
        val legacyJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "Hello, how can I help you?"}]
                    },
                    "turnComplete": true
                }
            }
        """.trimIndent()

        client.handleIncomingMessage(legacyJson)

        assertTrue(receivedText.contains("Hello, how can I help you?"))
        // Fallback should trigger turn complete since no interactionStatus was ever tracked
        assertEquals(1, turnCompleteCount)
    }

    @Test
    fun testDeepModel_TurnCompleteWithoutInteractionStatusFiresOnTurnComplete() {
        var turnCompleteCount = 0
        var receivedText = ""
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            preferredModel = "gemini-3.8-live-extended-thinking",
            onMessageReceived = { receivedText += it },
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" },
            onTurnComplete = { turnCompleteCount++ }
        )

        val deepLiveMessage = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "Mee peru Rocky ani naa memory lo undi."}]
                    },
                    "turnComplete": true
                }
            }
        """.trimIndent()

        client.handleIncomingMessage(deepLiveMessage)

        assertTrue(receivedText.contains("Mee peru Rocky"))
        // Deep model should not complete turn until authoritative IDLE status is received
        assertEquals(0, turnCompleteCount)

        val idleMessage = """
            {
                "serverContent": {
                    "interactionStatus": "IDLE"
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(idleMessage)
        assertEquals(1, turnCompleteCount)
    }

    @Test
    fun testBargeIn_ResetsTurnStateAndPreventsStaleCompletion() {
        var turnCompleteCount = 0
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" },
            onTurnComplete = { turnCompleteCount++ }
        )

        // Model starts speaking with IN_PROGRESS
        val startSpeakingJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [{"text": "I am answering your question..."}]
                    },
                    "interactionStatus": "IN_PROGRESS"
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(startSpeakingJson)
        assertEquals(0, turnCompleteCount)

        // User barges in: server sends interrupted = true
        val bargeInJson = """
            {
                "serverContent": {
                    "interrupted": true
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(bargeInJson)

        // Next frame marks IDLE, but since the turn was interrupted, onTurnComplete should not fire for the canceled turn
        val idleJson = """
            {
                "serverContent": {
                    "interactionStatus": "IDLE"
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(idleJson)

        assertEquals(0, turnCompleteCount)
    }

    @Test
    fun testAudioReadiness_BuffersAudioChunksBeforeSetupComplete_AndEnforcesUpperBound() {
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        // Client is not connected or setupComplete yet: sending audio should buffer in queue
        val chunk = ByteArray(640) { 1 }
        for (i in 0 until 60) {
            client.sendAudio(chunk)
        }

        // Bounded queue must cap at 50 chunks
        assertEquals(50, client.getPendingAudioQueueSize())
    }

    @Test
    fun testTranscription_SeparatesInterimCaptionsFromFinalAuthoritativeTranscription() {
        var interimCount = 0
        var lastInterim = ""
        var finalCount = 0
        var lastFinal = ""

        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" },
            onInterimTranscription = { text ->
                interimCount++
                lastInterim = text
            },
            onUserTranscription = { text ->
                finalCount++
                lastFinal = text
            }
        )

        // 1. Server sends partial interimInputTranscription while user is speaking
        val interimJson = """
            {
                "serverContent": {
                    "interimInputTranscription": {
                        "text": "Open Whats"
                    }
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(interimJson)

        assertEquals(1, interimCount)
        assertEquals("Open Whats", lastInterim)
        assertEquals(0, finalCount)

        // 2. Server sends finalized inputTranscription when speech finishes
        val finalJson = """
            {
                "serverContent": {
                    "inputTranscription": {
                        "text": "Open WhatsApp"
                    }
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(finalJson)

        assertEquals(1, interimCount)
        assertEquals(1, finalCount)
        assertEquals("Open WhatsApp", lastFinal)
    }

    @Test
    fun testInterruption_ClearsPendingAudioQueueAndTranscriptionBuffer() {
        val client = GeminiWebSocketClient(
            apiKey = "test_key",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        // Buffer audio chunks before setup
        val chunk = ByteArray(320) { 5 }
        client.sendAudio(chunk)
        client.sendAudio(chunk)
        assertEquals(2, client.getPendingAudioQueueSize())

        // User barges in: interrupted frame received
        val bargeInJson = """
            {
                "serverContent": {
                    "interrupted": true
                }
            }
        """.trimIndent()
        client.handleIncomingMessage(bargeInJson)

        // Pending queue must be safely cleared to prevent stale chunk leakage
        assertEquals(0, client.getPendingAudioQueueSize())
    }

    @Test
    fun testAudioMimeRateAndStreamEnd_Matches16kHzAndAutomaticVadSpec() {
        assertEquals("audio/pcm;rate=16000", GeminiWebSocketClient.CLIENT_AUDIO_MIME_TYPE)
        val endPayload = GeminiWebSocketClient.buildAudioStreamEndPayload()
        assertTrue("End payload must contain realtimeInput", endPayload.contains("realtimeInput"))
        assertTrue("End payload must contain audioStreamEnd: true", endPayload.contains("\"audioStreamEnd\": true"))
        assertFalse("Realtime audio stream end must NOT send clientContent turnComplete", endPayload.contains("clientContent"))
    }
}
