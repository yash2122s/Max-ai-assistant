package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MaxDimensions
import com.example.ui.theme.MaxSemanticColors
import com.example.ui.theme.MaxType

@Composable
fun MaxEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    iconTint: Color = MaxSemanticColors.accentCyan,
    actionButton: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MaxDimensions.space2xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
        Text(
            text = title,
            style = MaxType.Headline,
            fontWeight = FontWeight.Bold,
            color = MaxSemanticColors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
        Text(
            text = message,
            style = MaxType.Body,
            color = MaxSemanticColors.textMuted,
            textAlign = TextAlign.Center
        )
        if (actionButton != null) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))
            actionButton()
        }
    }
}
