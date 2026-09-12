package com.example.ui.routines

data class RoutineStepModel(
    val action: String,
    val description: String
)

data class RoutineItemModel(
    val name: String,
    val isBuiltIn: Boolean,
    val steps: List<RoutineStepModel>
)

data class RoutinesUiState(
    val routines: List<RoutineItemModel> = emptyList(),
    val isExecuting: Boolean = false,
    val currentlyRunningRoutine: String? = null,
    val isCreatingNew: Boolean = false,
    val lastExecutionLog: String? = null
)

sealed interface RoutinesUiEvent {
    data class ShowToast(val message: String) : RoutinesUiEvent
}
