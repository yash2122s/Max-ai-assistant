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
import com.example.ui.theme.*

enum class MaxButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    DANGER,
    ROYAL_GOLD,
    GHOST,
    GOOGLE
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
    backgroundColor: Color? = null,
    contentColor: Color? = null,
    height: Dp = MaxDimensions.buttonHeightMd,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    leadingIcon: (@Composable RowScope.() -> Unit)? = null,
    trailingIcon: (@Composable RowScope.() -> Unit)? = null
) {
    val cornerShape = RoundedCornerShape(MaxDimensions.radiusMd)

    val isDark = LocalIsDarkMode.current

    val (variantBgModifier, variantContentColor, variantBorderModifier) = when (variant) {
        MaxButtonVariant.GOOGLE -> Triple(
            Modifier.background(Color.White),
            Color(0xFF3C4043),
            Modifier.border(1.dp, Color(0xFFDADCE0), cornerShape)
        )
        MaxButtonVariant.PRIMARY -> Triple(
            Modifier.background(
                Brush.horizontalGradient(
                    colors = if (isDark) listOf(MaxPrimaryRoyal, Color(0xFF0070E0)) else listOf(NeonPink, MagentaRose)
                )
            ),
            Color.White,
            Modifier.border(MaxDimensions.borderHairline, if (isDark) Color(0x6600D2FF) else SoftRoseBorderStrong, cornerShape)
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
            Modifier.background(if (isDark) MaxSurfaceElevated else SoftRoseTint),
            if (isDark) MaxTextPrimary else SlateInkPrimary,
            Modifier.border(MaxDimensions.borderHairline, if (isDark) MaxBorderHairline else SoftRoseBorder, cornerShape)
        )
        MaxButtonVariant.OUTLINE -> Triple(
            Modifier.background(Color.Transparent),
            if (isDark) MaxPrimaryRoyal else NeonPink,
            Modifier.border(MaxDimensions.borderStandard, if (isDark) MaxPrimaryRoyal.copy(alpha = 0.6f) else NeonPink.copy(alpha = 0.6f), cornerShape)
        )
        MaxButtonVariant.DANGER -> Triple(
            Modifier.background(MaxDestructiveApple.copy(alpha = 0.15f)),
            MaxDestructiveApple,
            Modifier.border(MaxDimensions.borderHairline, MaxDestructiveApple.copy(alpha = 0.4f), cornerShape)
        )
        MaxButtonVariant.GHOST -> Triple(
            Modifier.background(Color.Transparent),
            if (isDark) MaxTextSecondary else SlateInkSecondary,
            Modifier
        )
    }

    val actualBgModifier = if (backgroundColor != null) Modifier.background(backgroundColor) else variantBgModifier
    val actualContentColor = contentColor ?: variantContentColor
    val actualBorderModifier = if (backgroundColor != null && variant != MaxButtonVariant.GOOGLE) {
        Modifier.border(1.dp, Color(0xFFDADCE0), cornerShape)
    } else {
        variantBorderModifier
    }

    val finalAlpha = if (enabled && !isLoading) 1f else 0.45f

    Box(
        modifier = modifier
            .height(height)
            .clip(cornerShape)
            .then(actualBgModifier)
            .then(actualBorderModifier)
            .iosPressEffect(
                enabled = enabled && !isLoading,
                scalePressed = 0.96f,
                alphaPressed = 0.85f,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = actualContentColor,
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
                    style = MaxType.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = actualContentColor.copy(alpha = finalAlpha)
                )
                trailingIcon?.invoke(this)
            }
        }
    }
}

