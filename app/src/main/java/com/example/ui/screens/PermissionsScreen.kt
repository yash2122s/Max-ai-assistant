package com.example.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.JarvisAccessibilityService
import com.example.service.WhatsAppNotificationService
import com.example.ui.theme.*

data class PermissionItem(
    val title: String,
    val description: String,
    val status: String, // "Granted", "Exempt", "Grant"
    val onClick: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State variables for each permission status
    var callsStatus by remember { mutableStateOf("Grant") }
    var bluetoothStatus by remember { mutableStateOf("Grant") }
    var notificationsStatus by remember { mutableStateOf("Grant") }
    var notificationAccessStatus by remember { mutableStateOf("Grant") }
    var accessibilityStatus by remember { mutableStateOf("Grant") }
    var batteryStatus by remember { mutableStateOf("Grant") }
    var overlayStatus by remember { mutableStateOf("Grant") }

    // Helper to refresh all statuses
    fun refreshPermissions() {
        // 1. Answer & manage calls (READ_CONTACTS, CALL_PHONE)
        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val callGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        callsStatus = if (contactsGranted && callGranted) "Granted" else "Grant"

        // 2. Bluetooth (BLUETOOTH_CONNECT for API 31+)
        bluetoothStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) "Granted" else "Grant"
        } else {
            "Granted"
        }

        // 3. App notifications (POST_NOTIFICATIONS for API 33+)
        notificationsStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "Granted" else "Grant"
        } else {
            "Granted"
        }

        // 4. Notification access (NotificationListenerService)
        notificationAccessStatus = if (isNotificationListenerEnabled(context)) "Granted" else "Grant"

        // 5. Accessibility service
        accessibilityStatus = if (isAccessibilityServiceEnabled(context)) "Granted" else "Grant"

        // 6. Battery - no optimization
        batteryStatus = if (isBatteryOptimizationIgnored(context)) "Exempt" else "Grant"

        // 7. Display over other apps (Overlay)
        overlayStatus = if (isOverlayPermissionGranted(context)) "Granted" else "Grant"
    }

    // Refresh when screen is first entered and on resume (returning from Settings screens)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Launchers for Runtime Permissions
    val callsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val contactsGranted = results[Manifest.permission.READ_CONTACTS] ?: false
        val callGranted = results[Manifest.permission.CALL_PHONE] ?: false
        callsStatus = if (contactsGranted && callGranted) "Granted" else "Grant"
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        bluetoothStatus = if (isGranted) "Granted" else "Grant"
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationsStatus = if (isGranted) "Granted" else "Grant"
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // No action needed for test
    }

    val permissions = listOf(
        PermissionItem(
            title = "Answer & manage calls",
            description = "Har incoming call ko MAX announce kare aur answer/reject/end kar sake - caller ka naam batane ke liye Call Log bhi chahiye.",
            status = callsStatus,
            onClick = {
                if (callsStatus != "Granted") {
                    callsLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE
                        )
                    )
                }
            }
        ),
        PermissionItem(
            title = "Bluetooth",
            description = "MAX ki awaaz Bluetooth headset/speaker pe bhej sake.",
            status = bluetoothStatus,
            onClick = {
                if (bluetoothStatus != "Granted") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            }
        ),
        PermissionItem(
            title = "App notifications",
            description = "Session chalti rahe iske liye MAX ki notification.",
            status = notificationsStatus,
            onClick = {
                if (notificationsStatus != "Granted") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Notification access",
            description = "Sabhi apps ke notifications (aur WhatsApp messages) padhne ke liye.",
            status = notificationAccessStatus,
            onClick = {
                if (notificationAccessStatus != "Granted") {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Accessibility service",
            description = "WhatsApp/YouTube control aur screen reading ke liye (list me 'MAX' enable karo).",
            status = accessibilityStatus,
            onClick = {
                if (accessibilityStatus != "Granted") {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Battery - no optimization",
            description = "MAX screen off / background me bhi chalti rahe - battery optimization se exempt karo.",
            status = batteryStatus,
            onClick = {
                if (batteryStatus != "Exempt") {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Display over other apps",
            description = "MAX doosre apps ke upar kaam kar sake.",
            status = overlayStatus,
            onClick = {
                if (overlayStatus != "Granted") {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Screen capture",
            description = "MAX tumhari screen live dekh sake (screen share). Iski permission MAX zaroorat padne par khud maang legi - har baar. Yahan se ek baar test bhi kar sakte ho.",
            status = "Grant",
            onClick = {
                val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mediaProjectionManager?.let {
                    try {
                        screenCaptureLauncher.launch(it.createScreenCaptureIntent())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions", color = TextLight, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(permissions) { perm ->
                PermissionCard(item = perm)
            }
        }
    }
}

@Composable
fun PermissionCard(item: PermissionItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(NeonPink.copy(alpha = 0.5f), NeonBlue.copy(alpha = 0.5f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { item.onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = item.title,
                    color = TextLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    color = TextLight.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            
            if (item.status == "Grant") {
                Button(
                    onClick = item.onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Grant", color = OnPrimaryDark, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = NeonPink,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.status,
                        color = NeonPink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// Helper methods for special permissions

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = ComponentName(context, JarvisAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, WhatsAppNotificationService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isOverlayPermissionGranted(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}
