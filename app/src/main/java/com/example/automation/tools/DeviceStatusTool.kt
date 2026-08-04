package com.example.automation.tools

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import com.example.automation.actions.BatteryAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DeviceStatusTool(private val batteryAction: BatteryAction) : Tool {
    private val TAG = "DeviceStatusTool"
    override val name: String = "device_status"
    override val supportedActions: Set<String> = setOf("GET_DEVICE_STATUS")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val status = JSONObject()

            // 1. Battery status
            val batteryInfo = batteryAction.getBatteryInfo(context)
            status.put("battery", batteryInfo)

            // 2. Wi-Fi status
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                status.put("wifiEnabled", wifiManager.isWifiEnabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Wifi state", e)
                status.put("wifiEnabled", "Unknown")
            }

            // 3. Bluetooth status
            try {
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                val btEnabled = bluetoothAdapter?.isEnabled ?: false
                status.put("bluetoothEnabled", btEnabled)
            } catch (e: SecurityException) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted on API 31+")
                status.put("bluetoothEnabled", "Unknown (Permission Required)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Bluetooth state", e)
                status.put("bluetoothEnabled", "Unknown")
            }

            // 4. Do Not Disturb (DND) status
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val currentFilter = notificationManager.currentInterruptionFilter
                val dndState = when (currentFilter) {
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "Total Silence"
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority Only"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms Only"
                    NotificationManager.INTERRUPTION_FILTER_ALL -> "Off"
                    else -> "Unknown (Permission Required)"
                }
                status.put("dndState", dndState)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get DND state", e)
                status.put("dndState", "Unknown (Permission Required)")
            }

            // 5. Brightness status
            try {
                val brightnessMode = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val isAuto = brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                
                val brightnessVal = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    -1
                )
                val brightnessPercent = if (brightnessVal != -1) (brightnessVal * 100 / 255) else -1
                
                status.put("brightness", JSONObject().apply {
                    put("mode", if (isAuto) "Automatic" else "Manual")
                    put("percentage", brightnessPercent)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get screen brightness", e)
                status.put("brightness", "Unknown")
            }

            // 6. Volume status
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val volumePercent = if (maxVolume > 0) (musicVolume * 100 / maxVolume) else 0
                status.put("musicVolumePercentage", volumePercent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get stream volume", e)
                status.put("musicVolumePercentage", "Unknown")
            }

            // 7. Storage status
            try {
                val path = Environment.getDataDirectory()
                val stat = StatFs(path.path)
                val blockSize = stat.blockSizeLong
                val availableBlocks = stat.availableBlocksLong
                val totalBlocks = stat.blockCountLong
                val freeStorageGb = String.format("%.2f", (availableBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0))
                val totalStorageGb = String.format("%.2f", (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0))

                status.put("storage", JSONObject().apply {
                    put("freeGb", freeStorageGb)
                    put("totalGb", totalStorageGb)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to calculate storage space", e)
                status.put("storage", "Unknown")
            }

            // 8. RAM status
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val availRamGb = String.format("%.2f", memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0))
                val totalRamGb = String.format("%.2f", memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0))

                status.put("ram", JSONObject().apply {
                    put("freeGb", availRamGb)
                    put("totalGb", totalRamGb)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get memory info", e)
                status.put("ram", "Unknown")
            }

            // 9. Windows Agent Connection status
            try {
                val isAgentConnected = com.example.network.agent.WindowsToolExecutor.isAgentAvailable()
                status.put("windowsAgentConnected", isAgentConnected)
                status.put("windowsAgentStatus", if (isAgentConnected) "Connected (Online)" else "Disconnected (Offline)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check Windows Agent state", e)
                status.put("windowsAgentConnected", false)
                status.put("windowsAgentStatus", "Unknown")
            }


            val message = "Device Status Summary retrieved successfully."
            ToolResult(
                success = true,
                toolName = name,
                message = message,
                metadata = status,
                verificationRequired = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error compiling device status summary", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "STATUS_ERROR",
                message = e.message ?: "Failed to query device status summary"
            )
        }
    }
}
