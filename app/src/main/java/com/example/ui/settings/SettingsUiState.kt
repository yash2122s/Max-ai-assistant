package com.example.ui.settings

data class SettingsUiState(
    val geminiApiKey: String = "",
    val uiAgentApiKey: String = "",
    val uiAgentProvider: String = "openrouter",
    val uiAgentModel: String = "stealth/ox-alpha",
    val liveVoiceModelName: String = "gemini-3.8-live",
    val modelRoutingMode: String = "AUTO",
    val liveVoiceName: String = "Aoede",
    val isLiveVoiceMode: Boolean = true,
    val isThinkingObservabilityEnabled: Boolean = true,
    val isAgenticModeEnabled: Boolean = false,
    val agenticMaxIterations: Int = 8,
    val agenticDailyBudget: Int = 50,
    val agenticIterationsUsedToday: Int = 0,
    val userName: String = "",
    val botPersona: String = "Human Type Talking",
    val languagePreference: String = "Tinglish",
    val isTelegramBotEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val isMaxEnabled: Boolean = true,
    val isRagEnabled: Boolean = true,
    val isSemanticSearchEnabled: Boolean = true,
    val isHybridSearchEnabled: Boolean = true,
    val ragTopK: Int = 5,
    val ragMinRelevance: Float = 0.35f,
    val ragMaxContextCharacters: Int = 6000,
    val ragEmbeddingProvider: String = "local",
    val isAutoIndexMemoriesEnabled: Boolean = true,
    val ragMemoriesIndexed: Int = 0,
    val ragChunksIndexed: Int = 0,
    val ragStatus: String = "READY",
    val isSaved: Boolean = false,
    val saveMessage: String? = null,
    val isDefaultAssistant: Boolean = false,
    val wakeWordStatus: String = "Requires system support"
)

sealed interface SettingsUiEvent {
    data class ShowToast(val message: String) : SettingsUiEvent
}
