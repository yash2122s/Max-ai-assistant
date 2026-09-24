package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.model.GeminiModelMode
import com.example.ui.theme.MaxIOSColors
import com.example.ui.theme.MaxIOSShapes
import com.example.ui.theme.MaxIOSSpacing

/**
 * Premium iOS-Inspired Translucent Glass Surface with Specular Border.
 */
@Composable
fun MaxGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaxIOSShapes.ShapeLarge,
    backgroundColor: Color = MaxIOSColors.DarkSurfaceGlass,
    borderColor: Color = MaxIOSColors.HairlineDark,
    borderWidth: Dp = 0.5.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(borderWidth, borderColor, shape)
    ) {
        content()
    }
}

/**
 * 60 FPS Apple-Intelligence Inspired Assistant Orb.
 * Reacts smoothly to microphone RMS audio, speech output, and multi-step reasoning.
 */
@Composable
fun MaxAssistantOrb(
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
    audioRms: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbTransitions")
    
    // Ambient breathing scale
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbBreathing"
    )

    // Thinking rotation
    val thinkingAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbThinking"
    )

    // Reactive amplitude scale
    val normalizedRms = (audioRms / 100f).coerceIn(0f, 1f)
    val rmsScale by animateFloatAsState(
        targetValue = if (isListening) 1.0f + (normalizedRms * 0.45f) else if (isSpeaking) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "OrbRmsScale"
    )

    val currentScale = if (isThinking) breathingScale else rmsScale

    // Dynamic gradient colors based on AI state
    val orbBrush = when {
        isThinking -> Brush.sweepGradient(
            colors = listOf(
                MaxIOSColors.SystemIndigo,
                MaxIOSColors.SystemPurple,
                MaxIOSColors.SystemBlue,
                MaxIOSColors.SystemTeal,
                MaxIOSColors.SystemIndigo
            )
        )
        isSpeaking -> Brush.radialGradient(
            colors = listOf(
                MaxIOSColors.SystemTeal.copy(alpha = 0.95f),
                MaxIOSColors.SystemBlue.copy(alpha = 0.85f),
                MaxIOSColors.DarkBase
            )
        )
        isListening -> Brush.radialGradient(
            colors = listOf(
                MaxIOSColors.SystemBlue.copy(alpha = 0.95f),
                MaxIOSColors.SystemIndigo.copy(alpha = 0.80f),
                MaxIOSColors.DarkBase
            )
        )
        else -> Brush.radialGradient(
            colors = listOf(
                MaxIOSColors.SystemBlue.copy(alpha = 0.7f),
                MaxIOSColors.SystemIndigo.copy(alpha = 0.4f),
                Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(currentScale)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer diffuse aura
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(CircleShape)
                .background(orbBrush)
                .rotate(if (isThinking) thinkingAngle else 0f)
        )

        // Core inner highlight
        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isSpeaking || isListening) 0.85f else 0.45f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * Floating iOS-Style Assistant Input Dock.
 * Features [ + ] quick action button, translucent input capsule, and mic/send controls.
 */
@Composable
fun MaxInputDock(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
    onPlusClick: () -> Unit,
    isListening: Boolean,
    isSpeaking: Boolean,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask MAX anything..."
) {
    MaxGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaxIOSSpacing.screenPadding)
            .height(56.dp),
        shape = MaxIOSShapes.ShapePill,
        backgroundColor = MaxIOSColors.DarkSurfaceElevated.copy(alpha = 0.95f),
        borderColor = MaxIOSColors.HairlineDark,
        borderWidth = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // [ + ] Quick Action Expander Button
            IconButton(
                onClick = onPlusClick,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Quick Actions",
                    tint = MaxIOSColors.TextSecondaryDark,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Input Text Field or Voice Indicator
            if (isSpeaking) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaxIOSColors.SystemTeal)
                    )
                    Text(
                        text = "MAX is speaking…",
                        color = MaxIOSColors.SystemTeal,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Stop button when speaking
                IconButton(
                    onClick = onStopSpeaking,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaxIOSColors.SystemRed.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaxIOSColors.SystemRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MaxIOSColors.TextTertiaryDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(
                            color = MaxIOSColors.TextPrimaryDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(MaxIOSColors.SystemBlue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotBlank()) {
                                onSend(text)
                            }
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Dynamic Mic / Send Action
                if (text.isNotBlank()) {
                    IconButton(
                        onClick = { onSend(text) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaxIOSColors.SystemBlue)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onMicClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening) MaxIOSColors.SystemBlue.copy(alpha = 0.25f)
                                else Color.White.copy(alpha = 0.08f)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (isListening) MaxIOSColors.SystemBlue else MaxIOSColors.TextSecondaryDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Live Activity Card for UI & Device Tool Execution.
 * Replaces technical logs and JSON dumps with human-centered status cards.
 */
@Composable
fun MaxToolActivityCard(
    appName: String,
    statusText: String,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    MaxGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaxIOSShapes.ShapeMedium,
        backgroundColor = MaxIOSColors.DarkSurfaceElevated.copy(alpha = 0.85f),
        borderColor = if (isCompleted) MaxIOSColors.SystemGreen.copy(alpha = 0.4f) else MaxIOSColors.HairlineDark,
        borderWidth = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isCompleted) MaxIOSColors.SystemGreen else MaxIOSColors.SystemBlue)
                )
                Column {
                    Text(
                        text = appName,
                        color = MaxIOSColors.TextPrimaryDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        color = MaxIOSColors.TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }

            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = MaxIOSColors.SystemGreen,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaxIOSColors.SystemBlue,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Minimalist Model Mode Indicator Capsule (FAST / DEEP / AUTO).
 */
@Composable
fun MaxModelIndicator(
    mode: GeminiModelMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modeLabel = when (mode) {
        GeminiModelMode.FAST -> "MAX Fast"
        GeminiModelMode.DEEP -> "MAX Deep"
        GeminiModelMode.AUTO -> "MAX Auto"
    }
    val accentColor = when (mode) {
        GeminiModelMode.FAST -> MaxIOSColors.SystemBlue
        GeminiModelMode.DEEP -> MaxIOSColors.SystemAmber
        GeminiModelMode.AUTO -> MaxIOSColors.SystemTeal
    }

    Box(
        modifier = modifier
            .clip(MaxIOSShapes.ShapePill)
            .background(MaxIOSColors.DarkSurfaceElevated.copy(alpha = 0.85f))
            .border(0.5.dp, accentColor.copy(alpha = 0.35f), MaxIOSShapes.ShapePill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = modeLabel,
                color = MaxIOSColors.TextPrimaryDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Horizontally Scrollable Action Capsule Pill.
 */
@Composable
fun MaxQuickActionCapsule(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaxIOSShapes.ShapePill)
            .background(MaxIOSColors.DarkSurfaceElevated.copy(alpha = 0.9f))
            .border(0.5.dp, MaxIOSColors.HairlineDark, MaxIOSShapes.ShapePill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaxIOSColors.SystemBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = text,
                color = MaxIOSColors.TextPrimaryDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Apple Inset Grouped Section for Settings.
 */
@Composable
fun MaxIOSSettingsSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaxIOSColors.TextTertiaryDark,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        MaxGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaxIOSShapes.ShapeLarge,
            backgroundColor = MaxIOSColors.DarkSurfaceGrouped,
            borderColor = MaxIOSColors.HairlineDark,
            borderWidth = 0.5.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                content()
            }
        }

        if (footer != null) {
            Text(
                text = footer,
                fontSize = 12.sp,
                color = MaxIOSColors.TextTertiaryDark,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Apple Inset Grouped Settings Row with Icon in Colored Rounded Square.
 */
@Composable
fun MaxIOSSettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = MaxIOSColors.SystemBlue,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick)
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaxIOSColors.TextPrimaryDark
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            fontSize = 13.sp,
                            color = MaxIOSColors.TextSecondaryDark
                        )
                    }
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaxIOSColors.TextTertiaryDark,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaxIOSColors.HairlineDark,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = if (icon != null) 60.dp else 16.dp)
            )
        }
    }
}
