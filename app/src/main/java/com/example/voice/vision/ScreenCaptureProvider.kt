package com.example.voice.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.Display
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCaptureProvider {
    private const val TAG = "ScreenCaptureProvider"
    private const val TARGET_MAX_DIMENSION = 720
    private const val JPEG_QUALITY = 80

    private val captureCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun captureCompressedJpeg(): ByteArray? {
        val count = captureCounter.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "[VisionTelemetry] captureCompressedJpeg #$count invoked at $timestamp")
        val bitmap = captureBitmap()
        if (bitmap == null) {
            Log.w(TAG, "[VisionTelemetry] captureCompressedJpeg #$count FAILED - captureBitmap returned null")
            return null
        }
        return try {
            val resized = resizeBitmap(bitmap, TARGET_MAX_DIMENSION)
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            Log.d(TAG, "[VisionTelemetry] captureCompressedJpeg #$count SUCCESS - JPEG size: ${bytes.size} bytes | res: ${resized.width}x${resized.height}")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "[VisionTelemetry] captureCompressedJpeg #$count ERROR: ${e.message}", e)
            null
        }
    }

    private fun captureBitmap(): Bitmap? {
        // Tier 1: Shizuku Silent In-Memory Screencap (RAM ONLY - Executes as Root/ADB process)
        if (com.example.automation.ShizukuManager.isShizukuAvailable() && com.example.automation.ShizukuManager.isPermissionGranted()) {
            try {
                val pngBytes = com.example.automation.ShizukuShellPlugin.runCommandBytes("screencap -p")
                if (pngBytes != null && pngBytes.isNotEmpty()) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                    if (bitmap != null) {
                        Log.d(TAG, "Captured screen frame in RAM via Tier 1 (Shizuku screencap stream, ${pngBytes.size} bytes)")
                        return bitmap
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 Shizuku screencap stream attempt failed: ${e.message}")
            }
        }

        // Tier 2: Try Direct Root su -c screencap -p RAM Stream
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
            val pngBytes = process.inputStream.buffered().readBytes()
            if (pngBytes.isNotEmpty()) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bitmap != null) {
                    Log.d(TAG, "Captured screen frame in RAM via Tier 2 (Root su screencap stream, ${pngBytes.size} bytes)")
                    return bitmap
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 Root su screencap stream attempt failed: ${e.message}")
        }

        // Tier 2: AccessibilityService takeScreenshot (Android 11 / API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val service = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
            if (service != null) {
                val latch = CountDownLatch(1)
                var resultBitmap: Bitmap? = null

                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                try {
                                    val hardwareBuffer = screenshot.hardwareBuffer
                                    val colorSpace = screenshot.colorSpace
                                    resultBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                    hardwareBuffer.close()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing hardware buffer: ${e.message}", e)
                                } finally {
                                    latch.countDown()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "Accessibility takeScreenshot failed with error code: $errorCode")
                                latch.countDown()
                            }
                        }
                    )
                    latch.await(2, TimeUnit.SECONDS)
                    if (resultBitmap != null) {
                        Log.d(TAG, "Captured screen frame via Tier 2 (Accessibility takeScreenshot)")
                        return resultBitmap
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Accessibility takeScreenshot exception: ${e.message}", e)
                }
            }
        }
        Log.w(TAG, "Screen capture unavailable across all tiers")
        return null
    }

    private fun resizeBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDimension && height <= maxDimension) return source

        val scale = maxDimension.toFloat() / Math.max(width, height)
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(source, 0, 0, width, height, matrix, true)
    }
}
