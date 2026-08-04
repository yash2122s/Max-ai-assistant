package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.memory.data.MemoryRepository
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Job
import com.example.network.GeminiWebSocketClient
import com.example.network.ConnectionState
import com.example.utils.AudioRecorder
import com.example.utils.playAudioResponse
import com.example.automation.engine.ActionDispatcher
import com.example.voice.vision.ScreenCaptureProvider
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class ChatViewModel : ViewModel() {
    companion object {
        private var _instance: ChatViewModel? = null
        val instance: ChatViewModel
            get() {
                if (_instance == null) {
                    _instance = ChatViewModel()
                }
                return _instance!!
            }
        
        val rmsFlow = MutableStateFlow(0f)
        
        fun updateRms(value: Float) {
            rmsFlow.value = value
        }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private var webSocketClient: GeminiWebSocketClient? = null
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false
    
    var appContext: Context? = null
    private var memoryRepository: MemoryRepository? = null
    private var currentMemoriesMarkdown: String = ""
    private var memoryObservationJob: Job? = null
    private var chatHistoryJob: Job? = null
    private var connectionGeneration = 0
    
    fun initialize(context: Context, apiKey: String, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        appContext = context.applicationContext
        val appCtx = context.applicationContext
        memoryRepository = MemoryRepository(appCtx)

        if (chatHistoryJob == null || chatHistoryJob?.isActive == false) {
            chatHistoryJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.example.data.local.AppDatabase.getDatabase(appCtx).chatMessageDao()
                    .getAllMessagesFlow().collect { roomMsgs ->
                        val viewMsgs = roomMsgs.map { 
                            ChatMessage(
                                role = if (it.sender == "user") "You" else "Gemini", 
                                content = it.text, 
                                timestamp = it.timestamp
                            ) 
                        }.reversed()
                        _uiState.update { state ->
                            if (state.messages.isEmpty()) {
                                state.copy(messages = viewMsgs)
                            } else {
                                state
                            }
                        }
                    }
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryRepository?.backfillMissingEmbeddings()
        }

        if (memoryObservationJob == null || memoryObservationJob?.isActive == false) {
            memoryObservationJob = viewModelScope.launch {
                memoryRepository?.memoriesMarkdownFlow?.collect { latestMarkdown ->
                    if (webSocketClient != null && currentMemoriesMarkdown != latestMarkdown) {
                        android.util.Log.d("ChatViewModel", "Memories markdown updated, reconnecting to apply new memories...")
                        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                        val savedKey = prefs.getString("api_key", "") ?: ""
                        val savedVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede"
                        val savedLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish"
                        val savedModel = prefs.getString("gemini_model", "Auto") ?: "Auto"
                        val currentApiKey = if (savedKey.isNotEmpty()) savedKey else com.example.BuildConfig.GEMINI_API_KEY
                        reconnect(currentApiKey, savedVoice, savedLanguage, savedModel)
                    }
                }
            }
        }
        
        if (webSocketClient == null) {
            createNewConnection(apiKey, voiceName, responseLanguage, preferredModel)
        }
    }

    private fun persistChatMessage(role: String, text: String) {
        val ctx = appContext ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dbMsg = com.example.data.local.ChatMessage(
                    sender = if (role == "You") "user" else "jarvis",
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
                com.example.data.local.AppDatabase.getDatabase(ctx).chatMessageDao().insertMessage(dbMsg)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error persisting chat message", e)
            }
        }
    }

    private fun createNewConnection(apiKey: String, voiceName: String, responseLanguage: String, preferredModel: String = "Auto") {
        destroyOldConnection()

        _uiState.update { it.copy(connectionState = ConnectionState.CONNECTING, error = null) }
        hasSentTrigger = false

        viewModelScope.launch {
            val markdown = memoryRepository?.getMemoriesMarkdown() ?: ""
            currentMemoriesMarkdown = markdown

            synchronized(this@ChatViewModel) {
                if (webSocketClient != null) return@launch

                val currentGen = ++connectionGeneration
                webSocketClient = GeminiWebSocketClient(
                    apiKey = apiKey,
                    voiceName = voiceName,
                    responseLanguage = responseLanguage,
                    preferredModel = preferredModel,
                    memoriesMarkdown = markdown,
                    onMessageReceived = { text ->
                        val isValid = synchronized(this@ChatViewModel) {
                            currentGen == connectionGeneration
                        }
                        if (isValid) {
                            persistChatMessage("Gemini", text)
                            try {
                                val jsonStart = text.indexOf("{")
                                val jsonEnd = text.lastIndexOf("}")
                                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                                    val jsonStr = text.substring(jsonStart, jsonEnd + 1)
                                    val jsonObj = JSONObject(jsonStr)
                                    _uiState.update { state -> 
                                        state.copy(pendingAutomation = jsonObj)
                                    }
                                }
                            } catch (e: JSONException) {
                                // Not valid JSON
                            }
                            
                            _uiState.update { it.copy(
                                messages = listOf(ChatMessage("Gemini", text)) + it.messages,
                                isThinking = false
                            )}
                        }
                    },
                    onAudioReceived = { audioData ->
                        val isValid = synchronized(this@ChatViewModel) {
                            currentGen == connectionGeneration
                        }
                        if (isValid) {
                            playAudioResponse(audioData)
                        }
                    },
                    onConnectionError = { error ->
                        val isValid = synchronized(this@ChatViewModel) {
                            currentGen == connectionGeneration
                        }
                        if (isValid) {
                            _uiState.update { it.copy(error = error.message) }
                        }
                    },
                    onConnectionStateChanged = { state ->
                        val isValid = synchronized(this@ChatViewModel) {
                            currentGen == connectionGeneration
                        }
                        if (isValid) {
                            _uiState.update { it.copy(connectionState = state) }
                        }
                    },
                    onExecuteAutomation = { jsonStr ->
                        val isStale = synchronized(this@ChatViewModel) {
                            currentGen != connectionGeneration
                        }
                        if (isStale) {
                            JSONObject().apply {
                                put("status", "error")
                                put("reason", "Stale connection generation")
                            }.toString()
                        } else {
                            val ctx = appContext
                            if (ctx != null) {
                                try {
                                    val jsonObj = JSONObject(jsonStr)
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

                                    val actionResultJsonStr = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                        ActionDispatcher.dispatchWithResult(ctx, jsonObj)
                                    }
                                    val resultObj = JSONObject(actionResultJsonStr)
                                    val status = resultObj.optString("status")
                                    val isSuccess = status == "success" || resultObj.optBoolean("success", false)
                                    val reason = if (resultObj.has("reason")) {
                                        resultObj.optString("reason")
                                    } else {
                                        resultObj.optString("message", "Unknown error")
                                    }
                                    
                                    val uiMessage = if (isSuccess) {
                                        val toolLogName = if (functionName.isNotEmpty()) functionName else action
                                        "Executed $toolLogName successfully."
                                    } else {
                                        "Failed to execute: $reason"
                                    }
                                    
                                    _uiState.update { state ->
                                        state.copy(messages = listOf(ChatMessage("Gemini", uiMessage)) + state.messages)
                                    }
                                    actionResultJsonStr
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatViewModel", "Error executing automation", e)
                                    JSONObject().apply {
                                        put("status", "error")
                                        put("reason", e.message ?: "Unknown error in View Model execution")
                                    }.toString()
                                }
                            } else {
                                JSONObject().apply {
                                    put("status", "error")
                                    put("reason", "Context not initialized in View Model")
                                }.toString()
                            }
                        }
                    }
                )
                webSocketClient?.connect()
            }
        }
    }

    private fun destroyOldConnection() {
        webSocketClient?.disconnect()
        webSocketClient = null
    }

    fun reconnect(newApiKey: String, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        createNewConnection(newApiKey, voiceName, responseLanguage, preferredModel)
    }
    
    fun sendTextMessage(text: String) {
        persistChatMessage("You", text)
        _uiState.update { it.copy(
            messages = listOf(ChatMessage("You", text)) + it.messages,
            isThinking = true
        )}

        val ctx = appContext
        if (ctx != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val offlineResult = com.example.automation.engine.OfflineCommandEngine.executeIfMatched(ctx, text)
                if (offlineResult != null) {
                    val rawMsg = offlineResult.message ?: if (offlineResult.success) "Action executed." else "Action failed."
                    val replyText = if (offlineResult.success) rawMsg else "Failed: $rawMsg"
                    persistChatMessage("Gemini", replyText)
                    _uiState.update { state ->
                        state.copy(
                            messages = listOf(ChatMessage("MAX (Offline)", replyText)) + state.messages,
                            isThinking = false
                        )
                    }
                }
            }
        }

        val cleanedText = text.lowercase().trim().replace(Regex("[.,!?]"), "")
        
        // Volume Interceptor
        val volumePatternPercent = Regex("set\\s+volume\\s+to\\s+(\\d+)(?:%|\\s+percent)?")
        val volumePatternUp = Regex("(?:increase|raise|volume|sound)\\s+(?:up|volume|sound|pencu|penchandi)")
        val volumePatternDown = Regex("(?:decrease|lower|volume|sound)\\s+(?:down|volume|sound|taggincu|tagginchandi)")
        
        var volumeActionJson: JSONObject? = null
        val volMatchPercent = volumePatternPercent.find(cleanedText)
        if (volMatchPercent != null) {
            val percentVal = volMatchPercent.groupValues[1].toIntOrNull() ?: 50
            volumeActionJson = JSONObject().apply {
                put("action", "SET_VOLUME")
                put("percent", percentVal)
            }
        } else if (volumePatternUp.containsMatchIn(cleanedText)) {
            volumeActionJson = JSONObject().apply {
                put("action", "SET_VOLUME")
                put("direction", "up")
            }
        } else if (volumePatternDown.containsMatchIn(cleanedText)) {
            volumeActionJson = JSONObject().apply {
                put("action", "SET_VOLUME")
                put("direction", "down")
            }
        }

        if (volumeActionJson != null) {
            _uiState.update { it.copy(pendingAutomation = volumeActionJson) }
            _uiState.update { it.copy(
                messages = listOf(ChatMessage("Gemini", "Adjusting volume...")) + it.messages
            )}
            return
        }

        // Brightness Interceptor
        val brightnessPatternPercent = Regex("set\\s+brightness\\s+to\\s+(\\d+)(?:%|\\s+percent)?")
        val brightnessPatternUp = Regex("(?:increase|raise|brightness|light)\\s+(?:up|brightness|light|pencu|penchandi)")
        val brightnessPatternDown = Regex("(?:decrease|lower|brightness|light)\\s+(?:down|brightness|light|taggincu|tagginchandi)")
        
        var brightnessActionJson: JSONObject? = null
        val brightMatchPercent = brightnessPatternPercent.find(cleanedText)
        if (brightMatchPercent != null) {
            val percentVal = brightMatchPercent.groupValues[1].toIntOrNull() ?: 50
            brightnessActionJson = JSONObject().apply {
                put("action", "SET_BRIGHTNESS")
                put("percent", percentVal)
            }
        } else if (brightnessPatternUp.containsMatchIn(cleanedText)) {
            brightnessActionJson = JSONObject().apply {
                put("action", "SET_BRIGHTNESS")
                put("direction", "up")
            }
        } else if (brightnessPatternDown.containsMatchIn(cleanedText)) {
            brightnessActionJson = JSONObject().apply {
                put("action", "SET_BRIGHTNESS")
                put("direction", "down")
            }
        }

        if (brightnessActionJson != null) {
            _uiState.update { it.copy(pendingAutomation = brightnessActionJson) }
            _uiState.update { it.copy(
                messages = listOf(ChatMessage("Gemini", "Adjusting screen brightness...")) + it.messages
            )}
            return
        }

        // Ringer & DND Interceptor
        val ringerPatternSilentOn = Regex("(?:silent|mute|nisshabdam)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)")
        val ringerPatternSilentOff = Regex("(?:silent|mute|nisshabdam)\\s+(?:mode\\s+)?(?:off|deactivate|teesey|teeseyyi)")
        val ringerPatternVibrate = Regex("(?:vibrate|vibration|kampanam)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)?")
        val ringerPatternDndOn = Regex("(?:dnd|do not disturb|disturb cheyoddu)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)")
        val ringerPatternDndOff = Regex("(?:dnd|do not disturb|disturb cheyoddu)\\s+(?:mode\\s+)?(?:off|deactivate|teesey|teeseyyi)")

        var ringerActionJson: JSONObject? = null
        if (ringerPatternSilentOn.containsMatchIn(cleanedText)) {
            ringerActionJson = JSONObject().apply {
                put("action", "SET_RINGER_MODE")
                put("mode", "silent")
            }
        } else if (ringerPatternSilentOff.containsMatchIn(cleanedText)) {
            ringerActionJson = JSONObject().apply {
                put("action", "SET_RINGER_MODE")
                put("mode", "normal")
            }
        } else if (ringerPatternVibrate.containsMatchIn(cleanedText)) {
            ringerActionJson = JSONObject().apply {
                put("action", "SET_RINGER_MODE")
                put("mode", "vibrate")
            }
        } else if (ringerPatternDndOn.containsMatchIn(cleanedText)) {
            ringerActionJson = JSONObject().apply {
                put("action", "SET_RINGER_MODE")
                put("mode", "dnd_on")
            }
        } else if (ringerPatternDndOff.containsMatchIn(cleanedText)) {
            ringerActionJson = JSONObject().apply {
                put("action", "SET_RINGER_MODE")
                put("mode", "dnd_off")
            }
        }

        if (ringerActionJson != null) {
            _uiState.update { it.copy(pendingAutomation = ringerActionJson) }
            val modeLabel = ringerActionJson.optString("mode")
            _uiState.update { it.copy(
                messages = listOf(ChatMessage("Gemini", "Updating ringer mode: $modeLabel...")) + it.messages
            )}
            return
        }

        // Windows PC Command Interceptor
        val isPcTarget = cleanedText.contains("pc") || cleanedText.contains("laptop") || cleanedText.contains("computer") || cleanedText.contains("ల్యాప్‌టాప్")
        if (isPcTarget) {
            var pcActionJson: JSONObject? = null
            var pcMsg = ""

            if (cleanedText.contains("lock")) {
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.terminal:run")
                    put("command", "rundll32.exe user32.dll,LockWorkStation")
                }
                pcMsg = "Locking PC..."
            } else if (cleanedText.contains("chrome")) {
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.app:launch")
                    put("app_name", "chrome")
                }
                pcMsg = "Opening Chrome on PC..."
            } else if (cleanedText.contains("notepad")) {
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.app:launch")
                    put("app_name", "notepad")
                }
                pcMsg = "Opening Notepad on PC..."
            } else if (cleanedText.contains("calculator") || cleanedText.contains("calc")) {
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.app:launch")
                    put("app_name", "calc")
                }
                pcMsg = "Opening Calculator on PC..."
            } else if (cleanedText.contains("open")) {
                val appToOpen = when {
                    cleanedText.contains("edge") -> "msedge"
                    cleanedText.contains("code") || cleanedText.contains("vs code") -> "code"
                    cleanedText.contains("spotify") -> "spotify"
                    cleanedText.contains("cmd") || cleanedText.contains("terminal") -> "cmd"
                    cleanedText.contains("explorer") -> "explorer"
                    else -> "chrome"
                }
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.app:launch")
                    put("app_name", appToOpen)
                }
                pcMsg = "Launching $appToOpen on PC..."
            } else if (cleanedText.contains("screenshot")) {
                pcActionJson = JSONObject().apply {
                    put("action", "WINDOWS_AGENT")
                    put("agent_action", "core.vision:capture")
                }
                pcMsg = "Taking screenshot of PC..."
            }

            if (pcActionJson != null) {
                _uiState.update { it.copy(pendingAutomation = pcActionJson) }
                _uiState.update { it.copy(
                    messages = listOf(ChatMessage("Gemini", pcMsg)) + it.messages
                )}
                return
            }
        }

        // Call Interceptor
        val callPattern1 = Regex("(?:call|dial)\\s+(.+)")
        val callPattern2 = Regex("(.+?)\\s*(?:ki\\s+)?(?:call\\s+chey|call\\s+cheyyi|phone\\s+chey|phone\\s+cheyyi)")
        
        var callActionJson: JSONObject? = null
        val callMatch1 = callPattern1.find(cleanedText)
        if (callMatch1 != null) {
            val contactName = callMatch1.groupValues[1]
            callActionJson = JSONObject().apply {
                put("action", "CALL_PHONE")
                put("contact", contactName)
            }
        } else {
            val callMatch2 = callPattern2.find(cleanedText)
            if (callMatch2 != null) {
                val contactName = callMatch2.groupValues[1]
                callActionJson = JSONObject().apply {
                    put("action", "CALL_PHONE")
                    put("contact", contactName)
                }
            }
        }

        if (callActionJson != null) {
            _uiState.update { it.copy(pendingAutomation = callActionJson) }
            val contactName = callActionJson.optString("contact")
            _uiState.update { it.copy(
                messages = listOf(ChatMessage("Gemini", "Calling $contactName...")) + it.messages
            )}
            return
        }

        // Create Contact Interceptor
        val createContactPattern = Regex("(?:create\\s+contact|add\\s+contact|new\\s+contact)\\s*(?:named|for)?\\s+(.+?)\\s+(?:with|number|phone)?\\s+(\\d+)")
        val createContactMatch = createContactPattern.find(cleanedText)
        if (createContactMatch != null) {
            val contactName = createContactMatch.groupValues[1].trim()
            val phoneNumber = createContactMatch.groupValues[2].trim()
            try {
                val jsonObj = JSONObject().apply {
                    put("action", "CREATE_CONTACT")
                    put("name", contactName)
                    put("phone", phoneNumber)
                }
                _uiState.update { it.copy(pendingAutomation = jsonObj) }
                _uiState.update { it.copy(
                    messages = listOf(ChatMessage("Gemini", "Opening contact creator for $contactName...")) + it.messages
                )}
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // YouTube Command Interception with dynamic query extraction
        val youtubePattern1 = Regex("(?:play|search)\\s+(.+?)\\s+on\\s+youtube")
        val youtubePattern2 = Regex("youtube\\s+(?:lo|search)\\s+(.+?)(?:\\s+play\\s+chey|\\s+play\\s+cheyyi|\\s+open\\s+chey|\\s+open\\s+cheyyi)?")
        val youtubePattern3 = Regex("యూట్యూబ్‌లో\\s+(.+?)\\s+ప్లే\\s+చేయి")
        
        var youtubeQuery: String? = null
        val match1 = youtubePattern1.find(cleanedText)
        if (match1 != null) {
            youtubeQuery = match1.groupValues[1]
        } else {
            val match2 = youtubePattern2.find(cleanedText)
            if (match2 != null) {
                youtubeQuery = match2.groupValues[1]
            } else {
                val match3 = youtubePattern3.find(cleanedText)
                if (match3 != null) {
                    youtubeQuery = match3.groupValues[1]
                }
            }
        }

        if (youtubeQuery != null) {
            try {
                val jsonObj = JSONObject().apply {
                    put("action", "YOUTUBE_SEARCH")
                    put("query", youtubeQuery)
                }
                _uiState.update { it.copy(pendingAutomation = jsonObj) }
                _uiState.update { it.copy(
                    messages = listOf(ChatMessage("Gemini", "Searching YouTube for: $youtubeQuery...")) + it.messages
                )}
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Diagnostics Interceptor
        val diagnosticsPattern = Regex("(?:show\\s+registered\\s+tools|max\\s+diagnostics|diagnostics|registered\\s+tools)")
        if (diagnosticsPattern.containsMatchIn(cleanedText)) {
            val context = appContext
            if (context != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val req = com.example.automation.engine.ExecutionRequest(
                        action = "MAX_DIAGNOSTICS",
                        arguments = com.google.gson.JsonObject(),
                        source = com.example.automation.engine.ExecutionSource.MANUAL
                    )
                    val result = com.example.automation.engine.ExecutionEngine.execute(context, req)
                    val reply = result.message ?: "Could not run diagnostics."
                    _uiState.update { state ->
                        state.copy(messages = listOf(ChatMessage("Gemini", reply)) + state.messages)
                    }
                }
                return
            }
        }

        // Local interception for system navigation commands (English & Telugu)
        val isHomeCommand = cleanedText in listOf(
            "go to home screen", "go to the home screen", "go home", "home screen", "home", "take me home", "press the home button", "return to the home screen",
            "home screen ki vellu", "హోమ్ స్క్రీన్‌కి వెళ్ళు", "home screen vellu", "home ki vellu"
        )
        val isBackCommand = cleanedText in listOf(
            "go back", "back", "back ki velli", "వెనుకకు వెళ్ళు", "వెనక్కి వెళ్ళు", "back vellu"
        )
        val isRecentsCommand = cleanedText in listOf(
            "open recent apps", "recent apps open chey", "recent apps", "రీసెంట్ యాప్స్ ఓపెన్ చెయ్", "recents open chey", "recents"
        )
        val isNotificationsCommand = cleanedText in listOf(
            "open notifications", "show notifications", "notifications", "notifications open chey", "నోటిఫికేషన్స్ ఓపెన్ చెయ్"
        )
        val isQuickSettingsCommand = cleanedText in listOf(
            "open quick settings", "quick settings", "quick settings open chey", "క్విక్ సెట్టింగ్స్ ఓపెన్ చెయ్"
        )
        val isScreenshotCommand = cleanedText in listOf(
            "take screenshot", "screenshot", "take a screenshot", "screenshot teeyi", "screenshot chey", "screenshot cheyyi", "స్క్రీన్‌షాట్ తీయి", "స్క్రీన్ షాట్ తీయి"
        )

        val isVisionQuery = isScreenshotCommand || 
            cleanedText.contains("screen") || 
            cleanedText.contains("display") || 
            cleanedText.contains(" what's on") || 
            cleanedText.contains(" read this") ||
            cleanedText.contains("స్క్రీన్")

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (isVisionQuery) {
                android.util.Log.d("ChatViewModel", "Vision query detected in prompt. Capturing screen frame...")
                val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    val sent = webSocketClient?.sendVideoFrame(jpeg)
                    android.util.Log.d("ChatViewModel", "Transmitted screen JPEG frame before prompt: sent=$sent (${jpeg.size} bytes)")
                } else {
                    android.util.Log.w("ChatViewModel", "Screen capture returned null or empty bytes!")
                }
            }
            webSocketClient?.sendText(text)
        }
    }
    
    private var hasSentTrigger = false

    fun toggleRecording() {
        if (isRecording) {
            stopAudioRecording()
        } else {
            // Barge-in: flush playing audio before listening
            com.example.utils.stopAudioResponse()
            if (!hasSentTrigger) {
                webSocketClient?.sendInitialTrigger()
                hasSentTrigger = true
            }
            startAudioRecording()
        }
        isRecording = !isRecording
        _uiState.update { it.copy(isRecording = isRecording) }
    }
    
    private var videoStreamJob: kotlinx.coroutines.Job? = null

    private fun startAudioRecording() {
        // Barge-in: flush playing audio so microphone does not capture speaker output
        com.example.utils.stopAudioResponse()
        videoStreamJob?.cancel()
        videoStreamJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    val sent = webSocketClient?.sendVideoFrame(jpeg)
                    android.util.Log.d("ChatViewModel", "[VisionTelemetry] Live voice video frame sent: sent=$sent (${jpeg.size} bytes)")
                } else {
                    android.util.Log.w("ChatViewModel", "[VisionTelemetry] Live voice video frame capture returned null/empty bytes!")
                }
                kotlinx.coroutines.delay(2500) // Stream fresh screen frame every 2.5s during active voice session
            }
        }

        if (audioRecorder == null) {
            audioRecorder = AudioRecorder()
        }
        audioRecorder?.startRecording { audioChunk ->
            webSocketClient?.sendAudio(audioChunk)
        }
    }
    
    private fun stopAudioRecording() {
        videoStreamJob?.cancel()
        videoStreamJob = null
        audioRecorder?.stopRecording()
        audioRecorder = null
    }
    
    override fun onCleared() {
        super.onCleared()
        webSocketClient?.disconnect()
        stopAudioRecording()
        com.example.utils.stopAudioResponse()
    }
    
    private fun hasMemoriesChanged(old: List<PermanentMemory>, new: List<PermanentMemory>): Boolean {
        if (old.size != new.size) return true
        val oldMap = old.associateBy { it.memoryId }
        for (newMemory in new) {
            val oldMemory = oldMap[newMemory.memoryId] ?: return true
            if (oldMemory.title != newMemory.title ||
                oldMemory.content != newMemory.content ||
                oldMemory.pinned != newMemory.pinned ||
                oldMemory.enabled != newMemory.enabled
            ) {
                return true
            }
        }
        return false
    }

    fun clearPendingAutomation() {
        _uiState.update { it.copy(pendingAutomation = null) }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val isThinking: Boolean = false,
    val error: String? = null,
    val pendingAutomation: JSONObject? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED
)

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
