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

enum class AssistantMode {
    STANDALONE,
    WORKER_MODE
}

object AssistantLifecycleManager {
    private val _engine = MutableStateFlow<AssistantEngine?>(null)
    val engine: StateFlow<AssistantEngine?> = _engine.asStateFlow()

    private val _mode = MutableStateFlow(AssistantMode.STANDALONE)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    var activeMasterSessionId: String? = null
        private set
    var sessionGeneration: Long = 0L
        private set

    private var isBound = false
    private var engineService: AssistantEngineService? = null

    private fun safeLog(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (e: Throwable) {
            println("[$tag] $msg")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AssistantEngineService.LocalBinder
            val svc = binder?.getService()
            engineService = svc
            isBound = true
            safeLog("LifecycleManager", "AssistantEngineService connected successfully")
            val eng = svc?.getEngine() ?: _engine.value
            if (_engine.value == null) {
                _engine.value = eng
                if (_mode.value == AssistantMode.STANDALONE) {
                    eng?.start()
                } else {
                    safeLog("LifecycleManager", "[WorkerMode] Engine started in suspended WORKER_MODE")
                    eng?.connectionManager?.suspendForWorkerMode()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineService = null
            isBound = false
            _engine.value = null
            safeLog("LifecycleManager", "AssistantEngineService disconnected")
        }
    }

    fun start(context: Context) {
        if (!isBound) {
            val intent = Intent(context.applicationContext, AssistantEngineService::class.java)
            context.applicationContext.startService(intent)
            context.applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stop() {
        getEngine()?.stop()
    }

    fun sleep() {
        getEngine()?.pause()
        engineService?.demoteFromForeground()
    }

    fun resume() {
        if (_mode.value == AssistantMode.STANDALONE) {
            getEngine()?.start()
        }
    }

    @Synchronized
    fun getOrCreateEngine(context: Context): AssistantEngine {
        start(context)
        return _engine.value ?: engineService?.getEngine() ?: synchronized(this) {
            _engine.value ?: AssistantEngine(context.applicationContext).also {
                _engine.value = it
                if (_mode.value == AssistantMode.STANDALONE) {
                    it.start()
                } else {
                    it.connectionManager.suspendForWorkerMode()
                }
            }
        }
    }

    fun getEngine(): AssistantEngine? {
        return _engine.value ?: engineService?.getEngine()
    }

    /**
     * P2.4: Enter Worker Mode upon successful authenticated Windows connection.
     * Suspends local voice authority, audio recording, and local Gemini Live session.
     */
    @Synchronized
    fun enterWorkerMode(masterSessionId: String) {
        if (masterSessionId.isBlank()) return
        if (activeMasterSessionId == masterSessionId && _mode.value == AssistantMode.WORKER_MODE) {
            safeLog("AssistantLifecycleManager", "[WorkerMode] Duplicate enterWorkerMode for session=$masterSessionId ignored")
            return
        }

        sessionGeneration++
        activeMasterSessionId = masterSessionId
        _mode.value = AssistantMode.WORKER_MODE
        safeLog("AssistantLifecycleManager", "[WorkerMode] MASTER_CONNECTED session=$masterSessionId generation=$sessionGeneration")
        safeLog("AssistantLifecycleManager", "[WorkerMode] ENTER_WORKER_MODE session=$masterSessionId")

        val eng = getEngine()
        if (eng != null) {
            try {
                eng.stopListening()
                safeLog("AssistantLifecycleManager", "[WorkerMode] VOSK_SUSPENDED (Microphone released)")
            } catch (e: Exception) {
                safeLog("AssistantLifecycleManager", "[WorkerMode] Error stopping listening: ${e.message}")
            }
            try {
                eng.streamingResponseManager.stopAllPlayback()
            } catch (e: Exception) {
                safeLog("AssistantLifecycleManager", "[WorkerMode] Error stopping playback: ${e.message}")
            }
            try {
                eng.connectionManager.suspendForWorkerMode()
                safeLog("AssistantLifecycleManager", "[WorkerMode] GEMINI_SUSPENDED (Reconnect loop disabled)")
            } catch (e: Exception) {
                safeLog("AssistantLifecycleManager", "[WorkerMode] Error suspending connection manager: ${e.message}")
            }
        }
    }

    /**
     * P2.4: Exit Worker Mode and restore STANDALONE mode upon Windows disconnect.
     * Re-enables local voice authority and Gemini Live.
     */
    @Synchronized
    fun exitWorkerMode(masterSessionId: String) {
        if (masterSessionId != activeMasterSessionId) {
            safeLog("AssistantLifecycleManager", "[WorkerMode] Stale exitWorkerMode ignored for session=$masterSessionId (active=$activeMasterSessionId)")
            return
        }

        safeLog("AssistantLifecycleManager", "[WorkerMode] MASTER_DISCONNECTED session=$masterSessionId")
        safeLog("AssistantLifecycleManager", "[WorkerMode] EXIT_WORKER_MODE session=$masterSessionId generation=$sessionGeneration")
        activeMasterSessionId = null
        _mode.value = AssistantMode.STANDALONE

        val eng = getEngine()
        if (eng != null) {
            try {
                eng.connectionManager.resumeFromWorkerMode()
                safeLog("AssistantLifecycleManager", "[WorkerMode] GEMINI_RESUMED")
            } catch (e: Exception) {
                safeLog("AssistantLifecycleManager", "[WorkerMode] Error resuming connection manager: ${e.message}")
            }
            safeLog("AssistantLifecycleManager", "[WorkerMode] VOSK_RESUMED (Ready for standalone interaction)")
        }
    }

    fun destroy(context: Context) {
        if (isBound) {
            try {
                context.applicationContext.unbindService(serviceConnection)
            } catch (e: Exception) {
                safeLog("LifecycleManager", "Error unbinding service: ${e.message}")
            }
            isBound = false
        }
        _engine.value?.destroy()
        _engine.value = null
        engineService = null
        activeMasterSessionId = null
        _mode.value = AssistantMode.STANDALONE
    }
}
