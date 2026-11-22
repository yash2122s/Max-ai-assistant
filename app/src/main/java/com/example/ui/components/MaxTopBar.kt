package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

/**
 * Modernized Navigation Top Bar with MaxType Typography and Semantic Colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaxSemanticColors.background
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = title,
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textPrimary
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle.uppercase(),
                                style = MaxType.Eyebrow,
                                color = MaxSemanticColors.accentCyan
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (navigationIcon != null) {
                        navigationIcon()
                    } else if (onBackClick != null) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.iosPressEffect(scalePressed = 0.90f, alphaPressed = 0.70f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
                                contentDescription = "Back",
                                tint = MaxSemanticColors.accentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else if (onMenuClick != null) {
                        IconButton(
                            onClick = onMenuClick,
                            modifier = Modifier.iosPressEffect(scalePressed = 0.90f, alphaPressed = 0.70f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaxSemanticColors.textPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaxSemanticColors.background,
                    titleContentColor = MaxSemanticColors.textPrimary,
                    actionIconContentColor = MaxSemanticColors.accentCyan
                )
            )
            HorizontalDivider(
                color = MaxSemanticColors.border,
                thickness = MaxDimensions.borderHairline
            )
        }
    }
}

