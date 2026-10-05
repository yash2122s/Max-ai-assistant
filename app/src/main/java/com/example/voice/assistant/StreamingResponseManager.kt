package com.example.voice.assistant

import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.example.voice.audio.AudioPlayer
import com.example.voice.tools.AssistantToolRegistry
import org.json.JSONObject
import android.util.Log

class StreamingResponseManager(
    private val audioPlayer: AudioPlayer,
    private val toolRegistry: AssistantToolRegistry,
    private val onExecuteToolResponse: (String, String, String) -> Unit, // id, name, resultJson
    private val onStateTransition: (SessionState) -> Unit
) {
    private val currentTurnText = StringBuilder()

    fun handleIncomingText(text: String) {
        val sanitized = com.example.utils.InternalReasoningFilter.sanitize(text)
        if (sanitized.isBlank()) return
        AssistantLogger.recordFirstTranscript()
        onStateTransition(SessionState.STREAMING_TEXT)
        synchronized(currentTurnText) {
            if (currentTurnText.isNotEmpty()) {
                val lastChar = currentTurnText.last()
                val firstChar = sanitized.first()
                if (!lastChar.isWhitespace() && lastChar != '\n' && !sanitized.startsWith(" ") && firstChar.isLetterOrDigit()) {
                    currentTurnText.append(' ')
                }
            }
            currentTurnText.append(sanitized)
        }
        AssistantEventBus.emit(AssistantEvent.ResponseChunk(sanitized))
    }

    private var firstAudioLogged = false

    fun handleIncomingAudio(audioData: ByteArray, onRmsChanged: (Float) -> Unit) {
        if (!firstAudioLogged) {
            firstAudioLogged = true
            android.util.Log.d("VoiceLatency", "[VoiceLatency] first_audio ts=${System.currentTimeMillis()}")
        }
        AssistantLogger.recordFirstAudioResponse()
        onStateTransition(SessionState.STREAMING_AUDIO)
        audioPlayer.play(audioData, onRmsChanged)
    }

    fun handleTurnComplete() {
        android.util.Log.d("VoiceLatency", "[VoiceLatency] response_complete ts=${System.currentTimeMillis()}")
        android.util.Log.d("GeminiFlow", "[GeminiFlow] thinking_cleared (turn_complete)")
        firstAudioLogged = false
        val fullText = synchronized(currentTurnText) {
            val res = currentTurnText.toString()
            currentTurnText.setLength(0)
            res
        }
        val verifiedText = com.example.automation.engine.truth.ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(fullText)
        if (verifiedText != fullText) {
            android.util.Log.w("StreamingResponseMgr", "Overriding final assembled turn text from '$fullText' to '$verifiedText'")
            stopAllPlayback()
        }
        onStateTransition(SessionState.CONNECTED)
        AssistantEventBus.emit(AssistantEvent.TurnComplete(verifiedText))
    }

    fun handleToolCall(callId: String, toolName: String, args: String) {
        onStateTransition(SessionState.TOOL_EXECUTION)
        
        try {
            val dummyCall = FunctionCall(
                name = toolName,
                args = com.google.gson.JsonParser.parseString(args).asJsonObject,
                id = callId
            )

            // Normalize using ToolDispatcher
            val jsonStr = ToolDispatcher.dispatch(dummyCall, "")
            val jsonObj = JSONObject(jsonStr)
            val action = jsonObj.optString("action")
            
            toolRegistry.executeToolAsync(action, jsonObj) { resultJsonStr ->
                onStateTransition(SessionState.WAITING_TOOL_RESULT)
                onExecuteToolResponse(callId, toolName, resultJsonStr)
            }
        } catch (e: Exception) {
            Log.e("StreamingResponseMgr", "Error handling tool call: ${e.message}", e)
            onExecuteToolResponse(callId, toolName, JSONObject().apply {
                put("status", "error")
                put("reason", e.message ?: "Failed parsing tool arguments")
            }.toString())
        }
    }

    fun handleUserSpeaking() {
        stopAllPlayback()
    }

    fun stopAllPlayback() {
        audioPlayer.stopAndFlush()
    }
}
