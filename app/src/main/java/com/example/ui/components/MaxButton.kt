package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

enum class MaxButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    DANGER,
    ROYAL_GOLD,
    GHOST
}

/**
 * iOS-Native Tactile Button.
 * Replaces Material ripple with smooth spring scale compression and subtle opacity dimming.
 */
@Composable
fun MaxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    variant: MaxButtonVariant = MaxButtonVariant.PRIMARY,
    height: Dp = MaxDimensions.buttonHeightMd,
    leadingIcon: (@Composable RowScope.() -> Unit)? = null,
    trailingIcon: (@Composable RowScope.() -> Unit)? = null
) {
    val cornerShape = RoundedCornerShape(MaxDimensions.radiusMd)

    val (bgModifier, contentColor, borderModifier) = when (variant) {
        MaxButtonVariant.PRIMARY -> Triple(
            Modifier.background(
                Brush.horizontalGradient(
                    colors = listOf(MaxPrimaryRoyal, Color(0xFF0070E0))
                )
            ),
            Color.White,
            Modifier.border(MaxDimensions.borderHairline, Color(0x6600D2FF), cornerShape)
        )
        MaxButtonVariant.ROYAL_GOLD -> Triple(
            Modifier.background(
                Brush.horizontalGradient(
                    colors = listOf(MaxRoyalGold, Color(0xFFFFB800))
                )
            ),
            Color(0xFF07090E),
            Modifier.border(MaxDimensions.borderHairline, Color(0x66FFE600), cornerShape)
        )
        MaxButtonVariant.SECONDARY -> Triple(
            Modifier.background(MaxSurfaceElevated),
            MaxTextPrimary,
            Modifier.border(MaxDimensions.borderHairline, MaxBorderHairline, cornerShape)
        )
        MaxButtonVariant.OUTLINE -> Triple(
            Modifier.background(Color.Transparent),
            MaxPrimaryRoyal,
            Modifier.border(MaxDimensions.borderStandard, MaxPrimaryRoyal.copy(alpha = 0.6f), cornerShape)
        )
        MaxButtonVariant.DANGER -> Triple(
            Modifier.background(MaxDestructiveApple.copy(alpha = 0.15f)),
            MaxDestructiveApple,
            Modifier.border(MaxDimensions.borderHairline, MaxDestructiveApple.copy(alpha = 0.4f), cornerShape)
        )
        MaxButtonVariant.GHOST -> Triple(
            Modifier.background(Color.Transparent),
            MaxTextSecondary,
            Modifier
        )
    }

    val finalAlpha = if (enabled && !isLoading) 1f else 0.45f

    Box(
        modifier = modifier
            .height(height)
            .clip(cornerShape)
            .then(borderModifier)
            .then(bgModifier)
            .iosPressEffect(
                enabled = enabled && !isLoading,
                scalePressed = 0.96f,
                alphaPressed = 0.85f,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = contentColor,
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                leadingIcon?.invoke(this)
                Text(
                    text = text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = contentColor.copy(alpha = finalAlpha),
                    letterSpacing = (-0.2).sp
                )
                trailingIcon?.invoke(this)
            }
        }
    }
}

