package com.example.network.agent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import org.json.JSONObject

class WindowsAgentClient(private val context: Context) {

    interface ConnectionListener {
        fun onConnected(capabilities: Map<String, Any>)
        fun onDisconnected()
        fun onError(t: Throwable)
    }

    interface ToolResponseCallback {
        fun onProgress(message: String)
        fun onResponse(status: String, output: String)
        fun onError(error: String)
    }

    private var client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING_PAIRING,
        PAIRING_HANDSHAKE,
        AUTHENTICATING,
        CONNECTED,
        CONNECTING_NORMAL,
        RECONNECT_WAIT,
        CLOSING
    }

    private val gson = Gson()
    private var isHandshakeDone = false
    private var connectionListener: ConnectionListener? = null
    private var sessionToken: String? = null
    private var savedCapabilities: Map<String, Any>? = null
    
    @Volatile
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var pendingPairingCode: String? = null
    private var pairCallback: ((Boolean, String?) -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun getConnectionState(): ConnectionState = connectionState
    fun isPairingInProgress(): Boolean = connectionState == ConnectionState.CONNECTING_PAIRING || connectionState == ConnectionState.PAIRING_HANDSHAKE

    private fun notifyPairResult(success: Boolean, error: String?) {
        val cb = pairCallback
        pairCallback = null
        pendingPairingCode = null
        if (success) {
            connectionState = ConnectionState.CONNECTED
        } else {
            connectionState = ConnectionState.DISCONNECTED
        }
        if (cb != null) {
            mainHandler.post {
                cb.invoke(success, error)
            }
        }
    }

    private val toolCallbacks = mutableMapOf<String, ToolResponseCallback>()
    private val toolCallbackTimestamps = mutableMapOf<String, Long>()
    private val TOOL_CALLBACK_TIMEOUT_MS = 60_000L // 60 seconds
    private var lastSentCapabilitiesJson: String? = null

    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        id
    }

    private fun createPinnedOkHttpClient(certFingerprint: String?, isPairing: Boolean = false): OkHttpClient {
        try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("Certificate chain is empty")
                    }
                    val serverCert = chain[0]
                    val sha256Bytes = MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                    val fingerprint = sha256Bytes.joinToString("") { "%02x".format(it) }
                    
                    Log.d("WindowsAgentClient", "[TLS_PIN_CHECK] Server certificate fingerprint: $fingerprint")
                    
                    val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                    val expectedFingerprint = certFingerprint ?: prefs.getString("cert_fingerprint", null)
                    
                    if (!expectedFingerprint.isNullOrEmpty()) {
                        Log.d("WindowsAgentClient", "[TLS_PIN_CHECK] Verifying against pinned certificate fingerprint: $expectedFingerprint")
                        if (fingerprint.lowercase() != expectedFingerprint.lowercase()) {
                            throw java.security.cert.CertificateException(
                                "Certificate fingerprint mismatch! Expected: $expectedFingerprint, Got: $fingerprint. Potential MITM attack blocked."
                            )
                        }
                    } else if (isPairing) {
                        // Explicit pairing flow: Trust server certificate for TOFU verification
                        Log.i("WindowsAgentClient", "[TLS_PIN_CHECK] Explicit pairing flow: trusting server certificate for TOFU verification.")
                    } else {
                        val isPaired = !prefs.getString("auth_token", null).isNullOrEmpty()
                        if (isPaired) {
                            // Already paired device MUST have pinned fingerprint
                            throw java.security.cert.CertificateException(
                                "Security Violation: Paired device is missing pinned TLS fingerprint. Re-pairing required."
                            )
                        } else {
                            Log.i("WindowsAgentClient", "[TLS_PIN_CHECK] Unpaired connection: trusting server certificate for TOFU.")
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            Log.e("WindowsAgentClient", "Error building SSL context: ${e.message}", e)
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED && webSocket != null
    fun isConnecting(): Boolean = (connectionState == ConnectionState.CONNECTING_PAIRING ||
            connectionState == ConnectionState.PAIRING_HANDSHAKE ||
            connectionState == ConnectionState.CONNECTING_NORMAL ||
            connectionState == ConnectionState.AUTHENTICATING) && webSocket != null

    fun connectForPairing(
        ip: String,
        port: Int,
        pairingCode: String,
        certFingerprint: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        if (connectionState == ConnectionState.CONNECTING_PAIRING || connectionState == ConnectionState.PAIRING_HANDSHAKE) {
            Log.d("WindowsAgentClient", "[PAIR_SKIP] Pairing already in progress. Skipping duplicate connectForPairing.")
            return
        }

        Log.i("WindowsAgentClient", "[PAIR_START] Initiating pairing with $ip:$port")
        try {
            webSocket?.close(1000, "Initiating new pairing")
        } catch (e: Exception) {
            Log.w("WindowsAgentClient", "Error closing previous socket: ${e.message}")
        }
        webSocket = null
        
        isHandshakeDone = false
        sessionToken = null
        savedCapabilities = null
        connectionState = ConnectionState.CONNECTING_PAIRING
        pendingPairingCode = pairingCode
        pairCallback = callback

        // Clear stale auth credentials for clean pairing flow
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("auth_token")
            .apply()

        val effectiveFingerprint = certFingerprint ?: prefs.getString("cert_fingerprint", null)
        client = createPinnedOkHttpClient(effectiveFingerprint, isPairing = true)

        val request = Request.Builder()
            .url("wss://$ip:$port")
            .build()

        Log.d("WindowsAgentClient", "[WS_CONNECT_INIT] Connecting to wss://$ip:$port (mode=PAIRING)")
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    fun connect(ip: String, port: Int, listener: ConnectionListener) {
        if (connectionState == ConnectionState.CONNECTING_PAIRING || connectionState == ConnectionState.PAIRING_HANDSHAKE) {
            Log.d("WindowsAgentClient", "Pairing in progress. Skipping normal connect.")
            return
        }
        if (connectionState == ConnectionState.CONNECTED && webSocket != null) {
            Log.d("WindowsAgentClient", "Already connected and authenticated. Skipping connect.")
            return
        }
        if (webSocket != null && (connectionState == ConnectionState.CONNECTING_NORMAL || connectionState == ConnectionState.AUTHENTICATING)) {
            Log.d("WindowsAgentClient", "Connection attempt already in progress. Skipping duplicate connect.")
            return
        }
        
        connectionListener = listener
        isHandshakeDone = false
        sessionToken = null
        savedCapabilities = null
        connectionState = ConnectionState.CONNECTING_NORMAL
        pendingPairingCode = null
        
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        val certFingerprint = prefs.getString("cert_fingerprint", null)
        
        client = createPinnedOkHttpClient(certFingerprint, isPairing = false)
        
        val request = Request.Builder()
            .url("wss://$ip:$port")
            .build()
            
        Log.d("WindowsAgentClient", "[WS_CONNECT_INIT] Connecting to wss://$ip:$port (mode=NORMAL)")
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WindowsAgentClient", "[WS_CONNECTED] WebSocket connection opened successfully (state=$connectionState).")
                if (connectionState == ConnectionState.CONNECTING_PAIRING) {
                    connectionState = ConnectionState.PAIRING_HANDSHAKE
                    val code = pendingPairingCode ?: ""
                    Log.i("WindowsAgentClient", "[PAIR_REQUEST_TX] Sending pair_request as first handshake packet...")
                    val envelope = Envelope(
                        type = "pair_request",
                        id = UUID.randomUUID().toString(),
                        source = Source(deviceId, "android"),
                        target = Target("windows-main"),
                        payload = PairRequestPayload(
                            pairingCode = code,
                            deviceName = android.os.Build.MODEL
                        )
                    )
                    webSocket.send(gson.toJson(envelope))
                } else {
                    connectionState = ConnectionState.AUTHENTICATING
                    Log.i("WindowsAgentClient", "[HELLO_TX] Sending HELLO handshake as first packet...")
                    sendHelloHandshake()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WindowsAgentClient", "[WS_CLOSED] WebSocket closed: code=$code, reason=$reason (priorState=$connectionState)")
                this@WindowsAgentClient.webSocket = null
                val priorSession = sessionToken
                val priorState = connectionState
                connectionState = ConnectionState.DISCONNECTED
                isHandshakeDone = false
                sessionToken = null
                if (!priorSession.isNullOrBlank()) {
                    com.example.voice.assistant.AssistantLifecycleManager.exitWorkerMode(priorSession)
                }
                WindowsToolExecutor.markConnected(false)
                if ((priorState == ConnectionState.CONNECTING_PAIRING || priorState == ConnectionState.PAIRING_HANDSHAKE) && pairCallback != null) {
                    Log.w("WindowsAgentClient", "[PAIR_FAIL] Pairing aborted due to socket closure (code=$code, reason=$reason)")
                    notifyPairResult(false, "Connection closed by server: $reason (code=$code)")
                }
                connectionListener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WindowsAgentClient", "[WS_FAILURE] WebSocket error: ${t.message} (priorState=$connectionState)", t)
                this@WindowsAgentClient.webSocket = null
                val priorSession = sessionToken
                val priorState = connectionState
                connectionState = ConnectionState.DISCONNECTED
                isHandshakeDone = false
                sessionToken = null
                if (!priorSession.isNullOrBlank()) {
                    com.example.voice.assistant.AssistantLifecycleManager.exitWorkerMode(priorSession)
                }
                WindowsToolExecutor.markConnected(false)
                if ((priorState == ConnectionState.CONNECTING_PAIRING || priorState == ConnectionState.PAIRING_HANDSHAKE) && pairCallback != null) {
                    Log.w("WindowsAgentClient", "[PAIR_FAIL] Pairing failed due to network error: ${t.message}")
                    notifyPairResult(false, "Connection error: ${t.message}")
                }
                connectionListener?.onError(t)
            }
        }
    }

    private fun sendHelloHandshake() {
        val caps = try {
            com.example.automation.tools.ToolRegistry.getAllCapabilities()
        } catch (e: Exception) {
            emptyList()
        }

        val isTablet = try {
            val config = context.resources.configuration
            (config.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >=
                    android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        } catch (e: Exception) {
            false
        }

        val envelope = Envelope(
            type = "hello",
            id = UUID.randomUUID().toString(),
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = HelloPayload(
                deviceName = android.os.Build.MODEL,
                appVersion = "1.0",
                deviceType = if (isTablet) "tablet" else "phone",
                model = android.os.Build.MODEL,
                osVersion = android.os.Build.VERSION.RELEASE,
                capabilities = caps
            )
        )
        webSocket?.send(gson.toJson(envelope))
    }


    private fun handleIncomingMessage(text: String) {
        try {
            val envelope = gson.fromJson(text, Envelope::class.java)
            val type = envelope.type
            val id = envelope.id
            
            Log.d("WindowsAgentClient", "Received packet: $type, id: $id")
            
            when (type) {
                "hello" -> {
                    val payload = gson.fromJson(gson.toJson(envelope.payload), HelloResponsePayload::class.java)
                    savedCapabilities = payload.capabilities
                }
                "auth_challenge" -> {
                    val payloadJson = gson.toJson(envelope.payload)
                    val payloadMap = gson.fromJson(payloadJson, Map::class.java)
                    val challenge = payloadMap["challenge"] as? String ?: ""
                    
                    val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                    val authToken = prefs.getString("auth_token", "") ?: ""
                    
                    val signature = hmacSha256(challenge, authToken)
                    
                    val responseEnv = Envelope(
                        type = "auth_response",
                        id = UUID.randomUUID().toString(),
                        source = Source(deviceId, "android"),
                        target = Target("windows-main"),
                        payload = mapOf("signature" to signature)
                    )
                    webSocket?.send(gson.toJson(responseEnv))
                }
                "auth_success" -> {
                    val payloadJson = gson.toJson(envelope.payload)
                    val payloadMap = gson.fromJson(payloadJson, Map::class.java)
                    val token = payloadMap["session_token"] as? String ?: UUID.randomUUID().toString()
                    sessionToken = token
                    isHandshakeDone = true
                    connectionState = ConnectionState.CONNECTED
                    com.example.voice.assistant.AssistantLifecycleManager.enterWorkerMode(token)
                    WindowsToolExecutor.markConnected(true)
                    sendCapabilitiesUpdate(force = true)
                    connectionListener?.onConnected(savedCapabilities ?: emptyMap())
                }
                "pair_response" -> {
                    Log.i("WindowsAgentClient", "[PAIR_RESPONSE_RX] Received pair_response")
                    val payload = gson.fromJson(gson.toJson(envelope.payload), PairResponsePayload::class.java)
                    if (payload.status == "success") {
                        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                        val editor = prefs.edit().putString("auth_token", payload.token)
                        if (!payload.cert_fingerprint.isNullOrEmpty()) {
                            editor.putString("cert_fingerprint", payload.cert_fingerprint)
                            Log.d("WindowsAgentClient", "Persisted server TLS certificate fingerprint: ${payload.cert_fingerprint}")
                        }
                        editor.apply()
                        sessionToken = payload.session_token
                        isHandshakeDone = true
                        connectionState = ConnectionState.CONNECTED
                        Log.i("WindowsAgentClient", "[AUTH_SUCCESS] Pair authentication successful. Session established.")
                        if (!payload.session_token.isNullOrBlank()) {
                            com.example.voice.assistant.AssistantLifecycleManager.enterWorkerMode(payload.session_token)
                            sendCapabilitiesUpdate(force = true)
                        }
                        WindowsToolExecutor.markConnected(true)
                        notifyPairResult(true, null)
                        connectionListener?.onConnected(savedCapabilities ?: emptyMap())
                    } else {
                        Log.w("WindowsAgentClient", "[PAIR_FAILURE] Pairing rejected by Windows: ${payload.message}")
                        webSocket?.close(1000, "Pairing rejected")
                        webSocket = null
                        notifyPairResult(false, payload.message ?: "Incorrect pairing code")
                    }
                }

                "tool_progress" -> {
                    val payload = gson.fromJson(gson.toJson(envelope.payload), ToolProgressPayload::class.java)
                    toolCallbacks[id]?.onProgress(payload.message ?: "")
                }
                "tool_response" -> {
                    val payload = gson.fromJson(gson.toJson(envelope.payload), ToolResponsePayload::class.java)
                    if (payload.status == "success") {
                        toolCallbacks[id]?.onResponse(payload.status, payload.output ?: "")
                    } else {
                        toolCallbacks[id]?.onError(payload.output ?: "Execution failed")
                    }
                    toolCallbacks.remove(id)
                    toolCallbackTimestamps.remove(id)
                }
                "heartbeat" -> {
                    Log.d("WindowsAgentClient", "Heartbeat echoed back successfully")
                }
                "event" -> {
                    val payloadJson = gson.toJson(envelope.payload)
                    val payloadMap = gson.fromJson(payloadJson, Map::class.java)
                    val eventName = payloadMap["event_name"] as? String ?: payloadMap["event"] as? String ?: ""
                    Log.d("WindowsAgentClient", "Event received: $eventName")
                    if (eventName == "core:pong") {
                        val telemetryData = payloadMap["data"]
                        Log.d("WindowsAgentClient", "Agent Telemetry metrics: $telemetryData")
                    }
                }
                "command_request" -> {
                    handleIncomingCommandRequest(envelope)
                }
                "command_result" -> {
                    Log.d("WindowsAgentClient", "Received command_result packet: id=$id")
                }
            }
        } catch (e: Exception) {
            Log.e("WindowsAgentClient", "Error parsing incoming frame: ${e.message}", e)
        }
    }

    val idempotencyCache = CommandIdempotencyCache()
    private val clientScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private fun handleIncomingCommandRequest(envelope: Envelope) {
        val payloadJson = gson.toJson(envelope.payload)
        val payload = try {
            gson.fromJson(payloadJson, CommandRequestPayload::class.java)
        } catch (e: Exception) {
            Log.w("WindowsAgentClient", "Failed to deserialize command_request: ${e.message}")
            sendValidationRejection(envelope.id, "", "MALFORMED_REQUEST", "Failed to deserialize command_request payload: ${e.message}")
            return
        }

        if (payload == null) {
            sendValidationRejection(envelope.id, "", "MALFORMED_REQUEST", "command_request payload is null")
            return
        }

        val validationError = validateCommandRequest(payload, envelope)
        if (validationError != null) {
            Log.w("WindowsAgentClient", "command_request validation failed: ${validationError.message}")
            sendValidationRejection(envelope.id, payload.commandId, validationError.code, validationError.message)
            return
        }

        Log.i("WindowsAgentClient", "Dispatching verified command_request: commandId=${payload.commandId}, tool=${payload.tool}, action=${payload.action}")
        
        clientScope.launch {
            val finalResult = idempotencyCache.executeOrAwait(payload.commandId) {
                executeLocalAction(payload)
            }
            
            val responseEnv = Envelope(
                type = "command_result",
                id = "res_" + UUID.randomUUID().toString().take(12),
                source = Source(deviceId, "android"),
                target = Target("windows-main"),
                payload = finalResult
            )
            webSocket?.send(gson.toJson(responseEnv))
        }
    }

    suspend fun executeLocalAction(payload: CommandRequestPayload): CommandResultPayload {
        val startTime = System.currentTimeMillis()
        val actionUpper = payload.action.trim().uppercase()

        // Check if ActionDispatcher can handle this action or if it's supported in ToolRegistry
        val tool = com.example.automation.tools.ToolRegistry.getToolForAction(actionUpper)
        if (tool == null && actionUpper != "SYSTEM_ACTION" && actionUpper != "TAKE_SCREENSHOT") {
            // Check legacy or fallback actions
            val isKnown = listOf(
                "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "OPEN_APP", "SET_VOLUME", "SET_BRIGHTNESS", 
                "SET_RINGER_MODE", "GET_BATTERY_STATUS", "GET_DEVICE_STATUS", "UNLOCK_DEVICE", 
                "TAKE_SCREENSHOT", "GET_NOTIFICATIONS", "GET_CLIPBOARD", "SET_CLIPBOARD", 
                "SEND_WHATSAPP", "YOUTUBE_SEARCH", "TOGGLE_WIFI", "SET_BLUETOOTH", "SET_DND",
                "TAKE_PHOTO", "GET_LOCATION", "CONTROL_MEDIA", "SET_ALARM", "SET_TIMER",
                "SEARCH_FILES", "CLICK_TEXT", "SCROLL_SCREEN", "GET_SCREEN_CONTEXT",
                "CALL_PHONE", "GET_CALENDAR_EVENTS", "ADD_CALENDAR_EVENT", "SEARCH_CONTACT",
                "CREATE_CONTACT", "GET_APP_USAGE", "OPEN_SETTINGS", "RUN_ROUTINE", "CREATE_ROUTINE",
                "SCHEDULE_TASK", "CANCEL_TASK", "LIST_TASKS", "SAVE_MEMORY", "FORGET_MEMORY",
                "SET_REMINDER", "GET_REMINDERS"
            ).contains(actionUpper)

            if (!isKnown) {
                return CommandResultPayload(
                    commandId = payload.commandId,
                    status = "unsupported",
                    action = actionUpper,
                    tool = payload.tool,
                    deviceId = deviceId,
                    deviceName = android.os.Build.MODEL,
                    data = emptyMap(),
                    error = CommandError("UNSUPPORTED_ACTION", "Action '$actionUpper' is not supported by this device"),
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // Construct JSON payload for ActionDispatcher
        val jsonPayload = org.json.JSONObject().apply {
            put("action", actionUpper)
            payload.arguments.forEach { (k, v) ->
                put(k, v)
            }
            if (!has("tool") && payload.tool.isNotBlank()) {
                put("tool", payload.tool)
            }
        }

        return try {
            val resultStr = com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, jsonPayload)
            val resultJson = org.json.JSONObject(resultStr)
            val success = resultJson.optBoolean("success", false)
            val durationMs = System.currentTimeMillis() - startTime

            if (success) {
                val dataMap = mutableMapOf<String, Any>()
                val dataObj = resultJson.optJSONObject("data")
                if (dataObj != null) {
                    val keys = dataObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        dataMap[key] = dataObj.get(key)
                    }
                }
                val msg = resultJson.optString("message", "Success")
                if (!dataMap.containsKey("message")) {
                    dataMap["message"] = msg
                }

                CommandResultPayload(
                    commandId = payload.commandId,
                    status = "success",
                    action = actionUpper,
                    tool = payload.tool,
                    deviceId = deviceId,
                    deviceName = android.os.Build.MODEL,
                    data = dataMap,
                    error = null,
                    executionTimeMs = durationMs
                )
            } else {
                val errObj = resultJson.optJSONObject("error")
                val errCode = errObj?.optString("code", "EXECUTION_FAILED") ?: "EXECUTION_FAILED"
                val errMsg = errObj?.optString("message", resultJson.optString("message", "Execution failed")) ?: "Execution failed"

                CommandResultPayload(
                    commandId = payload.commandId,
                    status = "failed",
                    action = actionUpper,
                    tool = payload.tool,
                    deviceId = deviceId,
                    deviceName = android.os.Build.MODEL,
                    data = emptyMap(),
                    error = CommandError(errCode, errMsg),
                    executionTimeMs = durationMs
                )
            }
        } catch (e: Exception) {
            Log.e("WindowsAgentClient", "Exception during action execution: ${e.message}", e)
            CommandResultPayload(
                commandId = payload.commandId,
                status = "failed",
                action = actionUpper,
                tool = payload.tool,
                deviceId = deviceId,
                deviceName = android.os.Build.MODEL,
                data = emptyMap(),
                error = CommandError("EXECUTION_FAILED", e.message ?: "Unknown execution error"),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    internal fun validateCommandRequest(payload: CommandRequestPayload, envelope: Envelope): CommandError? {
        if (!isHandshakeDone) {
            return CommandError("UNAUTHORIZED", "Connection handshake is not authenticated")
        }
        if (payload.commandId.isBlank()) {
            return CommandError("INVALID_ARGUMENTS", "command_id must be non-empty")
        }
        if (payload.tool.isBlank()) {
            return CommandError("INVALID_ARGUMENTS", "tool name must be non-empty")
        }
        if (payload.action.isBlank()) {
            return CommandError("INVALID_ARGUMENTS", "action name must be non-empty")
        }

        val dangerousPatternMatch = scanForDangerousPayload(payload.tool, payload.action, payload.arguments)
        if (dangerousPatternMatch != null) {
            return CommandError("DANGEROUS_PAYLOAD", "Payload contains disallowed dangerous pattern '$dangerousPatternMatch'")
        }

        if (payload.timeoutMs < 1000 || payload.timeoutMs > 30000) {
            return CommandError("INVALID_TIMEOUT", "timeout_ms must be between 1000 and 30000 ms (got ${payload.timeoutMs})")
        }
        if (envelope.target.device_id != deviceId && envelope.target.device_id != "all" && envelope.target.device_id != "broadcast") {
            return CommandError("DEVICE_MISMATCH", "Target device_id '${envelope.target.device_id}' does not match worker device_id '$deviceId'")
        }
        return null
    }

    companion object {
        // Token-aware dangerous shell/executable invocation patterns in tool/action names
        private val DANGEROUS_IDENTIFIER_PATTERNS = listOf(
            Regex("""(?i)\b(sh|bash|zsh|csh|ksh|powershell|cmd\.exe)\b"""),
            Regex("""(?i)\b(sh|bash)\s+-c\b"""),
            Regex("""(?i)\b(powershell|cmd\.exe)\s+[-/]"""),
            Regex("""(?i)\bRuntime\s*\.\s*(getRuntime\s*\(\s*\)\s*\.\s*)?exec\b"""),
            Regex("""(?i)\b(__import__|eval|exec)\s*\("""),
            Regex("""(?i)<\s*script\b""")
        )

        // Token-aware dangerous shell invocation patterns in payload arguments
        private val DANGEROUS_ARGUMENT_PATTERNS = listOf(
            Regex("""(?i)\b(sh|bash)\s+-c\s+"""),
            Regex("""(?i)\bpowershell(\.exe)?\s+(-enc|-encodedcommand|-c|-command)\s+"""),
            Regex("""(?i)\bcmd(\.exe)?\s+/[cC]\s+"""),
            Regex("""(?i)\bRuntime\s*\.\s*(getRuntime\s*\(\s*\)\s*\.\s*)?exec\s*\("""),
            Regex("""(?i)\b(__import__|eval|exec)\s*\("""),
            Regex("""(?i)<\s*script[\s>]""")
        )

        /**
         * Scans tool, action, and nested arguments for dangerous shell or dynamic code execution patterns.
         * Returns matching pattern string if dangerous, or null if clean.
         */
        fun scanForDangerousPayload(tool: String, action: String, arguments: Map<String, Any> = emptyMap()): String? {
            // 1. Check tool name
            for (pattern in DANGEROUS_IDENTIFIER_PATTERNS) {
                if (pattern.containsMatchIn(tool)) {
                    return pattern.pattern
                }
            }

            // 2. Check action name
            for (pattern in DANGEROUS_IDENTIFIER_PATTERNS) {
                if (pattern.containsMatchIn(action)) {
                    return pattern.pattern
                }
            }

            // 3. Recursively check argument values
            fun checkValue(v: Any?): String? {
                when (v) {
                    is String -> {
                        for (pattern in DANGEROUS_ARGUMENT_PATTERNS) {
                            if (pattern.containsMatchIn(v)) return pattern.pattern
                        }
                    }
                    is Map<*, *> -> {
                        for ((_, subVal) in v) {
                            val res = checkValue(subVal)
                            if (res != null) return res
                        }
                    }
                    is List<*> -> {
                        for (subVal in v) {
                            val res = checkValue(subVal)
                            if (res != null) return res
                        }
                    }
                }
                return null
            }

            for ((_, v) in arguments) {
                val match = checkValue(v)
                if (match != null) return match
            }

            return null
        }
    }

    private fun sendValidationRejection(envelopeId: String, commandId: String, code: String, message: String) {
        val resultPayload = CommandResultPayload(
            commandId = commandId.ifBlank { envelopeId },
            status = "rejected",
            action = "UNKNOWN",
            data = emptyMap(),
            error = CommandError(code, message),
            executionTimeMs = 0
        )
        val responseEnv = Envelope(
            type = "command_result",
            id = "res_" + UUID.randomUUID().toString().take(12),
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = resultPayload
        )
        webSocket?.send(gson.toJson(responseEnv))
    }



    fun pair(pairingCode: String, callback: (Boolean, String?) -> Unit) {
        pairCallback = callback
        val envelope = Envelope(
            type = "pair_request",
            id = UUID.randomUUID().toString(),
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = PairRequestPayload(
                pairingCode = pairingCode,
                deviceName = android.os.Build.MODEL
            )
        )
        val sent = webSocket?.send(gson.toJson(envelope)) ?: false
        if (!sent) {
            callback(false, "Connection is not online. Please try connecting first.")
            pairCallback = null
        }
    }

    fun sendToolRequest(tool: String, action: String, arguments: Map<String, Any>, callback: ToolResponseCallback) {
        val requestId = UUID.randomUUID().toString()
        toolCallbacks[requestId] = callback
        toolCallbackTimestamps[requestId] = System.currentTimeMillis()
        
        cleanExpiredCallbacks()
        
        val token = sessionToken ?: ""
        val envelope = Envelope(
            type = "tool_request",
            id = requestId,
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = ToolRequestPayload(
                token = token,
                tool = tool,
                action = action,
                arguments = arguments
            )
        )
        val sent = webSocket?.send(gson.toJson(envelope)) ?: false
        if (!sent) {
            callback.onError("Connection is not active")
            toolCallbacks.remove(requestId)
        }
    }

    fun sendEvent(event: String, payload: Map<String, Any>) {
        val requestId = UUID.randomUUID().toString()
        val envelope = Envelope(
            type = "event",
            id = requestId,
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = mapOf(
                "event" to event,
                "data" to payload
            )
        )
        webSocket?.send(gson.toJson(envelope))
    }

    fun sendCapabilitiesUpdate(customCapabilities: List<DeviceCapability>? = null, force: Boolean = false): Boolean {
        if (webSocket == null) {
            Log.d("WindowsAgentClient", "Cannot send capabilities update: webSocket is null")
            return false
        }

        val capsList: List<DeviceCapability> = customCapabilities ?: try {
            com.example.automation.tools.ToolRegistry.getAllCapabilities().map { action ->
                DeviceCapability(
                    capability = action.uppercase(),
                    available = true,
                    version = "1"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        val currentJson = gson.toJson(capsList.map { it.capability to it.available })
        if (!force && currentJson == lastSentCapabilitiesJson) {
            Log.d("WindowsAgentClient", "Capabilities unchanged. Suppressing duplicate update packet.")
            return false
        }

        val envelope = Envelope(
            type = "event",
            id = "cap_" + UUID.randomUUID().toString().take(12),
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = mapOf(
                "event_name" to "capabilities:update",
                "device_id" to deviceId,
                "capabilities" to capsList
            )
        )

        val sent = webSocket?.send(gson.toJson(envelope)) ?: false
        if (sent) {
            lastSentCapabilitiesJson = currentJson
            Log.i("WindowsAgentClient", "Sent dynamic capabilities update: ${capsList.size} capabilities")
        }
        return sent
    }

    fun disconnect() {
        connectionState = ConnectionState.DISCONNECTED
        webSocket?.close(1000, "Normal dismissal")
        webSocket = null
        isHandshakeDone = false
        sessionToken = null
        pendingPairingCode = null
        lastSentCapabilitiesJson = null
        toolCallbacks.clear()
        toolCallbackTimestamps.clear()
        pairCallback = null
        WindowsToolExecutor.markConnected(false)
    }

    private fun cleanExpiredCallbacks() {
        val now = System.currentTimeMillis()
        val expiredIds = toolCallbackTimestamps.filter { (_, timestamp) ->
            now - timestamp > TOOL_CALLBACK_TIMEOUT_MS
        }.keys
        for (id in expiredIds) {
            toolCallbacks[id]?.onError("Request timed out")
            toolCallbacks.remove(id)
            toolCallbackTimestamps.remove(id)
        }
    }

    // Protocol helper dataclasses
    data class Envelope(
        val protocol_version: String = "2.1",
        val id: String,
        val type: String,
        val timestamp: Long = System.currentTimeMillis() / 1000,
        val source: Source,
        val target: Target,
        val payload: Any
    )

    data class Source(val device_id: String, val platform: String)
    data class Target(val device_id: String)
    
    data class HelloPayload(
        @SerializedName("deviceName") val deviceName: String,
        @SerializedName("appVersion") val appVersion: String,
        @SerializedName("deviceType") val deviceType: String = "phone",
        @SerializedName("model") val model: String = "Android Device",
        @SerializedName("osVersion") val osVersion: String = "13.0",
        @SerializedName("capabilities") val capabilities: List<String> = emptyList()
    )

    data class HelloResponsePayload(val device_name: String?, val capabilities: Map<String, Any>?)


    
    data class PairRequestPayload(
        @SerializedName("pairing_code") val pairingCode: String,
        @SerializedName("device_name") val deviceName: String
    )
    data class PairResponsePayload(
        val status: String,
        val token: String?,
        val session_token: String?,
        val cert_fingerprint: String?,
        val message: String?
    )
    
    data class ToolRequestPayload(
        val token: String,
        val tool: String,
        val action: String,
        val arguments: Map<String, Any>
    )
    
    data class ToolProgressPayload(val state: String?, val message: String?)
    data class ToolResponsePayload(val status: String, val output: String?)

    // P2.1 Remote Command Protocol Models
    data class CommandRequestPayload(
        @SerializedName("command_id") val commandId: String,
        @SerializedName("tool") val tool: String,
        @SerializedName("action") val action: String,
        @SerializedName("arguments") val arguments: Map<String, Any> = emptyMap(),
        @SerializedName("timeout_ms") val timeoutMs: Int = 5000
    )

    data class CommandError(
        @SerializedName("code") val code: String,
        @SerializedName("message") val message: String,
        @SerializedName("details") val details: Map<String, Any>? = null
    )

    data class CommandResultPayload(
        @SerializedName("command_id") val commandId: String,
        @SerializedName("status") val status: String,
        @SerializedName("action") val action: String,
        @SerializedName("tool") val tool: String = "",
        @SerializedName("device_id") val deviceId: String = "",
        @SerializedName("device_name") val deviceName: String = "",
        @SerializedName("data") val data: Map<String, Any> = emptyMap(),
        @SerializedName("error") val error: CommandError? = null,
        @SerializedName("execution_time_ms") val executionTimeMs: Long = 0
    )

    // P2.5 Dynamic Capability Synchronization Models
    data class DeviceCapability(
        @SerializedName("capability") val capability: String,
        @SerializedName("available") val available: Boolean = true,
        @SerializedName("version") val version: String = "1",
        @SerializedName("metadata") val metadata: Map<String, Any> = emptyMap(),
        @SerializedName("last_updated") val lastUpdated: Long = System.currentTimeMillis()
    )
}
