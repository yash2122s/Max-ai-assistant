package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import com.example.domain.usecase.AssistantUseCase
import com.example.network.ConnectionState
import com.example.network.GeminiWebSocketClient
import com.example.voice.assistant.AssistantEngine
import com.example.voice.assistant.ConnectionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeySecurityUnitTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsManager = SettingsManager(context)
        // Clear all keys to simulate fresh install
        settingsManager.geminiApiKey = ""
        settingsManager.uiAgentApiKey = ""
    }

    @Test
    fun testFreshInstall_NoApiKey_ValidationReturnsFalse() {
        assertEquals("", settingsManager.geminiApiKey)
        assertEquals("", settingsManager.uiAgentApiKey)
        assertFalse("hasValidGeminiApiKey must be false on fresh install", settingsManager.hasValidGeminiApiKey())
        assertFalse("hasValidOpenRouterApiKey must be false on fresh install", settingsManager.hasValidOpenRouterApiKey())
        assertFalse("hasAnyValidAiKey must be false on fresh install", settingsManager.hasAnyValidAiKey())
    }

    @Test
    fun testSettingsManager_SavesAndTrimsKey() {
        val testKey = "  AIzaSyTestUserCustomKey1234567890  "
        settingsManager.geminiApiKey = testKey
        
        assertEquals("AIzaSyTestUserCustomKey1234567890", settingsManager.geminiApiKey)
        assertTrue(settingsManager.hasValidGeminiApiKey())
        assertTrue(settingsManager.hasAnyValidAiKey())
    }

    @Test
    fun testConnectionManager_BlocksConnectionWhenKeyMissing() {
        val connectionManager = ConnectionManager(
            context = context,
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        connectionManager.connect()
        assertFalse("ConnectionManager must not be connected when key is missing", connectionManager.isConnected())
        assertEquals(ConnectionState.DISCONNECTED, connectionManager.connectionState)
    }

    @Test
    fun testGeminiWebSocketClient_BlankKey_AbortsConnect() {
        val client = GeminiWebSocketClient(
            apiKey = "",
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionError = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "" }
        )

        client.connect()
        assertEquals("WebSocketClient must remain in DISCONNECTED state on blank key", ConnectionState.DISCONNECTED, client.currentState)
        assertFalse("WebSocketClient must not be connected on blank key", client.isConnected())
    }

    @Test
    fun testAssistantUseCase_BlankKey_BlocksAiMessageAndReturnsSecurityResult() = runBlocking {
        val useCase = AssistantUseCase(context)
        val result = useCase.sendTextMessage("What is the capital of France?")

        assertNotNull("Expected security ToolResult when key is missing", result)
        assertFalse("Result must not be successful", result!!.success)
        assertEquals("security_gate", result.toolName)
        assertTrue("Error message must indicate API key required", result.message?.contains("API key required") == true)
    }

    @Test
    fun testAssistantEngine_BlankKey_StartDoesNotConnect() {
        val engine = AssistantEngine(context)
        engine.start()
        assertFalse("AssistantEngine must not be connected when key is missing", engine.connectionManager.isConnected())
    }
}
