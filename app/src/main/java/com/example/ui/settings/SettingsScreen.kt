package com.example.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSkills: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "Settings",
                onBackClick = onNavigateBack,
                actions = {
                    MaxButton(
                        text = "Save",
                        onClick = { viewModel.saveAll() },
                        variant = MaxButtonVariant.PRIMARY,
                        modifier = Modifier.padding(end = MaxDimensions.spaceSm)
                    )
                }
            )
        },
        containerColor = MaxBgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceLg)
        ) {
            // Section 1: AI Engine & API Key
            SettingsSection(title = "AI Engine & API Key", icon = Icons.Default.Key) {
                MaxTextField(
                    value = uiState.geminiApiKey,
                    onValueChange = { viewModel.updateGeminiApiKey(it) },
                    label = "Gemini API Key",
                    placeholder = "AIzaSy...",
                    isPassword = true,
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Section 1.5: MAX System Assistant
            SettingsSection(title = "MAX Assistant", icon = Icons.Default.RecordVoiceOver) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default Digital Assistant",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary
                        )
                        Text(
                            text = if (uiState.isDefaultAssistant) "MAX is your active system digital assistant" else "Set MAX as system default assistant",
                            fontSize = 12.sp,
                            color = MaxTextMuted
                        )
                    }
                    Surface(
                        color = if (uiState.isDefaultAssistant) NeonGreen.copy(alpha = 0.15f) else MaxSurfaceVariant,
                        shape = RoundedCornerShape(MaxDimensions.radiusSm)
                    ) {
                        Text(
                            text = if (uiState.isDefaultAssistant) "Default" else "Not Default",
                            color = if (uiState.isDefaultAssistant) NeonGreen else MaxTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (!uiState.isDefaultAssistant) {
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    MaxButton(
                        text = "Set MAX as Default Assistant",
                        onClick = {
                            val intent = com.example.voice.assistant.SystemAssistantManager.createSetDefaultAssistantIntent(context)
                            context.startActivity(intent)
                        },
                        variant = MaxButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "System Wake Word (\"Hey MAX\")",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary
                        )
                        Text(
                            text = "System invocation via assist gesture / shortcut active",
                            fontSize = 12.sp,
                            color = MaxTextMuted
                        )
                    }
                    Surface(
                        color = MaxSurfaceVariant,
                        shape = RoundedCornerShape(MaxDimensions.radiusSm)
                    ) {
                        Text(
                            text = uiState.wakeWordStatus,
                            color = MaxTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Section 2: Automation Skills Management
            SettingsSection(title = "Automation Skills", icon = Icons.Default.Extension) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                        .background(MaxSurfaceVariant)
                        .clickable(onClick = onNavigateToSkills)
                        .padding(MaxDimensions.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manage & Import Skills",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary
                        )
                        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                        Text(
                            text = "Import declarative .json / .md skills, inspect capabilities, and toggle workflows",
                            fontSize = 12.sp,
                            color = MaxTextMuted
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Skills",
                        tint = MaxPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Section 3: Agentic Automation Limits
            SettingsSection(title = "Agentic Automation", icon = Icons.Default.AutoMode) {
                SettingsSwitchRow(
                    title = "Agentic Autonomous Mode",
                    subtitle = "Enable multi-step goal planning & UI loop execution",
                    isChecked = uiState.isAgenticModeEnabled,
                    onCheckedChange = { viewModel.updateIsAgenticModeEnabled(it) }
                )
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                Text(
                    text = "Max Iterations per Goal: ${uiState.agenticMaxIterations}",
                    fontSize = 13.sp,
                    color = MaxTextPrimary
                )
                Slider(
                    value = uiState.agenticMaxIterations.toFloat(),
                    onValueChange = { viewModel.updateAgenticMaxIterations(it.toInt()) },
                    valueRange = 1f..15f,
                    steps = 13,
                    colors = SliderDefaults.colors(
                        thumbColor = MaxPrimary,
                        activeTrackColor = MaxPrimary,
                        inactiveTrackColor = MaxSurfaceVariant
                    )
                )
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                Text(
                    text = "Daily Budget: ${uiState.agenticDailyBudget} iterations (Used: ${uiState.agenticIterationsUsedToday})",
                    fontSize = 13.sp,
                    color = MaxTextPrimary
                )
                Slider(
                    value = uiState.agenticDailyBudget.toFloat(),
                    onValueChange = { viewModel.updateAgenticDailyBudget(it.toInt()) },
                    valueRange = 5f..200f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaxSecondary,
                        activeTrackColor = MaxSecondary,
                        inactiveTrackColor = MaxSurfaceVariant
                    )
                )
            }

            // Section 4: Personalization & Identity
            SettingsSection(title = "Identity & Persona", icon = Icons.Default.Person) {
                MaxTextField(
                    value = uiState.userName,
                    onValueChange = { viewModel.updateUserName(it) },
                    label = "Your Name / Call Sign",
                    placeholder = "e.g., Yaswanth",
                    leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                MaxTextField(
                    value = uiState.botPersona,
                    onValueChange = { viewModel.updateBotPersona(it) },
                    label = "Assistant Persona",
                    placeholder = "Human Type Talking",
                    leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                MaxTextField(
                    value = uiState.languagePreference,
                    onValueChange = { viewModel.updateLanguagePreference(it) },
                    label = "Language Policy",
                    placeholder = "English / Telugu",
                    leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null, tint = MaxTextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Section 5: Integrations
            SettingsSection(title = "Telegram Bridge", icon = Icons.Default.Send) {
                SettingsSwitchRow(
                    title = "Telegram Bot Integration",
                    subtitle = "Receive commands & notifications over Telegram",
                    isChecked = uiState.isTelegramBotEnabled,
                    onCheckedChange = { viewModel.updateTelegramBotEnabled(it) }
                )
                if (uiState.isTelegramBotEnabled) {
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    MaxTextField(
                        value = uiState.telegramBotToken,
                        onValueChange = { viewModel.updateTelegramBotToken(it) },
                        label = "Bot Token",
                        placeholder = "123456:ABC-DEF...",
                        isPassword = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    MaxTextField(
                        value = uiState.telegramChatId,
                        onValueChange = { viewModel.updateTelegramChatId(it) },
                        label = "Allowed Chat ID",
                        placeholder = "e.g., 987654321",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Section 5.5: Native RAG Subsystem
            SettingsSection(title = "Native Memory RAG", icon = Icons.Default.Search) {
                SettingsSwitchRow(
                    title = "RAG Knowledge Retrieval",
                    subtitle = "Automatically augment AI responses with relevant memories",
                    isChecked = uiState.isRagEnabled,
                    onCheckedChange = { viewModel.updateIsRagEnabled(it) }
                )
                if (uiState.isRagEnabled) {
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    SettingsSwitchRow(
                        title = "Semantic Vector Search",
                        subtitle = "Use vector embeddings for conceptual matching",
                        isChecked = uiState.isSemanticSearchEnabled,
                        onCheckedChange = { viewModel.updateIsSemanticSearchEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    SettingsSwitchRow(
                        title = "Hybrid Rank Fusion",
                        subtitle = "Combine semantic vectors and exact keyword search",
                        isChecked = uiState.isHybridSearchEnabled,
                        onCheckedChange = { viewModel.updateIsHybridSearchEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))
                    Text(
                        text = "Embedding Provider",
                        fontSize = 12.sp,
                        color = MaxTextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                    ) {
                        ProviderChoiceChip(
                            label = "Local On-Device",
                            isSelected = uiState.ragEmbeddingProvider.equals("local", ignoreCase = true),
                            onClick = { viewModel.updateRagEmbeddingProvider("local") },
                            modifier = Modifier.weight(1f)
                        )
                        ProviderChoiceChip(
                            label = "Gemini Cloud (768d)",
                            isSelected = uiState.ragEmbeddingProvider.equals("gemini", ignoreCase = true),
                            onClick = { viewModel.updateRagEmbeddingProvider("gemini") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
                    Surface(
                        color = MaxSurfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(MaxDimensions.radiusMd),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(MaxDimensions.spaceSm)) {
                            Text(
                                text = "Status: ${uiState.ragStatus} • Indexed: ${uiState.ragMemoriesIndexed} memories (${uiState.ragChunksIndexed} chunks)",
                                fontSize = 12.sp,
                                color = MaxTextSecondary
                            )
                        }
                    }
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

            // Section 6: Master Operating Switch
            SettingsSection(title = "System Control", icon = Icons.Default.PowerSettingsNew) {
                SettingsSwitchRow(
                    title = "MAX Master State",
                    subtitle = "Global master enable/disable for all background services",
                    isChecked = uiState.isMaxEnabled,
                    onCheckedChange = { viewModel.updateIsMaxEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    MaxCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaxDimensions.radiusLg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaxPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary
                )
            }
            Spacer(modifier = Modifier.height(MaxDimensions.spaceMd))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaxTextPrimary
            )
            Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaxTextMuted
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaxBgDark,
                checkedTrackColor = MaxPrimary,
                uncheckedThumbColor = MaxTextMuted,
                uncheckedTrackColor = MaxSurfaceVariant
            )
        )
    }
}

@Composable
private fun ProviderChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
            .background(if (isSelected) MaxPrimary.copy(alpha = 0.2f) else MaxSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = MaxDimensions.spaceSm, horizontal = MaxDimensions.spaceSm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaxPrimary else MaxTextSecondary
        )
    }
}

@Composable
private fun VoiceChip(
    voice: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MaxDimensions.radiusSm))
            .background(if (isSelected) MaxPrimary else MaxSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = MaxDimensions.spaceXs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = voice,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaxBgDark else MaxTextSecondary
        )
    }
}
