package com.example

import android.app.Application
import android.util.Log
import com.example.automation.actions.OpenAppAction
import com.example.automation.tools.OpenAppTool
import com.example.automation.tools.ToolRegistry
import com.example.automation.verification.OpenAppVerifier
import com.example.automation.verification.VerificationRegistry
import kotlinx.coroutines.launch

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Starting MAX application lifecycle...")

        val openAppAction = OpenAppAction()
        val openAppVerifier = OpenAppVerifier()
        val openAppTool = OpenAppTool(openAppAction)

        val flashlightTool = com.example.automation.tools.FlashlightTool()
        val whatsAppTool = com.example.automation.tools.WhatsAppTool()
        val whatsAppVerifier = com.example.automation.verification.WhatsAppVerifier()

        val scheduleTaskTool = com.example.automation.scheduler.ScheduleTaskTool()
        val cancelTaskTool = com.example.automation.scheduler.CancelTaskTool()
        val listScheduledTasksTool = com.example.automation.scheduler.ListScheduledTasksTool()

        val volumeAction = com.example.automation.actions.VolumeAction()
        val volumeTool = com.example.automation.tools.VolumeTool(volumeAction)
        val volumeVerifier = com.example.automation.verification.VolumeVerifier()

        val brightnessAction = com.example.automation.actions.BrightnessAction()
        val brightnessTool = com.example.automation.tools.BrightnessTool(brightnessAction)
        val brightnessVerifier = com.example.automation.verification.BrightnessVerifier()

        val ringerAction = com.example.automation.actions.RingerAction()
        val ringerTool = com.example.automation.tools.RingerTool(ringerAction)
        val ringerVerifier = com.example.automation.verification.RingerVerifier()

        val callPhoneAction = com.example.automation.actions.CallPhoneAction()
        val callTool = com.example.automation.tools.CallTool(callPhoneAction)
        val callVerifier = com.example.automation.verification.CallVerifier()

        val systemAction = com.example.automation.actions.SystemAction()
        val systemTool = com.example.automation.tools.SystemTool(systemAction)
        val systemVerifier = com.example.automation.verification.SystemVerifier()

        val youtubeTool = com.example.automation.tools.YoutubeTool()
        val youtubeVerifier = com.example.automation.verification.YoutubeVerifier()

        val wifiSettingsAction = com.example.automation.actions.WifiSettingsAction()
        val wifiTool = com.example.automation.tools.WifiTool(wifiSettingsAction)
        val wifiVerifier = com.example.automation.verification.WifiVerifier()

        val diagnosticsTool = com.example.automation.tools.DiagnosticsTool()
        val diagnosticsVerifier = com.example.automation.verification.DiagnosticsVerifier()

        val createContactAction = com.example.automation.actions.CreateContactAction()
        val createContactTool = com.example.automation.tools.CreateContactTool(createContactAction)
        val createContactVerifier = com.example.automation.verification.CreateContactVerifier()

        val windowsAgentTool = com.example.automation.tools.WindowsAgentTool()
        val locationTool = com.example.automation.tools.LocationTool()

        // New skills
        val shizukuTool = com.example.automation.tools.ShizukuTool()
        val shizukuVerifier = com.example.automation.verification.ShizukuVerifier()

        val bluetoothAction = com.example.automation.actions.BluetoothAction()
        val bluetoothTool = com.example.automation.tools.BluetoothTool(bluetoothAction)
        val bluetoothVerifier = com.example.automation.verification.BluetoothVerifier()

        val batteryAction = com.example.automation.actions.BatteryAction()
        val batteryTool = com.example.automation.tools.BatteryTool(batteryAction)
        val batteryVerifier = com.example.automation.verification.BatteryVerifier()

        val dndAction = com.example.automation.actions.DndAction()
        val dndTool = com.example.automation.tools.DndTool(dndAction)
        val dndVerifier = com.example.automation.verification.DndVerifier()

        val cameraAction = com.example.automation.actions.CameraAction()
        val cameraTool = com.example.automation.tools.CameraTool(cameraAction)
        val cameraVerifier = com.example.automation.verification.CameraVerifier()

        val calendarAction = com.example.automation.actions.CalendarAction()
        val calendarTool = com.example.automation.tools.CalendarTool(calendarAction)
        val calendarVerifier = com.example.automation.verification.CalendarVerifier()

        val notificationTool = com.example.automation.tools.NotificationTool()
        val mediaTool = com.example.automation.tools.MediaTool()
        val alarmTool = com.example.automation.tools.AlarmTool()
        val fileSearchTool = com.example.automation.tools.FileSearchTool()

        val settingsSearchTool = com.example.automation.tools.SettingsSearchTool()
        val deviceStatusTool = com.example.automation.tools.DeviceStatusTool(batteryAction)
        val usageStatsTool = com.example.automation.tools.UsageStatsTool()
        val clipboardTool = com.example.automation.tools.ClipboardTool()
        val routineTool = com.example.automation.tools.RoutineTool()
        val periodTrackerTool = com.example.automation.tools.PeriodTrackerTool()
        val periodTrackerVerifier = com.example.automation.verification.PeriodTrackerVerifier()

        val searchContactAction = com.example.automation.actions.SearchContactAction()
        val searchContactTool = com.example.automation.tools.SearchContactTool(searchContactAction)
        val searchContactVerifier = com.example.automation.verification.SearchContactVerifier()

        VerificationRegistry.register(openAppVerifier)
        VerificationRegistry.register(whatsAppVerifier)
        VerificationRegistry.register(volumeVerifier)
        VerificationRegistry.register(brightnessVerifier)
        VerificationRegistry.register(ringerVerifier)
        VerificationRegistry.register(callVerifier)
        VerificationRegistry.register(systemVerifier)
        VerificationRegistry.register(youtubeVerifier)
        VerificationRegistry.register(wifiVerifier)
        VerificationRegistry.register(diagnosticsVerifier)
        VerificationRegistry.register(createContactVerifier)
        VerificationRegistry.register(shizukuVerifier)
        VerificationRegistry.register(bluetoothVerifier)
        VerificationRegistry.register(batteryVerifier)
        VerificationRegistry.register(dndVerifier)
        VerificationRegistry.register(cameraVerifier)
        VerificationRegistry.register(calendarVerifier)
        VerificationRegistry.register(periodTrackerVerifier)
        VerificationRegistry.register(searchContactVerifier)

        ToolRegistry.register(openAppTool)
        ToolRegistry.register(flashlightTool)
        ToolRegistry.register(whatsAppTool)
        ToolRegistry.register(scheduleTaskTool)
        ToolRegistry.register(cancelTaskTool)
        ToolRegistry.register(listScheduledTasksTool)
        ToolRegistry.register(volumeTool)
        ToolRegistry.register(brightnessTool)
        ToolRegistry.register(ringerTool)
        ToolRegistry.register(callTool)
        ToolRegistry.register(systemTool)
        ToolRegistry.register(youtubeTool)
        ToolRegistry.register(wifiTool)
        ToolRegistry.register(diagnosticsTool)
        ToolRegistry.register(createContactTool)
        ToolRegistry.register(windowsAgentTool)
        ToolRegistry.register(locationTool)
        ToolRegistry.register(shizukuTool)
        ToolRegistry.register(bluetoothTool)
        ToolRegistry.register(batteryTool)
        ToolRegistry.register(dndTool)
        ToolRegistry.register(cameraTool)
        ToolRegistry.register(calendarTool)
        ToolRegistry.register(notificationTool)
        ToolRegistry.register(mediaTool)
        ToolRegistry.register(alarmTool)
        ToolRegistry.register(fileSearchTool)
        ToolRegistry.register(settingsSearchTool)
        ToolRegistry.register(deviceStatusTool)
        ToolRegistry.register(usageStatsTool)
        ToolRegistry.register(clipboardTool)
        ToolRegistry.register(routineTool)
        ToolRegistry.register(periodTrackerTool)
        ToolRegistry.register(searchContactTool)
        ToolRegistry.register(com.example.automation.tools.SaveMemoryTool())
        ToolRegistry.register(com.example.automation.tools.ReminderTool())

        VerificationRegistry.freeze()
        ToolRegistry.freeze()
        
        // Start Telegram Bot Service only when fully configured (token + authorized chat)
        val settings = com.example.data.preferences.SettingsManager(this)
        if (settings.isTelegramBotEnabled &&
            settings.telegramBotToken.isNotBlank() &&
            settings.telegramChatId.isNotBlank()
        ) {
            try {
                val serviceIntent = android.content.Intent(this, com.example.service.TelegramBotService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: java.lang.Exception) {
                Log.e("MyApplication", "Failed to start TelegramBotService on startup", e)
            }
        } else if (settings.isTelegramBotEnabled) {
            Log.w("MyApplication", "Telegram bot enabled but token/chat ID missing — not starting service")
        }
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.example.data.local.PeriodDataSeeder.seedPeriodData(this@MyApplication)
            } catch (e: Exception) {
                Log.e("MyApplication", "Error seeding period data", e)
            }
        }

        Log.d("MyApplication", "MAX execution framework successfully initialized.")
    }
}
