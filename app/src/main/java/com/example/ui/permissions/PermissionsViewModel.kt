package com.example.ui.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.automation.ShizukuManager
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PermissionsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(checkAllPermissions())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    private val _events = Channel<PermissionsUiEvent>(Channel.BUFFERED)
    val events: Flow<PermissionsUiEvent> = _events.receiveAsFlow()

    fun refreshPermissions() {
        _uiState.value = checkAllPermissions()
    }

    private fun checkAllPermissions(): PermissionsUiState {
        val context = getApplication<Application>()

        val isAccessibility = JarvisAccessibilityService.isServiceRunning()
        val isNotifListener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val isOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else true

        val isMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val isNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val isContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val isPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val isSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val isLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val isShizukuAvail = ShizukuManager.isShizukuAvailable()
        val isShizukuGrant = isShizukuAvail && ShizukuManager.isPermissionGranted()

        val items = listOf(
            PermissionItemState("accessibility", "Accessibility Service", "Required for UI tree analysis, clicking, typing, and app automation", isAccessibility, true, PermissionCategory.CORE_SYSTEM),
            PermissionItemState("overlay", "Display Over Other Apps", "Required for floating capsule HUD and instant AI overlay", isOverlay, true, PermissionCategory.CORE_SYSTEM),
            PermissionItemState("battery", "Ignore Battery Optimizations", "Keeps MAX voice engine and background automation loop active", isBattery, true, PermissionCategory.CORE_SYSTEM),
            PermissionItemState("notif_listener", "Notification Listener", "Allows MAX to read, summarize, and auto-reply to messages", isNotifListener, false, PermissionCategory.CORE_SYSTEM),
            PermissionItemState("microphone", "Microphone", "Enables real-time duplex voice interaction and speech recognition", isMic, true, PermissionCategory.VOICE_AND_VISION),
            PermissionItemState("camera", "Camera & Vision", "Allows MAX to capture photos, analyze screenshots, and use flashlight", isCamera, false, PermissionCategory.VOICE_AND_VISION),
            PermissionItemState("notifications", "Post Notifications", "Shows ongoing status and task completion reminders", isNotif, false, PermissionCategory.CORE_SYSTEM),
            PermissionItemState("contacts", "Contacts Access", "Finds contact numbers when you say 'Call Mom' or 'Message Yash'", isContacts, false, PermissionCategory.COMMUNICATIONS),
            PermissionItemState("phone", "Phone & Calls", "Allows placing direct calls and managing call automations", isPhone, false, PermissionCategory.COMMUNICATIONS),
            PermissionItemState("sms", "SMS Messages", "Enables sending text messages through automation tools", isSms, false, PermissionCategory.COMMUNICATIONS),
            PermissionItemState("location", "Device Location", "Allows location-aware weather, queries, and maps automation", isLoc, false, PermissionCategory.COMMUNICATIONS),
            PermissionItemState("shizuku", "Shizuku ADB Shell", "Provides rootless privileged ADB shell execution for deep system actions", isShizukuGrant, false, PermissionCategory.ADVANCED_ROOT)
        )

        return PermissionsUiState(
            isAccessibilityGranted = isAccessibility,
            isNotificationListenerGranted = isNotifListener,
            isOverlayGranted = isOverlay,
            isBatteryOptimizationsIgnored = isBattery,
            isMicrophoneGranted = isMic,
            isCameraGranted = isCamera,
            isNotificationsGranted = isNotif,
            isContactsGranted = isContacts,
            isPhoneGranted = isPhone,
            isSmsGranted = isSms,
            isLocationGranted = isLoc,
            isShizukuAvailable = isShizukuAvail,
            isShizukuGranted = isShizukuGrant,
            items = items
        )
    }

    fun isRuntimePermission(id: String): Boolean {
        return id in setOf("microphone", "camera", "notifications", "contacts", "phone", "sms", "location")
    }

    fun getRuntimePermissions(id: String): Array<String> {
        return when (id) {
            "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
            "camera" -> arrayOf(Manifest.permission.CAMERA)
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
            "phone" -> arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            "sms" -> arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
            "location" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyArray()
        }
    }

    fun handlePermissionAction(id: String) {
        val context = getApplication<Application>()
        try {
            when (id) {
                "accessibility" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                }
                "overlay" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                    }
                }
                "battery" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                    }
                }
                "notif_listener" -> {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                }
                "shizuku" -> {
                    if (!ShizukuManager.isShizukuAvailable()) {
                        viewModelScope.launch {
                            _events.send(PermissionsUiEvent.ShowToast("Shizuku service is not running on device"))
                        }
                    } else {
                        ShizukuManager.requestPermission()
                    }
                }
                else -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            viewModelScope.launch {
                _events.send(PermissionsUiEvent.ShowToast("Error opening settings: ${e.localizedMessage}"))
            }
        }
    }
}
