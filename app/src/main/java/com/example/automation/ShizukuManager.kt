package com.example.automation

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku service ping failed", e)
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            if (isShizukuAvailable()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Shizuku permission", e)
            false
        }
    }

    fun requestPermission(activity: Activity) {
        try {
            if (isShizukuAvailable()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } else {
                Log.w(TAG, "Cannot request permission: Shizuku binder is not available.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
        }
    }
}
