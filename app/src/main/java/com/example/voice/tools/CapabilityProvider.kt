package com.example.voice.tools

import android.content.Context
import com.example.automation.ShizukuManager
import com.example.service.JarvisServiceStateManager
import java.io.File

class CapabilityProvider(private val context: Context) {
    fun hasAccessibility(): Boolean {
        return JarvisServiceStateManager.isServiceRunning.value
    }

    fun hasShizuku(): Boolean {
        return ShizukuManager.isShizukuAvailable() && ShizukuManager.isPermissionGranted()
    }

    fun hasRoot(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su"
            )
            paths.any { File(it).exists() }
        } catch (e: Exception) {
            false
        }
    }

    fun hasShell(): Boolean {
        return true
    }

    fun hasAndroidApi(): Boolean {
        return true
    }
}
