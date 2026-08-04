package com.example.voice.assistant

import android.content.Context
import com.example.network.ConnectionState
import com.example.voice.audio.*
import com.example.voice.tools.*
import org.json.JSONObject

import com.example.voice.vision.ScreenCaptureProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                try {
                    val jsonObj = org.json.JSONObject(jsonStr)
                    val functionName = jsonObj.optString("function_name")
                    val action = jsonObj.optString("action")
                
                    if (action.isEmpty() && functionName.isNotEmpty()) {
                        val actionName = when (functionName) {
                            "open_app" -> "OPEN_APP"
                            "send_whatsapp_message" -> "SEND_WHATSAPP"
                            "flashlight" -> {
                                val state = jsonObj.optBoolean("enabled", true)
                                if (state) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"
                            }
                            "schedule_task" -> "SCHEDULE_TASK"
                            "cancel_task" -> "CANCEL_TASK"
                            "list_scheduled_tasks" -> "LIST_TASKS"
                            else -> functionName.uppercase()
                        }
                        jsonObj.put("action", actionName)
                    }

                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, jsonObj)
                    }

                } catch (e: Exception) {
                    android.util.Log.e("AssistantEngine", "Error executing automation in voice assistant", e)
                    org.json.JSONObject().apply {
                        put("status", "error")
                        put("reason", e.message ?: "Unknown error in voice tool execution")
                    }.toString()
                }
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
        
        // Capture active screen frame asynchronously to feed Gemini Live Vision context
        CoroutineScope(Dispatchers.IO).launch {
            val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
            if (jpeg != null && jpeg.isNotEmpty()) {
                connectionManager.sendVideoFrame(jpeg)
            }
        }

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
