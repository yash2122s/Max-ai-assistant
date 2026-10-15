package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import com.example.ui.theme.*
import com.example.ui.theme.LocalIsDarkMode
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.network.model.GeminiModelMode
import com.example.ui.home.AssistantPhase

/**
 * Premium iOS-Inspired Translucent Glass Surface with Specular Border.
 */
@Composable
fun MaxGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaxIOSShapes.ShapeLarge,
    backgroundColor: Color = MaxIOSColors.DarkSurfaceGlass,
    borderColor: Color = MaxIOSColors.HairlineDark,
    borderBrush: Brush? = null,
    borderWidth: Dp = MaxStroke.hairline,
    content: @Composable BoxScope.() -> Unit
) {
    val borderModifier = if (borderBrush != null) {
        Modifier.border(borderWidth, borderBrush, shape)
    } else {
        Modifier.border(borderWidth, borderColor, shape)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .then(borderModifier)
    ) {
        content()
    }
}

/**
 * Quiet state indicator. Only active processing pulses while visible and motion is enabled.
 */
@Composable
fun MaxAssistantOrb(
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
    audioRms: Float = 0f,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onClick: () -> Unit = {},
    phase: AssistantPhase? = null
) {
    val listening = phase?.let { it == AssistantPhase.LISTENING } ?: isListening
    val speaking = phase?.let { it == AssistantPhase.SPEAKING } ?: isSpeaking
    val processing = phase?.let {
        it == AssistantPhase.CONNECTING || it == AssistantPhase.THINKING ||
            it == AssistantPhase.STREAMING || it == AssistantPhase.TOOL_EXECUTION
    } ?: isThinking
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val motionEnabled = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    val pulse = if (processing && resumed && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "ProcessingPulse")
        val value by transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "PulseScale"
        )
        value
    } else 1f
    val rmsScale = if (listening && motionEnabled) 1f + (audioRms / 100f).coerceIn(0f, 1f) * 0.08f else 1f
    val accent = when {
        phase == AssistantPhase.FAILED || phase == AssistantPhase.BLOCKED -> MaxIOSColors.SystemRed
        phase == AssistantPhase.COMPLETED -> MaxIOSColors.SystemGreen
        processing -> MaxIOSColors.SystemBlue
        listening || speaking -> MaxIOSColors.SystemTeal
        else -> MaxIOSColors.SystemIndigo
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(pulse * rmsScale)
            .clip(CircleShape)
            .background(MaxIOSColors.DarkSurfaceElevated)
            .border(1.dp, accent.copy(alpha = 0.65f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.72f)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.8f), MaxIOSColors.DarkSurfaceGrouped)))
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.16f)
                .clip(CircleShape)
                .background(MaxIOSColors.TextPrimaryDark.copy(alpha = 0.9f))
        )
    }
}

/**
 * Floating Assistant Input Dock (Stitch Aether HUD Aligned).
 * Features glowing pulse border when listening, tactile spring feedback, and glowing mic FAB.
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
    placeholder: String = "Ask MAX anything...",
    isBusy: Boolean = false,
    isCancelling: Boolean = false,
    onStopTask: (() -> Unit)? = null,
    enabled: Boolean = true,
    statusLabel: String? = null
) {
    val canSend = enabled && !isBusy && !isCancelling && text.isNotBlank()
    val showStop = isBusy || isCancelling || isSpeaking

    val infiniteTransition = rememberInfiniteTransition(label = "dock_listening")
    val micPulse by if (isListening) {
        infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "mic_pulse"
        )
    } else remember { mutableFloatStateOf(1f) }

    val borderBrush = if (isListening) {
        Brush.horizontalGradient(
            colors = listOf(
                com.example.ui.theme.AetherNeonPurple.copy(alpha = 0.85f),
                com.example.ui.theme.AetherNeonCyan,
                com.example.ui.theme.AetherNeonPurple.copy(alpha = 0.85f)
            )
        )
    } else null

    MaxGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaxIOSSpacing.screenPadding),
        shape = MaxIOSShapes.ShapeLarge,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        borderColor = if (isListening) com.example.ui.theme.AetherNeonCyan else MaterialTheme.colorScheme.outline,
        borderBrush = borderBrush,
        borderWidth = if (isListening) MaxStroke.emphasis else MaxStroke.hairline
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // [ + ] Quick Action Expander Button
            IconButton(
                onClick = onPlusClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Quick Actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (!statusLabel.isNullOrBlank()) {
                    Text(
                        statusLabel,
                        color = if (isListening) com.example.ui.theme.AetherNeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaxType.Caption,
                        fontFamily = com.example.ui.theme.MaxMono,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    textStyle = MaxType.Body.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(com.example.ui.theme.AetherNeonCyan),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = {
                            if (canSend) {
                                onSend(text)
                            }
                        }
                    ),
                    minLines = 1,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Message MAX" },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            if (text.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaxType.Body)
                            innerTextField()
                        }
                    }
                )
            }

            // Right Button: Stop / Send / Mic with smooth animated transition
            AnimatedContent(
                targetState = when {
                    showStop -> "STOP"
                    text.isNotBlank() -> "SEND"
                    else -> "MIC"
                },
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200))) togetherWith
                        (fadeOut(tween(150)) + scaleOut(tween(150)))
                },
                label = "dock_action_button"
            ) { target ->
                when (target) {
                    "STOP" -> {
                        IconButton(
                            onClick = { if (isBusy || isCancelling) onStopTask?.invoke() else onStopSpeaking() },
                            enabled = enabled && !isCancelling && (if (isBusy) onStopTask != null else true),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaxIOSColors.SystemRed.copy(alpha = 0.2f))
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = if (isCancelling) "Cancelling" else if (isBusy) "Stop task" else "Stop speaking",
                                tint = MaxIOSColors.SystemRed
                            )
                        }
                    }
                    "SEND" -> {
                        IconButton(
                            onClick = { if (canSend) onSend(text) },
                            enabled = canSend,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) Brush.linearGradient(com.example.ui.theme.AetherGradientAi)
                                    else SolidColor(com.example.ui.theme.AetherNeonCyan.copy(alpha = 0.3f))
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send message",
                                tint = Color.White
                            )
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = onMicClick,
                            enabled = enabled,
                            modifier = Modifier
                                .size(48.dp)
                                .scale(micPulse)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            com.example.ui.theme.NeonPink,
                                            com.example.ui.theme.MagentaRose
                                        )
                                    )
                                )
                                .border(1.dp, com.example.ui.theme.SoftRoseBorderStrong, CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = if (isListening) "Stop voice input" else "Start voice input",
                                tint = Color.White
                            )
                        }
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
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        borderColor = if (isCompleted) MaxIOSColors.SystemGreen.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline,
        borderWidth = MaxStroke.hairline
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
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaxType.Body,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaxType.Caption
                    )
                }
            }

            if (isCompleted) {
                Icon(
                    imageVector = Icons.Rounded.Check,
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
            .background(MaxIOSColors.SurfaceElevated.copy(alpha = 0.85f))
            .border(MaxStroke.hairline, accentColor.copy(alpha = 0.35f), MaxIOSShapes.ShapePill)
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
                color = MaxIOSColors.TextPrimary,
                style = MaxType.Caption,
                fontWeight = FontWeight.Medium
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "capsule_press"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(MaxIOSShapes.ShapePill)
            .background(MaxIOSColors.SurfaceGlass)
            .border(MaxStroke.hairline, com.example.ui.theme.SoftRoseBorder, MaxIOSShapes.ShapePill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
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
                    tint = com.example.ui.theme.NeonPink,
                    modifier = Modifier.size(MaxIconTokens.Small)
                )
            }
            Text(
                text = text,
                color = MaxIOSColors.TextPrimary,
                style = MaxType.Body,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Modern Luminous 2x2 Grid Quick Automation Card (Stitch Neural Design).
 */
