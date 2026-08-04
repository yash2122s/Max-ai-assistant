package com.example.network.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class CompanionConnectionService : Service() {
    companion object {
        private const val CHANNEL_ID = "max_link_service_channel"
        private const val NOTIFICATION_ID = 102
        private var instance: CompanionConnectionService? = null
        
        fun getInstance(): CompanionConnectionService? = instance
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    private val isDiscovering = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var backoffIndex = 0
    private val backoffDelays = listOf(1000L, 2000L, 5000L, 10000L, 30000L)
    
    private var autoReconnectJob: Job? = null
    private var resolveActive = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("CompanionConnectionService", "MAX Link Foreground Service created")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("MAX Link Service Active"))
        
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        setupNetworkCallback()
        startDiscovery()
        startFallbackConnectionLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopDiscovery()
        teardownNetworkCallback()
        autoReconnectJob?.cancel()
        serviceJob.cancel()
        Log.d("CompanionConnectionService", "MAX Link Foreground Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MAX Link Connection Channel"
            val descriptionText = "Monitors connectivity with MAX Windows Agent"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAX Desktop Link")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(statusText))
    }

    // mDNS service discovery
    private fun startDiscovery() {
        if (!isDiscovering.compareAndSet(false, true)) return
        
        Log.d("CompanionConnectionService", "Starting mDNS service discovery...")
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("CompanionConnectionService", "mDNS start discovery failed: $errorCode")
                isDiscovering.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("CompanionConnectionService", "mDNS stop discovery failed: $errorCode")
                isDiscovering.set(false)
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("CompanionConnectionService", "mDNS discovery started successfully")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("CompanionConnectionService", "mDNS discovery stopped")
                isDiscovering.set(false)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("CompanionConnectionService", "mDNS found service: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                if (serviceInfo.serviceType.contains("_max-agent")) {
                    resolveServiceSafely(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("CompanionConnectionService", "mDNS service lost: ${serviceInfo.serviceName}")
            }
        }
        
        try {
            nsdManager?.discoverServices(
                "_max-agent._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e("CompanionConnectionService", "Error initiating discoverServices", e)
            isDiscovering.set(false)
        }
    }

    private fun resolveServiceSafely(serviceInfo: NsdServiceInfo) {
        if (!resolveActive.compareAndSet(false, true)) return
        
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e("CompanionConnectionService", "Resolve failed: $errorCode")
                resolveActive.set(false)
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                resolveActive.set(false)
                val ip = resolvedServiceInfo.host.hostAddress
                val port = resolvedServiceInfo.port
                val attributes = resolvedServiceInfo.attributes
                
                val deviceIdBytes = attributes["device_id"]
                val deviceId = deviceIdBytes?.let { String(it) } ?: ""
                
                val prefs = getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                val pairedId = prefs.getString("paired_device_id", "") ?: ""
                
                Log.d("CompanionConnectionService", "Resolved mDNS service: $ip:$port, device_id: $deviceId (paired matching: $pairedId)")
                
                if (!ip.isNullOrEmpty() && (pairedId.isEmpty() || pairedId.startsWith(deviceId) || deviceId.isEmpty())) {
                    Log.d("CompanionConnectionService", "Device match or auto-discovery! Triggering WSS connection to $ip:$port.")
                    serviceScope.launch {
                        connectToAgent(ip, port)
                    }
                }
            }
        })
    }

    private fun stopDiscovery() {
        if (isDiscovering.compareAndSet(true, false)) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e("CompanionConnectionService", "Error stopping discovery", e)
            }
        }
    }

    // Network Callback Connectivity monitor
    private fun setupNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("CompanionConnectionService", "Network transition detected: Link available, resetting discovery...")
                stopDiscovery()
                startDiscovery()
            }

            override fun onLost(network: Network) {
                Log.d("CompanionConnectionService", "Network transition detected: Link lost, stopping discovery...")
                stopDiscovery()
            }
        }
        
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e("CompanionConnectionService", "Failed to register network callback", e)
        }
    }

    private fun teardownNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e("CompanionConnectionService", "Failed to unregister network callback", e)
        }
    }

    private var clipboardSyncJob: Job? = null
    private var lastSyncedClipboardText: String = ""

    private fun startClipboardSyncLoop() {
        clipboardSyncJob?.cancel()
        clipboardSyncJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (WindowsToolExecutor.isAgentAvailable() && WindowsToolExecutor.isClipboardSyncEnabled(applicationContext)) {
                    try {
                        withContext(Dispatchers.Main) {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val primaryClip = clipboard?.primaryClip
                            if (primaryClip != null && primaryClip.itemCount > 0) {
                                val currentText = primaryClip.getItemAt(0).text?.toString() ?: ""
                                if (currentText.isNotEmpty() && currentText.length <= 1000 && !currentText.contains("Imported Permanent Memories") && currentText != lastSyncedClipboardText) {
                                    lastSyncedClipboardText = currentText
                                    serviceScope.launch(Dispatchers.IO) {
                                        WindowsToolExecutor.executeTool(
                                            tool = "windows_agent",
                                            action = "core.clipboard:set",
                                            arguments = mapOf("message" to currentText),
                                            onProgress = {}
                                        )
                                        Log.d("CompanionConnectionService", "Auto-synced Android clipboard to Windows Laptop: ${currentText.take(20)}...")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CompanionConnectionService", "Clipboard sync check failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopClipboardSyncLoop() {
        clipboardSyncJob?.cancel()
        clipboardSyncJob = null
    }

    // Fallback Saved IP connecting loop
    private fun startFallbackConnectionLoop() {
        autoReconnectJob?.cancel()
        autoReconnectJob = serviceScope.launch {
            while (isActive) {
                if (!WindowsToolExecutor.isAgentAvailable() && !isConnecting.get()) {
                    val prefs = getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                    val lastIp = prefs.getString("agent_ip", null)
                    val lastPort = prefs.getInt("agent_port", -1)
                    
                    if (lastIp != null && lastPort != -1) {
                        Log.d("CompanionConnectionService", "mDNS idle: Trying saved fallback IP connection at $lastIp:$lastPort")
                        connectToAgent(lastIp, lastPort)
                    }
                }
                delay(15000)
            }
        }
    }

    // Connection execution and exponential backoff
    private fun connectToAgent(ip: String, port: Int) {
        if (WindowsToolExecutor.isAgentAvailable()) {
            Log.d("CompanionConnectionService", "Already connected to agent. Skipping connection attempt.")
            return
        }
        if (!isConnecting.compareAndSet(false, true)) return
        
        val client = WindowsToolExecutor.getClient() ?: return
        Log.d("CompanionConnectionService", "Initiating WebSocket connection to wss://$ip:$port")
        
        client.connect(ip, port, object : WindowsAgentClient.ConnectionListener {
            override fun onConnected(capabilities: Map<String, Any>) {
                Log.d("CompanionConnectionService", "Connection authenticated successfully over WSS!")
                isConnecting.set(false)
                backoffIndex = 0
                WindowsToolExecutor.markConnected(true)
                updateNotification("Connected to Yaswanth Laptop")
                startClipboardSyncLoop()
            }

            override fun onDisconnected() {
                Log.d("CompanionConnectionService", "Connection disconnected")
                stopClipboardSyncLoop()
                handleConnectionFailure(ip, port)
            }

            override fun onError(t: Throwable) {
                Log.e("CompanionConnectionService", "Connection error: ${t.message}")
                stopClipboardSyncLoop()
                handleConnectionFailure(ip, port)
            }
        })
    }

    private fun handleConnectionFailure(ip: String, port: Int) {
        isConnecting.set(false)
        WindowsToolExecutor.markConnected(false)
        updateNotification("Reconnecting to Yaswanth Laptop...")
        
        val delayTime = backoffDelays[backoffIndex]
        Log.d("CompanionConnectionService", "Backing off for ${delayTime}ms before reconnecting...")
        
        backoffIndex = (backoffIndex + 1).coerceAtMost(backoffDelays.size - 1)
        
        serviceScope.launch {
            delay(delayTime)
            connectToAgent(ip, port)
        }
    }
}
