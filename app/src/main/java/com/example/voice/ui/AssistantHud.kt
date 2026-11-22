package com.example.voice.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MaxIOSColors
import com.example.ui.theme.MaxTheme
import com.example.voice.assistant.AssistantVoiceController
import com.example.voice.assistant.SessionState

/**
 * Apple Intelligence (iOS 18+) Siri-style Assistant HUD.
 * Completely eliminates bulky bottom sheets, dark scrims, and obtrusive cards.
 * Provides a pure, translucent edge glow around the entire perimeter that pulses
 * dynamically with voice RMS and transitions across LISTENING, THINKING, and SPEAKING states.
 * Underneath, the user's active screen/app remains 100% visible.
 * Features an ultra-minimal floating frosted capsule at the bottom for captions and responses.
 */
@Composable
fun AssistantHud(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = remember { AssistantVoiceController() }
    val uiState by controller.uiState.collectAsState()

    // Determine current Siri glow state
    val siriState = remember(uiState.isRecording, uiState.sessionState, uiState.isThinking, uiState.isAiSpeaking) {
        when {
            uiState.isRecording || uiState.sessionState == SessionState.LISTENING -> SiriGlowState.LISTENING
            uiState.isThinking || uiState.sessionState == SessionState.WAITING_FOR_MODEL || uiState.sessionState == SessionState.TOOL_EXECUTION -> SiriGlowState.THINKING
            uiState.isAiSpeaking || uiState.sessionState == SessionState.STREAMING_AUDIO -> SiriGlowState.SPEAKING
            else -> SiriGlowState.IDLE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Full Display Perimeter Apple Intelligence Siri Edge Lighting
        LiquidScreenEdges(
            state = siriState,
            rms = uiState.visualizerRms
        )

        // Ultra-minimal floating capsule for captions & live status
        val showCaption = uiState.error != null ||
                uiState.activeToolName != null ||
                uiState.userTranscription.isNotBlank() ||
                uiState.geminiResponse.isNotBlank() ||
                siriState != SiriGlowState.IDLE

        AnimatedVisibility(
            visible = showCaption,
            enter = fadeIn(animationSpec = tween(300)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(200)),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 36.dp, start = 24.dp, end = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xCC0D0F17))
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // If AI is speaking, tapping stops speech; otherwise keep active
                        if (uiState.isAiSpeaking) {
                            controller.interruptSpeaking()
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(min = 120.dp, max = 340.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.error != null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaxIOSColors.SystemRed)
                            )
                            Text(
                                text = "Unable to connect",
                                color = MaxIOSColors.SystemRed,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Retry",
                                color = MaxIOSColors.SystemBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { controller.reconnect() }
                            )
                        }
                    }
                    uiState.activeToolName != null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaxIOSColors.SystemAmber)
                            )
                            Text(
                                text = "Executing ${uiState.activeToolName}…",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    uiState.geminiResponse.isNotBlank() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = uiState.geminiResponse,
                                color = Color.White,
                                fontSize = 14.5.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.isAiSpeaking) {
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .clickable { controller.interruptSpeaking() }
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        tint = MaxIOSColors.SystemRed,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Stop",
                                        color = MaxIOSColors.SystemRed,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                    uiState.userTranscription.isNotBlank() -> {
                        Text(
                            text = "“${uiState.userTranscription}”",
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    siriState == SiriGlowState.THINKING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaxIOSColors.SystemPurple)
                            )
                            Text(
                                text = if (uiState.thoughtSummary.isNotBlank()) uiState.thoughtSummary.take(40) + "…" else "Thinking…",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    siriState == SiriGlowState.LISTENING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaxIOSColors.SystemBlue)
                            )
                            Text(
                                text = "Listening…",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    else -> {
                        // Minimal ambient indicator
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Assistant HUD Preview", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AssistantHudPreview() {
    MaxTheme(darkTheme = true) {
        AssistantHud(
            onDismiss = {}
        )
    }
}
