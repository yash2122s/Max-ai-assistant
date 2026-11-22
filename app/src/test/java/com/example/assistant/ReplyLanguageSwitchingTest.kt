package com.example.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import com.example.domain.policy.AssistantLanguage
import com.example.domain.policy.LanguagePolicy
import com.example.domain.usecase.AssistantUseCase
import com.example.network.LiveSessionState
import com.example.network.SessionResumptionUpdate
import com.example.network.GeminiWebSocketClient
import com.example.ui.settings.SettingsViewModel
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.ConnectionManager
import com.example.network.ConnectionState
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import android.os.Looper
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplyLanguageSwitchingTest {
    private lateinit var app: Application
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        viewModels.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun supportedAndLegacyPreferencesAreCanonicalAndOverridesWin() {
        val settings = SettingsManager(app)
        for ((input, expected) in listOf(
            "Tenglish" to "Tinglish", " tinglish " to "Tinglish",
            "english" to "English", "TELUGU" to "Telugu", "తెలుగు" to "Telugu",
            "hindi" to "Hindi", "हिंदी" to "Hindi", "unsupported" to "Tinglish"
        )) {
            settings.languagePreference = input
            assertEquals(expected, settings.languagePreference)
            assertEquals(expected, SettingsManager(app).languagePreference)
        }
        settings.languagePreference = "Telugu"
        assertEquals(AssistantLanguage.ENGLISH,
            LanguagePolicy.resolveResponseLanguage("reply in English", settings.languagePreference))
        assertEquals(AssistantLanguage.TINGLISH,
            LanguagePolicy.resolveResponseLanguage("tenglish lo cheppu", settings.languagePreference))
        assertEquals(AssistantLanguage.TINGLISH,
            LanguagePolicy.resolveResponseLanguage("Tenglish only", settings.languagePreference))
        assertEquals(AssistantLanguage.TELUGU,
            LanguagePolicy.resolveResponseLanguage("what time is it", settings.languagePreference))
    }

    @Test fun observersOnDifferentManagerInstancesReceiveLatestPreferenceAndUnregister() = runBlocking {
        val first = SettingsManager(app)
        val second = SettingsManager(app)
        val changes = Channel<String>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            second.languagePreferenceFlow.collect { changes.send(it) }
        }
        try {
            assertEquals("Tinglish", withTimeout(2000) { changes.receive() })
            first.languagePreference = "Tenglish"
            // A legacy spelling is the same effective language, so it is not a change.
            first.languagePreference = "English"
            assertEquals("English", withTimeout(2000) { changes.receive() })
            first.languagePreference = "Hindi"
            assertEquals("Hindi", withTimeout(2000) { changes.receive() })
        } finally {
            job.cancel()
            changes.close()
        }
    }

    @Test fun savingPreferenceWithoutEngineDoesNotCreateAssistantOrNetworkSession() {
        val before = AssistantLifecycleManager.getEngine()
        AssistantUseCase(app).updateLanguage("English")
        assertEquals("English", SettingsManager(app).languagePreference)
        assertSame(before, AssistantLifecycleManager.getEngine())
    }

    @Test fun settingsScreenWritesCanonicalValueAndSeesExternalHomePreference() {
        val viewModel = SettingsViewModel(app)
        viewModels.put("settings", viewModel)
        viewModel.updateLanguagePreference("Tenglish")
        assertEquals("Tinglish", viewModel.uiState.value.languagePreference)
        viewModel.updateLanguagePreference("Telugu")
        assertEquals("Telugu", viewModel.uiState.value.languagePreference)
        // A separate writer (e.g. Home) updates the same preference and the screen observes it.
        SettingsManager(app).languagePreference = "English"
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("English", viewModel.uiState.value.languagePreference)
    }

    @Test fun languageChangeDiscardsResumptionButKeepsDuplicateCallProtection() {
        val state = LiveSessionState()
        val oldVersion = state.resumptionVersion()
        state.updateResumptionIfCurrent(oldVersion, "live-model", SessionResumptionUpdate("old", true))
        assertEquals("old", state.resumptionHandle("live-model"))
        assertTrue(state.reserveCall("call-id", "signature"))
        state.recordResult("call-id", "completed")

        state.invalidateResumption()
        assertNull(state.resumptionHandle("live-model"))
        assertFalse(state.reserveCall("call-id", "signature"))
        assertEquals("completed", state.resultFor("call-id", "signature"))
        state.updateResumptionIfCurrent(oldVersion, "live-model", SessionResumptionUpdate("late-old", true))
        assertNull(state.resumptionHandle("live-model"))

        val currentVersion = state.resumptionVersion()
        state.updateResumptionIfCurrent(currentVersion, "live-model", SessionResumptionUpdate("new", true))
        assertEquals("new", state.resumptionHandle("live-model"))
        // An ordinary transport reconnect continues on its fresh handle and retains the ledger.
        assertEquals("new", state.resumptionHandle("live-model"))
        assertFalse(state.reserveCall("call-id", "signature"))
        state.invalidateResumption()
        state.updateResumptionIfCurrent(currentVersion, "live-model", SessionResumptionUpdate("late-new", true))
        assertNull(state.resumptionHandle("live-model"))
    }

    @Test fun newLanguageSetupDoesNotIncludeOldHandleAndLateSocketCannotRestoreIt() {
        val state = LiveSessionState()
        state.updateResumption("gemini-3.8-live", SessionResumptionUpdate("old-handle", true))
        fun client(language: String) = GeminiWebSocketClient(
            apiKey = "", responseLanguage = language, sessionState = state,
            onMessageReceived = {}, onAudioReceived = {}, onConnectionError = {},
            onConnectionStateChanged = {}, onExecuteAutomation = { "{}" }
        )
        val old = client("Telugu")
        val before = JSONObject(old.buildSetupMessage()).getJSONObject("setup")
        assertEquals("old-handle", before.getJSONObject("sessionResumption").getString("handle"))
        state.invalidateResumption()
        val fresh = client("English")
        try {
            val setup = JSONObject(fresh.buildSetupMessage()).getJSONObject("setup")
            assertFalse(setup.getJSONObject("sessionResumption").has("handle"))
            assertTrue(setup.getJSONObject("systemInstruction").toString().contains("ENGLISH MODE"))
            old.handleIncomingMessage("""{"sessionResumptionUpdate":{"newHandle":"late-old","resumable":true}}""")
            assertNull(state.resumptionHandle("gemini-3.8-live"))
        } finally {
            old.disconnect()
            fresh.disconnect()
        }
    }

    private fun connectionManager() = ConnectionManager(
        context = app, onMessageReceived = {}, onAudioReceived = {},
        onConnectionStateChanged = {}, onExecuteAutomation = { "{}" }
    )

    private fun setField(manager: ConnectionManager, name: String, value: Any) {
        ConnectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    private fun getField(manager: ConnectionManager, name: String): Any? =
        ConnectionManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)

    @Test fun inactiveConnectionStaysInactiveWhenLanguageIsSaved() {
        val manager = connectionManager()
        try {
            SettingsManager(app).languagePreference = "Telugu"
            assertFalse(manager.updateLanguage("Telugu"))
            assertEquals(ConnectionState.DISCONNECTED, manager.connectionState)
            assertNull(getField(manager, "webSocketClient"))
            assertEquals(0, getField(manager, "connectionGeneration"))
        } finally { manager.destroy() }
    }

    @Test fun switchingDuringConnectionSetupUsesLatestLanguageAndRetainsSelectedModel() {
        val manager = connectionManager()
        try {
            // Simulate the async setup window before a socket exists; no real API key/network.
            setField(manager, "isExplicitlyDisconnected", false)
            setField(manager, "connectionState", ConnectionState.CONNECTING)
            setField(manager, "configuredLanguage", "Telugu")
            setField(manager, "configuredModel", "selected-live-model")
            val session = getField(manager, "liveSessionState") as LiveSessionState
            session.updateResumption("selected-live-model", SessionResumptionUpdate("old", true))
            session.reserveCall("executed", "signature")
            SettingsManager(app).languagePreference = "English"

            assertTrue(manager.updateLanguage("English"))
            assertEquals("English", getField(manager, "configuredLanguage"))
            assertEquals("selected-live-model", getField(manager, "configuredModel"))
            assertNull(session.resumptionHandle("selected-live-model"))
            assertFalse(session.reserveCall("executed", "signature"))

            // A delayed event for the previous choice must not undo a rapid newer choice.
            SettingsManager(app).languagePreference = "Hindi"
            assertTrue(manager.updateLanguage("English"))
            assertEquals("Hindi", getField(manager, "configuredLanguage"))
            assertFalse(manager.updateLanguage("Hindi"))
            assertNull(getField(manager, "webSocketClient"))
        } finally { manager.destroy() }
    }
}
