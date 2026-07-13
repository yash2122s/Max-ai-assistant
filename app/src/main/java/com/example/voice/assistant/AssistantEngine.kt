package com.example.voice.assistant

import android.content.Context
import com.example.network.ConnectionState
import com.example.voice.audio.*
import com.example.voice.tools.*
import org.json.JSONObject

class AssistantEngine(private val context: Context) {
    val stateMachine = SessionStateMachine()
    val conversationManager = ConversationManager()
    
    // Capabilities and Tools
    val capabilityProvider = CapabilityProvider(context)
    val executionPolicy = ExecutionPolicy(capabilityProvider)
    val authorizationManager = AuthorizationManager(context, executionPolicy)
    val toolRegistry = AssistantToolRegistry(context, authorizationManager)

    // Audio Stack
    val audioFocusManager = AudioFocusManager(context)
    val audioPlayer = AudioPlayer(audioFocusManager)
    
    private val audioProcessor = AudioProcessor {
        // Trigger silence timeout: stop recording audio
        stopListening()
    }
    val audioRecorder = AudioRecorder()

    // Connection manager and Streaming Response Manager as lateinit to resolve circular reference
    lateinit var connectionManager: ConnectionManager
    lateinit var streamingResponseManager: StreamingResponseManager

    init {
        connectionManager = ConnectionManager(
            context = context,
            onMessageReceived = { text ->
                conversationManager.addMessage("Gemini", text)
                streamingResponseManager.handleIncomingText(text)
            },
            onAudioReceived = { audioBytes ->
                streamingResponseManager.handleIncomingAudio(audioBytes) { rms ->
                    AssistantEventBus.emit(AssistantEvent.AudioRmsChanged(rms))
                }
            },
            onConnectionStateChanged = { connState ->
                val engineState = when (connState) {
                    ConnectionState.CONNECTED -> SessionState.CONNECTED
                    ConnectionState.CONNECTING -> SessionState.CONNECTING
                    ConnectionState.DISCONNECTED -> SessionState.IDLE
                    ConnectionState.FAILED -> SessionState.ERROR
                }
                stateMachine.transitionTo(engineState)
            },
            onExecuteAutomation = { jsonStr ->
                // Handled via the tool registry asynchronously, returning blank response for blocking interface
                ""
            }
        )

        streamingResponseManager = StreamingResponseManager(
            audioPlayer = audioPlayer,
            toolRegistry = toolRegistry,
            onExecuteToolResponse = { callId, toolName, resultJson ->
                connectionManager.sendToolResponse(callId, toolName, resultJson)
            },
            onStateTransition = { newState ->
                stateMachine.transitionTo(newState)
            }
        )
    }

    fun start() {
        AssistantLogger.startSession()
        if (!connectionManager.isConnected()) {
            connectionManager.connect()
        }
    }

    fun startListening() {
        if (audioRecorder.isRecording()) return
        
        // Interrupt Gemini audio playback if user starts speaking (barge-in)
        streamingResponseManager.handleUserSpeaking()
        
        stateMachine.transitionTo(SessionState.LISTENING)
        audioRecorder.startRecording(
            audioProcessor = audioProcessor,
            onAudioChunk = { chunk ->
                connectionManager.sendAudio(chunk)
            },
            onRmsChanged = { rms ->
                AssistantEventBus.emit(AssistantEvent.AudioRmsChanged(rms))
            }
        )
    }

    fun stopListening() {
        audioRecorder.stopRecording()
        if (stateMachine.getState() == SessionState.LISTENING) {
            stateMachine.transitionTo(SessionState.CONNECTED)
        }
    }

    fun sendTextMessage(text: String) {
        conversationManager.addMessage("You", text)
        stateMachine.transitionTo(SessionState.WAITING_FOR_MODEL)
        connectionManager.sendText(text)
    }

    fun pause() {
        stopListening()
        streamingResponseManager.stopAllPlayback()
    }

    fun stop() {
        pause()
    }

    fun destroy() {
        stop()
        connectionManager.disconnect()
    }
}
