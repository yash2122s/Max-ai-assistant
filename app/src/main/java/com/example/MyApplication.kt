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

        val answerCallAction = com.example.automation.actions.AnswerCallAction()
        val answerCallTool = com.example.automation.tools.AnswerCallTool(answerCallAction)
        
        val rejectCallAction = com.example.automation.actions.RejectCallAction()
        val rejectCallTool = com.example.automation.tools.RejectCallTool(rejectCallAction)
        
        val sendSmsAction = com.example.automation.actions.SendSmsAction()
        val smsTool = com.example.automation.tools.SmsTool(sendSmsAction)

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
        VerificationRegistry.register(com.example.automation.verification.ClickUiVerifier())
        VerificationRegistry.register(com.example.automation.verification.PressEnterVerifier())
        VerificationRegistry.register(com.example.automation.verification.KeyboardActionVerifier())

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
        ToolRegistry.register(answerCallTool)
        ToolRegistry.register(rejectCallTool)
        ToolRegistry.register(smsTool)
        ToolRegistry.register(com.example.automation.tools.SaveMemoryTool())
        ToolRegistry.register(com.example.automation.tools.ForgetMemoryTool())
        ToolRegistry.register(com.example.automation.tools.ReminderTool())
        ToolRegistry.register(com.example.automation.tools.UnlockTool())
        ToolRegistry.register(com.example.automation.tools.ClickUiTool())
        ToolRegistry.register(com.example.automation.tools.TypeTextTool())
        ToolRegistry.register(com.example.automation.tools.PressEnterTool())
        ToolRegistry.register(com.example.automation.tools.ScrollTool())
        ToolRegistry.register(com.example.automation.tools.ScreenContextTool())
        ToolRegistry.register(com.example.automation.tools.UiAgentTool())
        ToolRegistry.register(com.example.automation.tools.KeyboardActionTool())
        ToolRegistry.register(com.example.automation.tools.ClearTextTool())

        VerificationRegistry.freeze()
        ToolRegistry.freeze()

        // Inject LLM Planning Provider into GoalPlanner
        val planningProvider = com.example.network.provider.GeminiPlanningProvider(this)
        com.example.automation.engine.GoalPlanner.setPlanningProvider(planningProvider)
        
        // Initialize central MaxRuntimeManager
        com.example.core.lifecycle.MaxRuntimeManager.initialize(this)

        // Initialize and start lifecycle-driven visual execution feedback overlay
        try {
            val visualFeedbackManager = com.example.ui.overlay.MaxVisualFeedbackManager(this)
            visualFeedbackManager.startListening(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
        } catch (e: Exception) {
            Log.w("MyApplication", "Visual feedback overlay initialization deferred/failed: ${e.message}")
        }

        // Defer non-critical background services & scans to IO scope so Compose paints first frame immediately
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Background app scan
            try {
                com.example.knowledge.apps.InstalledAppsRepository.scanAndSaveInstalledApps(applicationContext)
            } catch (e: Exception) {
                Log.w("MyApplication", "Background apps scan deferred error", e)
            }

            // Restore runtime services safely if MAX is enabled
            com.example.core.lifecycle.MaxRuntimeManager.restoreRuntimeState(this@MyApplication)
        }

        // Foreground/Background Activity Tracker for Dynamic Capsule Overlay
        var startedActivities = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                _isMaxForeground.value = (startedActivities > 0)
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                _isMaxForeground.value = true
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                _isMaxForeground.value = (startedActivities > 0)
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

        Log.d("MyApplication", "MAX execution framework successfully initialized.")
    }

    companion object {
        private val _isMaxForeground = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isMaxForeground: kotlinx.coroutines.flow.StateFlow<Boolean> = _isMaxForeground

        fun setForegroundForTesting(foreground: Boolean) {
            _isMaxForeground.value = foreground
        }
    }
}
