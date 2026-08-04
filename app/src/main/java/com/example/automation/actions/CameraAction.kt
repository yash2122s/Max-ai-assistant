package com.example.automation.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import org.json.JSONObject

class CameraAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val action = payload.optString("action", "").uppercase()
        try {
            when (action) {
                "TAKE_PHOTO" -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    log("Launched Camera app to take a photo")
                }
                "TAKE_SCREENSHOT" -> {
                    val jpeg = com.example.voice.vision.ScreenCaptureProvider.captureCompressedJpeg()
                    if (jpeg != null) {
                        log("Captured silent background screenshot (${jpeg.size} bytes JPEG)")
                    } else {
                        log("Silent background screenshot unavailable (Shizuku/Accessibility disabled).")
                    }
                }
                else -> {
                    logError("Unknown camera action: $action")
                }
            }
        } catch (e: Exception) {
            logError("Failed to execute camera action: ${e.message}", e)
        }
    }
}
