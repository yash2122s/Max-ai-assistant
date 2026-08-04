package com.example.automation.registry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import com.example.automation.ShizukuManager

enum class CapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    REQUIRES_PERMISSION,
    NOT_SUPPORTED
}

data class CapabilityInfo(
    val capabilityName: String,
    val status: CapabilityStatus,
    val description: String
)

object ToolCapabilityRegistry {
    private const val TAG = "ToolCapabilityRegistry"

    fun getCapabilities(context: Context): Map<String, CapabilityInfo> {
        val capabilities = mutableMapOf<String, CapabilityInfo>()

        // 1. Accessibility Tap / Scroll / Type Node capabilities
        val hasAccessibility = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY) != null
        val accessibilityStatus = if (hasAccessibility) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE
        
        capabilities["tap_node"] = CapabilityInfo("tap_node", accessibilityStatus, "Target UI node click via AccessibilityService")
        capabilities["scroll_screen"] = CapabilityInfo("scroll_screen", accessibilityStatus, "Perform scroll gesture via AccessibilityService")
        capabilities["type_text"] = CapabilityInfo("type_text", accessibilityStatus, "Type text into focused editable field via AccessibilityService")

        // 2. Screenshot Capability
        val screenshotStatus = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasAccessibility -> CapabilityStatus.AVAILABLE
            else -> CapabilityStatus.UNAVAILABLE
        }
        capabilities["take_screenshot"] = CapabilityInfo("take_screenshot", screenshotStatus, "Capture screen frame via Accessibility API 30+")

        // 3. Camera Capture Capability
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val cameraStatus = if (hasCameraPermission) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_PERMISSION
        capabilities["camera_capture"] = CapabilityInfo("camera_capture", cameraStatus, "Capture photo frame using device camera")

        // 4. Shizuku Shell Execution Capability
        val isShizukuReady = ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()
        val shizukuStatus = if (isShizukuReady) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE
        capabilities["shizuku_shell"] = CapabilityInfo("shizuku_shell", shizukuStatus, "Privileged ADB shell command execution")

        Log.d(TAG, "Evaluated tool capability registry: ${capabilities.count { it.value.status == CapabilityStatus.AVAILABLE }} / ${capabilities.size} available")
        return capabilities
    }

    fun isCapabilityAvailable(context: Context, capabilityName: String): Boolean {
        val caps = getCapabilities(context)
        return caps[capabilityName]?.status == CapabilityStatus.AVAILABLE
    }
}
