package com.example.voice.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionChecker {
    fun hasPermissionForAction(context: Context, action: String): Boolean {
        return when (action) {
            "CALL_PHONE" -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            }
            "SET_BRIGHTNESS" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.System.canWrite(context)
                } else {
                    true
                }
            }
            "SET_RINGER_MODE" -> {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
                    notificationManager.isNotificationPolicyAccessGranted
                } else {
                    true
                }
            }
            "RECORD_AUDIO" -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }
}
