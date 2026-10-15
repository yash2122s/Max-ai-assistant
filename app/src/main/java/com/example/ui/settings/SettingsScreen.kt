package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.theme.*

/**
 * Flagship MAX Settings Screen — Apple Inset Grouped Redesign.
 * Reorganizes all assistant, AI model, voice, automation, and privacy controls
 * into beautiful unified Max design cards.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSkills: () -> Unit = {},
    onNavigateToLicense: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.checkDefaultAssistantStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    MaxScreen(
        title = "Settings",
        eyebrow = "PREFERENCES",
        auroraMode = AuroraMode.NONE,
        onBack = onNavigateBack,
        topBarActions = {
            MaxButton(
                text = "Save",
                variant = MaxButtonVariant.PRIMARY,
                onClick = { viewModel.saveAll() },
                height = 36.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceLg)
        ) {
            // ================================================================
            // SECTION: MASTER SYSTEM CONTROL
            // ================================================================
            SettingsSection(title = "System Master State") {
                SettingsRow(
                    title = "MAX Master State",
                    subtitle = "Controls all voice, automation, and background services",
                    icon = Icons.Default.PowerSettingsNew,
                    iconBg = if (uiState.isMaxEnabled) MaxSemanticColors.success else MaxSemanticColors.destructive,
                    trailingContent = {
                        Switch(
                            checked = uiState.isMaxEnabled,
                            onCheckedChange = { viewModel.updateIsMaxEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxSemanticColors.success
                            )
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: AI ENGINE
            // ================================================================
            SettingsSection(
                title = "Gemini Live AI Engine",
                footer = "Real-time AI model mode is set to Auto by default, dynamically selecting Fast for low-latency voice (<500ms) and Deep for multi-step reasoning."
            ) {
                Column(
                    modifier = Modifier.padding(MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    MaxTextField(
                        value = uiState.geminiApiKey,
                        onValueChange = { viewModel.updateGeminiApiKey(it) },
                        label = "Gemini API Key",
                        placeholder = "AIzaSy...",
                        isPassword = true,
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaxSemanticColors.accentCyan, modifier = Modifier.size(MaxIconTokens.Medium))
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(MaxSemanticColors.primaryRoyal.copy(alpha = 0.12f))
                            .border(MaxDimensions.borderHairline, MaxSemanticColors.primaryRoyal.copy(alpha = 0.3f), RoundedCornerShape(MaxDimensions.radiusSm))
                            .clickable {
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://aistudio.google.com/app/apikey")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaxSemanticColors.accentCyan,
                                modifier = Modifier.size(MaxIconTokens.Small)
                            )
                            Text(
                                text = "Get Free Key from Google AI Studio",
                                style = MaxType.Caption,
                                fontWeight = FontWeight.SemiBold,
                                color = MaxSemanticColors.accentCyan
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaxSemanticColors.accentCyan.copy(alpha = 0.7f),
                            modifier = Modifier.size(MaxIconTokens.Small)
                        )
                    }
                }

                HorizontalDivider(color = MaxSemanticColors.border, thickness = MaxDimensions.borderHairline, modifier = Modifier.padding(horizontal = MaxDimensions.spaceMd))

                SettingsRow(
                    title = "Thinking Observability",
                    subtitle = "Stream live AI reasoning thought summaries in UI",
                    icon = Icons.Default.Psychology,
                    iconBg = MaxSemanticColors.primaryRoyal,
                    trailingContent = {
                        Switch(
                            checked = uiState.isThinkingObservabilityEnabled,
                            onCheckedChange = { viewModel.updateIsThinkingObservabilityEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxSemanticColors.primaryRoyal,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = MaxSemanticColors.surfaceVariant
                            )
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: ASSISTANT VOICE & PERSONA (MALE / FEMALE)
            // ================================================================
            SettingsSection(
                title = "Assistant Voice & Persona",
                footer = "Select your preferred assistant voice and personality. Female speaks warmly and caringly, while Male speaks confidently with brotherly energy."
            ) {
                Column(
                    modifier = Modifier.padding(MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    val isMale = uiState.liveVoiceName.equals("Puck", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Charon", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Fenrir", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Orus", ignoreCase = true)

                    SettingsVoiceOption(
                        title = "Female Voice (Aoede)",
                        subtitle = "Caring, sweet, warm & affectionate persona (ప్రేమగా & కేరింగ్ గా మాట్లాడుతుంది)",
                        badge = "CARING",
                        badgeColor = MaxSemanticColors.accentPink,
                        icon = Icons.Default.Face,
                        isSelected = !isMale,
                        onClick = { viewModel.updateLiveVoiceName("Aoede") }
                    )

                    SettingsVoiceOption(
                        title = "Male Voice (Puck)",
                        subtitle = "Confident, sharp & brotherly persona (మగవాడిలా & కాన్ఫిడెంట్ గా మాట్లాడుతుంది)",
                        badge = "CONFIDENT",
                        badgeColor = MaxSemanticColors.accentCyan,
                        icon = Icons.Default.RecordVoiceOver,
                        isSelected = isMale,
                        onClick = { viewModel.updateLiveVoiceName("Puck") }
                    )
                }
            }

            // ================================================================
            // SECTION: SYSTEM ASSISTANT & WAKE WORD
            // ================================================================
            SettingsSection(title = "System Assistant") {
                SettingsRow(
                    title = "Default Digital Assistant",
                    subtitle = if (uiState.isDefaultAssistant) "MAX is active system assistant" else "Set MAX as system default assistant",
                    icon = Icons.Default.RecordVoiceOver,
                    iconBg = MaxSemanticColors.primaryRoyal,
                    trailingContent = {
                        if (uiState.isDefaultAssistant) {
                            Text(
                                text = "ACTIVE",
                                color = MaxSemanticColors.success,
                                style = MaxType.Caption,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            MaxButton(
                                text = "Set Default",
                                onClick = {
                                    val intent = com.example.voice.assistant.SystemAssistantManager.createSetDefaultAssistantIntent(context)
                                    context.startActivity(intent)
                                },
                                variant = MaxButtonVariant.PRIMARY,
                                height = 32.dp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    },
                    showDivider = true
                )

                SettingsRow(
                    title = "Voice Assist Gesture",
                    subtitle = "Power-button long press & digital assist trigger",
                    icon = Icons.Default.Hearing,
                    iconBg = MaxSemanticColors.primaryRoyal,
                    trailingContent = {
                        Text(
                            text = uiState.wakeWordStatus,
                            color = MaxSemanticColors.textSecondary,
                            style = MaxType.Caption
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: AUTOMATION & SKILLS
            // ================================================================
            SettingsSection(title = "Automation & Workflows") {
                SettingsRow(
                    title = "Automation Skills Index",
                    subtitle = "Inspect installed skills & automation triggers",
                    icon = Icons.Default.Extension,
                    iconBg = MaxSemanticColors.primaryRoyal,
                    onClick = onNavigateToSkills,
                    showDivider = true
                )

                SettingsRow(
                    title = "Agentic Autonomous Mode",
                    subtitle = "Allow multi-step planning & autonomous UI navigation",
                    icon = Icons.Default.AutoMode,
                    iconBg = MaxSemanticColors.accentCyan,
                    trailingContent = {
                        Switch(
                            checked = uiState.isAgenticModeEnabled,
                            onCheckedChange = { viewModel.updateIsAgenticModeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxSemanticColors.accentCyan
                            )
                        )
                    },
                    showDivider = uiState.isAgenticModeEnabled
                )

                if (uiState.isAgenticModeEnabled) {
                    Column(
                        modifier = Modifier.padding(MaxDimensions.spaceMd),
                        verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Max Iterations per Goal", style = MaxType.Caption, color = MaxSemanticColors.textSecondary)
                            Text(text = "${uiState.agenticMaxIterations}", style = MaxType.Caption, fontWeight = FontWeight.Bold, color = MaxSemanticColors.accentCyan)
                        }
                        Slider(
                            value = uiState.agenticMaxIterations.toFloat(),
                            onValueChange = { viewModel.updateAgenticMaxIterations(it.toInt()) },
                            valueRange = 1f..15f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaxSemanticColors.accentCyan,
                                inactiveTrackColor = MaxSemanticColors.surfaceVariant
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Daily Iteration Budget", style = MaxType.Caption, color = MaxSemanticColors.textSecondary)
                            Text(text = "${uiState.agenticDailyBudget} (Used: ${uiState.agenticIterationsUsedToday})", style = MaxType.Caption, fontWeight = FontWeight.Bold, color = MaxSemanticColors.primaryRoyal)
                        }
                        Slider(
                            value = uiState.agenticDailyBudget.toFloat(),
                            onValueChange = { viewModel.updateAgenticDailyBudget(it.toInt()) },
                            valueRange = 5f..200f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaxSemanticColors.primaryRoyal,
                                inactiveTrackColor = MaxSemanticColors.surfaceVariant
                            )
                        )
                    }
                }
            }

            // ================================================================
            // SECTION: PERSONALIZATION & IDENTITY
            // ================================================================
            SettingsSection(title = "Identity & Persona") {
                Column(
                    modifier = Modifier.padding(MaxDimensions.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    MaxTextField(
                        value = uiState.userName,
                        onValueChange = { viewModel.updateUserName(it) },
                        label = "Your Name / Call Sign",
                        placeholder = "e.g., Yash",
                        leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null, tint = MaxSemanticColors.accentCyan, modifier = Modifier.size(MaxIconTokens.Medium)) }
                    )
                    MaxTextField(
                        value = uiState.botPersona,
                        onValueChange = { viewModel.updateBotPersona(it) },
                        label = "Assistant Persona",
                        placeholder = "Autonomous High-Intelligence Companion",
                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = MaxSemanticColors.primaryRoyal, modifier = Modifier.size(MaxIconTokens.Medium)) }
                    )
                    MaxTextField(
                        value = uiState.languagePreference,
                        onValueChange = { viewModel.updateLanguagePreference(it) },
                        label = "Language Policy",
                        placeholder = "English / Telugu",
                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null, tint = MaxSemanticColors.accentCyan, modifier = Modifier.size(MaxIconTokens.Medium)) }
                    )
                }
            }

            // ================================================================
            // SECTION: MEMORY RAG ENGINE
            // ================================================================
            SettingsSection(title = "Memory & Knowledge") {
                SettingsRow(
                    title = "Memory Knowledge RAG",
                    subtitle = "Augment prompts with episodic & semantic memory",
                    icon = Icons.Default.Search,
                    iconBg = MaxSemanticColors.primaryRoyal,
                    trailingContent = {
                        Switch(
                            checked = uiState.isRagEnabled,
                            onCheckedChange = { viewModel.updateIsRagEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxSemanticColors.primaryRoyal
                            )
                        )
                    },
                    showDivider = uiState.isRagEnabled
                )

                if (uiState.isRagEnabled) {
                    SettingsRow(
                        title = "Semantic Vector Search",
                        subtitle = "Conceptual embeddings retrieval",
                        icon = Icons.Default.BlurOn,
                        iconBg = MaxSemanticColors.accentCyan,
                        trailingContent = {
                            Switch(
                                checked = uiState.isSemanticSearchEnabled,
                                onCheckedChange = { viewModel.updateIsSemanticSearchEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaxSemanticColors.accentCyan
                                )
                            )
                        },
                        showDivider = true
                    )

                    SettingsRow(
                        title = "Hybrid Rank Fusion",
                        subtitle = "Combine keyword + cosine similarity",
                        icon = Icons.Default.Hub,
                        iconBg = MaxSemanticColors.royalGold,
                        trailingContent = {
                            Switch(
                                checked = uiState.isHybridSearchEnabled,
                                onCheckedChange = { viewModel.updateIsHybridSearchEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaxSemanticColors.royalGold
                                )
                            )
                        },
                        showDivider = true
                    )

                    Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                        Text(
                            text = "Status: ${uiState.ragStatus} • ${uiState.ragMemoriesIndexed} memories indexed",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                        ) {
                            MaxButton(
                                text = "Rebuild Index",
                                onClick = { viewModel.rebuildRagIndex() },
                                variant = MaxButtonVariant.SECONDARY,
                                modifier = Modifier.weight(1f)
                            )
                            MaxButton(
                                text = "Clear Index",
                                onClick = { viewModel.clearRagIndex() },
                                variant = MaxButtonVariant.DANGER,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ================================================================
            // SECTION: LICENSE & ENTITLEMENTS
            // ================================================================
            SettingsSection(title = "License & Entitlements") {
                SettingsRow(
                    title = "License & Subscription",
                    subtitle = "Manage cloud license & premium status",
                    icon = Icons.Default.Verified,
                    iconBg = MaxSemanticColors.accentPink,
                    onClick = onNavigateToLicense,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceXs)
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaxType.Eyebrow,
                color = MaxSemanticColors.textMuted,
                modifier = Modifier.padding(horizontal = MaxDimensions.spaceMd)
            )
        }

        MaxCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = MaxDimensions.radiusLg,
            content = content
        )

        if (footer != null) {
            Text(
                text = footer,
                style = MaxType.Caption,
                color = MaxSemanticColors.textMuted,
                modifier = Modifier.padding(horizontal = MaxDimensions.spaceMd)
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = MaxSemanticColors.primaryRoyal,
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
                .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
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

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaxType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxSemanticColors.textPrimary
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary
                        )
                    }
                }
            }

            if (trailingContent != null) {
                Box(modifier = Modifier.padding(start = MaxDimensions.spaceSm)) {
                    trailingContent()
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaxSemanticColors.border,
                thickness = MaxDimensions.borderHairline,
                modifier = Modifier.padding(start = if (icon != null) 58.dp else MaxDimensions.spaceMd)
            )
        }
    }
}

@Composable
private fun SettingsVoiceOption(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkMode.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
            .background(if (isSelected) badgeColor.copy(alpha = 0.12f) else if (isDark) MaxSemanticColors.surfaceVariant.copy(alpha = 0.5f) else LuminousCanvas)
            .border(
                MaxDimensions.borderHairline,
                if (isSelected) badgeColor else MaxSemanticColors.border,
                RoundedCornerShape(MaxDimensions.radiusMd)
            )
            .clickable(onClick = onClick)
            .padding(MaxDimensions.spaceMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) badgeColor.copy(alpha = 0.2f) else if (isDark) Color.White.copy(alpha = 0.08f) else com.example.ui.theme.SoftRoseTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) badgeColor else MaxSemanticColors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaxType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) badgeColor else MaxSemanticColors.textPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                            .background(if (isSelected) badgeColor.copy(alpha = 0.2f) else if (isDark) Color.White.copy(alpha = 0.08f) else com.example.ui.theme.SoftRoseTint)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaxType.Eyebrow,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) badgeColor else MaxSemanticColors.textSecondary
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = badgeColor,
                    unselectedColor = MaxSemanticColors.textMuted
                )
            )
        }
    }
}
