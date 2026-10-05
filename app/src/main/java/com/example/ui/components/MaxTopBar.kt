package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * iOS-Style Translucent Navigation Top Bar with SF Pro Typography and Hairline Border.
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
        color = MaxBaseDark
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
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaxTextPrimary,
                            letterSpacing = (-0.3).sp
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaxTextTertiary,
                                letterSpacing = (-0.1).sp
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
                                tint = MaxPrimaryRoyal,
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
                                tint = MaxTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaxBaseDark,
                    titleContentColor = MaxTextPrimary,
                    actionIconContentColor = MaxPrimaryRoyal
                )
            )
            HorizontalDivider(
                color = MaxBorderHairline.copy(alpha = 0.6f),
                thickness = MaxDimensions.borderHairline
            )
        }
    }
}

