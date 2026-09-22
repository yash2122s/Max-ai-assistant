package com.example.ui.skills

import com.example.automation.skills.model.SkillDefinition

enum class PlatformSupport(val label: String) {
    ANDROID_ONLY("ANDROID"),
    WINDOWS_ONLY("WINDOWS"),
    BOTH("BOTH")
}

data class SkillItemUiModel(
    val skill: SkillDefinition,
    val skillId: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val platformSupport: PlatformSupport,
    val isEnabled: Boolean,
    val stepCount: Int,
    val requiredCapabilities: List<String>,
    val inputsCount: Int
)

data class PendingImportConflict(
    val existingSkill: SkillDefinition,
    val newSkill: SkillDefinition,
    val rawContent: String
)

data class SkillsUiState(
    val isLoading: Boolean = false,
    val installedSkills: List<SkillItemUiModel> = emptyList(),
    val selectedSkillForDetails: SkillItemUiModel? = null,
    val pendingImportConflict: PendingImportConflict? = null,
    val errorDialogMessage: String? = null,
    val deleteConfirmationSkill: SkillItemUiModel? = null
)

sealed interface SkillsUiEvent {
    data class ShowToast(val message: String) : SkillsUiEvent
    data class ShowSnackbar(val message: String) : SkillsUiEvent
}
