package com.example.utils

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.Settings
import android.util.Log

object QuickActions {
    fun toggleFlashlight(context: Context, turnOn: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                return "Camera Service is not available on this device."
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                return "No camera found to use flashlight."
            }
            cameraManager.setTorchMode(cameraId, turnOn)
            if (turnOn) "Flashlight turned ON successfully!" else "Flashlight turned OFF successfully!"
        } catch (e: Exception) {
            Log.e("QuickActions", "Error toggling flashlight", e)
            "Could not toggle flashlight: ${e.localizedMessage}. Please ensure the app has appropriate permissions."
        }
    }

    fun openWifiSettings(context: Context): String {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening WiFi Settings..."
        } catch (e: Exception) {
            Log.e("QuickActions", "Error opening wifi settings", e)
            "Could not open WiFi settings: ${e.localizedMessage}"
        }
    }

    fun openBluetoothSettings(context: Context): String {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening Bluetooth Settings..."
        } catch (e: Exception) {
            Log.e("QuickActions", "Error opening bluetooth settings", e)
            "Could not open Bluetooth settings: ${e.localizedMessage}"
        }
    }
}
