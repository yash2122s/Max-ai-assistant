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
                    val time = System.currentTimeMillis()
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MAX_PHOTO_$time.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MAX")
                        }
                    }
                    val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        if (imageUri != null) {
                            putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    }
                    context.startActivity(intent)
                    log("Launched Camera app with MediaStore output: $imageUri")
                }
                "TAKE_SCREENSHOT" -> {
                    val jpeg = com.example.voice.vision.ScreenCaptureProvider.captureCompressedJpeg()
                    if (jpeg != null) {
                        val time = System.currentTimeMillis()
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "MAX_Screenshot_$time.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                            }
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(jpeg)
                                os.flush()
                            }
                            log("Captured and persisted screenshot to MediaStore: $uri (${jpeg.size} bytes)")
                        } else {
                            log("Captured screenshot (${jpeg.size} bytes) but MediaStore insert failed")
                        }
                    } else {
                        // Fallback to accessibility global screenshot action
                        val service = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
                        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val dispatched = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                            log("Triggered system screenshot via Accessibility GLOBAL_ACTION_TAKE_SCREENSHOT: $dispatched")
                            if (!dispatched) {
                                throw IllegalStateException("Accessibility service failed to trigger system screenshot")
                            }
                        } else {
                            throw IllegalStateException("Screenshot capture unavailable (Accessibility service not running or permission missing)")
                        }
                    }
                }
                else -> {
                    logError("Unknown camera action: $action")
                }
            }
        } catch (e: Exception) {
            logError("Failed to execute camera action: ${e.message}", e)
            throw e
        }
    }
}
