package com.example.firebase.appcheck

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class MaxAppCheckManager(private val context: Context) {

    fun initialize() {
        try {
            val firebaseAppCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Configuring Firebase App Check for DEBUG environment with DebugAppCheckProviderFactory.")
                firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            } else {
                Log.i(TAG, "Configuring Firebase App Check for RELEASE environment with PlayIntegrityAppCheckProviderFactory.")
                firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "App Check initialization deferred/failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MaxAppCheckManager"
    }
}
