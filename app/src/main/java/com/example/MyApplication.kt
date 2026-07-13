package com.example

import android.app.Application
import android.util.Log
import com.example.automation.actions.OpenAppAction
import com.example.automation.tools.OpenAppTool
import com.example.automation.tools.ToolRegistry
import com.example.automation.verification.OpenAppVerifier
import com.example.automation.verification.VerificationRegistry

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

        VerificationRegistry.freeze()
        ToolRegistry.freeze()
        
        Log.d("MyApplication", "MAX execution framework successfully initialized.")
    }
}
