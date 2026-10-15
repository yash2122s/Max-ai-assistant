package com.example.ui.components

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*

/**
 * MAX AI Core State Architecture.
 * Drives real-time state transformations for the MAX Neural Core visualizer.
 */
enum class CoreState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    VISION,
    EXECUTING,
    ERROR;

    val label: String
        get() = when (this) {
            IDLE -> "SYSTEM STANDBY"
            LISTENING -> "LISTENING..."
            THINKING -> "PROCESSING..."
            SPEAKING -> "MAX SPEAKING..."
            VISION -> "VISION SCAN..."
            EXECUTING -> "EXECUTING TASK..."
            ERROR -> "SYSTEM ERROR"
        }

    val primaryColor: Color
        get() = when (this) {
            IDLE -> MaxSemanticColors.royalGold
            LISTENING -> MaxSemanticColors.accentCyan
            THINKING -> MaxSemanticColors.accentPink
            SPEAKING -> MaxSemanticColors.primaryRoyal
            VISION -> MaxSemanticColors.success
            EXECUTING -> MaxSemanticColors.info
            ERROR -> MaxSemanticColors.error
        }

    val secondaryColor: Color
        get() = when (this) {
            IDLE -> MaxSemanticColors.accentCyan
            LISTENING -> MaxSemanticColors.primaryRoyal
            THINKING -> MaxSemanticColors.warning
            SPEAKING -> MaxSemanticColors.primaryRoyal
            VISION -> MaxSemanticColors.successSurface
            EXECUTING -> MaxSemanticColors.accentCyan
            ERROR -> MaxSemanticColors.errorSurface
        }
}
