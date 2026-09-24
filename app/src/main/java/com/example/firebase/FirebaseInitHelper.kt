package com.example.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseInitHelper {
    private const val TAG = "FirebaseInitHelper"

    fun ensureFirebaseApp(context: Context): FirebaseApp? {
        return try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseApp.getInstance()
            } else {
                // In production, FirebaseInitProvider or initializeApp(context) will load google-services.json
                val initialized = FirebaseApp.initializeApp(context)
                if (initialized != null) {
                    initialized
                } else {
                    // Safe fallback for Robolectric or test environments without google-services.json resource inflation
                    val options = FirebaseOptions.Builder()
                        .setApplicationId("com.maxaiagent.yash")
                        .setProjectId("max-ai-assistant")
                        .setApiKey("AIzaSyMockDevelopmentKeyForMaxAssistant")
                        .build()
                    FirebaseApp.initializeApp(context, options, "[DEFAULT]")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp initialization deferred or running in test environment: ${e.message}")
            null
        }
    }
}
