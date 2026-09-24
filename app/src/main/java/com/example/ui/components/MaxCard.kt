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
    backgroundColor: Color = MaxSurfaceCard,
    borderColor: Color = MaxBorderHairline,
    borderWidth: Dp = MaxDimensions.borderHairline,
    shape: Shape? = null,
    cornerRadius: Dp = MaxDimensions.radiusXl,
    specularHighlight: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = shape ?: RoundedCornerShape(cornerRadius)

    val borderModifier = if (specularHighlight) {
        Modifier.specularBorder(cardShape, borderWidth, MaxBorderSpecular, borderColor)
    } else {
        Modifier.specularBorder(cardShape, borderWidth, borderColor, borderColor)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .then(borderModifier)
            .background(backgroundColor)
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