@Composable
fun MaxQuickAutomationCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = com.example.ui.theme.NeonPink
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "card_press"
    )

    val isDark = LocalIsDarkMode.current
    val cardBackground = if (isDark) {
        Brush.verticalGradient(listOf(MaxIOSColors.DarkSurfaceGrouped, MaxIOSColors.DarkSurfaceElevated))
    } else {
        Brush.verticalGradient(listOf(MaxSemanticColors.surfaceElevated, MaxSemanticColors.surface))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(MaxRadius.xl))
            .background(cardBackground)
            .border(MaxStroke.hairline, if (isDark) MaxIOSColors.HairlineDark else com.example.ui.theme.SoftRoseBorder, RoundedCornerShape(MaxRadius.xl))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(13.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(MaxRadius.sm))
                        .background(if (isDark) MaxIOSColors.DarkSurfaceElevated else com.example.ui.theme.SoftRoseTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(MaxIconTokens.Medium)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MaxRadius.md))
                        .background(if (isDark) MaxIOSColors.DarkSurfaceElevated else com.example.ui.theme.SoftRoseTint)
                        .border(MaxStroke.hairline, if (isDark) MaxIOSColors.HairlineDark else com.example.ui.theme.SoftRoseBorderStrong, RoundedCornerShape(MaxRadius.md))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = badge,
                        style = MaxType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) iconTint else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaxType.Body,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) MaxSemanticColors.textPrimary else Color(0xFF0F172A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaxType.Caption,
                    color = if (isDark) MaxSemanticColors.textSecondary else Color(0xFF475569),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Apple Inset Grouped Section for Settings.
 */
@Deprecated("Use MaxCard or MaxScreen components")
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
                style = MaxType.Caption,
                fontWeight = FontWeight.SemiBold,
                color = MaxIOSColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        MaxGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaxIOSShapes.ShapeLarge,
            backgroundColor = MaxIOSColors.SurfaceGrouped,
            borderColor = MaxIOSColors.Hairline,
            borderWidth = MaxStroke.hairline
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
                style = MaxType.Caption,
                color = MaxIOSColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Apple Inset Grouped Settings Row with Icon in Colored Rounded Square.
 */
@Deprecated("Use SettingsRow or MaxCard components")
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
                            .clip(RoundedCornerShape(MaxRadius.sm))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(MaxIconTokens.Medium)
                        )
                    }
                }

                Column {
                    Text(
                        text = title,
                        style = MaxType.Headline,
                        fontWeight = FontWeight.Medium,
                        color = MaxIOSColors.TextPrimary
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaxType.Caption,
                            color = MaxIOSColors.TextSecondary
                        )
                    }
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaxIOSColors.TextTertiary,
                    modifier = Modifier.size(MaxIconTokens.Medium)
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaxIOSColors.Hairline,
                thickness = MaxStroke.hairline,
                modifier = Modifier.padding(start = if (icon != null) 60.dp else 16.dp)
            )
        }
    }
}
