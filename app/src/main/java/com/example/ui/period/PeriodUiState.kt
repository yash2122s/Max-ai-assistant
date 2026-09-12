package com.example.ui.period

import com.example.data.local.PeriodLog

data class PeriodPrediction(
    val avgDurationDays: Int = 5,
    val avgCycleDays: Int = 28,
    val nextStartDateStr: String? = null,
    val daysUntilNext: Int? = null,
    val predictedOvulationStr: String? = null,
    val fertileWindowStr: String? = null,
    val currentPhase: String = "No Data Available",
    val phaseDescription: String = "Add logs to calculate.",
    val isBleeding: Boolean = false,
    val isFertile: Boolean = false
)

data class PeriodUiState(
    val logs: List<PeriodLog> = emptyList(),
    val prediction: PeriodPrediction = PeriodPrediction(),
    val isLoading: Boolean = false,
    val isCreatingNew: Boolean = false,
    val selectedLogForEdit: PeriodLog? = null
)

sealed interface PeriodUiEvent {
    data class ShowToast(val message: String) : PeriodUiEvent
}
