package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MaxColors
import com.example.ui.theme.MaxRadius

fun Modifier.minTouchTarget(): Modifier =
    this.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)

@Composable
fun TouchChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    // Outer = 48dp tap area, inner = visible pill
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(MaxRadius.xxl))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(MaxRadius.xl))
                .border(1.dp, MaxColors.extra.borderSubtle, RoundedCornerShape(MaxRadius.xl))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leading?.invoke()
            Text(label, style = MaterialTheme.typography.labelLarge)
            trailing?.invoke()
        }
    }
}
