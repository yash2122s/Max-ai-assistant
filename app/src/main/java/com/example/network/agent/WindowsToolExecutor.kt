package com.example.network.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object WindowsToolExecutor {
    private var client: WindowsAgentClient? = null
    private val isConnected = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    
    private var storedIp: String = "192.168.1.100"
    private var storedPort: Int = 9000

    fun isDesktopConnectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("desktop_connection_enabled", true)
    }

    fun setDesktopConnectionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("desktop_connection_enabled", enabled).apply()
        if (enabled) {
            startService(context)
        } else {
            stopService(context)
        }
    }

    fun isClipboardSyncEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("clipboard_sync_enabled", true)
    }

    fun setClipboardSyncEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clipboard_sync_enabled", enabled).apply()
    }

    fun initialize(context: Context) {
        if (client == null) {
            client = WindowsAgentClient(context.applicationContext)
            loadConfig(context)
            if (isDesktopConnectionEnabled(context)) {
                startService(context)
            }
        }
    }

    fun startService(context: Context) {
        try {
            val intent = android.content.Intent(context, CompanionConnectionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("WindowsToolExecutor", "Failed to start CompanionConnectionService: ${e.message}", e)
        }
    }

    fun stopService(context: Context) {
        try {
            val intent = android.content.Intent(context, CompanionConnectionService::class.java)
            context.stopService(intent)
            markConnected(false)
        } catch (e: Exception) {
            Log.e("WindowsToolExecutor", "Failed to stop CompanionConnectionService: ${e.message}", e)
        }
    }

    private fun loadConfig(context: Context) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        storedIp = prefs.getString("agent_ip", "192.168.0.152") ?: "192.168.0.152"
        storedPort = prefs.getInt("agent_port", 9500)
    }

    fun saveConfig(context: Context, ip: String, port: Int) {
        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("agent_ip", ip).putInt("agent_port", port).apply()
        storedIp = ip
        storedPort = port
    }

    fun getClient(): WindowsAgentClient? = client

    fun markConnected(connected: Boolean) {
        isConnected.set(connected)
        if (connected) {
            startHeartbeatLoop()
        } else {
            stopHeartbeatLoop()
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30000) // Heartbeat every 30 seconds
                if (isConnected.get()) {
                    client?.sendEvent("core:ping", emptyMap())
                }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun isAgentAvailable(): Boolean = isConnected.get()

    fun sendNotificationEvent(packageName: String, title: String, text: String) {
        if (isConnected.get()) {
            val payload = mapOf(
                "packageName" to packageName,
                "title" to title,
                "text" to text,
                "timestamp" to System.currentTimeMillis()
            )
            client?.sendEvent("notification:synced", payload)
        }
    }


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
