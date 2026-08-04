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

        try {
            when (action) {
                "SET_BLUETOOTH" -> {
                    if (enabled) {
                        if (!bluetoothAdapter.isEnabled) {
                            val success = bluetoothAdapter.enable()
                            if (!success) {
                                log("Direct enable failed. Launching Bluetooth settings as fallback.")
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                log("Enabling Bluetooth")
                            }
                        }
                    } else {
                        if (bluetoothAdapter.isEnabled) {
                            val success = bluetoothAdapter.disable()
                            if (!success) {
                                log("Direct disable failed. Launching Bluetooth settings as fallback.")
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                log("Disabling Bluetooth")
                            }
                        }
                    }
                }
                "TOGGLE_BLUETOOTH" -> {
                    if (bluetoothAdapter.isEnabled) {
                        bluetoothAdapter.disable()
                        log("Toggling Bluetooth: Disabling")
                    } else {
                        bluetoothAdapter.enable()
                        log("Toggling Bluetooth: Enabling")
                    }
                }
                "OPEN_BLUETOOTH", "" -> {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    log("Opening Bluetooth Settings")
                }
                else -> {
                    logError("Unknown Bluetooth action: $action")
                }
            }
        } catch (e: Exception) {
            logError("Error executing Bluetooth action: ${e.message}", e)
        }
    }
}
