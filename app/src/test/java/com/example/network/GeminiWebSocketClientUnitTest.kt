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
}
