package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

@Composable
fun MaxLoadingState(
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MaxDimensions.space2xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaxSemanticColors.accentCyan,
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp,
            trackColor = MaxSemanticColors.surfaceElevated
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
        Text(
            text = message,
            style = MaxType.Body,
            color = MaxSemanticColors.textSecondary
        )
    }
}

@Composable
fun MaxErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MaxDimensions.space2xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaxSemanticColors.error,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
        Text(
            text = "Error Encountered",
            style = MaxType.Headline,
            fontWeight = FontWeight.Bold,
            color = MaxSemanticColors.textPrimary
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
        Text(
            text = message,
            style = MaxType.Caption,
            color = MaxSemanticColors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))
            MaxButton(
                text = "Retry",
                onClick = onRetry,
                variant = MaxButtonVariant.OUTLINE
            )
        }
    }
}
