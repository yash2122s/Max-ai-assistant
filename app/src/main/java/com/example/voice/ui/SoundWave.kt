package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.NeonPink

@Composable
fun SoundWave(
    rms: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 6
    val animationScale = remember { Animatable(0f) }

    LaunchedEffect(isRecording, rms) {
        if (isRecording || rms > 0.01f) {
            animationScale.animateTo(
                targetValue = rms.coerceIn(0.2f, 1.0f),
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)
            )
        } else {
            animationScale.animateTo(0.08f)
        }
    }

    Row(
        modifier = modifier.height(36.dp).width(50.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val infiniteTransition = rememberInfiniteTransition(label = "bar_$i")
            val wavePhase by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durations[i % durations.size], easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave_phase"
            )

            val currentScale = animationScale.value * wavePhase
            val heightDp = (6.dp + (28.dp * currentScale)).coerceAtMost(36.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(heightDp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(NeonBlue, NeonPink)
                        )
                    )
            )
        }
    }
}

private val durations = listOf(350, 550, 420, 680, 490, 610)

