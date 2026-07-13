package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PrimaryLight

@Composable
fun SoundWave(
    rms: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 4
    val animationScale = remember { Animatable(0f) }

    LaunchedEffect(isRecording, rms) {
        if (isRecording || rms > 0.01f) {
            animationScale.animateTo(
                targetValue = rms.coerceIn(0.15f, 1.0f),
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)
            )
        } else {
            animationScale.animateTo(0.05f)
        }
    }

    Row(
        modifier = modifier.height(32.dp).width(36.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val infiniteTransition = rememberInfiniteTransition(label = "bar_$i")
            val wavePhase by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durations[i], easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave_phase"
            )

            val currentScale = animationScale.value * wavePhase
            val heightDp = (8.dp + (24.dp * currentScale)).coerceAtMost(32.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(heightDp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PrimaryLight)
            )
        }
    }
}

private val durations = listOf(450, 650, 520, 720)
