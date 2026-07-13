package com.example.voice.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.voice.session.AssistantEngineService

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AssistantLifecycleManager {
    private val _engine = MutableStateFlow<AssistantEngine?>(null)
    val engine: StateFlow<AssistantEngine?> = _engine.asStateFlow()

    private var isBound = false
    private var engineService: AssistantEngineService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AssistantEngineService.LocalBinder
            val svc = binder?.getService()
            engineService = svc
            isBound = true
            Log.d("LifecycleManager", "AssistantEngineService connected successfully")
            val eng = svc?.getEngine()
            _engine.value = eng
            eng?.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineService = null
            isBound = false
            _engine.value = null
            Log.d("LifecycleManager", "AssistantEngineService disconnected")
        }
    }

    fun start(context: Context) {
        val intent = Intent(context.applicationContext, AssistantEngineService::class.java)
        context.applicationContext.startService(intent)
        context.applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stop() {
        engineService?.getEngine()?.stop()
    }

    fun sleep() {
        engineService?.getEngine()?.pause()
        engineService?.demoteFromForeground()
    }

    fun resume() {
        engineService?.getEngine()?.start()
    }

    fun getEngine(): AssistantEngine? {
        return engineService?.getEngine()
    }

    fun destroy(context: Context) {
        if (isBound) {
            try {
                context.applicationContext.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e("LifecycleManager", "Error unbinding service", e)
            }
            isBound = false
        }
        engineService?.getEngine()?.destroy()
        engineService = null
    }
}
