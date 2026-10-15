package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

/**
 * iOS Inset Grouped Card with continuous squircle corners and specular top highlight.
 */
@Composable
fun MaxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = MaxDimensions.borderHairline,
    shape: Shape? = null,
    cornerRadius: Dp = MaxDimensions.radiusXl,
    specularHighlight: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkMode.current
    val actualBg = if (backgroundColor != Color.Unspecified) backgroundColor else if (isDark) MaxSurfaceCard else LuminousWhite
    val actualBorder = if (borderColor != Color.Unspecified) borderColor else if (isDark) MaxBorderHairline else SoftRoseBorder
    val actualSpecular = if (isDark) MaxBorderSpecular else SoftRoseBorderStrong
    val cardShape = shape ?: RoundedCornerShape(cornerRadius)

    val borderModifier = if (specularHighlight) {
        Modifier.specularBorder(cardShape, borderWidth, actualSpecular, actualBorder)
    } else {
        Modifier.specularBorder(cardShape, borderWidth, actualBorder, actualBorder)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .then(borderModifier)
            .background(actualBg)
            .then(
                if (onClick != null) {
                    Modifier.iosPressEffect(
                        scalePressed = 0.98f,
                        alphaPressed = 0.90f,
                        onClick = onClick
                    )
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

