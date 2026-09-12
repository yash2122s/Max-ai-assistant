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
import androidx.compose.ui.unit.sp
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
            color = MaxPrimary,
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaxTextMuted
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
            tint = MaxError,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
        Text(
            text = "Error Encountered",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaxTextMuted,
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
