package com.example.automation.actions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

class WifiSettingsAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logError("Failed to open WiFi settings", e)
        }
    }
}
