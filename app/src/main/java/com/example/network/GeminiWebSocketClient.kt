package com.example.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.example.memory.data.PermanentMemory
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import com.example.network.model.LiveThinkingLevel
import org.json.JSONObject
import org.json.JSONArray

import com.example.network.model.GeminiLiveModels
import com.example.network.model.GeminiModelMode
import com.example.network.model.ModelHandoffContext
import com.example.network.telemetry.DualModelTelemetry
import com.example.voice.vision.ScreenCaptureProvider
import com.example.voice.vision.ScreenContextProvider

class GeminiWebSocketClient(
    private val apiKey: String,
    val voiceName: String = "Aoede",
    val responseLanguage: String = "Tenglish",
    val preferredModel: String = "Auto",
    private val memoriesMarkdown: String = "",
    val isNewSession: Boolean = true,
    val emotionState: com.example.emotion.model.EmotionState? = null,
    val handoffContext: ModelHandoffContext? = null,
    private val onMessageReceived: (String) -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionError: (Throwable) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onExecuteAutomation: suspend (String) -> String,
    private val onUserTranscription: ((String) -> Unit)? = null,
    val onInterimTranscription: ((String) -> Unit)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    val onThoughtSummaryDelta: ((delta: String, fullSummary: String) -> Unit)? = null,
    val onInteractionStatusChanged: ((status: String) -> Unit)? = null,
    val thinkingLevel: LiveThinkingLevel = LiveThinkingLevel.LOW,
    val enableProactiveAudio: Boolean = true,
    val enableAffectiveDialog: Boolean = true,
    private val sessionState: LiveSessionState = LiveSessionState(),
    private val clientScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
    private val applicationContext: Context? = null
) {
    private val client = sharedClient
    private val resumptionVersion = sessionState.resumptionVersion()

    var promptProvider: (() -> String)? = null
    private val lastVideoFrameSentAt = java.util.concurrent.atomic.AtomicLong(0L)
    private var renewalJob: kotlinx.coroutines.Job? = null

    private val gson = Gson()
    
    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .pingInterval(60, TimeUnit.SECONDS)
                .build()
        }

        const val DEFAULT_LIVE_MODEL = GeminiLiveModels.FAST
        const val DEEP_LIVE_MODEL = GeminiLiveModels.DEEP
        const val FALLBACK_LIVE_MODEL = GeminiLiveModels.FALLBACK

        const val CLIENT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"

        fun sanitizeModelName(name: String): String {
            val trimmed = name.trim()
            return when {
                trimmed.isBlank() || trimmed.equals("Auto", ignoreCase = true) -> DEFAULT_LIVE_MODEL
                trimmed.startsWith("models/") -> trimmed.substringAfter("models/")
                else -> trimmed
            }
        }

        fun buildAudioStreamEndPayload(): String = """
        {
            "realtimeInput": {
                "audioStreamEnd": true
            }
        }
        """.trimIndent()

        private val liveScreenActionNames = setOf(
            "open_app",
            "click_element",
            "type_text",
            "press_enter",
            "press_key",
            "clear_text",
            "scroll_screen",
            "get_screen_context",
            "inspect_screen",
            "computer_use",
            "ui_agent",
            "execute_automation",
            "task_intent"
        )

        internal fun isLiveScreenAction(functionName: String): Boolean =
            functionName in liveScreenActionNames

        internal fun requiresFastLiveModel(modelName: String, functionName: String): Boolean =
            isLiveScreenAction(functionName) && modelName == GeminiLiveModels.FAST

        internal fun buildLiveModelRequiredResult(functionName: String, activeModelName: String): String =
            Gson().toJson(linkedMapOf(
                "success" to false,
                "status" to "LIVE_MODEL_REQUIRED",
                "executionState" to "LIVE_MODEL_REQUIRED",
                "verificationStatus" to "UNVERIFIED",
                "functionName" to functionName,
                "requiredModel" to GeminiLiveModels.FAST,
                "activeModel" to activeModelName,
                "reason" to "UI automation from Gemini Live requires the exact ${GeminiLiveModels.FAST} model."
            ))

        internal fun buildScreenContinuationPayload(
            packageName: String?,
            visibleText: String,
            clickableElements: List<String>,
            actionName: String
        ): String {
            val observation = linkedMapOf(
                "foregroundPackage" to (packageName ?: "unknown"),
                "visibleText" to visibleText,
                "clickableElements" to clickableElements
            )
            val continuation = """
                The previous UI action '$actionName' has completed. This is a fresh post-action screen observation.
                Screen observation: ${Gson().toJson(observation)}
                Choose the next click, type, scroll, or other UI action yourself based on this visible state. Call task_completed only after visibly verifying that the user's requested result is present.
            """.trimIndent()
            return Gson().toJson(linkedMapOf(
                "clientContent" to linkedMapOf(
                    "turns" to listOf(linkedMapOf(
                        "role" to "user",
                        "parts" to listOf(linkedMapOf("text" to continuation))
                    )),
                    "turnComplete" to true
                )
            ))
        }
    }

    @Volatile
    private var webSocket: WebSocket? = null

    private val modelCandidates: List<String> = buildModelCandidates(preferredModel)
    private var candidateIndex = 0
    private var activeModelName = modelCandidates.firstOrNull() ?: DEFAULT_LIVE_MODEL

    private fun buildModelCandidates(preferred: String): List<String> {
        val sanitized = sanitizeModelName(preferred)
        val candidates = mutableListOf<String>()
        candidates.add(sanitized)
        if (sanitized != DEFAULT_LIVE_MODEL) {
            candidates.add(DEFAULT_LIVE_MODEL)
        }
        if (sanitized != FALLBACK_LIVE_MODEL && DEFAULT_LIVE_MODEL != FALLBACK_LIVE_MODEL) {
            candidates.add(FALLBACK_LIVE_MODEL)
        }
        return candidates.distinct()
    }
    
    @Volatile
    private var isSetupComplete = false

    @Volatile
    private var hasReceivedInteractionStatus: Boolean = false

    @Volatile
    private var isTurnInProgress: Boolean = false

    /**
     * Full accumulated transcription of the CURRENT user voice turn.
     *
     * Gemini Live streams `serverContent.inputTranscription` as incremental
     * deltas (e.g. "S", "earch", " Telugu", ...). These MUST be accumulated —
     * assigning each delta directly lets a late partial chunk replace the whole
     * phrase, which is how truncated queries like "F" used to reach tools.
     */
    @Volatile
    private var lastInputTranscription: String = ""

    @Volatile
    private var lastInputAtMs: Long = 0L

    /** Per-turn input snapshot preserving transcription across multiple tool calls in a turn with an approval consumption flag. */
    data class TurnInputSnapshot(
        val transcript: String,
        val timestampMs: Long,
        val consumedForApproval: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    )

    @Volatile
    private var currentTurnSnapshot: TurnInputSnapshot? = null

    // Guards the per-turn transcription accumulator (WebSocket callbacks arrive
    // on a single OkHttp thread today, but keep access safe regardless).
    private val transcriptionLock = Any()
    private val inputTranscriptionBuffer = StringBuilder()

    /** Appends the next live-transcription delta and returns the full accumulated transcript for this turn. */
    private fun accumulateInputTranscription(delta: String): String = synchronized(transcriptionLock) {
        if (inputTranscriptionBuffer.isEmpty()) {
            lastInputAtMs = System.currentTimeMillis()
        }
        inputTranscriptionBuffer.append(delta)
        val fullTranscript = inputTranscriptionBuffer.toString()
        lastInputTranscription = fullTranscript
        currentTurnSnapshot = TurnInputSnapshot(fullTranscript, lastInputAtMs)
        fullTranscript
    }

    /** Clears the per-turn transcription accumulator buffer at turn boundaries while preserving lastInputTranscription for tool dispatch. */
    private fun resetInputTranscriptionAccumulator() {
        synchronized(transcriptionLock) {
            inputTranscriptionBuffer.setLength(0)
        }
    }

    /** Clears all transcriptions when disconnecting or starting a fresh session. */
    private fun clearSessionTranscription() {
        synchronized(transcriptionLock) {
            inputTranscriptionBuffer.setLength(0)
            lastInputTranscription = ""
            lastInputAtMs = 0L
            currentTurnSnapshot = null
        }
        pendingAudioQueue.clear()
        hasReceivedInteractionStatus = false
        isTurnInProgress = false
        idlePending = false
    }

    private var thinkingIndicatorSent = false
    private val toolJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var idlePending = false

    /** Clears the per-turn thought accumulator buffer at turn boundaries. */
    private fun resetThoughtAccumulator() {
        thinkingIndicatorSent = false
    }
    
    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    private val pendingTextMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val pendingAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val maxPendingAudioChunks = 50 // ~2 seconds bounded buffer at 40ms chunks

    fun getPendingAudioQueueSize(): Int = pendingAudioQueue.size

    val currentState: ConnectionState
        get() = connectionState

    val connectedModelName: String? get() = activeModelName.takeIf { isSetupComplete }

    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED

    @Synchronized
    private fun updateState(newState: ConnectionState) {
        if (connectionState == newState) return
        connectionState = newState
        onConnectionStateChanged(newState)
    }
    
    @Volatile
    private var isManuallyClosed = false

    fun connect() {
        registerTaskCancellation()
        // Fresh session: no user turn has been spoken yet, so start with an empty transcript.
        clearSessionTranscription()
        val keyPresent = apiKey.isNotBlank()
        Log.d("GeminiFlow", "[GeminiFlow] key_present=$keyPresent")
        if (!keyPresent) {
            Log.w("GeminiFlow", "[GeminiFlow] error=API key is missing")
            updateState(ConnectionState.DISCONNECTED)
            return
        }
        isManuallyClosed = false
        ServiceRegistry.register(ServiceType.VOICE, this)
        isSetupComplete = false
        updateState(ConnectionState.CONNECTING)

        this.connectStartTime = System.currentTimeMillis()
        Log.d("Latency", "[Latency] ws_connect_start at=$connectStartTime")

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        Log.d("GeminiWebSocket", "Connecting to Gemini WebSocket using candidate [$candidateIndex/${modelCandidates.size}]: '$activeModelName'...")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                if (isManuallyClosed) { webSocket.close(1000, "Closed"); return }
                val wsOpenTime = System.currentTimeMillis()
                Log.d("Latency", "[Latency] ws_open (elapsed=${wsOpenTime - connectStartTime}ms)")
                Log.d("GeminiFlow", "[GeminiFlow] socket_connected")
                Log.d("GeminiWebSocket", "WebSocket onOpen - sending setup for model '$activeModelName'")
                sendSetupMessage()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                if (webSocket !== this@GeminiWebSocketClient.webSocket || isManuallyClosed) return
                handleIncomingMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                super.onMessage(webSocket, bytes)
                val text = bytes.utf8()
                if (webSocket !== this@GeminiWebSocketClient.webSocket || isManuallyClosed) return
                handleIncomingMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                if (webSocket !== this@GeminiWebSocketClient.webSocket || isManuallyClosed) return
                Log.e("GeminiFlow", "[GeminiFlow] error=${t.message?.take(100) ?: "WebSocket failure"}")
                Log.e("GeminiWebSocket", "WebSocket onFailure: ${t.message} (isManuallyClosed=$isManuallyClosed)", t)
                response?.let { Log.e("GeminiWebSocket", "Response code: ${it.code}, body: ${it.body?.string()?.take(500)}") }
                stopHeartbeatLoop()
                cancelToolJobs()
                if (!isManuallyClosed) {
                    // A transport failure is not evidence that the model is unsupported.
                    isSetupComplete = false
                    updateState(ConnectionState.FAILED)
                    onConnectionError(t)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Log.e("GeminiWebSocket", "WebSocket onClosing: code=$code, reason=$reason")
                try {
                    webSocket.close(code, reason)
                } catch (e: Exception) {
                    Log.w("GeminiWebSocket", "Error acknowledging closing frame: ${e.message}")
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                if (webSocket !== this@GeminiWebSocketClient.webSocket || isManuallyClosed) return
                Log.d("GeminiWebSocket", "WebSocket onClosed: $code $reason (isManuallyClosed=$isManuallyClosed)")
                stopHeartbeatLoop()
                cancelToolJobs()
                if (!isManuallyClosed) {
                    if (!isSetupComplete && code == 1008 &&
                        (reason.contains("not found", true) || reason.contains("not supported", true)) &&
                        candidateIndex < modelCandidates.size - 1) {
                        Log.w("GeminiWebSocket", "WebSocket closed with code $code. Attempting fallback model...")
                        tryNextModelOrReportError(reason)
                    } else {
                        isSetupComplete = false
                        updateState(ConnectionState.DISCONNECTED)
                        Log.w("GeminiWebSocket", "WebSocket closed unexpectedly by server. Triggering auto-reconnect...")
                        onConnectionError(Exception("Live API closed ($code: $reason)"))
                    }
                }
            }
        })
    }

    private fun tryNextModelOrReportError(errorMsg: String) {
        if (candidateIndex < modelCandidates.size - 1) {
            val prevModel = activeModelName
            candidateIndex++
            activeModelName = modelCandidates[candidateIndex]
            Log.w("GeminiWebSocket", "Model fallback triggered! Next candidate [$candidateIndex/${modelCandidates.size}]: '$activeModelName'")
            DualModelTelemetry.recordModelSwitch(
                taskId = handoffContext?.taskId ?: "ws_fallback",
                newMode = if (GeminiLiveModels.isDeepModel(activeModelName)) GeminiModelMode.DEEP else GeminiModelMode.FAST,
                newModel = activeModelName,
                reason = "Fallback from $prevModel: $errorMsg"
            )
            connect()
        } else {
            Log.e("GeminiFlow", "[GeminiFlow] error=All model candidates exhausted: $errorMsg")
            Log.e("GeminiWebSocket", "All model candidates exhausted. Final error: $errorMsg")
            updateState(ConnectionState.FAILED)
            onConnectionError(Exception("Live API Error: $errorMsg"))
        }
    }
    
    private val roomWriteMutex = Mutex()
    @Volatile private var taskStopped = false

    init {
        registerTaskCancellation()
    }

    private fun registerTaskCancellation() {
        com.example.automation.engine.TaskCancellationCoordinator.registerCancellationListener(this) {
            taskStopped = true
            cancelToolJobs()
        }
    }
    private var connectStartTime: Long = 0L

    internal fun buildSetupMessage(): String {
        val baseSystemInstruction = promptProvider?.invoke()
            ?: PromptBuilder.buildSystemInstruction(responseLanguage, memoriesMarkdown, emotionState, voiceName)
        val handoffSection = handoffContext?.toSystemInstructionPrompt() ?: ""
        val systemInstructionText = baseSystemInstruction + handoffSection

        val toolsJsonStr = """[{"functionDeclarations":[{"name":"open_app","description":"Opens an app on the phone by its name.","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","description":"The name of the app to open, e.g. Instagram, WhatsApp"}},"required":["app_name"]}},{"name":"type_text","description":"Types clean text into a target or focused text field on screen. The 'text' argument must be ONLY the exact clean query or text to type (e.g. 'Yashwanth'). Never include instructions or action phrases like 'search bar click chesi' or 'ani type chey'.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The exact clean text string to type into the field (e.g. 'Yashwanth'). Never include instructions or action phrases like 'search bar click chesi' or 'ani type chey'."},"target":{"type":"STRING","description":"Optional target field name, label, or hint (e.g. 'Search')."},"press_enter":{"type":"BOOLEAN","description":"Optional true to automatically press Enter/Search after typing."}},"required":["text"]}},{"name":"press_enter","description":"Presses the keyboard Enter / Search / Go action button (IME action). Use whenever user says 'press enter', 'enter kottu', 'hit enter', 'submit', 'search'.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"Optional target field name or submit button label."}}}},{"name":"press_key","description":"Presses a specific keyboard key (e.g. enter, back, backspace, tab, delete, space, home, end, escape, arrows). Use whenever user asks to press a specific key.","parameters":{"type":"OBJECT","properties":{"key":{"type":"STRING","description":"The key name to press: 'enter', 'back', 'tab', 'space', 'delete', 'backspace', 'esc', 'home', 'end', 'arrow_up', 'arrow_down', 'arrow_left', 'arrow_right'."}},"required":["key"]}},{"name":"clear_text","description":"Clears the text in the currently focused or specified text field. Use whenever user says 'clear text', 'clear field', 'text delete chey', 'erase'.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"Optional target field name or hint."}}}},{"name":"answer_call","description":"Answers the current incoming phone call. Use when the user says 'answer', 'answer the call', 'pick up', 'accept', 'lift the call', 'matladu'.","parameters":{"type":"OBJECT","properties":{}}},{"name":"reject_call","description":"Rejects or declines the current incoming phone call. Use when the user says 'reject', 'reject the call', 'decline', 'cut the call', 'cut chey', 'disconnect'.","parameters":{"type":"OBJECT","properties":{}}},{"name":"send_sms","description":"Sends a direct SMS text message to a phone number or contact.","parameters":{"type":"OBJECT","properties":{"number":{"type":"STRING","description":"The recipient phone number."},"message":{"type":"STRING","description":"The SMS message text content to send."}},"required":["number","message"]}},{"name":"execute_automation","description":"Executes an automation command on the Android device.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"The action to perform, e.g. OPEN_APP, FLASHLIGHT_ON, SET_VOLUME, VOLUME_UP, VOLUME_DOWN, SET_BRIGHTNESS, BRIGHTNESS_UP, BRIGHTNESS_DOWN, DND_ON, DND_OFF, SILENT_MODE_ON, SILENT_MODE_OFF"},"app":{"type":"STRING","description":"The app name to open, if applicable"},"query":{"type":"STRING","description":"The search query or video name, if applicable"},"percent":{"type":"INTEGER","description":"The volume or brightness percentage value from 0 to 100, if applicable"},"direction":{"type":"STRING","description":"The direction for change, either 'up' or 'down', if applicable"},"mode":{"type":"STRING","description":"The target mode for ringer or DND, either 'silent', 'vibrate', 'normal', 'dnd_on', 'dnd_off', if applicable"}},"required":["action"]}},{"name":"click_element","description":"Clicks a visible UI element, button, text, or row on the current screen. Use whenever user says 'click X', 'tap X', 'X meeda click chey', 'press X'.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The visible label or text of the target UI element to click."},"description":{"type":"STRING","description":"Optional accessibility content description of the target element."}},"required":["text"]}},{"name":"scroll_screen","description":"Scrolls the currently visible screen up or down. Use whenever user says 'scroll down', 'scroll up', 'kindha scroll chey', 'paina scroll chey'.","parameters":{"type":"OBJECT","properties":{"direction":{"type":"STRING","description":"Scroll direction: 'down' or 'up'","enum":["down","up"]}},"required":["direction"]}},{"name":"search_contact","description":"Searches the device contacts for a given name and returns their contactId, phoneId, and normalized phone numbers. ALWAYS use this before calling a contact.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The name of the contact to search for."}},"required":["query"]}},{"name":"call_contact","description":"Places a direct phone call to a phone number or contact name. Use whenever the user asks to call a number or person (e.g., 'call 9876543210', '9876543210 ki call chey', 'call Mom', 'Ravi ki call chey'). Pass 'phoneNumber' for raw digits or 'contact' for contact name.","parameters":{"type":"OBJECT","properties":{"phoneNumber":{"type":"STRING","description":"The phone number digits to call directly (e.g., '9876543210')."},"contact":{"type":"STRING","description":"The contact name to call (e.g. 'Mom', 'Ravi')."},"contactId":{"type":"INTEGER","description":"Optional numeric contact ID if previously searched."},"phoneId":{"type":"INTEGER","description":"Optional numeric phone ID if previously searched."}}}},{"name":"youtube_search","description":"Search YouTube for the given query and automatically play the first matching video. Use this tool whenever the user wants to: play music, play songs, search YouTube, watch videos, search and play content. Do NOT use open_app for these requests.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The video title, artist, or song to play, e.g. 'Starboy', 'Believer'."}},"required":["query"]}},{"name":"send_whatsapp_message","description":"Sends a WhatsApp message to a contact name or phone number.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The contact name or phone number to send the message to."},"message":{"type":"STRING","description":"The message text content to send."}},"required":["contact","message"]}},{"name":"open_whatsapp_chat","description":"Opens a specific WhatsApp chat or conversation with a contact name or phone number without sending a message. Use whenever user asks to open someone's chat, see their conversation, or 'X chat open chey'.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The contact name or phone number whose chat to open."}},"required":["contact"]}},{"name":"navigate_whatsapp","description":"Navigates to a specific screen, tab, or section in WhatsApp (chats, updates, status, communities, calls, settings, new_chat, linked_devices, starred_messages). Use whenever user asks to go to a WhatsApp tab or screen, e.g. 'whatsapp status ki vellu', 'whatsapp calls open chey', 'whatsapp settings'.","parameters":{"type":"OBJECT","properties":{"screen":{"type":"STRING","description":"Target screen or tab: 'chats', 'updates', 'status', 'communities', 'calls', 'settings', 'new_chat', 'linked_devices', 'starred_messages'."}},"required":["screen"]}},{"name":"flashlight","description":"Turns the phone flashlight (torch) ON or OFF.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to turn ON, false to turn OFF."}},"required":["enabled"]}},{"name":"set_volume","description":"Sets or adjusts the device media volume. Can set volume to an exact percentage (0 to 100), or adjust volume up/down, or show the volume slider. Use whenever user asks to set volume, change volume percentage, 'volume 50%', 'volume 50 percent', 'volume 50% pettu', 'volume 50% pe to', 'volume penchu', 'volume tagginchu', 'volume adjust chey'.","parameters":{"type":"OBJECT","properties":{"percent":{"type":"INTEGER","description":"The target volume percentage from 0 to 100."},"direction":{"type":"STRING","description":"Optional direction if not specifying percentage: 'up', 'down', or 'show'."},"mode":{"type":"STRING","description":"Optional mode: 'up', 'down', 'show'."}}}},{"name":"schedule_task","description":"Schedules a tool to execute at a later time.","parameters":{"type":"OBJECT","properties":{"tool":{"type":"STRING","description":"The name of the target tool to run (flashlight, send_whatsapp_message, open_app)."},"time_expression":{"type":"STRING","description":"The natural language expression when it should run (e.g. 'in 5 minutes', 'tomorrow at 9 AM', 'tonight at 8:30 PM', 'on Friday at 3 PM')."},"repeat_type":{"type":"STRING","description":"Optional repeat interval ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY').","enum":["NONE","DAILY","WEEKLY","MONTHLY"]},"contact":{"type":"STRING","description":"Optional contact name/number if scheduling send_whatsapp_message."},"message":{"type":"STRING","description":"Optional message body if scheduling send_whatsapp_message."},"app_name":{"type":"STRING","description":"Optional app name if scheduling open_app."},"enabled":{"type":"BOOLEAN","description":"Optional flashlight state if scheduling flashlight."}},"required":["tool","time_expression"]}},{"name":"cancel_task","description":"Cancels a pending scheduled task by its task ID.","parameters":{"type":"OBJECT","properties":{"task_id":{"type":"STRING","description":"The unique ID of the task to cancel."}},"required":["task_id"]}},{"name":"list_scheduled_tasks","description":"Returns a list of all currently active pending scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},{"name":"diagnostics","description":"Runs MAX diagnostics to report all registered tools and their actions.","parameters":{"type":"OBJECT","properties":{}}},{"name":"create_contact","description":"Creates a new contact on the device.","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING","description":"The display name of the contact"},"phone":{"type":"STRING","description":"The phone number of the contact"}},"required":["name"]}},{"name":"windows_cmd","description":"Executes a command on the paired Windows laptop agent. Actions include listing directory (dir), changing working directory (cd), locating program files (where), or echoing output (echo).","parameters":{"type":"OBJECT","properties":{"cmd_action":{"type":"STRING","description":"The action to execute: 'dir' to list files, 'cd' to change folders, 'where' to locate executables, 'echo' to print a string.","enum":["dir","cd","where","echo"]},"path":{"type":"STRING","description":"The folder path target, if applicable (e.g. for dir or cd)."},"message":{"type":"STRING","description":"The message string target, if applicable (e.g. for echo)."},"program":{"type":"STRING","description":"The program executable search name, if applicable (e.g. for where)."}},"required":["cmd_action"]}},{"name":"windows_agent","description":"Orchestrates automation on the paired Windows PC agent (Clipboard, Windows management, Desktop Vision, File Search, and Terminal Control).","parameters":{"type":"OBJECT","properties":{"agent_action":{"type":"STRING","description":"The namespace action of Windows automation to execute.","enum":["core.clipboard:get","core.clipboard:set","core.window:list","core.window:minimize","core.window:maximize","core.window:close","core.window:focus","core.filesystem:search","core.filesystem:open","core.vision:capture","core.terminal:run","core.terminal:kill","core.app:list","core.app:launch","core.app:is_running","core.app:close"]},"path":{"type":"STRING","description":"Folder/file path target (e.g., for core.filesystem:search or core.filesystem:open)."},"message":{"type":"STRING","description":"The message text to sync (e.g., for core.clipboard:set)."},"query":{"type":"STRING","description":"Search query keyword (e.g., for core.filesystem:search)."},"target_name":{"type":"STRING","description":"Fuzzy window title, application name, or process executable to close/minimize/maximize/focus. Pass 'all' to minimize all windows."},"app_name":{"type":"STRING","description":"Application name to launch, query, or close (e.g., 'chrome', 'notepad', 'calculator', 'vs code')."},"app":{"type":"STRING","description":"Alternative application name parameter."},"command":{"type":"STRING","description":"PowerShell/cmd shell command to run asynchronously (e.g., for core.terminal:run)."}},"required":["agent_action"]}},{"name":"get_battery_status","description":"Gets the current battery percentage and charging status of the device."},{"name":"get_dnd_status","description":"Checks if Do Not Disturb (DND) mode is currently enabled on the device."},{"name":"set_dnd","description":"Enables or disables Do Not Disturb (DND) mode.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to enable DND, false to disable it."}},"required":["enabled"]}},{"name":"get_calendar_events","description":"Retrieves list of calendar events on the device for the next 7 days."},{"name":"add_calendar_event","description":"Schedules a new calendar event on the device.","parameters":{"type":"OBJECT","properties":{"title":{"type":"STRING","description":"Title of the event"},"description":{"type":"STRING","description":"Optional description"},"startTime":{"type":"INTEGER","description":"Epoch start time in milliseconds"},"endTime":{"type":"INTEGER","description":"Epoch end time in milliseconds"}},"required":["title","startTime","endTime"]}},{"name":"take_screenshot","description":"Takes a screenshot of the current screen."},{"name":"take_photo","description":"Takes a photo using the device camera."},{"name":"run_adb_command","description":"Runs an ADB shell command via Shizuku on the device. Requires Shizuku running and authorized.","parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"The shell command to run (e.g. 'pm list packages')"}},"required":["command"]}},{"name":"get_bluetooth_status","description":"Checks if Bluetooth is currently enabled on the device."},{"name":"set_bluetooth","description":"Enables or disables Bluetooth.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to enable Bluetooth, false to disable it."}},"required":["enabled"]}},{"name":"get_notifications","description":"Retrieves a list of active status bar notifications from communication/messaging apps on the device."},{"name":"reply_notification","description":"Sends a direct text reply to an active notification.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The sender contact name or package name of the notification to reply to."},"message":{"type":"STRING","description":"The reply message text."}},"required":["contact","message"]}},{"name":"set_alarm","description":"Schedules a new alarm on the device clock.","parameters":{"type":"OBJECT","properties":{"hour":{"type":"INTEGER","description":"Hour of the alarm (0-23)"},"minutes":{"type":"INTEGER","description":"Minutes of the alarm (0-59)"},"message":{"type":"STRING","description":"Optional custom label for the alarm"}},"required":["hour","minutes"]}},{"name":"set_timer","description":"Starts a countdown timer on the device clock.","parameters":{"type":"OBJECT","properties":{"seconds":{"type":"INTEGER","description":"Duration of the timer in seconds"},"message":{"type":"STRING","description":"Optional custom label for the timer"}},"required":["seconds"]}},{"name":"control_media","description":"Controls media playback for active media sessions (e.g. Spotify, YouTube).","parameters":{"type":"OBJECT","properties":{"media_action":{"type":"STRING","description":"The playback command to send.","enum":["PLAY_MEDIA","PAUSE_MEDIA","NEXT_MEDIA","PREVIOUS_MEDIA"]}},"required":["media_action"]}},{"name":"search_files","description":"Searches local directories (Downloads, Documents, Pictures) for matching files.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"Keyword to search in file names"},"file_type":{"type":"STRING","description":"Filter by file type categories.","enum":["pdf","image","doc","any"]}},"required":["query"]}},{"name":"toggle_wifi","description":"Opens the device Wi-Fi connectivity panel to toggle Wi-Fi on or off."},{"name":"open_settings","description":"Opens a specific Android system settings screen.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"The settings target screen to open.","enum":["wifi","bluetooth","battery","display","accessibility","location","apps","airplane","sound","date_time","main"]}},"required":["target"]}},{"name":"get_device_status","description":"Retrieves a detailed status and health summary of the device (battery, storage, RAM, settings states, volume, brightness)."},{"name":"get_app_usage","description":"Queries the foreground application usage duration statistics for today."},{"name":"get_clipboard","description":"Reads the current text content from the system clipboard. (Note: Only succeeds if the app is in the foreground)."},{"name":"set_clipboard","description":"Copies the provided text to the system copy-paste clipboard.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The text to copy to clipboard"}},"required":["text"]}},{"name":"get_location","description":"Retrieves the device's current GPS location coordinates and returns a Google Maps link."},{"name":"run_routine","description":"Runs a pre-defined or custom routine of actions sequentially.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"The name of the routine to execute (e.g. 'sleep', 'morning', 'work', 'gaming', or custom names)."},"dry_run":{"type":"BOOLEAN","description":"If true, only runs a simulation validation check and returns the trace without applying updates."}},"required":["routine_name"]}},{"name":"create_routine","description":"Creates or updates a custom routine with sequential execution steps.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"Name of the custom routine to create (e.g. 'commute')."},"steps_json":{"type":"STRING","description":"A JSON array string of steps, each containing 'action' and 'arguments' (e.g. '[{\"action\":\"SET_DND\",\"arguments\":{\"enabled\":true}}]')."}},"required":["routine_name","steps_json"]}},{"name":"delete_routine","description":"Deletes a custom routine from local storage by its name. Built-in routines cannot be deleted.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"Name of the custom routine to delete."}},"required":["routine_name"]}},{"name":"list_routines","description":"Lists all available routines, both built-in presets and custom user-defined routines."},{"name":"log_period_start","description":"Logs the start date of a new period cycle. Use this when the user says they started their period.","parameters":{"type":"OBJECT","properties":{"date":{"type":"STRING","description":"Natural language date expression, e.g., 'today', 'yesterday', '3 days ago', 'July 15th'."},"notes":{"type":"STRING","description":"Optional symptoms or notes to record."}},"required":["date"]}},{"name":"log_period_end","description":"Logs the end date of the current active period cycle. Use this when the user says their period finished or ended.","parameters":{"type":"OBJECT","properties":{"date":{"type":"STRING","description":"Natural language date expression, e.g., 'today', 'yesterday', '1 day ago', 'July 20th'."}},"required":["date"]}},{"name":"log_period_note","description":"Adds mood, symptoms, or tracking notes to the latest period cycle. Use this when the user mentions symptoms or notes without starting/ending a cycle.","parameters":{"type":"OBJECT","properties":{"notes":{"type":"STRING","description":"The symptoms, mood, or other tracking notes to log."}},"required":["notes"]}},{"name":"get_period_history","description":"Gets a list of all logged period cycles including start dates, end dates, durations, and notes."},{"name":"get_period_prediction","description":"Calculates average cycle stats and predicts the next period start date, ovulation day, and fertile window. Exposes whether the user is on their period currently."},{"name":"clear_all_period_data","description":"Deletes all logged periods and cycle data from the device database."},{"name":"system_action","description":"Performs system navigation gestures on the Android device.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","enum":["home","back","recent","notifications","quick_settings","screenshot"],"description":"The system action to perform."}},"required":["action"]}},{"name":"save_memory","description":"Saves a user identity fact, name, preference, goal, or detail to permanent Room memory and the Markdown vault. Use whenever user shares their name (e.g. 'my name is Yash', 'naa peru Yash'), introduces themselves, says 'remember this', 'note down', 'my favorite X is Y', or asks to store any detail.","parameters":{"type":"OBJECT","properties":{"title":{"type":"STRING","description":"Short descriptive title for the memory (e.g. 'User Name', 'Favorite Food')"},"content":{"type":"STRING","description":"The memory content/fact to remember (e.g. 'User name is Yash')"},"category":{"type":"STRING","description":"Optional category (Personal, Preferences, Work, Goals, General)"},"type":{"type":"STRING","description":"FACT, PREFERENCE, GOAL, or CUSTOM","enum":["FACT","PREFERENCE","GOAL","CUSTOM"]}},"required":["title","content"]}},{"name":"forget_memory","description":"Deletes or forgets a specific permanent memory, preference, or fact. Use whenever user says 'forget that X', 'delete memory about X', 'naaku Telugu ante ishtam ani marchipo', etc.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The memory topic, title, or content to forget and remove."}},"required":["query"]}},{"name":"set_reminder","description":"Sets a reminder for a specific task, note, or event at a given time or relative interval. Use whenever user says 'remind me to X in Y minutes', 'set a reminder for X', etc.","parameters":{"type":"OBJECT","properties":{"message":{"type":"STRING","description":"The reminder title or message note text"},"time_expression":{"type":"STRING","description":"Natural language time expression e.g. 'in 10 minutes', 'tomorrow at 5 PM', 'at 6:30 PM'"},"minutes_from_now":{"type":"INTEGER","description":"Optional minutes count from now"}},"required":["message"]}},{"name":"unlock_device","description":"Unlocks the phone using the stored PIN code and wake lock automation. Use whenever user says 'unlock my phone', 'open sesame', 'unlock phone', etc.","parameters":{"type":"OBJECT","properties":{}}},{"name":"get_screen_context","description":"Captures and analyzes the current device screen content (both visual screenshot frame and textual UI hierarchy). Use whenever the user asks about what is on their screen, to read an article, explain an error, summarize a page, or asks where to click.","parameters":{"type":"OBJECT","properties":{"focus":{"type":"STRING","description":"The focus mode: 'summary' (overview of page), 'read_text' (article/text content), 'explain_error' (troubleshoot visible errors), 'find_element' (locate buttons/fields), 'general' (hybrid).","enum":["summary","read_text","explain_error","find_element","general"]}}}},{"name":"wait","description":"Waits for a specified duration in seconds (1 to 30) for screen transitions, animations, or state updates to settle, and returns a verified UI summary of the screen.","parameters":{"type":"OBJECT","properties":{"seconds":{"type":"INTEGER","description":"Duration to wait in seconds (1 to 30)."}},"required":["seconds"]}},{"name":"inspect_screen","description":"Inspects the current screen state and returns formatted visual structure, element positions, interactive targets, and any pending SMS/notification OTP code.","parameters":{"type":"OBJECT","properties":{"wait_for_content":{"type":"BOOLEAN","description":"Optional true to wait for screen stability before inspecting."}}}},{"name":"spotify_smart_search","description":"Searches Spotify with automatic clean query normalization, targets search inputs, and verifies audio playback.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"Song, artist, or album name to play on Spotify."}},"required":["query"]}},{"name":"instagram_bulk_unlike","description":"Unlikes posts/reels in Instagram 'Your activity -> Likes' screen in bulk with verification and scroll handling.","parameters":{"type":"OBJECT","properties":{"target_count":{"type":"INTEGER","description":"Number of items to unlike (default 10, max 50)."}}}},{"name":"task_completed","description":"Signals that the multi-step autonomous task or goal is 100% completed and verified on screen. Use ONLY when the user's requested action is fully finished and visible on screen.","parameters":{"type":"OBJECT","properties":{"summary":{"type":"STRING","description":"Clear, concise summary of the completed task and verified outcome."}},"required":["summary"]}}]}]"""

        val isDeepMode = activeModelName == GeminiLiveModels.DEEP || activeModelName.contains("extended-thinking")

        val speechConfig = JSONObject().apply {
            put("voiceConfig", JSONObject().apply {
                put("prebuiltVoiceConfig", JSONObject().apply {
                    put("voiceName", voiceName)
                })
            })
        }

        // Map LiveThinkingLevel enum to valid integer token budget (avoids HTTP 400 on unknown field 'thinkingLevel')
        val thinkingBudgetTokens = when (thinkingLevel.name.uppercase()) {
            "NONE", "OFF" -> 0
            "LOW" -> 512
            "MEDIUM" -> 1024
            "HIGH" -> 2048
            else -> 1024
        }

        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply {
                put("AUDIO")
            })
            put("speechConfig", speechConfig)
            if (isDeepMode && thinkingBudgetTokens > 0) {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", thinkingBudgetTokens)
                    put("includeThoughts", false)
                })
            }
        }

        val proactiveInstructionSuffix = if (enableProactiveAudio) {
            "\n\n[PROACTIVE AUDIO MODE ACTIVE]: Listen continuously. Stay completely silent during background noise, media playback, or when the user is speaking to someone else in the room. Speak ONLY when directly addressed, when answering a user question, or when reporting an urgent automation/screen status."
        } else ""

        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", systemInstructionText + proactiveInstructionSuffix)
                })
            })
        }

        val setupObj = JSONObject().apply {
            put("model", "models/$activeModelName")
            put("generationConfig", generationConfig)
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("systemInstruction", systemInstruction)

            val tools = JSONArray(toolsJsonStr)
            if (isDeepMode) {
                for (i in 0 until tools.length()) {
                    val declarations = tools.getJSONObject(i).optJSONArray("functionDeclarations") ?: continue
                    for (j in 0 until declarations.length()) {
                        declarations.getJSONObject(j).put("behavior", "NON_BLOCKING")
                    }
                }
            }
            put("tools", tools)

            put("sessionResumption", JSONObject().apply {
                sessionState.resumptionHandle(activeModelName)?.let { put("handle", it) }
            })
            put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        }

        val setupPayload = JSONObject().apply {
            put("setup", setupObj)
        }.toString()

        return setupPayload
    }

    private fun sendSetupMessage() {
        val setupPayload = buildSetupMessage()
        Log.d("GeminiWebSocket", "Sending setup (model=$activeModelName, thinking=$thinkingLevel)")
        Log.d("GeminiWebSocket", "Setup payload: $setupPayload")
        val sent = webSocket?.send(setupPayload)
        val setupSentTime = System.currentTimeMillis()
        Log.d("Latency", "[Latency] setup_sent (elapsed=${setupSentTime - connectStartTime}ms)")
        Log.d("GeminiWebSocket", "Setup send result: $sent")
    }

    private var hasGreeted = false

    fun sendInitialTrigger() {
        Log.d("GeminiWebSocket", "Sending initial trigger greeting...")
        sendText("Hello! Please give a short, warm, feminine greeting to Yaswanth.")
    }
    
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    @Synchronized
    private fun startHeartbeatLoop() {
        stopHeartbeatLoop()
        heartbeatJob = clientScope.launch {
            val silentAudio = ByteArray(240)
            while (heartbeatJob?.isActive == true) {
                kotlinx.coroutines.delay(25000)
                val currentWs = webSocket
                if (isSetupComplete && currentWs != null) {
                    try {
                        val base64Audio = Base64.encodeToString(silentAudio, Base64.NO_WRAP)
                        val heartbeatJson = """{"realtimeInput":{"audio":{"mimeType":"$CLIENT_AUDIO_MIME_TYPE","data":"$base64Audio"}}}"""
                        currentWs.send(heartbeatJson)
                        Log.d("GeminiWebSocket", "[Heartbeat] Sent 25s silent keep-alive frame")
                    } catch (e: Exception) {
                        Log.w("GeminiWebSocket", "[Heartbeat] Failed to send keep-alive: ${e.message}")
                    }
                }
            }
        }
    }

    @Synchronized
    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    @Synchronized
    private fun startSessionRenewal() {
        stopSessionRenewal()
        renewalJob = clientScope.launch {
            kotlinx.coroutines.delay(9 * 60 * 1000L) // 9 minutes proactive close before Gemini's 10m hard timeout
            if (!isManuallyClosed && isSetupComplete) {
                Log.i("GeminiWebSocket", "Proactive 9-minute session renewal triggered")
                webSocket?.close(1000, "Session renewal")
            }
        }
    }

    @Synchronized
    private fun stopSessionRenewal() {
        renewalJob?.cancel()
        renewalJob = null
    }

    private fun flushPendingMessages() {
        while (true) {
            val msg = pendingTextMessages.poll() ?: break
            Log.d("GeminiFlow", "[GeminiFlow] flushing pending message after session ready: $msg")
            sendText(msg)
        }
    }

    private fun flushPendingAudio() {
        var count = 0
        while (true) {
            val chunk = pendingAudioQueue.poll() ?: break
            count++
            sendAudio(chunk)
        }
        if (count > 0) {
            Log.i("GeminiWebSocket", "Flushed $count pre-setup audio chunks in order after setupComplete")
        }
    }

    internal fun handleIncomingMessage(json: String) {
        try {
            val message = gson.fromJson(json, ServerMessage::class.java)
            
            // Check for setup complete
            if (message.setupComplete != null) {
                val sessionReadyTime = System.currentTimeMillis()
                Log.d("Latency", "[Latency] session_ready (elapsed=${sessionReadyTime - connectStartTime}ms)")
                Log.d("GeminiFlow", "[GeminiFlow] session_ready")
                Log.d("GeminiWebSocket", "Setup complete received!")
                isSetupComplete = true
                updateState(ConnectionState.CONNECTED)
                startHeartbeatLoop()
                startSessionRenewal()
                flushPendingAudio()
                flushPendingMessages()
            }
            message.sessionResumptionUpdate?.let {
                sessionState.updateResumptionIfCurrent(resumptionVersion, activeModelName, it)
            }
            message.toolCallCancellation?.ids?.forEach { id -> toolJobs[id]?.cancel() }
            message.goAway?.let {
                // Close cleanly; ConnectionManager reconnects with the latest resumable handle.
                // Never replay audio, user turns, or action dispatches on our own.
                webSocket?.close(1000, "Server requested session rotation")
            }
            
            // A. Interim user input transcription (live captions only, where supported)
            message.serverContent?.interimInputTranscription?.text?.let { interimText ->
                if (interimText.isNotBlank()) {
                    Log.d("GeminiWebSocket", "Interim user voice transcription delta='$interimText'")
                    onInterimTranscription?.invoke(interimText)
                }
            }

            // B. Ensure turn states flip to active when authoritative user input transcription arrives
            message.serverContent?.inputTranscription?.text?.let { transcription ->
                if (transcription.isBlank()) return@let
                if (lastInputTranscription.isBlank()) {
                    com.example.automation.engine.truth.ExecutionTruthCoordinator.clearTurnExecution()
                    resetInputTranscriptionAccumulator()
                }
                taskStopped = false
                isTurnInProgress = true
                idlePending = false
                val fullTranscript = accumulateInputTranscription(transcription)
                Log.d("GeminiWebSocket", "User voice transcription delta='$transcription' | accumulated='$fullTranscript'")
                onUserTranscription?.invoke(fullTranscript)
            }

            if (message.serverContent?.interrupted == true) {
                // Barge-in canceled the ongoing model turn; the next audio is a NEW user
                // turn, so the previous turn's transcript must not leak into it.
                Log.d("GeminiWebSocket", "Server reported interruption (barge-in): resetting transcription accumulator and flushing audio playback")
                isTurnInProgress = false // Interrupted by user intervention
                idlePending = false
                pendingAudioQueue.clear()
                lastInputTranscription = ""
                lastInputAtMs = 0L
                currentTurnSnapshot = null
                resetInputTranscriptionAccumulator()
                resetThoughtAccumulator()
                com.example.utils.AudioPlaybackManager.stopAndFlush()
            }

            var textDispatchedInFrame = false
            message.serverContent?.outputTranscription?.text?.let { rawText ->
                val text = com.example.utils.InternalReasoningFilter.sanitizeDelta(rawText)
                if (text.isNotEmpty()) {
                    isTurnInProgress = true
                    Log.d("GeminiFlow", "[GeminiFlow] response_received (outputTranscription)")
                    Log.d("GeminiWebSocket", "Output transcription received: $text")
                    onMessageReceived(text)
                    textDispatchedInFrame = true
                }
            }

            var receivedAnyPart = false
            message.serverContent?.modelTurn?.parts?.forEach { part ->
                if (part.thought == true) {
                    isTurnInProgress = true
                    if (!thinkingIndicatorSent) {
                        thinkingIndicatorSent = true
                        val progress = "Working on your request…"
                        onThoughtSummaryDelta?.invoke(progress, progress)
                    }
                    return@forEach
                }

                part.text?.let { rawText ->
                    val text = com.example.utils.InternalReasoningFilter.sanitizeDelta(rawText)
                    if (text.isNotEmpty()) {
                        receivedAnyPart = true
                        isTurnInProgress = true
                        Log.d("GeminiFlow", "[GeminiFlow] response_received")
                        Log.d("GeminiFlow", "[GeminiFlow] response_parsed")
                        Log.d("GeminiWebSocket", "Sanitized user-facing text: $text")
                        if (!textDispatchedInFrame) {
                            onMessageReceived(text)
                        }
                    }
                }
                
                part.inlineData?.let { inlineData ->
                    receivedAnyPart = true
                    isTurnInProgress = true
                    Log.d("GeminiFlow", "[GeminiFlow] response_received")
                    Log.d("GeminiFlow", "[GeminiFlow] response_parsed")
                    var audioBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                    if (audioBytes.size >= 16) {
                        val even = audioBytes.size and 1.inv()
                        if (even != audioBytes.size) {
                            audioBytes = audioBytes.copyOf(even)
                        }
                    }
                    Log.d("HUD_TEST", "WebSocket received audio bytes: ${audioBytes.size}, invoking callback")
                    onAudioReceived(audioBytes)
                }
            }

            message.toolCall?.functionCalls?.forEach(::executeFunctionCall)

            // Process boundaries AFTER every text/audio/tool part in the same frame.
            // Neither callback is permission to complete a durable task.
            val content = message.serverContent
            val serverSaysDone = content?.turnComplete == true ||
                    content?.generationComplete == true ||
                    content?.endOfTurn == true
            when (val status = content?.interactionStatus?.uppercase()) {
                "IN_PROGRESS" -> {
                    hasReceivedInteractionStatus = true
                    isTurnInProgress = true
                    idlePending = false
                    onInteractionStatusChanged?.invoke(status)
                }
                "IDLE" -> {
                    hasReceivedInteractionStatus = true
                    idlePending = isTurnInProgress
                    onInteractionStatusChanged?.invoke(status)
                    completeInteractionIfIdle()
                }
                else -> if (serverSaysDone) {
                    val isDeep = activeModelName == GeminiLiveModels.DEEP || activeModelName.contains("extended-thinking")
                    if (!isDeep) {
                        idlePending = isTurnInProgress
                        completeInteractionIfIdle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiWebSocket", "Error handling message: ${e.message}", e)
        }
    }

    @Synchronized
    private fun completeInteractionIfIdle() {
        if (isManuallyClosed || !idlePending || toolJobs.isNotEmpty() || !isTurnInProgress) return
        idlePending = false
        isTurnInProgress = false
        lastInputTranscription = ""
        lastInputAtMs = 0L
        currentTurnSnapshot = null
        resetInputTranscriptionAccumulator()
        resetThoughtAccumulator()
        onTurnComplete?.invoke()
    }

    private fun toolFailure(state: String, reason: String): String = JSONObject()
        .put("success", false).put("status", state.lowercase())
        .put("executionState", state).put("verificationStatus", "UNVERIFIED")
        .put("reason", reason).toString()

    private fun executeFunctionCall(call: FunctionCall) {
        isTurnInProgress = true
        idlePending = false
        val id = call.id?.takeIf { it.isNotBlank() } ?: return // An uncorrelatable call cannot execute.
        if (taskStopped) {
            sendToolResponse(id, call.name, toolFailure("CANCELLED", "Task stopped by user; continuation was not executed."))
            return
        }
        val signature = "${call.name}:${call.args}"
        if (!sessionState.reserveCall(id, signature)) {
            if (toolJobs.containsKey(id)) return
            sendToolResponse(id, call.name, sessionState.resultFor(id, signature)
                ?: toolFailure("UNVERIFIED", "Call already reserved or session call limit reached; execution was not replayed."))
            return
        }
        if (call.name !in ToolDispatcher.supportedTools) {
            val result = toolFailure("FAILED", "Unsupported tool: ${call.name}")
            sessionState.recordResult(id, result)
            sendToolResponse(id, call.name, result)
            return
        }
        if (isLiveScreenAction(call.name) && !requiresFastLiveModel(activeModelName, call.name)) {
            val result = buildLiveModelRequiredResult(call.name, activeModelName)
            sessionState.recordResult(id, result)
            sendToolResponse(id, call.name, result)
            return
        }
        // Translation can prepare pending call state, so it belongs INSIDE the serialized job.
        val snapshot = currentTurnSnapshot ?: TurnInputSnapshot(lastInputTranscription, lastInputAtMs).also { currentTurnSnapshot = it }
        val userIntent = snapshot.transcript
        val inputTime = snapshot.timestampMs

        val job = clientScope.launch(start = CoroutineStart.LAZY) {
            try {
                roomWriteMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (taskStopped) throw CancellationException("Task stopped before dispatch")

                    // Transcript-timing safety: if a call is pending and at dispatch the transcript is
                    // empty or not fresh, wait up to 800 ms for the finalized input transcription before deciding.
                    val isCallAction = call.name == "call_contact" || call.name == "call_phone"
                    if (isCallAction && com.example.automation.engine.PendingCallManager.hasPendingCall()) {
                        val pendingSince = com.example.automation.engine.PendingCallManager.pendingSinceMs()
                        var currentSnap = currentTurnSnapshot ?: snapshot
                        if (currentSnap.transcript.isBlank() || currentSnap.timestampMs <= pendingSince) {
                            val waitDeadline = System.currentTimeMillis() + 800L
                            while (System.currentTimeMillis() < waitDeadline && currentCoroutineContext().isActive) {
                                val latestSnap = currentTurnSnapshot
                                if (latestSnap != null && latestSnap.transcript.isNotBlank() && latestSnap.timestampMs > pendingSince) {
                                    break
                                }
                                kotlinx.coroutines.delay(50L)
                            }
                        }
                    }

                    val activeSnapshot = currentTurnSnapshot ?: snapshot
                    val activeUserIntent = activeSnapshot.transcript
                    val activeInputTime = activeSnapshot.timestampMs
                    val isApprovalConsumed = activeSnapshot.consumedForApproval.get()
                    val rawDispatchStr = ToolDispatcher.dispatch(call, activeUserIntent, activeInputTime, isApprovalConsumed)
                    val dispatchObj = JSONObject(rawDispatchStr)
                    if (dispatchObj.optBoolean("confirmed", false)) {
                        val didConsume = activeSnapshot.consumedForApproval.compareAndSet(false, true)
                        if (!didConsume) {
                            dispatchObj.remove("confirmed")
                            dispatchObj.remove("user_confirmed")
                        }
                    }
                    val jsonStr = dispatchObj
                        .put("toolCallId", id).put("requestId", id).toString()
                    val startedAt = System.nanoTime()
                    val result = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        onExecuteAutomation(jsonStr)
                    } ?: toolFailure("UNVERIFIED", "Tool deadline exceeded (15s); an external effect may have occurred. Do not replay.")
                    currentCoroutineContext().ensureActive()
                    sessionState.recordResult(id, result)
                    Log.i("Latency", "tool_result name=${call.name} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                    if (!isManuallyClosed) {
                        sendToolResponse(id, call.name, result)
                        if (isLiveScreenAction(call.name)) {
                            sendFreshScreenContinuation(call.name)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                sessionState.recordResult(id, toolFailure("UNVERIFIED", "Execution cancelled; any dispatched effect requires verification before retry."))
                throw cancelled
            } catch (e: Exception) {
                Log.e("GeminiWebSocket", "Tool execution failed for ${call.name}", e)
                val result = toolFailure("FAILED", "Tool execution failed: ${e.message}")
                sessionState.recordResult(id, result)
                if (!isManuallyClosed) sendToolResponse(id, call.name, result)
            }
        }
        toolJobs[id] = job
        job.invokeOnCompletion {
            toolJobs.remove(id, job)
            completeInteractionIfIdle()
        }
        job.start()
    }

    private fun cancelToolJobs() {
        toolJobs.values.forEach { it.cancel() }
    }

    private suspend fun sendFreshScreenContinuation(actionName: String) {
        val context = applicationContext ?: return
        try {
            val screenContext = ScreenContextProvider.captureScreenContext(
                context = context,
                focus = "accessibility_only"
            )
            val screenshot = ScreenCaptureProvider.captureCompressedJpeg()
            if (!isManuallyClosed && screenshot != null && screenshot.isNotEmpty()) {
                sendVideoFrameForced(screenshot)
            }
            if (!isManuallyClosed && isSetupComplete && webSocket != null) {
                val continuation = buildScreenContinuationPayload(
                    packageName = screenContext.packageName,
                    visibleText = screenContext.visibleText,
                    clickableElements = screenContext.clickableElements,
                    actionName = actionName
                )
                isTurnInProgress = true
                idlePending = false
                webSocket?.send(continuation)
                Log.d("GeminiWebSocket", "Sent fresh post-action screen continuation for $actionName")
            }
        } catch (e: Exception) {
            Log.w("GeminiWebSocket", "Post-action screen continuation failed for $actionName: ${e.message}")
        }
    }
    
    fun sendText(text: String) {
        taskStopped = false
        com.example.automation.engine.truth.ExecutionTruthCoordinator.clearTurnExecution()
        synchronized(transcriptionLock) {
            inputTranscriptionBuffer.setLength(0)
            inputTranscriptionBuffer.append(text)
        }
        lastInputTranscription = text
        lastInputAtMs = System.currentTimeMillis()
        currentTurnSnapshot = TurnInputSnapshot(text, lastInputAtMs)
        isTurnInProgress = true
        idlePending = false
        if (!isSetupComplete || webSocket == null) {
            Log.w("GeminiWebSocket", "Cannot send text immediately: isSetupComplete=$isSetupComplete, queuing text into FIFO buffer")
            pendingTextMessages.offer(text)
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.FAILED) {
                connect()
            }
            return
        }
        val safeText = gson.toJson(text)
        val textJson = """
        {
            "clientContent": {
                "turns": [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": $safeText
                            }
                        ]
                    }
                ],
                "turnComplete": true
            }
        }
        """.trimIndent()
        
        Log.d("GeminiFlow", "[GeminiFlow] message_sent")
        Log.d("GeminiWebSocket", "Sending text message")
        webSocket?.send(textJson)
    }
    
    fun sendAudio(audioData: ByteArray) {
        if (!isSetupComplete || webSocket == null) {
            if (pendingAudioQueue.size < maxPendingAudioChunks) {
                pendingAudioQueue.offer(audioData)
                Log.d("GeminiWebSocket", "Audio chunk buffered before setupComplete (buffered count: ${pendingAudioQueue.size})")
            } else {
                Log.w("GeminiWebSocket", "Pre-setup audio queue full (${pendingAudioQueue.size}), dropping audio chunk")
            }
            return
        }
        // Do NOT set isTurnInProgress = true on raw mic PCM frames;
        // serverContent.inputTranscription and modelTurn already flip isTurnInProgress when real speech is detected.
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val audioJson = """
        {
            "realtimeInput": {
                "audio": {
                    "mimeType": "$CLIENT_AUDIO_MIME_TYPE",
                    "data": "$base64Audio"
                }
            }
        }
        """.trimIndent()
        
        Log.d("GeminiWebSocket", "Sending audio chunk size: ${audioData.size}")
        webSocket?.send(audioJson)
    }

    /**
     * Signals the end of the streaming realtime audio input in automatic VAD mode.
     * Transmits realtimeInput.audioStreamEnd: true to Gemini Live.
     */
    fun sendAudioStreamEnd() {
        if (!isSetupComplete || webSocket == null) {
            Log.w("GeminiWebSocket", "Cannot send audioStreamEnd: ws not ready (isSetupComplete=$isSetupComplete)")
            return
        }
        val streamEndJson = buildAudioStreamEndPayload()
        Log.d("GeminiFlow", "[GeminiFlow] realtime_audio_stream_end")
        Log.d("GeminiWebSocket", "Sending realtimeInput audioStreamEnd: true")
        webSocket?.send(streamEndJson)
    }

    /**
     * Signals activity end for manual VAD mode if explicitly enabled.
     */
    fun sendActivityEnd() {
        if (!isSetupComplete || webSocket == null) return
        val activityEndJson = """{"realtimeInput":{"activityEnd":{}}}"""
        Log.d("GeminiWebSocket", "Sending realtimeInput activityEnd")
        webSocket?.send(activityEndJson)
    }

    /**
     * Sends client-side interruption signal to server on user barge-in.
     */
    fun sendInterruptionSignalToServer() {
        if (!isSetupComplete || webSocket == null) return
        val interruptionJson = """{"clientContent":{"turns":[],"turnComplete":false}}"""
        Log.d("GeminiWebSocket", "Sending client interruption signal to server")
        webSocket?.send(interruptionJson)
    }

    /**
     * Explicitly commits the streaming audio turn to Gemini Live.
     * This signals End-of-Speech from the client side without waiting for cloud silence timeout.
     */
    fun commitAudioTurn() {
        if (!isSetupComplete || webSocket == null) return
        val commitJson = """
        {
            "clientContent": {
                "turns": [],
                "turnComplete": true
            }
        }
        """.trimIndent()
        Log.d("GeminiFlow", "[GeminiFlow] audio_turn_commit")
        Log.d("GeminiWebSocket", "Sending client-side audio turn complete commit")
        webSocket?.send(commitJson)
    }

    private val videoFrameTxCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun sendVideoFrame(jpegBytes: ByteArray): Boolean {
        return sendVideoFrameInternal(jpegBytes, force = false)
    }

    private fun sendVideoFrameForced(jpegBytes: ByteArray): Boolean =
        sendVideoFrameInternal(jpegBytes, force = true)

    private fun sendVideoFrameInternal(jpegBytes: ByteArray, force: Boolean): Boolean {
        if (!isSetupComplete || webSocket == null || jpegBytes.isEmpty()) return false

        if (!force) {
            // 1 FPS CAS gate — drop frames sent faster than 1000ms
            val now = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val prev = lastVideoFrameSentAt.get()
                if (now - prev < 1000L) return false
                if (lastVideoFrameSentAt.compareAndSet(prev, now)) break
            }
        }

        val txCount = videoFrameTxCounter.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        val base64Video = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val videoJson = """
        {
            "realtimeInput": {
                "video": {
                    "mimeType": "image/jpeg",
                    "data": "$base64Video"
                }
            }
        }
        """.trimIndent()
        
        val sent = webSocket?.send(videoJson) ?: false
        Log.d("GeminiWebSocket", "[VisionTelemetry] sendVideoFrame #$txCount TRANSMITTED: $sent | bytes: ${jpegBytes.size} | base64Len: ${base64Video.length} | model: $activeModelName at $timestamp")
        return sent
    }

    /**
     * Injects a text turn (protocol-level client_content alias).
     */
    fun sendClientContent(text: String) = sendText(text)
    
    @Synchronized
    fun disconnect() {
        com.example.automation.engine.TaskCancellationCoordinator.unregisterCancellationListener(this)
        isManuallyClosed = true
        clientScope.coroutineContext.cancelChildren()
        stopHeartbeatLoop()
        stopSessionRenewal()
        clearSessionTranscription()
        val ws = webSocket
        webSocket = null
        isSetupComplete = false
        if (ws != null) {
            try {
                ws.close(1000, "Closing connection")
            } catch (e: Exception) {
                Log.w("GeminiWebSocket", "Error closing websocket: ${e.message}")
            }
        }
        updateState(ConnectionState.DISCONNECTED)
    }

    fun sendToolResponse(
        id: String,
        name: String,
        responseJsonStr: String,
        scheduling: String? = null
    ) {
        if (id.isBlank()) return
        val responseObj = try {
            org.json.JSONObject(responseJsonStr)
        } catch (e: Exception) {
            responseJsonStr
        }

        val output = if (responseObj is JSONObject) responseObj else JSONObject().put("message", responseObj)
        output.put("toolCallId", id).put("timestamp", System.currentTimeMillis())

        // Determine smart scheduling for NON_BLOCKING tool responses
        val resolvedScheduling = scheduling ?: when {
            name == "save_memory" -> "SILENT"
            output.optBoolean("success", true) == false -> "INTERRUPT"
            else -> "WHEN_IDLE"
        }

        val responseWrapper = JSONObject()
            .put("output", output)
            .put("scheduling", resolvedScheduling)

        val functionResponse = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("response", responseWrapper)

        val responseJson = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(functionResponse))
        ).toString()

        Log.d("GeminiWebSocket", "Sending tool response name=$name scheduling=$resolvedScheduling")
        webSocket?.send(responseJson)
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}
