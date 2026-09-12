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
            IDLE -> JarvisGold
            LISTENING -> AccentCyan
            THINKING -> Color(0xFFF43F5E) // Neon Rose
            SPEAKING -> Color(0xFFD946EF) // Electric Magenta
            VISION -> Color(0xFF10B981) // Emerald Green
            EXECUTING -> Color(0xFF3B82F6) // Blue Pulse
            ERROR -> NeonPink
        }

    val secondaryColor: Color
        get() = when (this) {
            IDLE -> Color(0xFF00E5FF)
            LISTENING -> Blue600
            THINKING -> JarvisAmber
            SPEAKING -> AccentPurple
            VISION -> Color(0xFF064E3B)
            EXECUTING -> AccentCyan
            ERROR -> Color(0xFF990033)
        }
}
