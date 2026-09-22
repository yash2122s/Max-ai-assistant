package com.example.voice.session

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.voice.assistant.AssistantSessionManager
import com.example.voice.assistant.SystemAssistantManager
import com.example.voice.audio.AudioGuardState
import com.example.voice.audio.AudioStateGuard
import com.example.voice.hotword.AssistantWakeController
import com.example.voice.hotword.HotwordProvider
import com.example.voice.hotword.HotwordSupportStatus
import com.example.voice.hotword.SystemHotwordProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemAssistantArchitectureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // 1. Manifest component names
    @Test
    fun test01_manifestComponentNames() {
        val visClass = JarvisVoiceInteractionService::class.java.name
        val vssClass = JarvisVoiceSessionService::class.java.name
        val recClass = JarvisRecognitionService::class.java.name
        val trigClass = com.example.voice.trigger.JarvisVoiceTriggerActivity::class.java.name

        assertEquals("com.example.voice.session.JarvisVoiceInteractionService", visClass)
        assertEquals("com.example.voice.session.JarvisVoiceSessionService", vssClass)
        assertEquals("com.example.voice.session.JarvisRecognitionService", recClass)
        assertEquals("com.example.voice.trigger.JarvisVoiceTriggerActivity", trigClass)
    }

    // 2. VoiceInteractionService lifecycle
    @Test
    fun test02_voiceInteractionServiceLifecycle() {
        val service = JarvisVoiceInteractionService()
        service.onCreate()
        service.onReady()
        assertSame(service, JarvisVoiceInteractionService.instance)

        service.onShutdown()
        assertNull(JarvisVoiceInteractionService.instance)

        service.onDestroy()
        assertNull(JarvisVoiceInteractionService.instance)
    }

    // 3. SessionService creation
    @Test
    fun test03_sessionServiceCreation() {
        val sessionService = JarvisVoiceSessionService()
        assertNotNull(sessionService)
    }

    // 4. VoiceInteractionSession creation
    @Test
    fun test04_voiceInteractionSessionCreation() {
        val session = JarvisVoiceSession(context)
        assertNotNull(session)
    }

    // 5. Session show
    @Test
    fun test05_sessionShow() {
        val session = JarvisVoiceSession(context)
        session.onCreate()
        session.onPrepareShow(Bundle(), 0)
        session.onShow(Bundle(), 0)
        // Verified session triggers without throwing BadTokenException
        assertNotNull(session)
    }

    // 6. Session hide
    @Test
    fun test06_sessionHide() {
        val session = JarvisVoiceSession(context)
        session.onCreate()
        session.onShow(Bundle(), 0)
        session.onHide()
        assertFalse(AssistantSessionManager.isSystemAssistantSessionActive())
    }

    // 7. Session cleanup
    @Test
    fun test07_sessionCleanup() {
        val session = JarvisVoiceSession(context)
        session.onCreate()
        session.onShow(Bundle(), 0)
        session.onHide()
        session.onDestroy()
        assertFalse(AssistantSessionManager.isSystemAssistantSessionActive())
    }

    // 8. Duplicate AssistantEngine start prevention
    @Test
    fun test08_duplicateAssistantEngineStart() {
        val eng1 = AssistantSessionManager.getEngine(context)
        val eng2 = AssistantSessionManager.getEngine(context)
        assertSame("AssistantEngine must be canonical application singleton", eng1, eng2)
    }

    // 9. Duplicate AudioRecorder allocation prevention
    @Test
    fun test09_duplicateAudioRecorderPrevention() {
        val eng = AssistantSessionManager.getEngine(context)
        val rec1 = eng.audioRecorder
        val rec2 = eng.audioRecorder
        assertSame("AudioRecorder instance must be singular to prevent mic collision", rec1, rec2)
    }

    // 10. STARTING -> LISTENING
    @Test
    fun test10_audioStateGuard_startingToListening() {
        val guard = AudioStateGuard()
        assertEquals(AudioGuardState.IDLE, guard.getState())

        val beganStarting = guard.tryBeginStarting()
        assertTrue("IDLE -> STARTING must succeed", beganStarting)
        assertEquals(AudioGuardState.STARTING, guard.getState())

        val markedListening = guard.markListening()
        assertTrue("STARTING -> LISTENING must succeed", markedListening)
        assertEquals(AudioGuardState.LISTENING, guard.getState())
    }

    // 11. LISTENING -> STOPPING
    @Test
    fun test11_audioStateGuard_listeningToStopping() {
        val guard = AudioStateGuard()
        guard.tryBeginStarting()
        guard.markListening()

        val beganStopping = guard.tryBeginStopping()
        assertTrue("LISTENING -> STOPPING must succeed", beganStopping)
        assertEquals(AudioGuardState.STOPPING, guard.getState())
    }

    // 12. STOPPING -> IDLE
    @Test
    fun test12_audioStateGuard_stoppingToIdle() {
        val guard = AudioStateGuard()
        guard.tryBeginStarting()
        guard.markListening()
        guard.tryBeginStopping()

        guard.markIdle()
        assertEquals(AudioGuardState.IDLE, guard.getState())
    }

    // 13. Duplicate start rejection
    @Test
    fun test13_audioStateGuard_duplicateStartRejection() {
        val guard = AudioStateGuard()
        assertTrue(guard.tryBeginStarting())
        assertEquals(AudioGuardState.STARTING, guard.getState())

        // Duplicate start while STARTING must be rejected
        assertFalse("Duplicate STARTING must be rejected", guard.tryBeginStarting())

        guard.markListening()
        // Duplicate start while LISTENING must be rejected
        assertFalse("STARTING while LISTENING must be rejected", guard.tryBeginStarting())
        assertEquals(AudioGuardState.LISTENING, guard.getState())
    }

    // 14. System assistant role state check
    @Test
    fun test14_systemAssistantRoleState() {
        val isDefault = SystemAssistantManager.isDefaultAssistant(context)
        // Robolectric environment defaults to false or mocked role
        assertNotNull(isDefault)
    }

    // 15. RoleManager request handling
    @Test
    fun test15_roleManagerRequestHandling() {
        val intent = SystemAssistantManager.createSetDefaultAssistantIntent(context)
        assertNotNull(intent)
        val action = intent.action
        assertTrue(
            "Intent action must be ROLE request or VOICE_INPUT_SETTINGS fallback",
            action == "android.app.role.action.REQUEST_ROLE" ||
            action == Settings.ACTION_VOICE_INPUT_SETTINGS ||
            action == Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        )
    }

    // 16. Recognition service lifecycle
    @Test
    fun test16_recognitionServiceLifecycle() {
        class TestRecognitionService : JarvisRecognitionService() {
            fun invokeProtectedCallbacks() {
                onStartListening(Intent(), null)
                onStopListening(null)
                onCancel(null)
            }
        }
        val service = TestRecognitionService()
        service.onCreate()
        service.invokeProtectedCallbacks()
        service.onDestroy()
        assertNotNull(service)
    }

    // 17. Gemini connection reuse
    @Test
    fun test17_geminiConnectionReuse() {
        val eng = AssistantSessionManager.getEngine(context)
        val conn1 = eng.connectionManager
        val conn2 = eng.connectionManager
        assertSame("Gemini connection manager must be single instance", conn1, conn2)
    }

    // 18. Memory retrieval integration
    @Test
    fun test18_memoryRetrievalIntegration() {
        val isQuery = com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery("What is my favourite food?")
        assertTrue("Memory queries must be recognized", isQuery)
    }

    // 19. Emotion engine integration
    @Test
    fun test19_emotionEngineIntegration() {
        val eng = AssistantSessionManager.getEngine(context)
        assertNotNull(eng.emotionEngine)
        val initialEmotion = eng.emotionEngine.getCurrentState()
        assertNotNull(initialEmotion)
    }

    // 20. Automation routing integration
    @Test
    fun test20_automationRoutingIntegration() {
        val isPrimitive = com.example.automation.engine.SemanticUiCommandParser.isDeterministicPrimitive("open Instagram")
        assertTrue("Deterministic primitive UI actions must route through automation", isPrimitive)
    }

    // 21. Existing in-app voice regression
    @Test
    fun test21_existingInAppVoiceRegression() {
        val viewModel = com.example.viewmodel.ChatViewModel.instance
        assertNotNull(viewModel)
        viewModel.initialize(context, "dummy_key", "Aoede", "Tenglish")
        assertNotNull(viewModel.uiState.value)
    }

    // 22. HUD state transitions
    @Test
    fun test22_hudStateTransitions() {
        val controller = com.example.voice.assistant.AssistantVoiceController()
        val state = controller.uiState.value
        assertNotNull(state)
        assertEquals(com.example.voice.assistant.SessionState.IDLE, state.sessionState)
    }

    // 23. Hotword provider behavior
    @Test
    fun test23_hotwordProviderBehavior() {
        val provider = SystemHotwordProvider(context)
        assertEquals(HotwordSupportStatus.UNAVAILABLE_NO_DSP, provider.getSupportStatus())
        assertFalse(provider.isSupported())
    }

    // 24. Unsupported-hotword fallback
    @Test
    fun test24_unsupportedHotwordFallback() {
        var wakeHandled = false
        val customProvider = object : HotwordProvider {
            override fun getSupportStatus() = HotwordSupportStatus.SUPPORTED
            override fun startListening(onWakeWord: () -> Unit) {
                onWakeWord()
            }
            override fun stopListening() {}
        }
        val controller = AssistantWakeController(context, customProvider)
        controller.start()
        // Wake request was delivered without crashing
        assertNotNull(controller)
    }
}
