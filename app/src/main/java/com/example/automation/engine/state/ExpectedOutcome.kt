package com.example.automation.engine.state

/**
 * Action-specific verification contracts ensuring the orchestrator does not rely on arbitrary sleeps.
 */
sealed interface ExpectedOutcome {
    data class PackageForeground(val expectedPackage: String, val timeoutMs: Long = 5000L) : ExpectedOutcome
    data class ElementPresent(
        val targetQuery: String,
        val timeoutMs: Long = 2000L,
        val expectedPackage: String? = null
    ) : ExpectedOutcome
    data class TextContentEquals(
        val expectedText: String,
        val timeoutMs: Long = 2000L,
        val expectedPackage: String? = null
    ) : ExpectedOutcome
    data class SystemSettingState(val settingKey: String, val expectedValue: Any, val timeoutMs: Long = 3000L) : ExpectedOutcome
    data class MessageSent(val contactName: String, val timeoutMs: Long = 3000L) : ExpectedOutcome
    data class PlaybackActive(val timeoutMs: Long = 3000L) : ExpectedOutcome
}
