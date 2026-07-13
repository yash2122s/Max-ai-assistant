package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GradientBorderColors

@Composable
fun Modifier.glowBorder(
    shape: RoundedCornerShape = RoundedCornerShape(28.dp)
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glow_animation")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_offset"
    )

    // Flowing neon linear gradient using repeated tile mode
    val brush = Brush.linearGradient(
        colors = GradientBorderColors,
        start = Offset(offset, offset),
        end = Offset(offset + 300f, offset + 300f),
        tileMode = TileMode.Repeated
    )

    return this.border(2.dp, brush, shape)
}
