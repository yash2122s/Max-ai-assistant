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
    private val gson = Gson()
    private var isHandshakeDone = false
    private var connectionListener: ConnectionListener? = null
    private var sessionToken: String? = null
    private var savedCapabilities: Map<String, Any>? = null
    
    private val toolCallbacks = mutableMapOf<String, ToolResponseCallback>()
    private val toolCallbackTimestamps = mutableMapOf<String, Long>()
    private val TOOL_CALLBACK_TIMEOUT_MS = 60_000L // 60 seconds
    private var pairCallback: ((Boolean, String?) -> Unit)? = null

    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        id
    }

    private fun createPinnedOkHttpClient(certFingerprint: String?): OkHttpClient {
        try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (certFingerprint.isNullOrEmpty()) return // Trust self-signed certificates on local LAN
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("Certificate chain is empty")
                    }
                    val serverCert = chain[0]
                    val sha256Bytes = MessageDigest.getInstance("SHA-256").digest(serverCert.encoded)
                    val fingerprint = sha256Bytes.joinToString("") { "%02x".format(it) }
                    
                    Log.d("WindowsAgentClient", "Server certificate fingerprint: $fingerprint")
                    Log.d("WindowsAgentClient", "Expected certificate fingerprint: $certFingerprint")
                    
                    if (fingerprint.lowercase() != certFingerprint.lowercase()) {
                        throw java.security.cert.CertificateException("Certificate fingerprint mismatch! Potential MITM attack blocked.")
                    }
                }
                
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            
            return OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            Log.e("WindowsAgentClient", "Error building SSL context: ${e.message}", e)
            return OkHttpClient.Builder()
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

    fun connect(ip: String, port: Int, listener: ConnectionListener) {
        if (isHandshakeDone && webSocket != null) {
            Log.d("WindowsAgentClient", "Already connected and authenticated. Skipping connect.")
            return
        }
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        
        connectionListener = listener
        isHandshakeDone = false
        sessionToken = null
        savedCapabilities = null
        
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        val certFingerprint = prefs.getString("cert_fingerprint", null)
        
        client = createPinnedOkHttpClient(certFingerprint)
        
        val request = Request.Builder()
            .url("wss://$ip:$port")
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WindowsAgentClient", "WebSocket connection opened, sending hello...")
                sendHelloHandshake()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WindowsAgentClient", "WebSocket closed: $reason")
                isHandshakeDone = false
                sessionToken = null
                connectionListener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WindowsAgentClient", "WebSocket error: ${t.message}", t)
                isHandshakeDone = false
                sessionToken = null
                connectionListener?.onError(t)
            }
        })
    }

    private fun sendHelloHandshake() {
        val envelope = Envelope(
            type = "hello",
            id = UUID.randomUUID().toString(),
            source = Source(deviceId, "android"),
            target = Target("windows-main"),
            payload = HelloPayload(
                deviceName = android.os.Build.MODEL,
                appVersion = "1.0"
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
                    sessionToken = payloadMap["session_token"] as? String
                    isHandshakeDone = true
                    connectionListener?.onConnected(savedCapabilities ?: emptyMap())
                }
                "pair_response" -> {
                    val payload = gson.fromJson(gson.toJson(envelope.payload), PairResponsePayload::class.java)
                    if (payload.status == "success") {
                        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("auth_token", payload.token).apply()
                        sessionToken = payload.session_token
                        isHandshakeDone = true
                        pairCallback?.invoke(true, null)
                    } else {
                        pairCallback?.invoke(false, payload.message ?: "Incorrect code")
                    }
                    pairCallback = null
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
            }
        } catch (e: Exception) {
            Log.e("WindowsAgentClient", "Error parsing incoming frame: ${e.message}", e)
        }
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

    fun disconnect() {
        webSocket?.close(1000, "Normal dismissal")
        webSocket = null
        isHandshakeDone = false
        sessionToken = null
        toolCallbacks.clear()
        toolCallbackTimestamps.clear()
        pairCallback = null
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
    
    data class HelloPayload(val deviceName: String, val appVersion: String)
    data class HelloResponsePayload(val device_name: String?, val capabilities: Map<String, Any>?)

    
    data class PairRequestPayload(
        @SerializedName("pairing_code") val pairingCode: String,
        @SerializedName("device_name") val deviceName: String
    )
    data class PairResponsePayload(val status: String, val token: String?, val session_token: String?, val message: String?)
    
    data class ToolRequestPayload(
        val token: String,
        val tool: String,
        val action: String,
        val arguments: Map<String, Any>
    )
    
    data class ToolProgressPayload(val state: String?, val message: String?)
    data class ToolResponsePayload(val status: String, val output: String?)
}
