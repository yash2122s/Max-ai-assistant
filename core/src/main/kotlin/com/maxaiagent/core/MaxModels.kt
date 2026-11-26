package com.maxaiagent.core

import kotlinx.serialization.Serializable

/**
 * Authoritative assistant runtime state shared between MAX Android and MAX Desktop.
 */
@Serializable
enum class MaxAssistantState(val label: String) {
    OFF("OFF"),
    OFFLINE("OFFLINE"),
    CONNECTING("CONNECTING"),
    ONLINE("ONLINE"),
    LISTENING("LISTENING"),
    THINKING("THINKING"),
    SPEAKING("SPEAKING"),
    ERROR("ERROR")
}

/**
 * Dual-model / adaptive routing mode for MAX AI.
 */
@Serializable
enum class MaxOperatingMode(val label: String, val modelId: String) {
    AUTO("AUTO", "gemini-3.8-live-extended-thinking"),
    FAST("FAST", "gemini-3.8-live"),
    DEEP("DEEP", "gemini-3.8-live-extended-thinking")
}

@Serializable
enum class MaxIntentKind {
    HOME_NAVIGATION,
    STATUS_QUERY,
    TOGGLE_RUNTIME,
    WINDOWS_BRIDGE_PING,
    CRYPTO_VAULT_HASH,
    GENERAL_PROMPT
}

@Serializable
data class MaxNormalizedCommand(
    val rawText: String,
    val normalizedText: String,
    val intent: MaxIntentKind,
    val fingerprint: String
)

@Serializable
data class MaxLogEntry(
    val timestamp: String,
    val source: String,
    val message: String,
    val isError: Boolean = false
)

@Serializable
data class MaxBridgeStatus(
    val reachable: Boolean,
    val endpoint: String,
    val detail: String
)
