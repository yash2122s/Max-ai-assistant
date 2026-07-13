package com.example.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class GeminiWebSocketClient(
    private val apiKey: String,
    val voiceName: String = "Aoede",
    val responseLanguage: String = "Tenglish",
    private val onMessageReceived: (String) -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionError: (Throwable) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onExecuteAutomation: (String) -> String
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    
    private val MODEL_NAME = "gemini-3.1-flash-live-preview"
    
    private var isSetupComplete = false
    private var lastInputTranscription: String = ""
    
    fun connect() {
        isSetupComplete = false
        onConnectionStateChanged(ConnectionState.CONNECTING)
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        Log.d("GeminiWebSocket", "Connecting with key: ${apiKey.take(8)}...${apiKey.takeLast(4)} (length=${apiKey.length})")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.d("GeminiWebSocket", "WebSocket onOpen - sending setup")
                sendSetupMessage()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("GeminiWebSocket", "Received text message: ${text.take(500)}")
                handleIncomingMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                super.onMessage(webSocket, bytes)
                val text = bytes.utf8()
                Log.d("GeminiWebSocket", "Received bytes message: ${text.take(500)}")
                handleIncomingMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e("GeminiWebSocket", "WebSocket onFailure: ${t.message}", t)
                response?.let { Log.e("GeminiWebSocket", "Response code: ${it.code}, body: ${it.body?.string()?.take(500)}") }
                onConnectionStateChanged(ConnectionState.FAILED)
                onConnectionError(t)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Log.d("GeminiWebSocket", "WebSocket onClosing: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Log.d("GeminiWebSocket", "WebSocket onClosed: $code $reason")
                onConnectionStateChanged(ConnectionState.DISCONNECTED)
            }
        })
    }
    
    private fun sendSetupMessage() {
        val systemInstructionText = PromptBuilder.buildSystemInstruction(responseLanguage)

        val toolsJsonStr = """[{"functionDeclarations":[{"name":"execute_automation","description":"Executes an automation command on the Android device.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"The action to perform, e.g. OPEN_APP, FLASHLIGHT_ON, SET_VOLUME, VOLUME_UP, VOLUME_DOWN, SET_BRIGHTNESS, BRIGHTNESS_UP, BRIGHTNESS_DOWN, DND_ON, DND_OFF, SILENT_MODE_ON, SILENT_MODE_OFF"},"app":{"type":"STRING","description":"The app name to open, if applicable"},"query":{"type":"STRING","description":"The search query or video name, if applicable"},"percent":{"type":"INTEGER","description":"The volume or brightness percentage value from 0 to 100, if applicable"},"direction":{"type":"STRING","description":"The direction for change, either 'up' or 'down', if applicable"},"mode":{"type":"STRING","description":"The target mode for ringer or DND, either 'silent', 'vibrate', 'normal', 'dnd_on', 'dnd_off', if applicable"}},"required":["action"]}},{"name":"call_contact","description":"Calls a contact by their name or dials a specific phone number directly.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The name of the contact to call, or a raw phone number (e.g. 'Mom', '9876543210')."}},"required":["contact"]}},{"name":"youtube_search","description":"Search YouTube for the given query and automatically play the first matching video. Use this tool whenever the user wants to: play music, play songs, search YouTube, watch videos, search and play content. Do NOT use open_app for these requests.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The video title, artist, or song to play, e.g. 'Starboy', 'Believer'."}},"required":["query"]}},{"name":"open_app","description":"Opens an app on the phone by its name.","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","description":"The name of the app to open, e.g. Instagram, WhatsApp"}},"required":["app_name"]}},{"name":"send_whatsapp_message","description":"Sends a WhatsApp message to a contact name or phone number.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The contact name or phone number to send the message to."},"message":{"type":"STRING","description":"The message text content to send."}},"required":["contact","message"]}},{"name":"flashlight","description":"Turns the phone flashlight (torch) ON or OFF.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to turn ON, false to turn OFF."}},"required":["enabled"]}},{"name":"schedule_task","description":"Schedules a tool to execute at a later time.","parameters":{"type":"OBJECT","properties":{"tool":{"type":"STRING","description":"The name of the target tool to run (flashlight, send_whatsapp_message, open_app)."},"time_expression":{"type":"STRING","description":"The natural language expression when it should run (e.g. 'in 5 minutes', 'tomorrow at 9 AM', 'tonight at 8:30 PM', 'on Friday at 3 PM')."},"repeat_type":{"type":"STRING","description":"Optional repeat interval ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY').","enum":["NONE","DAILY","WEEKLY","MONTHLY"]},"contact":{"type":"STRING","description":"Optional contact name/number if scheduling send_whatsapp_message."},"message":{"type":"STRING","description":"Optional message body if scheduling send_whatsapp_message."},"app_name":{"type":"STRING","description":"Optional app name if scheduling open_app."},"enabled":{"type":"BOOLEAN","description":"Optional flashlight state if scheduling flashlight."}},"required":["tool","time_expression"]}},{"name":"cancel_task","description":"Cancels a pending scheduled task by its task ID.","parameters":{"type":"OBJECT","properties":{"task_id":{"type":"STRING","description":"The unique ID of the task to cancel."}},"required":["task_id"]}},{"name":"list_scheduled_tasks","description":"Returns a list of all currently active pending scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},{"name":"diagnostics","description":"Runs MAX diagnostics to report all registered tools and their actions.","parameters":{"type":"OBJECT","properties":{}}},{"name":"create_contact","description":"Creates a new contact on the device.","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING","description":"The display name of the contact"},"phone":{"type":"STRING","description":"The phone number of the contact"}},"required":["name"]}},{"name":"windows_cmd","description":"Executes a command on the paired Windows laptop agent. Actions include listing directory (dir), changing working directory (cd), locating program files (where), or echoing output (echo).","parameters":{"type":"OBJECT","properties":{"cmd_action":{"type":"STRING","description":"The action to execute: 'dir' to list files, 'cd' to change folders, 'where' to locate executables, 'echo' to print a string.","enum":["dir","cd","where","echo"]},"path":{"type":"STRING","description":"The folder path target, if applicable (e.g. for dir or cd)."},"message":{"type":"STRING","description":"The message string target, if applicable (e.g. for echo)."},"program":{"type":"STRING","description":"The program executable search name, if applicable (e.g. for where)."}},"required":["cmd_action"]}}]}]"""
        val jsonTextValue = Gson().toJson(systemInstructionText)
        val setupJson = """{"setup":{"model":"models/$MODEL_NAME","generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"$voiceName"}}}},"systemInstruction":{"parts":[{"text":$jsonTextValue}]},"tools":$toolsJsonStr}}"""
        
        Log.d("GeminiWebSocket", "Sending setup: $setupJson")
        val sent = webSocket?.send(setupJson)
        Log.d("GeminiWebSocket", "Setup send result: $sent")
    }
    
    fun sendInitialTrigger() {
        Log.d("GeminiWebSocket", "Sending initial trigger hello...")
        sendText("Hello, please say your greeting.")
    }
    
    private fun handleIncomingMessage(json: String) {
        try {
            val gson = Gson()
            val message = gson.fromJson(json, ServerMessage::class.java)
            
            // Check for setup complete
            if (json.contains("\"setupComplete\"")) {
                Log.d("GeminiWebSocket", "Setup complete received!")
                isSetupComplete = true
                onConnectionStateChanged(ConnectionState.CONNECTED)
                return
            }
            
            message.serverContent?.inputTranscription?.text?.let { transcription ->
                lastInputTranscription = transcription
                Log.d("GeminiWebSocket", "User voice transcription tracked: '$lastInputTranscription'")
            }

            message.serverContent?.modelTurn?.parts?.forEach { part ->
                part.text?.let { text ->
                    Log.d("GeminiWebSocket", "Text received: $text")
                    onMessageReceived(text)
                }
                
                part.inlineData?.let { inlineData ->
                    val audioBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                    Log.d("HUD_TEST", "WebSocket received audio bytes: ${audioBytes.size}, invoking callback")
                    onAudioReceived(audioBytes)
                }
            }
 
            message.toolCall?.functionCalls?.forEach { functionCall ->
                Log.d("GeminiWebSocket", "Function call received: ${functionCall.name}")
                if (ToolDispatcher.supportedTools.contains(functionCall.name)) {
                    val jsonStr = ToolDispatcher.dispatch(functionCall, lastInputTranscription)
                    Log.d("GeminiWebSocket", "Unified executing json: $jsonStr")
                    
                    val resultJsonStr = onExecuteAutomation(jsonStr)
                    
                    // Reply to the tool call so the model continues
                    functionCall.id?.let { callId ->
                        sendToolResponse(callId, functionCall.name, resultJsonStr)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiWebSocket", "Error handling message: ${e.message}", e)
        }
    }
    
    fun sendText(text: String) {
        if (!isSetupComplete) {
            Log.w("GeminiWebSocket", "Cannot send text before setupComplete")
            return
        }
        val textJson = """
        {
            "clientContent": {
                "turns": [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "$text"
                            }
                        ]
                    }
                ],
                "turnComplete": true
            }
        }
        """.trimIndent()
        
        Log.d("GeminiWebSocket", "Sending text: $textJson")
        webSocket?.send(textJson)
    }
    
    fun sendAudio(audioData: ByteArray) {
        if (!isSetupComplete) return
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val audioJson = """
        {
            "realtimeInput": {
                "audio": {
                    "mimeType": "audio/pcm;rate=16000",
                    "data": "$base64Audio"
                }
            }
        }
        """.trimIndent()
        
        Log.d("GeminiWebSocket", "Sending audio chunk size: ${audioData.size}")
        webSocket?.send(audioJson)
    }
    
    fun disconnect() {
        webSocket?.close(1000, "Closing connection")
        webSocket = null
        onConnectionStateChanged(ConnectionState.DISCONNECTED)
    }

    fun sendToolResponse(id: String, name: String, responseJsonStr: String) {
        val responseJson = """
        {
            "toolResponse": {
                "functionResponses": [
                    {
                        "id": "$id",
                        "name": "$name",
                        "response": $responseJsonStr
                    }
                ]
            }
        }
        """.trimIndent()
        Log.d("GeminiWebSocket", "Sending tool response: $responseJson")
        webSocket?.send(responseJson)
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}
