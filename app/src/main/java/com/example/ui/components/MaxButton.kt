package com.example.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MaxDimensions
import com.example.ui.theme.MaxPrimary
import com.example.ui.theme.MaxSurfaceVariant
import com.example.ui.theme.OnPrimaryDark

enum class MaxButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    DANGER
}

@Composable
fun MaxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    variant: MaxButtonVariant = MaxButtonVariant.PRIMARY,
    height: Dp = MaxDimensions.buttonHeightMd,
    leadingIcon: (@Composable RowScope.() -> Unit)? = null
) {
    val containerColor = when (variant) {
        MaxButtonVariant.PRIMARY -> MaxPrimary
        MaxButtonVariant.SECONDARY -> MaxSurfaceVariant
        MaxButtonVariant.OUTLINE -> Color.Transparent
        MaxButtonVariant.DANGER -> com.example.ui.theme.MaxError
    }
    
    val contentColor = when (variant) {
        MaxButtonVariant.PRIMARY -> OnPrimaryDark
        MaxButtonVariant.SECONDARY -> Color.White
        MaxButtonVariant.OUTLINE -> MaxPrimary
        MaxButtonVariant.DANGER -> OnPrimaryDark
    }

    if (variant == MaxButtonVariant.OUTLINE) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(height),
            enabled = enabled && !isLoading,
            shape = RoundedCornerShape(MaxDimensions.radiusMd),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = contentColor, modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                leadingIcon?.invoke(this)
                Text(text = text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(height),
            enabled = enabled && !isLoading,
            shape = RoundedCornerShape(MaxDimensions.radiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = contentColor, modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                leadingIcon?.invoke(this)
                Text(text = text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}
