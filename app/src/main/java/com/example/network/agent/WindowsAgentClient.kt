package com.example.network.agent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class WindowsAgentClient(private val context: Context) {
    interface ConnectionListener {
        fun onConnected(capabilities: Map<String, Int>)
        fun onDisconnected()
        fun onError(t: Throwable)
    }

    interface ToolResponseCallback {
        fun onProgress(message: String)
        fun onResponse(status: String, output: String)
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var isHandshakeDone = false
    private var connectionListener: ConnectionListener? = null
    
    private val toolCallbacks = mutableMapOf<String, ToolResponseCallback>()
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

    fun connect(ip: String, port: Int, listener: ConnectionListener) {
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        
        connectionListener = listener
        isHandshakeDone = false
        
        val request = Request.Builder()
            .url("ws://$ip:$port")
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
                connectionListener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WindowsAgentClient", "WebSocket error: ${t.message}", t)
                isHandshakeDone = false
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
                    isHandshakeDone = true
                    val payload = gson.fromJson(gson.toJson(envelope.payload), HelloResponsePayload::class.java)
                    connectionListener?.onConnected(payload.capabilities ?: emptyMap())
                }
                "pair_response" -> {
                    val payload = gson.fromJson(gson.toJson(envelope.payload), PairResponsePayload::class.java)
                    if (payload.status == "success") {
                        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("auth_token", payload.token).apply()
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
                }
                "heartbeat" -> {
                    Log.d("WindowsAgentClient", "Heartbeat echoed back successfully")
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
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("auth_token", "") ?: ""
        
        val requestId = UUID.randomUUID().toString()
        toolCallbacks[requestId] = callback
        
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
        toolCallbacks.clear()
        pairCallback = null
    }

    // Protocol helper dataclasses
    data class Envelope(
        val protocol_version: Int = 1,
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
    data class HelloResponsePayload(val device_name: String?, val capabilities: Map<String, Int>?)
    
    data class PairRequestPayload(
        @SerializedName("pairing_code") val pairingCode: String,
        @SerializedName("device_name") val deviceName: String
    )
    data class PairResponsePayload(val status: String, val token: String?, val message: String?)
    
    data class ToolRequestPayload(
        val token: String,
        val tool: String,
        val action: String,
        val arguments: Map<String, Any>
    )
    
    data class ToolProgressPayload(val state: String?, val message: String?)
    data class ToolResponsePayload(val status: String, val output: String?)
}
