package com.example.network.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object WindowsToolExecutor {
    private var client: WindowsAgentClient? = null
    private var isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    
    private var storedIp: String = "192.168.1.100"
    private var storedPort: Int = 9000

    fun initialize(context: Context) {
        if (client == null) {
            client = WindowsAgentClient(context.applicationContext)
            loadConfig(context)
            startAutoConnectLoop(context)
        }
    }

    private fun loadConfig(context: Context) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        storedIp = prefs.getString("agent_ip", "192.168.1.100") ?: "192.168.1.100"
        storedPort = prefs.getInt("agent_port", 9000)
    }

    fun saveConfig(context: Context, ip: String, port: Int) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        val oldIp = prefs.getString("agent_ip", "")
        val oldPort = prefs.getInt("agent_port", -1)
        val changed = oldIp != ip || oldPort != port
        
        prefs.edit().putString("agent_ip", ip).putInt("agent_port", port).apply()
        storedIp = ip
        storedPort = port
        
        if (changed || !isConnected.get()) {
            client?.disconnect()
            isConnected.set(false)
            stopHeartbeatLoop()
            scope.launch {
                delay(1000)
                tryConnect(context)
            }
        }
    }

    fun getClient(): WindowsAgentClient? = client

    private fun startAutoConnectLoop(context: Context) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                if (!isConnected.get()) {
                    Log.d("WindowsToolExecutor", "Attempting connection to $storedIp:$storedPort...")
                    tryConnect(context)
                }
                delay(10000)
            }
        }
    }

    private fun tryConnect(context: Context) {
        val currentClient = client ?: return
        currentClient.connect(storedIp, storedPort, object : WindowsAgentClient.ConnectionListener {
            override fun onConnected(capabilities: Map<String, Int>) {
                Log.d("WindowsToolExecutor", "Connected to Windows Agent! Capabilities: $capabilities")
                isConnected.set(true)
                startHeartbeatLoop()
            }

            override fun onDisconnected() {
                Log.d("WindowsToolExecutor", "Disconnected from Windows Agent")
                isConnected.set(false)
                stopHeartbeatLoop()
            }

            override fun onError(t: Throwable) {
                Log.e("WindowsToolExecutor", "Connection error: ${t.message}")
                isConnected.set(false)
                stopHeartbeatLoop()
            }
        })
    }

    private suspend fun reconnect(context: Context) {
        client?.disconnect()
        isConnected.set(false)
        stopHeartbeatLoop()
        delay(1000)
        tryConnect(context)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(25000)
                if (isConnected.get()) {
                    client?.sendEvent("heartbeat", emptyMap())
                }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun isAgentAvailable(): Boolean = isConnected.get()

    suspend fun executeTool(
        tool: String,
        action: String,
        arguments: Map<String, Any>,
        onProgress: (String) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val currentClient = client
        if (currentClient == null || !isConnected.get()) {
            continuation.resume(
                "{\"status\":\"error\",\"output\":\"Windows Agent is offline.\"}"
            )
            return@suspendCancellableCoroutine
        }

        currentClient.sendToolRequest(
            tool,
            action,
            arguments,
            object : WindowsAgentClient.ToolResponseCallback {
                override fun onProgress(message: String) {
                    onProgress(message)
                }

                override fun onResponse(status: String, output: String) {
                    val resultJson = "{\"status\":\"$status\",\"output\":\"${escapeJson(output)}\"}"
                    if (continuation.isActive) {
                        continuation.resume(resultJson)
                    }
                }

                override fun onError(error: String) {
                    val resultJson = "{\"status\":\"error\",\"output\":\"${escapeJson(error)}\"}"
                    if (continuation.isActive) {
                        continuation.resume(resultJson)
                    }
                }
            }
        )
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
