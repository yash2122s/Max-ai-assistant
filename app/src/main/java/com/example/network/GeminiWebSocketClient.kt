package com.example.network

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
import org.json.JSONObject
import org.json.JSONArray

import com.example.network.model.GeminiLiveModels
import com.example.network.model.GeminiModelMode
import com.example.network.model.ModelHandoffContext
import com.example.network.telemetry.DualModelTelemetry

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
    private val onTurnComplete: (() -> Unit)? = null,
    val onThoughtSummaryDelta: ((delta: String, fullSummary: String) -> Unit)? = null,
    val onInteractionStatusChanged: ((status: String) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    
    companion object {
        const val DEFAULT_LIVE_MODEL = GeminiLiveModels.FAST
        const val DEEP_LIVE_MODEL = GeminiLiveModels.DEEP
        const val FALLBACK_LIVE_MODEL = GeminiLiveModels.FALLBACK

        fun sanitizeModelName(name: String): String {
            val trimmed = name.trim()
            return when {
                trimmed.isBlank() || trimmed.equals("Auto", ignoreCase = true) -> DEFAULT_LIVE_MODEL
                trimmed.startsWith("models/") -> trimmed.substringAfter("models/")
                else -> trimmed
            }
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

    // Guards the per-turn transcription accumulator (WebSocket callbacks arrive
    // on a single OkHttp thread today, but keep access safe regardless).
    private val transcriptionLock = Any()
    private val inputTranscriptionBuffer = StringBuilder()

    /** Appends the next live-transcription delta and returns the full accumulated transcript for this turn. */
    private fun accumulateInputTranscription(delta: String): String = synchronized(transcriptionLock) {
        inputTranscriptionBuffer.append(delta)
        val fullTranscript = inputTranscriptionBuffer.toString()
        lastInputTranscription = fullTranscript
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
        }
    }

    private val thoughtLock = Any()
    private val thoughtBuffer = StringBuilder()

    /** Appends the next live thought delta and returns the full accumulated thought summary for this turn. */
    private fun accumulateThought(delta: String): String = synchronized(thoughtLock) {
        thoughtBuffer.append(delta)
        thoughtBuffer.toString()
    }

    /** Clears the per-turn thought accumulator buffer at turn boundaries. */
    private fun resetThoughtAccumulator() {
        synchronized(thoughtLock) {
            thoughtBuffer.setLength(0)
        }
    }
    
    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    private val pendingTextMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    val currentState: ConnectionState
        get() = connectionState

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
                val wsOpenTime = System.currentTimeMillis()
                Log.d("Latency", "[Latency] ws_open (elapsed=${wsOpenTime - connectStartTime}ms)")
                Log.d("GeminiFlow", "[GeminiFlow] socket_connected")
                Log.d("GeminiWebSocket", "WebSocket onOpen - sending setup for model '$activeModelName'")
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
                Log.e("GeminiFlow", "[GeminiFlow] error=${t.message?.take(100) ?: "WebSocket failure"}")
                Log.e("GeminiWebSocket", "WebSocket onFailure: ${t.message} (isManuallyClosed=$isManuallyClosed)", t)
                response?.let { Log.e("GeminiWebSocket", "Response code: ${it.code}, body: ${it.body?.string()?.take(500)}") }
                stopHeartbeatLoop()
                if (!isManuallyClosed) {
                    tryNextModelOrReportError(t.message ?: "WebSocket failure")
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
                Log.d("GeminiWebSocket", "WebSocket onClosed: $code $reason (isManuallyClosed=$isManuallyClosed)")
                stopHeartbeatLoop()
                updateState(ConnectionState.DISCONNECTED)
                if (!isManuallyClosed) {
                    if ((code == 1008 || code == 1007 || code == 1002) && candidateIndex < modelCandidates.size - 1) {
                        Log.w("GeminiWebSocket", "WebSocket closed with code $code. Attempting fallback model...")
                        tryNextModelOrReportError(reason)
                    } else {
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
    private var connectStartTime: Long = 0L

    private fun sendSetupMessage() {
        val baseSystemInstruction = PromptBuilder.buildSystemInstruction(responseLanguage, memoriesMarkdown, emotionState)
        val handoffSection = handoffContext?.toSystemInstructionPrompt() ?: ""
        val systemInstructionText = baseSystemInstruction + handoffSection

        val toolsJsonStr = """[{"functionDeclarations":[{"name":"computer_use","description":"Performs multi-step in-app navigation, searching, profile opening, clicking, and verified UI automation on the Android screen. Use whenever user asks to search inside apps (Instagram, Maps, YouTube, Chrome, etc.), navigate screens, find profiles/accounts, or inspect and interact with the screen.","parameters":{"type":"OBJECT","properties":{"goal":{"type":"STRING","description":"The natural language user goal to accomplish on screen."},"target_app":{"type":"STRING","description":"Optional target application name (e.g. 'Instagram', 'Google Maps', 'Chrome')."}},"required":["goal"]}},{"name":"task_intent","description":"Dispatches a structured multi-step or autonomous automation goal on the device.","parameters":{"type":"OBJECT","properties":{"goal":{"type":"STRING","description":"Normalized user goal (e.g., 'send_whatsapp_message')."},"target_entity":{"type":"STRING","description":"Canonical app, setting, or contact entity."},"required_outcome":{"type":"STRING","description":"The expected end-state verification."},"parameters":{"type":"OBJECT","description":"Key-value arguments extracted from intent."}},"required":["goal","target_entity","required_outcome"]}},{"name":"type_text","description":"Types arbitrary text into a target or currently focused text field on screen. Use whenever user says 'type X', 'enter X in the field', 'search for X', 'X type chey', 'X ani rayi'.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The exact arbitrary text string to type into the field."},"target":{"type":"STRING","description":"Optional target field name, label, or hint."},"press_enter":{"type":"BOOLEAN","description":"Optional true to automatically press Enter/Search after typing."}},"required":["text"]}},{"name":"press_enter","description":"Presses the keyboard Enter / Search / Go action button (IME action). Use whenever user says 'press enter', 'enter kottu', 'hit enter', 'submit', 'search'.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"Optional target field name or submit button label."}}}},{"name":"press_key","description":"Presses a specific keyboard key (e.g. enter, back, backspace, tab, delete, space, home, end, escape, arrows). Use whenever user asks to press a specific key.","parameters":{"type":"OBJECT","properties":{"key":{"type":"STRING","description":"The key name to press: 'enter', 'back', 'tab', 'space', 'delete', 'backspace', 'esc', 'home', 'end', 'arrow_up', 'arrow_down', 'arrow_left', 'arrow_right'."}},"required":["key"]}},{"name":"clear_text","description":"Clears the text in the currently focused or specified text field. Use whenever user says 'clear text', 'clear field', 'text delete chey', 'erase'.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"Optional target field name or hint."}}}},{"name":"answer_call","description":"Answers the current incoming phone call. Use when the user says 'answer', 'answer the call', 'pick up', 'accept', 'lift the call', 'matladu'.","parameters":{"type":"OBJECT","properties":{}}},{"name":"reject_call","description":"Rejects or declines the current incoming phone call. Use when the user says 'reject', 'reject the call', 'decline', 'cut the call', 'cut chey', 'disconnect'.","parameters":{"type":"OBJECT","properties":{}}},{"name":"send_sms","description":"Sends a direct SMS text message to a phone number or contact.","parameters":{"type":"OBJECT","properties":{"number":{"type":"STRING","description":"The recipient phone number."},"message":{"type":"STRING","description":"The SMS message text content to send."}},"required":["number","message"]}},{"name":"execute_automation","description":"Executes an automation command on the Android device.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","description":"The action to perform, e.g. OPEN_APP, FLASHLIGHT_ON, SET_VOLUME, VOLUME_UP, VOLUME_DOWN, SET_BRIGHTNESS, BRIGHTNESS_UP, BRIGHTNESS_DOWN, DND_ON, DND_OFF, SILENT_MODE_ON, SILENT_MODE_OFF"},"app":{"type":"STRING","description":"The app name to open, if applicable"},"query":{"type":"STRING","description":"The search query or video name, if applicable"},"percent":{"type":"INTEGER","description":"The volume or brightness percentage value from 0 to 100, if applicable"},"direction":{"type":"STRING","description":"The direction for change, either 'up' or 'down', if applicable"},"mode":{"type":"STRING","description":"The target mode for ringer or DND, either 'silent', 'vibrate', 'normal', 'dnd_on', 'dnd_off', if applicable"}},"required":["action"]}},{"name":"click_element","description":"Clicks a visible UI element, button, text, or row on the current screen. Use whenever user says 'click X', 'tap X', 'X meeda click chey', 'press X'.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The visible label or text of the target UI element to click."},"description":{"type":"STRING","description":"Optional accessibility content description of the target element."}},"required":["text"]}},{"name":"scroll_screen","description":"Scrolls the currently visible screen up or down. Use whenever user says 'scroll down', 'scroll up', 'kindha scroll chey', 'paina scroll chey'.","parameters":{"type":"OBJECT","properties":{"direction":{"type":"STRING","description":"Scroll direction: 'down' or 'up'","enum":["down","up"]}},"required":["direction"]}},{"name":"search_contact","description":"Searches the device contacts for a given name and returns their contactId, phoneId, and normalized phone numbers. ALWAYS use this before calling a contact.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The name of the contact to search for."}},"required":["query"]}},{"name":"call_contact","description":"Calls a specific contact using their contactId and phoneId. NEVER use this without first invoking search_contact and getting user confirmation.","parameters":{"type":"OBJECT","properties":{"contactId":{"type":"INTEGER","description":"The numeric ID of the contact from search_contact."},"phoneId":{"type":"INTEGER","description":"The numeric phone ID to call from search_contact."}},"required":["contactId","phoneId"]}},{"name":"youtube_search","description":"Search YouTube for the given query and automatically play the first matching video. Use this tool whenever the user wants to: play music, play songs, search YouTube, watch videos, search and play content. Do NOT use open_app for these requests.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The video title, artist, or song to play, e.g. 'Starboy', 'Believer'."}},"required":["query"]}},{"name":"open_app","description":"Opens an app on the phone by its name.","parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING","description":"The name of the app to open, e.g. Instagram, WhatsApp"}},"required":["app_name"]}},{"name":"send_whatsapp_message","description":"Sends a WhatsApp message to a contact name or phone number.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The contact name or phone number to send the message to."},"message":{"type":"STRING","description":"The message text content to send."}},"required":["contact","message"]}},{"name":"flashlight","description":"Turns the phone flashlight (torch) ON or OFF.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to turn ON, false to turn OFF."}},"required":["enabled"]}},{"name":"schedule_task","description":"Schedules a tool to execute at a later time.","parameters":{"type":"OBJECT","properties":{"tool":{"type":"STRING","description":"The name of the target tool to run (flashlight, send_whatsapp_message, open_app)."},"time_expression":{"type":"STRING","description":"The natural language expression when it should run (e.g. 'in 5 minutes', 'tomorrow at 9 AM', 'tonight at 8:30 PM', 'on Friday at 3 PM')."},"repeat_type":{"type":"STRING","description":"Optional repeat interval ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY').","enum":["NONE","DAILY","WEEKLY","MONTHLY"]},"contact":{"type":"STRING","description":"Optional contact name/number if scheduling send_whatsapp_message."},"message":{"type":"STRING","description":"Optional message body if scheduling send_whatsapp_message."},"app_name":{"type":"STRING","description":"Optional app name if scheduling open_app."},"enabled":{"type":"BOOLEAN","description":"Optional flashlight state if scheduling flashlight."}},"required":["tool","time_expression"]}},{"name":"cancel_task","description":"Cancels a pending scheduled task by its task ID.","parameters":{"type":"OBJECT","properties":{"task_id":{"type":"STRING","description":"The unique ID of the task to cancel."}},"required":["task_id"]}},{"name":"list_scheduled_tasks","description":"Returns a list of all currently active pending scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},{"name":"diagnostics","description":"Runs MAX diagnostics to report all registered tools and their actions.","parameters":{"type":"OBJECT","properties":{}}},{"name":"create_contact","description":"Creates a new contact on the device.","parameters":{"type":"OBJECT","properties":{"name":{"type":"STRING","description":"The display name of the contact"},"phone":{"type":"STRING","description":"The phone number of the contact"}},"required":["name"]}},{"name":"windows_cmd","description":"Executes a command on the paired Windows laptop agent. Actions include listing directory (dir), changing working directory (cd), locating program files (where), or echoing output (echo).","parameters":{"type":"OBJECT","properties":{"cmd_action":{"type":"STRING","description":"The action to execute: 'dir' to list files, 'cd' to change folders, 'where' to locate executables, 'echo' to print a string.","enum":["dir","cd","where","echo"]},"path":{"type":"STRING","description":"The folder path target, if applicable (e.g. for dir or cd)."},"message":{"type":"STRING","description":"The message string target, if applicable (e.g. for echo)."},"program":{"type":"STRING","description":"The program executable search name, if applicable (e.g. for where)."}},"required":["cmd_action"]}},{"name":"windows_agent","description":"Orchestrates automation on the paired Windows PC agent (Clipboard, Windows management, Desktop Vision, File Search, and Terminal Control).","parameters":{"type":"OBJECT","properties":{"agent_action":{"type":"STRING","description":"The namespace action of Windows automation to execute.","enum":["core.clipboard:get","core.clipboard:set","core.window:list","core.window:minimize","core.window:maximize","core.window:close","core.window:focus","core.filesystem:search","core.filesystem:open","core.vision:capture","core.terminal:run","core.terminal:kill","core.app:list","core.app:launch","core.app:is_running","core.app:close"]},"path":{"type":"STRING","description":"Folder/file path target (e.g., for core.filesystem:search or core.filesystem:open)."},"message":{"type":"STRING","description":"The message text to sync (e.g., for core.clipboard:set)."},"query":{"type":"STRING","description":"Search query keyword (e.g., for core.filesystem:search)."},"target_name":{"type":"STRING","description":"Fuzzy window title, application name, or process executable to close/minimize/maximize/focus. Pass 'all' to minimize all windows."},"app_name":{"type":"STRING","description":"Application name to launch, query, or close (e.g., 'chrome', 'notepad', 'calculator', 'vs code')."},"app":{"type":"STRING","description":"Alternative application name parameter."},"command":{"type":"STRING","description":"PowerShell/cmd shell command to run asynchronously (e.g., for core.terminal:run)."}},"required":["agent_action"]}},{"name":"get_battery_status","description":"Gets the current battery percentage and charging status of the device."},{"name":"get_dnd_status","description":"Checks if Do Not Disturb (DND) mode is currently enabled on the device."},{"name":"set_dnd","description":"Enables or disables Do Not Disturb (DND) mode.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to enable DND, false to disable it."}},"required":["enabled"]}},{"name":"get_calendar_events","description":"Retrieves list of calendar events on the device for the next 7 days."},{"name":"add_calendar_event","description":"Schedules a new calendar event on the device.","parameters":{"type":"OBJECT","properties":{"title":{"type":"STRING","description":"Title of the event"},"description":{"type":"STRING","description":"Optional description"},"startTime":{"type":"INTEGER","description":"Epoch start time in milliseconds"},"endTime":{"type":"INTEGER","description":"Epoch end time in milliseconds"}},"required":["title","startTime","endTime"]}},{"name":"take_screenshot","description":"Takes a screenshot of the current screen."},{"name":"take_photo","description":"Takes a photo using the device camera."},{"name":"run_adb_command","description":"Runs an ADB shell command via Shizuku on the device. Requires Shizuku running and authorized.","parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING","description":"The shell command to run (e.g. 'pm list packages')"}},"required":["command"]}},{"name":"get_bluetooth_status","description":"Checks if Bluetooth is currently enabled on the device."},{"name":"set_bluetooth","description":"Enables or disables Bluetooth.","parameters":{"type":"OBJECT","properties":{"enabled":{"type":"BOOLEAN","description":"True to enable Bluetooth, false to disable it."}},"required":["enabled"]}},{"name":"get_notifications","description":"Retrieves a list of active status bar notifications from communication/messaging apps on the device."},{"name":"reply_notification","description":"Sends a direct text reply to an active notification.","parameters":{"type":"OBJECT","properties":{"contact":{"type":"STRING","description":"The sender contact name or package name of the notification to reply to."},"message":{"type":"STRING","description":"The reply message text."}},"required":["contact","message"]}},{"name":"set_alarm","description":"Schedules a new alarm on the device clock.","parameters":{"type":"OBJECT","properties":{"hour":{"type":"INTEGER","description":"Hour of the alarm (0-23)"},"minutes":{"type":"INTEGER","description":"Minutes of the alarm (0-59)"},"message":{"type":"STRING","description":"Optional custom label for the alarm"}},"required":["hour","minutes"]}},{"name":"set_timer","description":"Starts a countdown timer on the device clock.","parameters":{"type":"OBJECT","properties":{"seconds":{"type":"INTEGER","description":"Duration of the timer in seconds"},"message":{"type":"STRING","description":"Optional custom label for the timer"}},"required":["seconds"]}},{"name":"control_media","description":"Controls media playback for active media sessions (e.g. Spotify, YouTube).","parameters":{"type":"OBJECT","properties":{"media_action":{"type":"STRING","description":"The playback command to send.","enum":["PLAY_MEDIA","PAUSE_MEDIA","NEXT_MEDIA","PREVIOUS_MEDIA"]}},"required":["media_action"]}},{"name":"search_files","description":"Searches local directories (Downloads, Documents, Pictures) for matching files.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"Keyword to search in file names"},"file_type":{"type":"STRING","description":"Filter by file type categories.","enum":["pdf","image","doc","any"]}},"required":["query"]}},{"name":"toggle_wifi","description":"Opens the device Wi-Fi connectivity panel to toggle Wi-Fi on or off."},{"name":"open_settings","description":"Opens a specific Android system settings screen.","parameters":{"type":"OBJECT","properties":{"target":{"type":"STRING","description":"The settings target screen to open.","enum":["wifi","bluetooth","battery","display","accessibility","location","apps","airplane","sound","date_time","main"]}},"required":["target"]}},{"name":"get_device_status","description":"Retrieves a detailed status and health summary of the device (battery, storage, RAM, settings states, volume, brightness)."},{"name":"get_app_usage","description":"Queries the foreground application usage duration statistics for today."},{"name":"get_clipboard","description":"Reads the current text content from the system clipboard. (Note: Only succeeds if the app is in the foreground)."},{"name":"set_clipboard","description":"Copies the provided text to the system copy-paste clipboard.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING","description":"The text to copy to clipboard"}},"required":["text"]}},{"name":"get_location","description":"Retrieves the device's current GPS location coordinates and returns a Google Maps link."},{"name":"run_routine","description":"Runs a pre-defined or custom routine of actions sequentially.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"The name of the routine to execute (e.g. 'sleep', 'morning', 'work', 'gaming', or custom names)."},"dry_run":{"type":"BOOLEAN","description":"If true, only runs a simulation validation check and returns the trace without applying updates."}},"required":["routine_name"]}},{"name":"create_routine","description":"Creates or updates a custom routine with sequential execution steps.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"Name of the custom routine to create (e.g. 'commute')."},"steps_json":{"type":"STRING","description":"A JSON array string of steps, each containing 'action' and 'arguments' (e.g. '[{\"action\":\"SET_DND\",\"arguments\":{\"enabled\":true}}]')."}},"required":["routine_name","steps_json"]}},{"name":"delete_routine","description":"Deletes a custom routine from local storage by its name. Built-in routines cannot be deleted.","parameters":{"type":"OBJECT","properties":{"routine_name":{"type":"STRING","description":"Name of the custom routine to delete."}},"required":["routine_name"]}},{"name":"list_routines","description":"Lists all available routines, both built-in presets and custom user-defined routines."},{"name":"log_period_start","description":"Logs the start date of a new period cycle. Use this when the user says they started their period.","parameters":{"type":"OBJECT","properties":{"date":{"type":"STRING","description":"Natural language date expression, e.g., 'today', 'yesterday', '3 days ago', 'July 15th'."},"notes":{"type":"STRING","description":"Optional symptoms or notes to record."}},"required":["date"]}},{"name":"log_period_end","description":"Logs the end date of the current active period cycle. Use this when the user says their period finished or ended.","parameters":{"type":"OBJECT","properties":{"date":{"type":"STRING","description":"Natural language date expression, e.g., 'today', 'yesterday', '1 day ago', 'July 20th'."}},"required":["date"]}},{"name":"log_period_note","description":"Adds mood, symptoms, or tracking notes to the latest period cycle. Use this when the user mentions symptoms or notes without starting/ending a cycle.","parameters":{"type":"OBJECT","properties":{"notes":{"type":"STRING","description":"The symptoms, mood, or other tracking notes to log."}},"required":["notes"]}},{"name":"get_period_history","description":"Gets a list of all logged period cycles including start dates, end dates, durations, and notes."},{"name":"get_period_prediction","description":"Calculates average cycle stats and predicts the next period start date, ovulation day, and fertile window. Exposes whether the user is on their period currently."},{"name":"clear_all_period_data","description":"Deletes all logged periods and cycle data from the device database."},{"name":"system_action","description":"Performs system navigation gestures on the Android device.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","enum":["home","back","recent","notifications","quick_settings","screenshot"],"description":"The system action to perform."}},"required":["action"]}},{"name":"save_memory","description":"Saves a user identity fact, name, preference, goal, or detail to permanent Room memory and the Markdown vault. Use whenever user shares their name (e.g. 'my name is Yash', 'naa peru Yash'), introduces themselves, says 'remember this', 'note down', 'my favorite X is Y', or asks to store any detail.","parameters":{"type":"OBJECT","properties":{"title":{"type":"STRING","description":"Short descriptive title for the memory (e.g. 'User Name', 'Favorite Food')"},"content":{"type":"STRING","description":"The memory content/fact to remember (e.g. 'User name is Yash')"},"category":{"type":"STRING","description":"Optional category (Personal, Preferences, Work, Goals, General)"},"type":{"type":"STRING","description":"FACT, PREFERENCE, GOAL, or CUSTOM","enum":["FACT","PREFERENCE","GOAL","CUSTOM"]}},"required":["title","content"]}},{"name":"forget_memory","description":"Deletes or forgets a specific permanent memory, preference, or fact. Use whenever user says 'forget that X', 'delete memory about X', 'naaku Telugu ante ishtam ani marchipo', etc.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The memory topic, title, or content to forget and remove."}},"required":["query"]}},{"name":"set_reminder","description":"Sets a reminder for a specific task, note, or event at a given time or relative interval. Use whenever user says 'remind me to X in Y minutes', 'set a reminder for X', etc.","parameters":{"type":"OBJECT","properties":{"message":{"type":"STRING","description":"The reminder title or message note text"},"time_expression":{"type":"STRING","description":"Natural language time expression e.g. 'in 10 minutes', 'tomorrow at 5 PM', 'at 6:30 PM'"},"minutes_from_now":{"type":"INTEGER","description":"Optional minutes count from now"}},"required":["message"]}},{"name":"unlock_device","description":"Unlocks the phone using the stored PIN code and wake lock automation. Use whenever user says 'unlock my phone', 'open sesame', 'unlock phone', etc.","parameters":{"type":"OBJECT","properties":{}}},{"name":"get_screen_context","description":"Captures and analyzes the current device screen content (both visual screenshot frame and textual UI hierarchy). Use whenever the user asks about what is on their screen, to read an article, explain an error, summarize a page, or asks where to click.","parameters":{"type":"OBJECT","properties":{"focus":{"type":"STRING","description":"The focus mode: 'summary' (overview of page), 'read_text' (article/text content), 'explain_error' (troubleshoot visible errors), 'find_element' (locate buttons/fields), 'general' (hybrid).","enum":["summary","read_text","explain_error","find_element","general"]}}}}]}]"""

        val isDeepMode = activeModelName == GeminiLiveModels.DEEP || activeModelName.contains("extended-thinking")

        val speechConfig = JSONObject().apply {
            put("voiceConfig", JSONObject().apply {
                put("prebuiltVoiceConfig", JSONObject().apply {
                    put("voiceName", voiceName)
                })
            })
        }

        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().apply {
                put("AUDIO")
            })
            put("speechConfig", speechConfig)
            if (isDeepMode) {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", "HIGH")
                })
            }
        }

        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", systemInstructionText)
                })
            })
        }

        val setupObj = JSONObject().apply {
            put("model", "models/$activeModelName")
            put("generationConfig", generationConfig)
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("systemInstruction", systemInstruction)
            put("tools", JSONArray(toolsJsonStr))
        }

        val setupPayload = JSONObject().apply {
            put("setup", setupObj)
        }.toString()

        Log.d("GeminiWebSocket", "Sending setup (isDeepMode=$isDeepMode, model=$activeModelName): $setupPayload")
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
    private val clientScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

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
                        val heartbeatJson = """{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=24000","data":"$base64Audio"}}}"""
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

    private fun flushPendingMessages() {
        while (true) {
            val msg = pendingTextMessages.poll() ?: break
            Log.d("GeminiFlow", "[GeminiFlow] flushing pending message after session ready: $msg")
            sendText(msg)
        }
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val gson = Gson()
            val message = gson.fromJson(json, ServerMessage::class.java)
            
            // Check for setup complete
            if (json.contains("\"setupComplete\"")) {
                val sessionReadyTime = System.currentTimeMillis()
                Log.d("Latency", "[Latency] session_ready (elapsed=${sessionReadyTime - connectStartTime}ms)")
                Log.d("GeminiFlow", "[GeminiFlow] session_ready")
                Log.d("GeminiWebSocket", "Setup complete received!")
                isSetupComplete = true
                updateState(ConnectionState.CONNECTED)
                startHeartbeatLoop()
                flushPendingMessages()
                return
            }

            // Parse interactionStatus (e.g. IN_PROGRESS vs IDLE)
            message.serverContent?.interactionStatus?.let { status ->
                Log.d("GeminiWebSocket", "Live API interactionStatus received: $status")
                onInteractionStatusChanged?.invoke(status)
            }
            
            message.serverContent?.inputTranscription?.text?.let { transcription ->
                if (transcription.isBlank()) return@let
                // Deltas must be appended, never assigned, so the full phrase survives.
                val fullTranscript = accumulateInputTranscription(transcription)
                Log.d("GeminiWebSocket", "User voice transcription delta='$transcription' | accumulated='$fullTranscript'")
                onUserTranscription?.invoke(fullTranscript)
            }

            if (message.serverContent?.interrupted == true) {
                // Barge-in canceled the ongoing model turn; the next audio is a NEW user
                // turn, so the previous turn's transcript must not leak into it.
                Log.d("GeminiWebSocket", "Server reported interruption (barge-in): resetting transcription accumulator and flushing audio playback")
                resetInputTranscriptionAccumulator()
                resetThoughtAccumulator()
                com.example.utils.AudioPlaybackManager.stopAndFlush()
            }

            var textDispatchedInFrame = false
            message.serverContent?.outputTranscription?.text?.let { rawText ->
                val text = com.example.utils.InternalReasoningFilter.sanitize(rawText)
                if (text.isNotBlank()) {
                    Log.d("GeminiFlow", "[GeminiFlow] response_received (outputTranscription)")
                    Log.d("GeminiWebSocket", "Output transcription received: $text")
                    onMessageReceived(text)
                    textDispatchedInFrame = true
                }
            }

            var receivedAnyPart = false
            message.serverContent?.modelTurn?.parts?.forEach { part ->
                if (part.thought == true) {
                    part.text?.let { rawThought ->
                        val thoughtChunk = com.example.utils.InternalReasoningFilter.sanitize(rawThought)
                        if (thoughtChunk.isNotBlank()) {
                            val accumulatedThought = accumulateThought(thoughtChunk)
                            Log.d("GeminiWebSocket", "Thought summary chunk: ${thoughtChunk.take(60)}... (full length: ${accumulatedThought.length})")
                            onThoughtSummaryDelta?.invoke(thoughtChunk, accumulatedThought)
                        }
                    }
                    return@forEach
                }

                part.text?.let { rawText ->
                    val text = com.example.utils.InternalReasoningFilter.sanitize(rawText)
                    if (text.isNotBlank()) {
                        receivedAnyPart = true
                        Log.d("GeminiFlow", "[GeminiFlow] response_received")
                        Log.d("GeminiFlow", "[GeminiFlow] response_parsed")
                        Log.d("GeminiWebSocket", "Sanitized user-facing text: $text")
                        if (!textDispatchedInFrame) {
                            onMessageReceived(text)
                            textDispatchedInFrame = true
                        }
                    }
                }
                
                part.inlineData?.let { inlineData ->
                    receivedAnyPart = true
                    Log.d("GeminiFlow", "[GeminiFlow] response_received")
                    Log.d("GeminiFlow", "[GeminiFlow] response_parsed")
                    val audioBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                    Log.d("HUD_TEST", "WebSocket received audio bytes: ${audioBytes.size}, invoking callback")
                    onAudioReceived(audioBytes)
                }
            }

            message.toolCall?.functionCalls?.forEach { functionCall ->
                val callId = functionCall.id ?: "call_0"
                Log.d("GeminiWebSocket", "Function call received: ${functionCall.name}, id=$callId")
                if (ToolDispatcher.supportedTools.contains(functionCall.name)) {
                    val jsonStr = ToolDispatcher.dispatch(functionCall, lastInputTranscription)
                    Log.d("GeminiWebSocket", "Unified executing json asynchronously: $jsonStr")
                    
                    clientScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        roomWriteMutex.withLock {
                            try {
                                val resultJsonStr = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                                    onExecuteAutomation(jsonStr)
                                } ?: org.json.JSONObject().apply {
                                    put("status", "timeout")
                                    put("success", false)
                                    put("executionState", "TIMEOUT")
                                    put("reason", "Tool execution timed out after 20 seconds")
                                }.toString()
                                sendToolResponse(callId, functionCall.name, resultJsonStr)
                            } catch (e: Exception) {
                                Log.e("GeminiWebSocket", "Async tool execution error: ${e.message}", e)
                                val errJson = org.json.JSONObject().apply {
                                    put("status", "error")
                                    put("success", false)
                                    put("executionState", "FAILED")
                                    put("reason", e.message ?: "Tool execution failed")
                                }.toString()
                                sendToolResponse(callId, functionCall.name, errJson)
                            }
                        }
                    }
                } else {
                    Log.w("GeminiWebSocket", "Unsupported tool call received: ${functionCall.name}, immediately sending error response to prevent hang")
                    val unsupportedJson = org.json.JSONObject().apply {
                        put("status", "error")
                        put("success", false)
                        put("executionState", "FAILED")
                        put("reason", "Tool '${functionCall.name}' is not supported on this device.")
                    }.toString()
                    sendToolResponse(callId, functionCall.name, unsupportedJson)
                }
            }

            if (message.serverContent?.turnComplete == true) {
                Log.d("GeminiFlow", "[GeminiFlow] thinking_cleared")
                onTurnComplete?.invoke()
                // Model finished answering: reset buffer for next turn's incoming deltas
                resetInputTranscriptionAccumulator()
                resetThoughtAccumulator()
            }
        } catch (e: Exception) {
            Log.e("GeminiWebSocket", "Error handling message: ${e.message}", e)
        }
    }
    
    fun sendText(text: String) {
        lastInputTranscription = text
        if (!isSetupComplete || webSocket == null) {
            Log.w("GeminiWebSocket", "Cannot send text immediately: isSetupComplete=$isSetupComplete, queuing text into FIFO buffer")
            pendingTextMessages.offer(text)
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.FAILED) {
                connect()
            }
            return
        }
        val safeText = Gson().toJson(text)
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
        if (!isSetupComplete) return
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val audioJson = """
        {
            "realtimeInput": {
                "audio": {
                    "mimeType": "audio/pcm;rate=24000",
                    "data": "$base64Audio"
                }
            }
        }
        """.trimIndent()
        
        Log.d("GeminiWebSocket", "Sending audio chunk size: ${audioData.size}")
        webSocket?.send(audioJson)
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
        val txCount = videoFrameTxCounter.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        if (!isSetupComplete) {
            Log.w("GeminiWebSocket", "[VisionTelemetry] sendVideoFrame #$txCount DROPPED: isSetupComplete is FALSE at $timestamp")
            return false
        }
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
    
    @Synchronized
    fun disconnect() {
        isManuallyClosed = true
        stopHeartbeatLoop()
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
        } else {
            updateState(ConnectionState.DISCONNECTED)
        }
    }

    fun sendToolResponse(id: String, name: String, responseJsonStr: String) {
        val safeId = if (id.isEmpty()) "call_0" else id
        val responseObj = try {
            org.json.JSONObject(responseJsonStr)
        } catch (e: Exception) {
            responseJsonStr
        }

        val safeOutputJson = if (responseObj is String) Gson().toJson(responseObj) else responseObj.toString()
        val responseJson = """{"toolResponse":{"functionResponses":[{"id":"$safeId","name":"$name","response":{"output":$safeOutputJson}}]}}"""
        Log.d("GeminiWebSocket", "Sending clean tool response for $name: $responseJson")
        webSocket?.send(responseJson)
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}
