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
    fun handleIncomingText(text: String) {
        AssistantLogger.recordFirstTranscript()
        AssistantEventBus.emit(AssistantEvent.TextTranscribed("Gemini", text))
    }

    fun handleIncomingAudio(audioData: ByteArray, onRmsChanged: (Float) -> Unit) {
        AssistantLogger.recordFirstAudioResponse()
        onStateTransition(SessionState.STREAMING_AUDIO)
        audioPlayer.play(audioData, onRmsChanged)
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
        if (audioPlayer.isPlaying()) {
            AssistantLogger.logInfo("Barge-in triggered: user speech detected during assistant playback. Stopping TTS output.")
            audioPlayer.stopAndFlush()
            onStateTransition(SessionState.LISTENING)
        }
    }

    fun stopAllPlayback() {
        audioPlayer.stopAndFlush()
    }
}
