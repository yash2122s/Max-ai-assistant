package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.knowledge.apps.InstalledAppsRepository

class AppsInstalledReceiver : BroadcastReceiver() {
    private val TAG = "AppsInstalledReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received package action broadcast: $action")
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {
            
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            Log.d(TAG, "Package changed action: $action, isReplacing: $isReplacing. Scanning installed apps...")
            InstalledAppsRepository.scanAndSaveInstalledApps(context)
        }
    }
}
