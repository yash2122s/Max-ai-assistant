package com.example.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.MaxAssistantOrb
import com.example.ui.theme.*
import kotlinx.coroutines.flow.StateFlow

internal fun phaseColor(phase: AssistantPhase): Color = when (phase) {
    AssistantPhase.FAILED, AssistantPhase.BLOCKED -> MaxIOSColors.SystemRed
    AssistantPhase.UNVERIFIED, AssistantPhase.WAITING_FOR_APPROVAL,
    AssistantPhase.WAITING_FOR_INPUT, AssistantPhase.CANCELLING -> MaxIOSColors.SystemAmber
    AssistantPhase.COMPLETED -> MaxIOSColors.SystemGreen
    AssistantPhase.STREAMING, AssistantPhase.SPEAKING, AssistantPhase.LISTENING -> MaxAccentCyan
    AssistantPhase.IDLE, AssistantPhase.CANCELLED -> MaxAccentCyan
    else -> MaxAccentCyan
}

@Composable
internal fun LiveAssistantOrb(presentation: AssistantPresentation, rms: StateFlow<Float>, onMicClick: (() -> Unit)? = null) {
    // Audio observations are confined to this small composable, not the feed.
    val amplitude by rms.collectAsStateWithLifecycle()
    val isRecording = presentation.phase == AssistantPhase.LISTENING
    val isAiSpeaking = presentation.phase == AssistantPhase.SPEAKING
    val isThinking = presentation.phase == AssistantPhase.THINKING ||
        presentation.phase == AssistantPhase.CONNECTING ||
        presentation.phase == AssistantPhase.STREAMING ||
        presentation.phase == AssistantPhase.TOOL_EXECUTION

    com.example.ui.components.JarvisNeuralOrb(
        isActive = true,
        isRecording = isRecording,
        isAiSpeaking = isAiSpeaking,
        isThinking = isThinking,
        rms = amplitude,
        orbSize = 170.dp,
        showSuggestions = false
    )
}

@Composable
internal fun AssistantStatusStrip(
    presentation: AssistantPresentation,
    enabled: Boolean = true,
    onDismissError: (() -> Unit)? = null
) {
    val label = if (enabled) presentation.label else "MAX is paused"
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(phaseColor(presentation.phase)))
            AnimatedContent(label, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "Assistant status", modifier = Modifier.weight(1f)) { value ->
                Text(value, color = MaxColors.extra.textSecondary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth())
            }
            if (onDismissError != null) IconButton(onClick = onDismissError) {
                Icon(Icons.Rounded.Close, "Dismiss error", tint = MaxColors.extra.textSecondary, modifier = Modifier.size(MaxIconTokens.Small))
            }
        }
        if (!presentation.detail.isNullOrBlank() && enabled) {
            Text(presentation.detail, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
