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
            val cameraIds = cameraManager.cameraIdList
            val cameraId = cameraIds.firstOrNull { id ->
                val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
                val hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBack = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                hasFlash && isBack
            } ?: cameraIds.firstOrNull { id ->
                val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: return@firstOrNull false
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                return "No flash-capable camera found to use flashlight."
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
