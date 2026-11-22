package com.example.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.firebase.FirebaseInitHelper
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object MaxActivityTracker {
    private const val TAG = "MaxActivityTracker"
    private const val MAX_QUEUE_SIZE = 50
    private const val FLUSH_BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 10_000L // 10 seconds

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private data class QueuedEvent(val uid: String, val event: ActivityEvent)
    private val eventQueue = ConcurrentLinkedQueue<QueuedEvent>()
    @Volatile private var appContext: Context? = null
    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    private val SENSITIVE_KEYS = setOf(
        "password", "token", "secret", "prompt", "message", "text", "transcript",
        "key", "auth", "credential", "bearer", "cookie"
    )

    init {
        startPeriodicFlush()
    }

    private fun startPeriodicFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                appContext?.let { flushQueue(it) }
            }
        }
    }

    /**
     * Resolves the app version safely.
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * Sanitizes metadata to prevent storing PII, raw messages, prompts, or sensitive credentials.
     */
    private fun sanitizeMetadata(metadata: Map<String, Any>): Map<String, Any> {
        val sanitized = mutableMapOf<String, Any>()
        var count = 0
        for ((key, value) in metadata) {
            if (count >= 10) break
            val lowerKey = key.lowercase(Locale.ROOT)
            if (SENSITIVE_KEYS.any { lowerKey.contains(it) }) {
                continue // Redact sensitive properties completely
            }
            when (value) {
                is Number, is Boolean -> {
                    sanitized[key] = value
                    count++
                }
                is String -> {
                    sanitized[key] = value.take(200) // Bound string length
                    count++
                }
            }
        }
        return sanitized
    }

    /**
     * Enqueues an event for batched Firestore ingestion and logs to Firebase Analytics.
     */
    fun trackEvent(context: Context, event: ActivityEvent, immediate: Boolean = false) {
        try {
            val settings = SettingsManager.getInstance(context)
            if (!settings.isAnalyticsEnabled) {
                return
            }

            // 1. Dual logging: Firebase Analytics
            val sanitized = event.copy(metadata = sanitizeMetadata(event.metadata))
            logToFirebaseAnalytics(context, sanitized)

            // 2. Queue for Firestore Ingestion
            appContext = context.applicationContext
            val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return
            val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                eventQueue.poll() // Drop oldest event if backlog exceeds bounded capacity
            }
            eventQueue.offer(QueuedEvent(uid, sanitized))

            if (immediate || eventQueue.size >= FLUSH_BATCH_SIZE) {
                scope.launch { flushQueue(context) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-blocking analytics tracking error: ${e.message}")
        }
    }

    private fun logToFirebaseAnalytics(context: Context, event: ActivityEvent) {
        try {
            val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return
            val firebaseAnalytics = FirebaseAnalytics.getInstance(context)
            val bundle = Bundle().apply {
                putString("feature", event.feature)
                putString("status", event.status)
                putString("session_id", event.sessionId)
                putString("app_version", event.appVersion)
                event.durationMs?.let { putLong("duration_ms", it) }
                event.errorCategory?.let { putString("error_category", it) }
                for ((k, v) in event.metadata) {
                    when (v) {
                        is String -> putString(k, v)
                        is Long -> putLong(k, v)
                        is Int -> putInt(k, v)
                        is Double -> putDouble(k, v)
                        is Boolean -> putString(k, v.toString())
                    }
                }
            }
            val sanitizedName = event.eventType.lowercase(Locale.ROOT).replace("-", "_")
            firebaseAnalytics.logEvent(sanitizedName, bundle)
        } catch (e: Exception) {
            Log.v(TAG, "Firebase Analytics logging skipped or unavailable: ${e.message}")
        }
    }

    private suspend fun flushQueue(context: Context) {
        if (eventQueue.isEmpty() || !flushMutex.tryLock()) return
        val batch = mutableListOf<QueuedEvent>()
        try {
            if (!SettingsManager.getInstance(context).isAnalyticsEnabled) {
                eventQueue.clear()
                return
            }
            val app = FirebaseInitHelper.ensureFirebaseApp(context) ?: return
            val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return
            while (batch.size < FLUSH_BATCH_SIZE) {
                val queued = eventQueue.poll() ?: break
                if (queued.uid == uid) batch.add(queued)
            }
            if (batch.isEmpty()) return
            val payload = mapOf("ownerUid" to uid, "events" to batch.map { it.event.toIngestMap() })
            FirebaseFunctions.getInstance(app).getHttpsCallable("ingestActivityEvents").call(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? FirebaseFunctionsException)?.code
            if (code !in setOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT, FirebaseFunctionsException.Code.PERMISSION_DENIED)) {
                for (queued in batch) {
                    if (eventQueue.size < MAX_QUEUE_SIZE) eventQueue.offer(queued)
                }
            }
            Log.w(TAG, "Activity ingestion failed ($code)")
        } finally {
            flushMutex.unlock()
        }
    }

    // --- Specialized Tracking Helpers ---

    fun trackSessionStart(context: Context) {
        val appVersion = getAppVersion(context)
        SessionManager.startSession(context, appVersion)
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.SESSION_START.rawName,
                feature = "AppLifecycle",
                status = ActivityStatus.SUCCESS.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = appVersion
            ),
            immediate = true
        )
    }

    fun trackSessionHeartbeat(context: Context) {
        SessionManager.heartbeat(context)
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.SESSION_HEARTBEAT.rawName,
                feature = "AppLifecycle",
                status = ActivityStatus.SUCCESS.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context)
            )
        )
    }

    fun trackSessionEnd(context: Context) {
        val duration = System.currentTimeMillis() - SessionManager.sessionStartTime
        val sessionId = SessionManager.currentSessionId
        SessionManager.endSession(context)
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.SESSION_END.rawName,
                feature = "AppLifecycle",
                status = ActivityStatus.SUCCESS.rawName,
                durationMs = duration,
                sessionId = sessionId,
                appVersion = getAppVersion(context)
            ),
            immediate = true
        )
    }

    fun trackAuth(context: Context, eventType: ActivityEventType, success: Boolean, method: String = "google") {
        trackEvent(
            context,
            ActivityEvent(
                eventType = eventType.rawName,
                feature = "Authentication",
                status = if (success) ActivityStatus.SUCCESS.rawName else ActivityStatus.FAILURE.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                metadata = mapOf("method" to method)
            ),
            immediate = true
        )
    }

    fun trackChatRequest(
        context: Context,
        status: ActivityStatus,
        durationMs: Long? = null,
        model: String? = null,
        errorCategory: String? = null
    ) {
        val meta = mutableMapOf<String, Any>()
        model?.let { meta["model"] = it }
        trackEvent(
            context,
            ActivityEvent(
                eventType = when (status) {
                    ActivityStatus.SUCCESS -> ActivityEventType.CHAT_REQUEST_COMPLETE.rawName
                    ActivityStatus.FAILURE -> ActivityEventType.CHAT_REQUEST_FAIL.rawName
                    else -> ActivityEventType.CHAT_REQUEST_START.rawName
                },
                feature = "Chat",
                status = status.rawName,
                durationMs = durationMs,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                errorCategory = errorCategory,
                metadata = sanitizeMetadata(meta)
            )
        )
    }

    fun trackAiRequest(
        context: Context,
        status: ActivityStatus,
        durationMs: Long? = null,
        model: String? = null,
        errorCategory: String? = null
    ) {
        val meta = mutableMapOf<String, Any>()
        model?.let { meta["model"] = it }
        trackEvent(
            context,
            ActivityEvent(
                eventType = when (status) {
                    ActivityStatus.SUCCESS -> ActivityEventType.AI_REQUEST_COMPLETE.rawName
                    ActivityStatus.FAILURE -> ActivityEventType.AI_REQUEST_FAIL.rawName
                    else -> ActivityEventType.AI_REQUEST_START.rawName
                },
                feature = "AI",
                status = status.rawName,
                durationMs = durationMs,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                errorCategory = errorCategory,
                metadata = sanitizeMetadata(meta)
            )
        )
    }

    fun trackVoiceSessionStart(context: Context, model: String? = null) {
        val meta = mutableMapOf<String, Any>()
        model?.let { meta["model"] = it }
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.VOICE_SESSION_START.rawName,
                feature = "VoiceAssistant",
                status = ActivityStatus.IN_PROGRESS.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                metadata = sanitizeMetadata(meta)
            )
        )
    }

    fun trackVoiceSessionEnd(
        context: Context,
        durationMs: Long,
        turnsCount: Int = 0,
        status: ActivityStatus = ActivityStatus.SUCCESS
    ) {
        val meta = mapOf("turnsCount" to turnsCount)
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.VOICE_SESSION_END.rawName,
                feature = "VoiceAssistant",
                status = status.rawName,
                durationMs = durationMs,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                metadata = sanitizeMetadata(meta)
            ),
            immediate = true
        )
    }

    fun trackToolExecution(
        context: Context,
        toolName: String,
        status: ActivityStatus,
        durationMs: Long? = null,
        errorCategory: String? = null
    ) {
        trackEvent(
            context,
            ActivityEvent(
                eventType = when (status) {
                    ActivityStatus.SUCCESS -> ActivityEventType.TOOL_EXECUTION_SUCCESS.rawName
                    ActivityStatus.FAILURE -> ActivityEventType.TOOL_EXECUTION_FAIL.rawName
                    else -> ActivityEventType.TOOL_EXECUTION_START.rawName
                },
                feature = "Tool:$toolName",
                status = status.rawName,
                durationMs = durationMs,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                errorCategory = errorCategory,
                metadata = mapOf("toolName" to toolName)
            )
        )
    }

    fun trackTaskOperation(
        context: Context,
        taskType: String,
        action: String,
        status: ActivityStatus,
        errorCategory: String? = null
    ) {
        trackEvent(
            context,
            ActivityEvent(
                eventType = when (action.uppercase(Locale.ROOT)) {
                    "SCHEDULE" -> ActivityEventType.TASK_SCHEDULED.rawName
                    "CANCEL" -> ActivityEventType.TASK_CANCELLED.rawName
                    "EXECUTE" -> ActivityEventType.TASK_EXECUTED.rawName
                    else -> ActivityEventType.TASK_SCHEDULED.rawName
                },
                feature = "TaskManager",
                status = status.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                errorCategory = errorCategory,
                metadata = mapOf("taskType" to taskType, "action" to action)
            )
        )
    }

    fun trackMemoryOperation(
        context: Context,
        operation: String,
        count: Int = 1,
        status: ActivityStatus = ActivityStatus.SUCCESS
    ) {
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.MEMORY_OP.rawName,
                feature = "Memory",
                status = status.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                metadata = mapOf("operation" to operation, "itemCount" to count)
            )
        )
    }

    fun trackScreenView(context: Context, screenName: String) {
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.SCREEN_VIEW.rawName,
                feature = "Screen:$screenName",
                status = ActivityStatus.SUCCESS.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                metadata = mapOf("screenName" to screenName)
            )
        )
    }

    fun trackError(
        context: Context,
        category: String,
        message: String,
        feature: String
    ) {
        val sanitizedMsg = message.take(150)
        trackEvent(
            context,
            ActivityEvent(
                eventType = ActivityEventType.ERROR_REPORT.rawName,
                feature = feature,
                status = ActivityStatus.FAILURE.rawName,
                sessionId = SessionManager.currentSessionId,
                appVersion = getAppVersion(context),
                errorCategory = category,
                metadata = mapOf("errorMessage" to sanitizedMsg)
            ),
            immediate = true
        )
    }
}
