package com.example.core

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

enum class InputSource {
    CHAT, VOICE, HUD, TELEGRAM
}

data class JarvisResponse(
    val text: String,
    val source: InputSource,
    val isAutomation: Boolean
)

object JarvisCore {
    private const val TAG = "JarvisCore"

    private val _responses = MutableSharedFlow<JarvisResponse>()
    val responses = _responses.asSharedFlow()

    fun processCommand(context: Context, text: String, source: InputSource) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Processing command from $source: $text")
                
                // Save user message to UI database locally
                val db = AppDatabase.getDatabase(context)
                db.chatMessageDao().insertMessage(ChatMessage(sender = "user", text = text))
                
                val settings = com.example.data.preferences.SettingsManager(context)
                if (settings.isLocalAiMode) {
                    Log.d(TAG, "ConversationEngine placeholder for: $text")
                } else {
                    // Send to Cloud Brain
                    val payload = org.json.JSONObject().apply {
                        put("type", "user_message")
                        put("request_id", "req_" + System.currentTimeMillis())
                        val payloadObj = org.json.JSONObject().apply {
                            put("text", text)
                            put("input_mode", source.name.lowercase())
                            put("client_type", "android")
                        }
                        put("payload", payloadObj)
                    }
                    com.example.telegram.CloudSocketManager.sendMessage(payload.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command", e)
                emitResponse(JarvisResponse("Sorry, I encountered an error processing that request.", source, false))
            }
        }
    }
    
    fun processServerResponse(context: Context, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // Save Jarvis response to UI database locally
            val db = AppDatabase.getDatabase(context)
            db.chatMessageDao().insertMessage(ChatMessage(sender = "jarvis", text = text))
            
            emitResponse(JarvisResponse(text, InputSource.CHAT, false))
        }
    }

    suspend fun emitResponse(response: JarvisResponse) {
        _responses.emit(response)
        if (response.source == InputSource.VOICE || response.source == InputSource.HUD || response.source == InputSource.CHAT) {
            Log.d(TAG, "Speak placeholder for: ${response.text}")
        } else if (response.source == InputSource.TELEGRAM) {
            com.example.telegram.CloudSocketManager.sendReply(response.text)
        }
    }
}
