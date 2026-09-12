package com.example.ui.permissions

data class PermissionItemState(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val category: PermissionCategory
)

enum class PermissionCategory {
    CORE_SYSTEM,
    VOICE_AND_VISION,
    COMMUNICATIONS,
    ADVANCED_ROOT
}

data class PermissionsUiState(
    val isAccessibilityGranted: Boolean = false,
    val isNotificationListenerGranted: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isBatteryOptimizationsIgnored: Boolean = false,
    val isMicrophoneGranted: Boolean = false,
    val isCameraGranted: Boolean = false,
    val isNotificationsGranted: Boolean = false,
    val isContactsGranted: Boolean = false,
    val isPhoneGranted: Boolean = false,
    val isSmsGranted: Boolean = false,
    val isLocationGranted: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isShizukuGranted: Boolean = false,
    val items: List<PermissionItemState> = emptyList()
)

sealed interface PermissionsUiEvent {
    data class ShowToast(val message: String) : PermissionsUiEvent
    data class OpenSettings(val action: String) : PermissionsUiEvent
}
