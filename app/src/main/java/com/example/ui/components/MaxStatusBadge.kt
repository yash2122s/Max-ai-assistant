package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.ConnectionState
import com.example.ui.theme.*

@Composable
fun MaxStatusBadge(
    connectionState: ConnectionState,
    isMaxEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val (bgColor, dotColor, text) = when {
        !isMaxEnabled -> Triple(Color(0xFF2D1B20), MaxError, "MAX OFF")
        connectionState == ConnectionState.CONNECTED -> Triple(Color(0xFF132A1C), MaxSuccess, "LIVE ONLINE")
        connectionState == ConnectionState.CONNECTING -> Triple(Color(0xFF2C2413), AccentAmber, "CONNECTING...")
        else -> Triple(Color(0xFF1B232D), MaxTextMuted, "STANDALONE")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusPill))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = dotColor,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun MaxStatusBadge(
    label: String,
    color: Color = MaxPrimary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusPill))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.5.sp
        )
    }
}
