package com.example.ui.screens

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.example.receiver.MyDeviceAdminReceiver
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
    var locationStatus by remember { mutableStateOf("Grant") }
    var restrictedSettingsStatus by remember { mutableStateOf("Grant") }
    var allFilesStatus by remember { mutableStateOf("Grant") }
    var deviceAdminStatus by remember { mutableStateOf("Grant") }
    var usageDataStatus by remember { mutableStateOf("Grant") }
    var appNotificationsStatus by remember { mutableStateOf("Grant") }
    var calendarStatus by remember { mutableStateOf("Grant") }
    var shizukuStatus by remember { mutableStateOf("Grant") }

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

        // 8. Location
        locationStatus = if (isLocationPermissionGranted(context)) "Granted" else "Grant"

        // 9. Allow restricted settings helper status
        restrictedSettingsStatus = if (isAccessibilityServiceEnabled(context) || isNotificationListenerEnabled(context)) "Granted" else "Grant"

        // 10. All files access
        allFilesStatus = if (isAllFilesAccessGranted(context)) "Granted" else "Grant"

        // 11. Device administrator
        deviceAdminStatus = if (isDeviceAdminActive(context)) "Granted" else "Grant"

        // 12. Usage data
        usageDataStatus = if (isUsageAccessGranted(context)) "Granted" else "Grant"

        // 13. Disable app notifications
        appNotificationsStatus = if (isNotificationsEnabled(context)) "Granted" else "Grant"

        // 14. Calendar
        val readCalendar = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val writeCalendar = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        calendarStatus = if (readCalendar && writeCalendar) "Granted" else "Grant"

        // 15. Shizuku
        shizukuStatus = if (com.example.automation.ShizukuManager.isShizukuAvailable()) {
            if (com.example.automation.ShizukuManager.isPermissionGranted()) "Granted" else "Grant"
        } else {
            "Not Running"
        }
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

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        locationStatus = if (fineGranted && coarseGranted) "Granted" else "Grant"
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // No action needed for test
    }

    val calendarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val readGranted = results[Manifest.permission.READ_CALENDAR] ?: false
        val writeGranted = results[Manifest.permission.WRITE_CALENDAR] ?: false
        calendarStatus = if (readGranted && writeGranted) "Granted" else "Grant"
    }

    val permissions = listOf(
        PermissionItem(
            title = "Enable accessibility",
            description = "Accessibility is useful for several features of the application (Instant messaging, call recording, live viewing, application blocking and website history).",
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
            title = "Activate all permissions",
            description = "Activate the permissions necessary for the proper functioning of the application (Calls, Bluetooth, and Notifications).",
            status = if (callsStatus == "Granted" && bluetoothStatus == "Granted" && notificationsStatus == "Granted") "Granted" else "Grant",
            onClick = {
                if (callsStatus != "Granted") {
                    callsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
                }
                if (bluetoothStatus != "Granted" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (notificationsStatus != "Granted" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        ),
        PermissionItem(
            title = "Allow restricted settings",
            description = "You must allow restricted settings to enable accessibility, access to notifications and SMS permission.",
            status = restrictedSettingsStatus,
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                }
            }
        ),
        PermissionItem(
            title = "All files access",
            description = "Allow access to manage all files on the device.",
            status = allFilesStatus,
            onClick = {
                if (allFilesStatus != "Granted") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }
                }
            }
        ),
        PermissionItem(
            title = "Allow location",
            description = "Always allow location access for this application.",
            status = locationStatus,
            onClick = {
                if (locationStatus != "Granted") {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        ),
        PermissionItem(
            title = "Device administrator",
            description = "Administrator rights prevent the application from shutting down, locking the phone, erasing all data, and blocking the camera.",
            status = deviceAdminStatus,
            onClick = {
                if (deviceAdminStatus != "Granted") {
                    try {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, MyDeviceAdminReceiver::class.java))
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Activate device administrator rights.")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        ),
        PermissionItem(
            title = "Enable access to notifications",
            description = "Hide system notifications for the application and retrieve messages received from instant messengers.",
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
            title = "Usage data",
            description = "Provides statistics on the use of installed applications.",
            status = usageDataStatus,
            onClick = {
                if (usageDataStatus != "Granted") {
                    try {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            }
        ),
        PermissionItem(
            title = "Overlay on other apps.",
            description = "Useful for the good functioning of the application.",
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
            title = "Disable app notifications",
            description = "Hide notifications for the application.",
            status = appNotificationsStatus,
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                }
            }
        ),
        PermissionItem(
            title = "Do not optimize battery usage",
            description = "This makes it possible to keep the application running in the background.",
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
            title = "Protecting application",
            description = "Important step for the application not to be stopped.",
            status = "Grant",
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        ),
        PermissionItem(
            title = "Calendar access",
            description = "Allows MAX to query, display, and add calendar events on your device.",
            status = calendarStatus,
            onClick = {
                if (calendarStatus != "Granted") {
                    calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
            }
        ),
        PermissionItem(
            title = "Shizuku ADB execution",
            description = "Enables executing ADB shell commands safely. The Shizuku app must be running in the background.",
            status = shizukuStatus,
            onClick = {
                if (shizukuStatus == "Grant") {
                    var currentContext = context
                    var activity: android.app.Activity? = null
                    while (currentContext is android.content.ContextWrapper) {
                        if (currentContext is android.app.Activity) {
                            activity = currentContext
                            break
                        }
                        currentContext = currentContext.baseContext
                    }
                    if (activity != null) {
                        com.example.automation.ShizukuManager.requestPermission(activity)
                    }
                } else if (shizukuStatus == "Not Running") {
                    android.widget.Toast.makeText(context, "Please start the Shizuku app/service first!", android.widget.Toast.LENGTH_LONG).show()
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

private fun isAllFilesAccessGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun isLocationPermissionGranted(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fineGranted && coarseGranted
}

private fun isDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val cn = ComponentName(context, MyDeviceAdminReceiver::class.java)
    return dpm.isAdminActive(cn)
}

private fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun isNotificationsEnabled(context: Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}
