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
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MaxDimensions
import com.example.ui.theme.MaxPrimary
import com.example.ui.theme.MaxTextMuted
import com.example.ui.theme.MaxTextPrimary

@Composable
fun MaxEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    iconTint: Color = MaxPrimary,
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
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaxTextMuted,
            textAlign = TextAlign.Center
        )
        if (actionButton != null) {
            Spacer(modifier = Modifier.height(MaxDimensions.spaceLg))
            actionButton()
        }
    }
}
