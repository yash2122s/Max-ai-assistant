package com.example.analytics

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.firebase.FirebaseInitHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import java.util.UUID

object SessionManager {
    private const val TAG = "SessionManager"
    private const val HEARTBEAT_INTERVAL_MS = 60_000L // 1 minute

    @Volatile
    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    @Volatile
    var sessionStartTime: Long = System.currentTimeMillis()
        private set

    @Volatile
    var isSessionActive: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isSessionActive = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentSessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()
    }

    fun startSession(context: Context, appVersion: String) {
        if (!SettingsManager.getInstance(context).isAnalyticsEnabled) return
        if (isSessionActive) return
        isSessionActive = true
        currentSessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()

        Log.d(TAG, "Starting new session: $currentSessionId")

        // Persist session document in Firestore if authenticated
        recordSessionStartInFirestore(context, currentSessionId, appVersion)

        // Launch periodic heartbeat loop
        startHeartbeatLoop(context, currentSessionId)
    }

    fun endSession(context: Context) {
        if (!isSessionActive) return
        isSessionActive = false
        heartbeatJob?.cancel()
        heartbeatJob = null

        val endedSessionId = currentSessionId
        val durationMs = System.currentTimeMillis() - sessionStartTime
        Log.d(TAG, "Ending session: $endedSessionId, duration: ${durationMs}ms")

        recordSessionEndInFirestore(context, endedSessionId)
    }

    fun heartbeat(context: Context) {
        if (!isSessionActive) return
        recordHeartbeatInFirestore(context, currentSessionId)
    }

    private fun startHeartbeatLoop(context: Context, sessionId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isSessionActive && currentSessionId == sessionId) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isActive && isSessionActive && currentSessionId == sessionId) {
                    recordHeartbeatInFirestore(context, sessionId)
                }
            }
        }
    }

    private fun getUserId(context: Context): String {
        return try {
            val app = FirebaseInitHelper.ensureFirebaseApp(context)
            if (app != null) {
                FirebaseAuth.getInstance(app).currentUser?.uid
                    ?: SettingsManager.getInstance(context).userId
            } else {
                SettingsManager.getInstance(context).userId
            }
        } catch (e: Exception) {
            SettingsManager.getInstance(context).userId
        }
    }

    private fun recordSessionStartInFirestore(context: Context, sessionId: String, appVersion: String) {
        scope.launch {
            try {
                if (!SettingsManager.getInstance(context).isAnalyticsEnabled) return@launch
                val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return@launch
                val auth = FirebaseAuth.getInstance(app)
                val uid = auth.currentUser?.uid ?: return@launch // Ingest only for authenticated users

                val db = FirebaseFirestore.getInstance(app)
                val sessionDoc = mapOf(
                    "sessionId" to sessionId,
                    "userId" to uid,
                    "startedAt" to FieldValue.serverTimestamp(),
                    "lastHeartbeat" to FieldValue.serverTimestamp(),
                    "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                    "appVersion" to appVersion,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "platform" to "Android",
                    "isActive" to true
                )

                db.collection("userSessions").document(sessionId)
                    .set(sessionDoc, SetOptions.merge())
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to record session start in Firestore", e)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Error recording session start", e)
            }
        }
    }

    private fun recordHeartbeatInFirestore(context: Context, sessionId: String) {
        scope.launch {
            try {
                if (!SettingsManager.getInstance(context).isAnalyticsEnabled) return@launch
                val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return@launch
                val auth = FirebaseAuth.getInstance(app)
                val uid = auth.currentUser?.uid ?: return@launch

                val db = FirebaseFirestore.getInstance(app)
                val update = mapOf(
                    "lastHeartbeat" to FieldValue.serverTimestamp(),
                    "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                    "isActive" to true
                )

                db.collection("userSessions").document(sessionId)
                    .update(update)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to record heartbeat in Firestore", e)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Error recording heartbeat", e)
            }
        }
    }

    private fun recordSessionEndInFirestore(context: Context, sessionId: String) {
        scope.launch {
            try {
                if (!SettingsManager.getInstance(context).isAnalyticsEnabled) return@launch
                val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return@launch
                val auth = FirebaseAuth.getInstance(app)
                val uid = auth.currentUser?.uid ?: return@launch

                val db = FirebaseFirestore.getInstance(app)
                val update = mapOf(
                    "endedAt" to FieldValue.serverTimestamp(),
                    "isActive" to false
                )

                db.collection("userSessions").document(sessionId)
                    .update(update)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to record session end in Firestore", e)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Error recording session end", e)
            }
        }
    }
}
