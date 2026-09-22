package com.example.ui.skills

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.parser.SkillParser
import com.example.automation.skills.registry.SkillRegistry
import com.example.automation.skills.validation.SkillValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillsViewModel @JvmOverloads constructor(
    application: Application,
    private val registry: SkillRegistry = SkillRegistry(application)
) : AndroidViewModel(application) {

    private val TAG = "SkillsViewModel"

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SkillsUiEvent>()
    val events: SharedFlow<SkillsUiEvent> = _events.asSharedFlow()

    init {
        loadSkills()
    }

    fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                registry.refreshFromStorage()
                val allInstalled = registry.listInstalled(includeDisabled = true)

                val uiModels = allInstalled.map { skill ->
                    val platforms = skill.supportedPlatforms.map { it.lowercase() }
                    val platformSupport = when {
                        "android" in platforms && "windows" in platforms -> PlatformSupport.BOTH
                        "android" in platforms -> PlatformSupport.ANDROID_ONLY
                        else -> PlatformSupport.WINDOWS_ONLY
                    }
                    val isEnabled = registry.isSkillEnabled(skill.skillId)

                    SkillItemUiModel(
                        skill = skill,
                        skillId = skill.skillId,
                        name = skill.name,
                        description = skill.description,
                        version = skill.version,
                        author = skill.author,
                        platformSupport = platformSupport,
                        isEnabled = isEnabled,
                        stepCount = skill.steps.size,
                        requiredCapabilities = skill.allowedCapabilities,
                        inputsCount = skill.inputs.size
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        installedSkills = uiModels,
                        // Refresh details if currently selected
                        selectedSkillForDetails = current.selectedSkillForDetails?.let { sel ->
                            uiModels.find { it.skillId == sel.skillId }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading skills: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun importSkillFromUri(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val rawContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }

                if (rawContent.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorDialogMessage = "Selected file is empty or could not be read."
                        )
                    }
                    return@launch
                }

                // 1. Parse
                val parseResult = SkillParser.parse(rawContent)
                if (!parseResult.success || parseResult.skill == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorDialogMessage = parseResult.errorMessage ?: "Failed to parse skill file. Supported formats: .json or .md with YAML frontmatter."
                        )
                    }
                    return@launch
                }

                val newSkill = parseResult.skill

                // 2. Validate
                val validation = SkillValidator.validate(newSkill, targetPlatform = "android")
                if (!validation.isValid) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorDialogMessage = "Validation Rejected: ${validation.errorSummary()}"
                        )
                    }
                    return@launch
                }

                // 3. Duplicate Detection
                val existing = registry.listInstalled(includeDisabled = true).find { it.skillId == newSkill.skillId }
                if (existing != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            pendingImportConflict = PendingImportConflict(
                                existingSkill = existing,
                                newSkill = newSkill,
                                rawContent = rawContent
                            )
                        )
                    }
                    return@launch
                }

                // 4. Clean Install
                executeInstall(newSkill, rawContent)
            } catch (e: Exception) {
                Log.e(TAG, "Exception importing skill from URI: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorDialogMessage = "Unexpected import error: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun confirmOverwrite() {
        val conflict = _uiState.value.pendingImportConflict ?: return
        _uiState.update { it.copy(pendingImportConflict = null) }
        viewModelScope.launch(Dispatchers.IO) {
            executeInstall(conflict.newSkill, conflict.rawContent)
        }
    }

    fun cancelOverwrite() {
        _uiState.update { it.copy(pendingImportConflict = null) }
    }

    private suspend fun executeInstall(skill: SkillDefinition, rawContent: String) {
        val result = registry.install(rawContent, targetPlatform = "android")
        if (result.success) {
            _events.emit(SkillsUiEvent.ShowSnackbar("Skill '${skill.name}' (v${skill.version}) imported successfully."))
            loadSkills()
        } else {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorDialogMessage = result.errorMessage ?: "Installation to storage failed."
                )
            }
        }
    }

    fun toggleSkillEnabled(skillId: String, enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                registry.enable(skillId)
            } else {
                registry.disable(skillId)
            }
            loadSkills()
        }
    }

    fun requestDelete(item: SkillItemUiModel) {
        _uiState.update { it.copy(deleteConfirmationSkill = item) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(deleteConfirmationSkill = null) }
    }

    fun confirmDelete() {
        val toDelete = _uiState.value.deleteConfirmationSkill ?: return
        _uiState.update { it.copy(deleteConfirmationSkill = null, selectedSkillForDetails = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = registry.delete(toDelete.skillId)
            if (deleted) {
                _events.emit(SkillsUiEvent.ShowSnackbar("Deleted '${toDelete.name}'."))
            } else {
                _events.emit(SkillsUiEvent.ShowToast("Failed to delete '${toDelete.name}'."))
            }
            loadSkills()
        }
    }

    fun selectSkillForDetails(item: SkillItemUiModel) {
        _uiState.update { it.copy(selectedSkillForDetails = item) }
    }

    fun dismissDetails() {
        _uiState.update { it.copy(selectedSkillForDetails = null) }
    }

    fun dismissErrorDialog() {
        _uiState.update { it.copy(errorDialogMessage = null) }
    }
}
