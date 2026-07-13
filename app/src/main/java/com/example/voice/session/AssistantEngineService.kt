package com.example.voice.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voice.assistant.AssistantEngine
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AssistantEngineService : Service() {
    private val binder = LocalBinder()
    private lateinit var engine: AssistantEngine
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForeground = false
    private val NOTIFICATION_ID = 9999
    private val CHANNEL_ID = "assistant_engine_channel"

    inner class LocalBinder : Binder() {
        fun getService(): AssistantEngineService = this@AssistantEngineService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AssistantEngineService", "Creating AssistantEngineService...")
        engine = AssistantEngine(applicationContext)

        // Observe state transitions to promote/demote foreground status
        scope.launch {
            engine.stateMachine.currentState.collect { state ->
                when (state) {
                    SessionState.LISTENING, SessionState.STREAMING_AUDIO, SessionState.TOOL_EXECUTION -> {
                        promoteToForeground("MAX Assistant is active")
                    }
                    SessionState.IDLE, SessionState.CONNECTED, SessionState.ERROR -> {
                        demoteFromForeground()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun getEngine(): AssistantEngine = engine

    private fun promoteToForeground(statusMessage: String) {
        if (isForeground) return
        isForeground = true
        Log.d("AssistantEngineService", "Promoting service to foreground: $statusMessage")

        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAX Digital Assistant")
            .setContentText(statusMessage)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun demoteFromForeground() {
        if (!isForeground) return
        isForeground = false
        Log.d("AssistantEngineService", "Demoting service from foreground")
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MAX Assistant Active Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AssistantEngineService", "Destroying AssistantEngineService...")
        engine.destroy()
    }
}
