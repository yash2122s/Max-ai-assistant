package com.example.voice.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Manages Android Digital Assistant role checks and system settings intents.
 */
object SystemAssistantManager {
    private const val TAG = "SystemAssistantManager"

    fun isDefaultAssistant(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                val isHeld = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
                if (isHeld) return true
            }
            // Fallback for secure settings check
            val currentAssistant = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            val currentPkg = context.packageName
            currentAssistant != null && currentAssistant.contains(currentPkg)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking default assistant role", e)
            false
        }
    }

    fun isRoleAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
        } else {
            false
        }
    }

    fun createSetDefaultAssistantIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                try {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "RoleManager.createRequestRoleIntent failed, falling back to settings", e)
                }
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getWakeWordStatusDescription(): String {
        return "Requires system DSP support"
    }
}
