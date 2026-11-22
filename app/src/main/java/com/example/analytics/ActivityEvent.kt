package com.example.analytics

enum class ActivityEventType(val rawName: String) {
    SESSION_START("session_start"),
    SESSION_HEARTBEAT("session_heartbeat"),
    SESSION_END("session_end"),
    AUTH_LOGIN("auth_login"),
    AUTH_LOGOUT("auth_logout"),
    AUTH_REGISTER("auth_register"),
    CHAT_REQUEST_START("chat_request_start"),
    CHAT_REQUEST_COMPLETE("chat_request_complete"),
    CHAT_REQUEST_FAIL("chat_request_fail"),
    AI_REQUEST_START("ai_request_start"),
    AI_REQUEST_COMPLETE("ai_request_complete"),
    AI_REQUEST_FAIL("ai_request_fail"),
    VOICE_SESSION_START("voice_session_start"),
    VOICE_SESSION_END("voice_session_end"),
    TOOL_EXECUTION_START("tool_execution_start"),
    TOOL_EXECUTION_SUCCESS("tool_execution_success"),
    TOOL_EXECUTION_FAIL("tool_execution_fail"),
    TASK_SCHEDULED("task_scheduled"),
    TASK_CANCELLED("task_cancelled"),
    TASK_EXECUTED("task_executed"),
    TASK_FAILED("task_failed"),
    MEMORY_OP("memory_op"),
    SCREEN_VIEW("screen_view"),
    ERROR_REPORT("error_report");

    companion object {
        fun fromRaw(raw: String): ActivityEventType =
            values().firstOrNull { it.rawName.equals(raw, ignoreCase = true) } ?: ERROR_REPORT
    }
}

enum class ActivityStatus(val rawName: String) {
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    IN_PROGRESS("IN_PROGRESS"),
    CANCELLED("CANCELLED"),
    TIMEOUT("TIMEOUT")
}

data class ActivityEvent(
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val eventType: String,
    val feature: String,
    val status: String,
    val durationMs: Long? = null,
    val sessionId: String,
    val appVersion: String,
    val platform: String = "Android",
    val errorCategory: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val clientTimestamp: Long = System.currentTimeMillis()
) {
    fun toIngestMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "eventId" to eventId,
            "eventType" to eventType,
            "feature" to feature,
            "status" to status,
            "sessionId" to sessionId,
            "appVersion" to appVersion,
            "platform" to platform,
            "clientTimestamp" to clientTimestamp
        )
        durationMs?.let { map["durationMs"] = it }
        errorCategory?.let { map["errorCategory"] = it }
        if (metadata.isNotEmpty()) {
            map["metadata"] = metadata
        }
        return map
    }
}
