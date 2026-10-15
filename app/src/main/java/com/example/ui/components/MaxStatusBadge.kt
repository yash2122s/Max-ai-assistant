package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.network.ConnectionState
import com.example.ui.theme.*

@Composable
fun MaxStatusBadge(
    connectionState: ConnectionState,
    isMaxEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val (bgColor, dotColor, text, isLive) = when {
        !isMaxEnabled -> Quadruple(MaxSemanticColors.destructiveSurface, MaxSemanticColors.destructive, "MAX OFF", false)
        connectionState == ConnectionState.CONNECTED -> Quadruple(MaxSemanticColors.successSurface, MaxSemanticColors.success, "AUTONOMOUS LIVE", true)
        connectionState == ConnectionState.CONNECTING -> Quadruple(MaxSemanticColors.warningSurface, MaxSemanticColors.warning, "CONNECTING...", true)
        else -> Quadruple(MaxSemanticColors.surfaceVariant, MaxSemanticColors.textMuted, "STANDALONE", false)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by if (isLive) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusPill))
            .background(bgColor)
            .border(MaxDimensions.borderHairline, dotColor.copy(alpha = 0.35f), RoundedCornerShape(MaxDimensions.radiusPill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaxType.Caption,
            fontWeight = FontWeight.Bold,
            color = dotColor
        )
    }
}

@Composable
fun MaxStatusBadge(
    label: String,
    color: Color = MaxSemanticColors.accentCyan,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusPill))
            .background(color.copy(alpha = 0.12f))
            .border(MaxDimensions.borderHairline, color.copy(alpha = 0.30f), RoundedCornerShape(MaxDimensions.radiusPill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaxType.Caption,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

