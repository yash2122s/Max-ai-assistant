package com.example.automation.actions

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

class WifiSettingsAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            log("Opening WiFi Toggle Settings Panel")
        } catch (e: Exception) {
            logError("Failed to open WiFi settings panel", e)
            try {
                val settingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(settingsIntent)
            } catch (ex: Exception) {
                logError("Failed to open WiFi settings page", ex)
            }
        }
    }
}
