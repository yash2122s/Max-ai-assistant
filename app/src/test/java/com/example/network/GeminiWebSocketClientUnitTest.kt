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
}
