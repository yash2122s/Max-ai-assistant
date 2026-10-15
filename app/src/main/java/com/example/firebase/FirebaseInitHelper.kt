package com.example.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

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
                    // Missing resources are a configuration failure, not permission
                    // to initialize a different project. Tests inject their SDK boundary.
                    Log.w(TAG, "Firebase resources missing; initialization unavailable")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp initialization deferred or running in test environment: ${e.message}")
            null
        }
    }
}
