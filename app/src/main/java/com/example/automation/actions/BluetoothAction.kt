package com.example.automation.actions

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

class BluetoothAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val action = payload.optString("action", "").uppercase()
        val enabled = payload.optBoolean("enabled", false)

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            logError("Bluetooth not supported on this device")
            return
        }

        val hasConnectPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasConnectPermission) {
            log("BLUETOOTH_CONNECT permission not granted. Launching Bluetooth settings as fallback.")
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        try {
            val targetEnable = when (action) {
                "SET_BLUETOOTH" -> enabled
                "TOGGLE_BLUETOOTH" -> !bluetoothAdapter.isEnabled
                "OPEN_BLUETOOTH", "" -> {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    log("Opening Bluetooth Settings")
                    return
                }
                else -> enabled
            }

            // 1. Privileged Shizuku path (works silently without popup on Android 12, 13, 14, 15)
            if (com.example.automation.ShizukuManager.isShizukuAvailable() && com.example.automation.ShizukuManager.isPermissionGranted()) {
                val shizukuCmd = if (targetEnable) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable"
                try {
                    val output = com.example.automation.ShizukuShellPlugin.runCommand(shizukuCmd)
                    log("Executed Bluetooth command via Shizuku: $output")
                    return
                } catch (e: Exception) {
                    log("Shizuku Bluetooth command failed, falling back to system API: ${e.message}")
                }
            }

            // 2. Direct Adapter call (works on Android < 13 or with system permissions)
            val success = if (targetEnable) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            if (success) {
                log("Bluetooth state changed to: $targetEnable via adapter")
                return
            }

            // 3. System prompt or Settings fallback on modern Android (API 33+)
            if (targetEnable) {
                try {
                    val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(enableIntent)
                    log("Requested Bluetooth enable via ACTION_REQUEST_ENABLE")
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    log("Launched Bluetooth Settings fallback")
                }
            } else {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                log("Launched Bluetooth Settings for manual disable")
            }
        } catch (e: Exception) {
            logError("Error executing Bluetooth action: ${e.message}", e)
        }
    }
}
