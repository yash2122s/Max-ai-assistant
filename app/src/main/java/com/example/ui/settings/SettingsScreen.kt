package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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

/**
 * Flagship MAX Settings Screen — Apple Inset Grouped Redesign.
 * Reorganizes all assistant, AI model, voice, automation, and privacy controls
 * into beautiful iOS-style grouped cards with rounded colored square icons.
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaxIOSColors.DarkBase)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(MaxIOSShapes.ShapeSmall)
                            .background(MaxIOSColors.DarkSurfaceElevated)
                            .border(0.5.dp, MaxIOSColors.HairlineDark, MaxIOSShapes.ShapeSmall)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaxIOSColors.TextPrimaryDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = "Settings",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxIOSColors.TextPrimaryDark,
                        letterSpacing = (-0.41).sp
                    )

                    Button(
                        onClick = {
                            MaxHaptics.successPattern(context)
                            viewModel.saveAll()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaxIOSColors.SystemBlue,
                            contentColor = Color.White
                        ),
                        shape = MaxIOSShapes.ShapePill,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        containerColor = MaxIOSColors.DarkBase
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ================================================================
            // SECTION: MASTER SYSTEM CONTROL
            // ================================================================
            MaxIOSSettingsSection(title = "System Master State") {
                MaxIOSSettingsRow(
                    title = "MAX Master State",
                    subtitle = "Controls all voice, automation, and background services",
                    icon = Icons.Default.PowerSettingsNew,
                    iconBg = if (uiState.isMaxEnabled) MaxIOSColors.SystemGreen else MaxIOSColors.SystemRed,
                    trailingContent = {
                        Switch(
                            checked = uiState.isMaxEnabled,
                            onCheckedChange = { viewModel.updateIsMaxEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxIOSColors.SystemGreen
                            )
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: AI ENGINE
            // ================================================================
            MaxIOSSettingsSection(
                title = "Gemini Live AI Engine",
                footer = "Real-time AI model mode is set to Auto by default, dynamically selecting Fast for low-latency voice (<500ms) and Deep for multi-step reasoning."
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MaxTextField(
                        value = uiState.geminiApiKey,
                        onValueChange = { viewModel.updateGeminiApiKey(it) },
                        label = "Gemini API Key",
                        placeholder = "AIzaSy...",
                        isPassword = true,
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaxIOSColors.SystemTeal, modifier = Modifier.size(18.dp))
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaxIOSShapes.ShapeSmall)
                            .background(MaxIOSColors.SystemBlue.copy(alpha = 0.12f))
                            .border(0.5.dp, MaxIOSColors.SystemBlue.copy(alpha = 0.3f), MaxIOSShapes.ShapeSmall)
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
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = MaxIOSColors.SystemBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Get Free Key from Google AI Studio",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaxIOSColors.SystemBlue
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaxIOSColors.SystemBlue.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaxIOSColors.HairlineDark, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))

                MaxIOSSettingsRow(
                    title = "Thinking Observability",
                    subtitle = "Stream live AI reasoning thought summaries in UI",
                    icon = Icons.Default.Psychology,
                    iconBg = MaxIOSColors.SystemPurple,
                    trailingContent = {
                        Switch(
                            checked = uiState.isThinkingObservabilityEnabled,
                            onCheckedChange = { viewModel.updateIsThinkingObservabilityEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxIOSColors.SystemPurple,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color(0xFF333842)
                            )
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: ASSISTANT VOICE & PERSONA (MALE / FEMALE)
            // ================================================================
            MaxIOSSettingsSection(
                title = "Assistant Voice & Persona",
                footer = "Select your preferred assistant voice and personality. Female speaks warmly and caringly, while Male speaks confidently with brotherly energy."
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isMale = uiState.liveVoiceName.equals("Puck", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Charon", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Fenrir", ignoreCase = true) ||
                            uiState.liveVoiceName.equals("Orus", ignoreCase = true)

                    IOSVoiceOption(
                        title = "Female Voice (Aoede)",
                        subtitle = "Caring, sweet, warm & affectionate persona (ప్రేమగా & కేరింగ్ గా మాట్లాడుతుంది)",
                        badge = "CARING",
                        badgeColor = Color(0xFFFF4081),
                        icon = Icons.Default.Face,
                        isSelected = !isMale,
                        onClick = { viewModel.updateLiveVoiceName("Aoede") }
                    )

                    IOSVoiceOption(
                        title = "Male Voice (Puck)",
                        subtitle = "Confident, sharp & brotherly persona (మగవాడిలా & కాన్ఫిడెంట్ గా మాట్లాడుతుంది)",
                        badge = "CONFIDENT",
                        badgeColor = MaxIOSColors.SystemBlue,
                        icon = Icons.Default.RecordVoiceOver,
                        isSelected = isMale,
                        onClick = { viewModel.updateLiveVoiceName("Puck") }
                    )
                }
            }

            // ================================================================
            // SECTION: SYSTEM ASSISTANT & WAKE WORD
            // ================================================================
            MaxIOSSettingsSection(title = "System Assistant") {
                MaxIOSSettingsRow(
                    title = "Default Digital Assistant",
                    subtitle = if (uiState.isDefaultAssistant) "MAX is active system assistant" else "Set MAX as system default assistant",
                    icon = Icons.Default.RecordVoiceOver,
                    iconBg = MaxIOSColors.SystemBlue,
                    trailingContent = {
                        if (uiState.isDefaultAssistant) {
                            Text(
                                text = "ACTIVE",
                                color = MaxIOSColors.SystemGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Button(
                                onClick = {
                                    val intent = com.example.voice.assistant.SystemAssistantManager.createSetDefaultAssistantIntent(context)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaxIOSColors.SystemBlue,
                                    contentColor = Color.White
                                ),
                                shape = MaxIOSShapes.ShapePill,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Set Default", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    showDivider = true
                )

                MaxIOSSettingsRow(
                    title = "Voice Assist Gesture",
                    subtitle = "Power-button long press & digital assist trigger",
                    icon = Icons.Default.Hearing,
                    iconBg = MaxIOSColors.SystemIndigo,
                    trailingContent = {
                        Text(
                            text = uiState.wakeWordStatus,
                            color = MaxIOSColors.TextSecondaryDark,
                            fontSize = 12.sp
                        )
                    },
                    showDivider = false
                )
            }

            // ================================================================
            // SECTION: AUTOMATION & SKILLS
            // ================================================================
            MaxIOSSettingsSection(title = "Automation & Workflows") {
                MaxIOSSettingsRow(
                    title = "Automation Skills Index",
                    subtitle = "Inspect installed skills & automation triggers",
                    icon = Icons.Default.Extension,
                    iconBg = MaxIOSColors.SystemPurple,
                    onClick = onNavigateToSkills,
                    showDivider = true
                )

                MaxIOSSettingsRow(
                    title = "Agentic Autonomous Mode",
                    subtitle = "Allow multi-step planning & autonomous UI navigation",
                    icon = Icons.Default.AutoMode,
                    iconBg = MaxIOSColors.SystemTeal,
                    trailingContent = {
                        Switch(
                            checked = uiState.isAgenticModeEnabled,
                            onCheckedChange = { viewModel.updateIsAgenticModeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxIOSColors.SystemBlue
                            )
                        )
                    },
                    showDivider = uiState.isAgenticModeEnabled
                )

                if (uiState.isAgenticModeEnabled) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Max Iterations per Goal", fontSize = 13.sp, color = MaxIOSColors.TextSecondaryDark)
                            Text(text = "${uiState.agenticMaxIterations}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaxIOSColors.SystemBlue)
                        }
                        Slider(
                            value = uiState.agenticMaxIterations.toFloat(),
                            onValueChange = { viewModel.updateAgenticMaxIterations(it.toInt()) },
                            valueRange = 1f..15f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaxIOSColors.SystemBlue,
                                inactiveTrackColor = MaxIOSColors.DarkSurfaceElevated
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Daily Iteration Budget", fontSize = 13.sp, color = MaxIOSColors.TextSecondaryDark)
                            Text(text = "${uiState.agenticDailyBudget} (Used: ${uiState.agenticIterationsUsedToday})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaxIOSColors.SystemTeal)
                        }
                        Slider(
                            value = uiState.agenticDailyBudget.toFloat(),
                            onValueChange = { viewModel.updateAgenticDailyBudget(it.toInt()) },
                            valueRange = 5f..200f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaxIOSColors.SystemTeal,
                                inactiveTrackColor = MaxIOSColors.DarkSurfaceElevated
                            )
                        )
                    }
                }
            }

            // ================================================================
            // SECTION: PERSONALIZATION & IDENTITY
            // ================================================================
            MaxIOSSettingsSection(title = "Identity & Persona") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MaxTextField(
                        value = uiState.userName,
                        onValueChange = { viewModel.updateUserName(it) },
                        label = "Your Name / Call Sign",
                        placeholder = "e.g., Yash",
                        leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null, tint = MaxIOSColors.SystemBlue, modifier = Modifier.size(18.dp)) }
                    )
                    MaxTextField(
                        value = uiState.botPersona,
                        onValueChange = { viewModel.updateBotPersona(it) },
                        label = "Assistant Persona",
                        placeholder = "Autonomous High-Intelligence Companion",
                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = MaxIOSColors.SystemPurple, modifier = Modifier.size(18.dp)) }
                    )
                    MaxTextField(
                        value = uiState.languagePreference,
                        onValueChange = { viewModel.updateLanguagePreference(it) },
                        label = "Language Policy",
                        placeholder = "English / Telugu",
                        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null, tint = MaxIOSColors.SystemTeal, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            // ================================================================
            // SECTION: MEMORY RAG ENGINE
            // ================================================================
            MaxIOSSettingsSection(title = "Memory & Knowledge") {
                MaxIOSSettingsRow(
                    title = "Memory Knowledge RAG",
                    subtitle = "Augment prompts with episodic & semantic memory",
                    icon = Icons.Default.Search,
                    iconBg = MaxIOSColors.SystemPurple,
                    trailingContent = {
                        Switch(
                            checked = uiState.isRagEnabled,
                            onCheckedChange = { viewModel.updateIsRagEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaxIOSColors.SystemBlue
                            )
                        )
                    },
                    showDivider = uiState.isRagEnabled
                )

                if (uiState.isRagEnabled) {
                    MaxIOSSettingsRow(
                        title = "Semantic Vector Search",
                        subtitle = "Conceptual embeddings retrieval",
                        icon = Icons.Default.BlurOn,
                        iconBg = MaxIOSColors.SystemTeal,
                        trailingContent = {
                            Switch(
                                checked = uiState.isSemanticSearchEnabled,
                                onCheckedChange = { viewModel.updateIsSemanticSearchEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaxIOSColors.SystemBlue
                                )
                            )
                        },
                        showDivider = true
                    )

                    MaxIOSSettingsRow(
                        title = "Hybrid Rank Fusion",
                        subtitle = "Combine keyword + cosine similarity",
                        icon = Icons.Default.Hub,
                        iconBg = MaxIOSColors.SystemAmber,
                        trailingContent = {
                            Switch(
                                checked = uiState.isHybridSearchEnabled,
                                onCheckedChange = { viewModel.updateIsHybridSearchEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaxIOSColors.SystemBlue
                                )
                            )
                        },
                        showDivider = true
                    )

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Status: ${uiState.ragStatus} • ${uiState.ragMemoriesIndexed} memories indexed",
                            fontSize = 12.sp,
                            color = MaxIOSColors.TextSecondaryDark
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.rebuildRagIndex() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaxIOSColors.DarkSurfaceElevated, contentColor = MaxIOSColors.SystemBlue),
                                shape = MaxIOSShapes.ShapeMedium,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Rebuild Index", fontSize = 13.sp)
                            }
                            Button(
                                onClick = { viewModel.clearRagIndex() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaxIOSColors.SystemRed.copy(alpha = 0.15f), contentColor = MaxIOSColors.SystemRed),
                                shape = MaxIOSShapes.ShapeMedium,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear Index", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ================================================================
            // SECTION: LICENSE & ENTITLEMENTS
            // ================================================================
            MaxIOSSettingsSection(title = "License & Entitlements") {
                MaxIOSSettingsRow(
                    title = "License & Subscription",
                    subtitle = "Manage cloud license & premium status",
                    icon = Icons.Default.Verified,
                    iconBg = MaxIOSColors.SystemGreen,
                    onClick = onNavigateToLicense,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IOSVoiceOption(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaxIOSShapes.ShapeMedium)
            .background(if (isSelected) badgeColor.copy(alpha = 0.12f) else MaxIOSColors.DarkBase.copy(alpha = 0.5f))
            .border(
                0.5.dp,
                if (isSelected) badgeColor else MaxIOSColors.HairlineDark,
                MaxIOSShapes.ShapeMedium
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) badgeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) badgeColor else MaxIOSColors.TextSecondaryDark,
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) badgeColor else MaxIOSColors.TextPrimaryDark
                    )
                    Box(
                        modifier = Modifier
                            .clip(MaxIOSShapes.ShapePill)
                            .background(if (isSelected) badgeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) badgeColor else MaxIOSColors.TextSecondaryDark
                        )
                    }
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaxIOSColors.TextSecondaryDark,
                    lineHeight = 16.sp
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = badgeColor,
                    unselectedColor = MaxIOSColors.TextTertiaryDark
                )
            )
        }
    }
}
