package com.example.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val ragEngine = com.example.rag.engine.RagEngine.getInstance(application)
    private val memoryRepository = com.example.memory.data.MemoryRepository.getInstance(application)

    private val _uiState = MutableStateFlow(loadCurrentSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val events: Flow<SettingsUiEvent> = _events.receiveAsFlow()

    init {
        if (!settingsManager.modelRoutingMode.equals("AUTO", ignoreCase = true)) {
            settingsManager.modelRoutingMode = "AUTO"
        }
        viewModelScope.launch(Dispatchers.IO) {
            ragEngine.diagnosticsFlow.collect { diag ->
                _uiState.update {
                    it.copy(
                        ragMemoriesIndexed = diag.memoriesIndexed,
                        ragChunksIndexed = diag.chunksIndexed,
                        ragStatus = diag.status
                    )
                }
            }
        }
    }

    private fun loadCurrentSettings(): SettingsUiState {
        return SettingsUiState(
            geminiApiKey = settingsManager.geminiApiKey,
            uiAgentApiKey = settingsManager.uiAgentApiKey,
            uiAgentProvider = settingsManager.uiAgentProvider,
            uiAgentModel = settingsManager.uiAgentModel,
            liveVoiceModelName = settingsManager.liveVoiceModelName,
            modelRoutingMode = "AUTO",
            liveVoiceName = settingsManager.liveVoiceName,
            isLiveVoiceMode = settingsManager.isLiveVoiceMode,
            isThinkingObservabilityEnabled = settingsManager.isThinkingObservabilityEnabled,
            isAgenticModeEnabled = settingsManager.isAgenticModeEnabled,
            agenticMaxIterations = settingsManager.agenticMaxIterations,
            agenticDailyBudget = settingsManager.agenticDailyBudget,
            agenticIterationsUsedToday = settingsManager.agenticIterationsUsedToday,
            userName = settingsManager.userName,
            botPersona = settingsManager.botPersona,
            languagePreference = settingsManager.languagePreference,
            isTelegramBotEnabled = settingsManager.isTelegramBotEnabled,
            telegramBotToken = settingsManager.telegramBotToken,
            telegramChatId = settingsManager.telegramChatId,
            isMaxEnabled = settingsManager.isMaxEnabled,
            isRagEnabled = settingsManager.isRagEnabled,
            isSemanticSearchEnabled = settingsManager.isSemanticSearchEnabled,
            isHybridSearchEnabled = settingsManager.isHybridSearchEnabled,
            ragTopK = settingsManager.ragTopK,
            ragMinRelevance = settingsManager.ragMinRelevance,
            ragMaxContextCharacters = settingsManager.ragMaxContextCharacters,
            ragEmbeddingProvider = settingsManager.ragEmbeddingProvider,
            isAutoIndexMemoriesEnabled = settingsManager.isAutoIndexMemoriesEnabled,
            isDefaultAssistant = com.example.voice.assistant.SystemAssistantManager.isDefaultAssistant(getApplication()),
            wakeWordStatus = com.example.voice.assistant.SystemAssistantManager.getWakeWordStatusDescription()
        )
    }

    fun checkDefaultAssistantStatus() {
        val isDefault = com.example.voice.assistant.SystemAssistantManager.isDefaultAssistant(getApplication())
        _uiState.update { it.copy(isDefaultAssistant = isDefault) }
    }

    fun updateGeminiApiKey(key: String) {
        _uiState.update { it.copy(geminiApiKey = key) }
        settingsManager.geminiApiKey = key
    }

    fun updateUiAgentApiKey(key: String) {
        _uiState.update { it.copy(uiAgentApiKey = key) }
        settingsManager.uiAgentApiKey = key
    }

    fun updateUiAgentProvider(provider: String) {
        _uiState.update { it.copy(uiAgentProvider = provider) }
        settingsManager.uiAgentProvider = provider
    }

    fun updateUiAgentModel(model: String) {
        _uiState.update { it.copy(uiAgentModel = model) }
        settingsManager.uiAgentModel = model
    }

    fun updateLiveVoiceName(voiceName: String) {
        _uiState.update { it.copy(liveVoiceName = voiceName) }
        settingsManager.liveVoiceName = voiceName
    }

    fun updateLiveVoiceModelName(modelName: String) {
        _uiState.update { it.copy(liveVoiceModelName = modelName) }
        settingsManager.liveVoiceModelName = modelName
    }

    fun updateModelRoutingMode(mode: String) {
        val normalized = mode.trim().uppercase()
        _uiState.update { it.copy(modelRoutingMode = normalized) }
        settingsManager.modelRoutingMode = normalized
        val resolvedModel = when (normalized) {
            "DEEP" -> com.example.network.model.GeminiLiveModels.DEEP
            else -> com.example.network.model.GeminiLiveModels.FAST
        }
        _uiState.update { it.copy(liveVoiceModelName = resolvedModel) }
        settingsManager.liveVoiceModelName = resolvedModel
    }

    fun updateIsLiveVoiceMode(enabled: Boolean) {
        _uiState.update { it.copy(isLiveVoiceMode = enabled) }
        settingsManager.isLiveVoiceMode = enabled
    }

    fun updateIsThinkingObservabilityEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isThinkingObservabilityEnabled = enabled) }
        settingsManager.isThinkingObservabilityEnabled = enabled
    }

    fun updateIsAgenticModeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAgenticModeEnabled = enabled) }
        settingsManager.isAgenticModeEnabled = enabled
    }

    fun updateAgenticMaxIterations(iterations: Int) {
        _uiState.update { it.copy(agenticMaxIterations = iterations) }
        settingsManager.agenticMaxIterations = iterations
    }

    fun updateAgenticDailyBudget(budget: Int) {
        _uiState.update { it.copy(agenticDailyBudget = budget) }
        settingsManager.agenticDailyBudget = budget
    }

    fun updateUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
        settingsManager.userName = name
    }

    fun updateBotPersona(persona: String) {
        _uiState.update { it.copy(botPersona = persona) }
        settingsManager.botPersona = persona
    }

    fun updateLanguagePreference(language: String) {
        _uiState.update { it.copy(languagePreference = language) }
        settingsManager.languagePreference = language
    }

    fun updateTelegramBotEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isTelegramBotEnabled = enabled) }
        settingsManager.isTelegramBotEnabled = enabled
    }

    fun updateTelegramBotToken(token: String) {
        _uiState.update { it.copy(telegramBotToken = token) }
        settingsManager.telegramBotToken = token
    }

    fun updateTelegramChatId(chatId: String) {
        _uiState.update { it.copy(telegramChatId = chatId) }
        settingsManager.telegramChatId = chatId
    }

    fun updateIsMaxEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isMaxEnabled = enabled) }
        settingsManager.isMaxEnabled = enabled
        viewModelScope.launch(Dispatchers.IO) {
            MaxRuntimeManager.setGlobalEnabled(getApplication(), enabled)
        }
    }

    fun updateIsRagEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isRagEnabled = enabled) }
        settingsManager.isRagEnabled = enabled
    }

    fun updateIsSemanticSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isSemanticSearchEnabled = enabled) }
        settingsManager.isSemanticSearchEnabled = enabled
    }

    fun updateIsHybridSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isHybridSearchEnabled = enabled) }
        settingsManager.isHybridSearchEnabled = enabled
    }

    fun updateRagTopK(topK: Int) {
        _uiState.update { it.copy(ragTopK = topK) }
        settingsManager.ragTopK = topK
    }

    fun updateRagMinRelevance(minRelevance: Float) {
        _uiState.update { it.copy(ragMinRelevance = minRelevance) }
        settingsManager.ragMinRelevance = minRelevance
    }

    fun updateRagEmbeddingProvider(provider: String) {
        _uiState.update { it.copy(ragEmbeddingProvider = provider) }
        settingsManager.ragEmbeddingProvider = provider
    }

    fun updateIsAutoIndexMemoriesEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAutoIndexMemoriesEnabled = enabled) }
        settingsManager.isAutoIndexMemoriesEnabled = enabled
    }

    fun rebuildRagIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allMemories = memoryRepository.getAll()
                val count = ragEngine.rebuildIndex(allMemories)
                _events.send(SettingsUiEvent.ShowToast("RAG index rebuilt ($count memories indexed)"))
            } catch (e: Exception) {
                _events.send(SettingsUiEvent.ShowToast("Rebuild failed: ${e.message}"))
            }
        }
    }

    fun clearRagIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ragEngine.clearIndex()
                _events.send(SettingsUiEvent.ShowToast("RAG index cleared"))
            } catch (e: Exception) {
                _events.send(SettingsUiEvent.ShowToast("Clear failed: ${e.message}"))
            }
        }
    }

    fun saveAll() {
        viewModelScope.launch {
            _events.send(SettingsUiEvent.ShowToast("Settings saved successfully"))
        }
    }
}
