package com.example.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Flagship MAX Digital Assistant Home Screen — Premium iOS-Inspired Redesign.
 * Features:
 * - Minimal, elegant Apple-style top navigation bar with live status indicator.
 * - Contextual greeting & Assistant Orb with horizontally scrollable Quick Action pills.
 * - Open conversational layout with compact right-aligned user bubbles & left-aligned MAX text.
 * - Compact Live Activity cards for tool execution.
 * - Floating iOS-style input dock with voice waveform & tap-to-interrupt.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateTo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioRms by viewModel.audioRms.collectAsStateWithLifecycle()
    val liveUserCaption by viewModel.liveUserCaption.collectAsStateWithLifecycle()
    val liveAiCaption by viewModel.liveAiCaption.collectAsStateWithLifecycle()
    val edgeRevealTrigger by viewModel.edgeRevealTrigger.collectAsStateWithLifecycle()
    val cloudLicense by viewModel.cloudLicense.collectAsStateWithLifecycle()
    val dailyUsage by viewModel.dailyUsage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf("") }
    var showQuickPrompts by remember { mutableStateOf(false) }

    // Scroll-to-bottom badge state
    val isScrolledUp by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems <= 1) false
            else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex < totalItems - 2
            }
        }
    }

    // Permission launcher for voice recording
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleRecording()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for voice interaction.")
            }
        }
    }

    // Handle one-shot events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is HomeUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is HomeUiEvent.RequestPermission -> micPermissionLauncher.launch(event.permission)
                is HomeUiEvent.NavigateTo -> onNavigateTo(event.route)
                is HomeUiEvent.ScrollToBottom -> {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size - 1)
                    }
                }
            }
        }
    }

    // Auto-connect on screen display
    LaunchedEffect(Unit) {
        viewModel.ensureConnected()
    }

    // Auto-scroll on new message
    LaunchedEffect(uiState.messages.size, uiState.liveAiCaption) {
        if (uiState.messages.isNotEmpty() && !isScrolledUp) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaxIOSColors.DarkBase)
            .edgeRevealAnimation(triggerKey = edgeRevealTrigger)
            .edgeLightingGlow(
                state = uiState.edgeLightingState,
                amplitude = audioRms
            ),
        containerColor = MaxIOSColors.DarkBase,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            IOSTopBar(
                isMaxEnabled = uiState.isMaxEnabled,
                connectionState = uiState.connectionState,
                onMenuClick = onOpenDrawer,
                onToggleMax = { viewModel.setRuntimeEnabled(it) },
                onClearChat = { viewModel.clearChatHistory() },
                onNavigateToSettings = { onNavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            TrialQuotaBanner(
                license = cloudLicense,
                dailyUsage = dailyUsage,
                onClick = { onNavigateTo(com.example.ui.navigation.MaxDestinations.LICENSE) }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Main Message Feed or Empty State
            if (uiState.messages.isEmpty() && liveAiCaption.isBlank() && !uiState.isThinking) {
                IOSDashboardEmptyState(
                    onPromptSelected = { prompt ->
                        viewModel.sendTextMessage(prompt)
                    },
                    onMicClick = {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    audioRms = audioRms,
                    isListening = uiState.isRecording,
                    isThinking = uiState.isThinking,
                    isSpeaking = uiState.isAiSpeaking,
                    onNavigateTo = onNavigateTo,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { msg ->
                        if (msg.isUser) {
                            IOSUserMessageBubble(
                                message = msg,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        } else {
                            IOSMaxMessageBubble(
                                message = msg,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        }
                    }

                    // Live AI Caption streaming chunk if active
                    if (uiState.isAiSpeaking && liveAiCaption.isNotBlank()) {
                        item(key = "live_ai_streaming") {
                            MaxGlassSurface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = MaxIOSShapes.ShapeLarge,
                                backgroundColor = MaxIOSColors.DarkSurfaceGrouped.copy(alpha = 0.9f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaxIOSColors.SystemTeal)
                                    )
                                    Text(
                                        text = liveAiCaption,
                                        color = MaxIOSColors.TextPrimaryDark,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                    }

                    // Three-Dot Typing Indicator when thinking
                    if (uiState.isThinking && !uiState.isAiSpeaking) {
                        item(key = "typing_indicator") {
                            IOSTypingIndicator()
                        }
                    }
                }
            }

            // Floating "Scroll to Bottom" Pill
            androidx.compose.animation.AnimatedVisibility(
                visible = isScrolledUp,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp)
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            if (uiState.messages.isNotEmpty()) {
                                listState.animateScrollToItem(uiState.messages.size - 1)
                            }
                        }
                    },
                    shape = MaxIOSShapes.ShapePill,
                    color = MaxIOSColors.DarkSurfaceElevated,
                    shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaxIOSColors.HairlineDark)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll down",
                            tint = MaxIOSColors.SystemBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New messages",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaxIOSColors.TextPrimaryDark
                        )
                    }
                }
            }

            // Quick Actions Drawer (Opened by Plus Button)
            androidx.compose.animation.AnimatedVisibility(
                visible = showQuickPrompts,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 76.dp, start = 16.dp, end = 16.dp)
            ) {
                IOSQuickActionsCard(
                    onSelectPrompt = { prompt ->
                        showQuickPrompts = false
                        viewModel.sendTextMessage(prompt)
                    },
                    onDismiss = { showQuickPrompts = false }
                )
            }

            // Floating iOS-Style Input Dock with background scrim
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaxIOSColors.DarkBase.copy(alpha = 0.85f),
                                MaxIOSColors.DarkBase
                            )
                        )
                    )
                    .padding(bottom = 12.dp)
            ) {
                MaxInputDock(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        val textToSend = inputText.trim()
                        if (textToSend.isNotEmpty()) {
                            viewModel.sendTextMessage(textToSend)
                            inputText = ""
                            keyboardController?.hide()
                        }
                    },
                    onMicClick = {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    onPlusClick = {
                        showQuickPrompts = !showQuickPrompts
                    },
                    isListening = uiState.isRecording,
                    isSpeaking = uiState.isAiSpeaking,
                    onStopSpeaking = {
                        viewModel.stopRecording()
                        viewModel.interruptSpeech()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
}

private data class TrialBannerData(
    val bgGradient: List<Color>,
    val text: String,
    val subtext: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color
)

@Composable
fun TrialQuotaBanner(
    license: com.example.firebase.model.CloudLicense,
    dailyUsage: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (license.plan == com.example.firebase.model.LicensePlan.LIFETIME) return

    val isExpired = license.isExpired()
    val isLimitReached = dailyUsage >= 10L
    val remainingDays = license.remainingTrialDays()

    val banner = when {
        isExpired -> {
            TrialBannerData(
                listOf(Color(0xFF3B151A), Color(0xFF260D10)),
                "Free Trial Expired",
                "Your 3-day trial has ended. Tap to activate Lifetime Premium.",
                Icons.Default.ErrorOutline,
                Color(0xFFFF453A)
            )
        }
        isLimitReached -> {
            TrialBannerData(
                listOf(Color(0xFF38230D), Color(0xFF231608)),
                "Daily Free Limit Reached (10/10)",
                "Daily quota used. Tap to activate Lifetime Premium.",
                Icons.Default.Warning,
                Color(0xFFFF9F0A)
            )
        }
        else -> {
            TrialBannerData(
                listOf(Color(0xFF0F2640), Color(0xFF091728)),
                "3-Day Free Trial Active",
                "$remainingDays days remaining • $dailyUsage/10 messages used today",
                Icons.Default.HourglassTop,
                Color(0xFF30D158)
            )
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, banner.iconColor.copy(alpha = 0.4f))
    ) {
        Box(
            modifier = Modifier
                .background(Brush.horizontalGradient(banner.bgGradient))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = banner.icon,
                    contentDescription = null,
                    tint = banner.iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = banner.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = banner.subtext,
                        fontSize = 11.5.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR (Minimalist iOS Header)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSTopBar(
    isMaxEnabled: Boolean,
    connectionState: ConnectionState,
    onMenuClick: () -> Unit,
    onToggleMax: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

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
            // Drawer Menu Button
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaxIOSShapes.ShapeSmall)
                    .background(MaxIOSColors.DarkSurfaceElevated)
                    .border(0.5.dp, MaxIOSColors.HairlineDark, MaxIOSShapes.ShapeSmall)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "Open Drawer",
                    tint = MaxIOSColors.TextPrimaryDark,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Center Assistant Identity & Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isMaxEnabled) MaxIOSColors.TextTertiaryDark
                            else when (connectionState) {
                                ConnectionState.CONNECTED -> MaxIOSColors.SystemGreen
                                ConnectionState.CONNECTING -> MaxIOSColors.SystemAmber
                                else -> MaxIOSColors.TextTertiaryDark
                            }
                        )
                )
                Text(
                    text = if (!isMaxEnabled) "MAX Offline" else "MAX",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxIOSColors.TextPrimaryDark,
                    letterSpacing = (-0.41).sp
                )
            }

            // Options & Settings Menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaxIOSShapes.ShapeSmall)
                        .background(MaxIOSColors.DarkSurfaceElevated)
                        .border(0.5.dp, MaxIOSColors.HairlineDark, MaxIOSShapes.ShapeSmall)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaxIOSColors.TextPrimaryDark,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaxIOSColors.DarkSurfaceGrouped)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (isMaxEnabled) "MAX Active" else "MAX Paused",
                                    color = MaxIOSColors.TextPrimaryDark,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Switch(
                                    checked = isMaxEnabled,
                                    onCheckedChange = {
                                        onToggleMax(it)
                                        showMenu = false
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaxIOSColors.SystemBlue
                                    )
                                )
                            }
                        },
                        onClick = { onToggleMax(!isMaxEnabled) }
                    )

                    HorizontalDivider(color = MaxIOSColors.HairlineDark, thickness = 0.5.dp)

                    DropdownMenuItem(
                        text = { Text("Clear Chat History", color = MaxIOSColors.TextPrimaryDark, fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaxIOSColors.TextSecondaryDark, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onClearChat()
                            showMenu = false
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Settings", color = MaxIOSColors.TextPrimaryDark, fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = MaxIOSColors.TextSecondaryDark, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            onNavigateToSettings()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD EMPTY STATE (Greeting + Reactive Orb + Quick Action Pills)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSDashboardEmptyState(
    onPromptSelected: (String) -> Unit,
    onMicClick: () -> Unit,
    audioRms: Float,
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
    onNavigateTo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Hello"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Assistant Orb Hero
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MaxAssistantOrb(
                    isListening = isListening,
                    isThinking = isThinking,
                    isSpeaking = isSpeaking,
                    audioRms = audioRms,
                    size = 92.dp,
                    onClick = onMicClick
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = greeting,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxIOSColors.TextPrimaryDark,
                        letterSpacing = 0.36.sp
                    )
                    Text(
                        text = "What can I help you with?",
                        fontSize = 16.sp,
                        color = MaxIOSColors.TextSecondaryDark,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Quick Actions Scrollable Pill Row
        item {
            val quickActions = listOf(
                "💬 Message" to "Send a WhatsApp message",
                "⏰ Alarm" to "Set an alarm for 7 AM",
                "🗺️ Open Maps" to "Open Google Maps",
                "📷 Camera" to "Take a photo",
                "🔍 Search" to "Search the web for news"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickActions.forEach { (label, prompt) ->
                    MaxQuickActionCapsule(
                        text = label,
                        onClick = { onPromptSelected(prompt) }
                    )
                }
            }
        }

        // Inset Grouped Section for Common Capabilities
        item {
            MaxIOSSettingsSection(title = "Capabilities") {
                MaxIOSSettingsRow(
                    title = "Device & App Automation",
                    subtitle = "Split-screen, open apps, control settings",
                    icon = Icons.Default.AutoAwesome,
                    iconBg = MaxIOSColors.SystemBlue,
                    onClick = { onNavigateTo(com.example.ui.navigation.MaxDestinations.CAPABILITIES) },
                    showDivider = true
                )
                MaxIOSSettingsRow(
                    title = "Memory Vault",
                    subtitle = "Search & manage personal AI knowledge",
                    icon = Icons.Default.FolderShared,
                    iconBg = MaxIOSColors.SystemPurple,
                    onClick = { onNavigateTo(com.example.ui.navigation.MaxDestinations.MEMORY) },
                    showDivider = true
                )
                MaxIOSSettingsRow(
                    title = "Reminders & Routines",
                    subtitle = "Manage scheduled tasks and automated workflows",
                    icon = Icons.Default.Alarm,
                    iconBg = MaxIOSColors.SystemAmber,
                    onClick = { onNavigateTo(com.example.ui.navigation.MaxDestinations.ROUTINES) },
                    showDivider = false
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// USER MESSAGE BUBBLE (Compact Right-Aligned iOS Pill)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSUserMessageBubble(
    message: ChatMessageItem,
    onCopyText: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                    .background(MaxIOSColors.SystemBlue)
                    .clickable { onCopyText() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            Text(
                text = formattedTime,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaxIOSColors.TextTertiaryDark,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAX MESSAGE BUBBLE (Open Conversational Left-Aligned Layout)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSMaxMessageBubble(
    message: ChatMessageItem,
    onCopyText: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            MaxGlassSurface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                backgroundColor = MaxIOSColors.DarkSurfaceGrouped,
                borderColor = MaxIOSColors.HairlineDark,
                borderWidth = 0.5.dp,
                modifier = Modifier.clickable { onCopyText() }
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        MarkdownChatText(text = message.text)
                    }
                }
            }

            Text(
                text = if (message.isPending) "MAX • Generating…" else "MAX • $formattedTime",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (message.isPending) MaxIOSColors.SystemBlue else MaxIOSColors.TextTertiaryDark,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TYPING INDICATOR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val dot1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 150, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 300, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(MaxIOSShapes.ShapeMedium)
                .background(MaxIOSColors.DarkSurfaceGrouped)
                .border(0.5.dp, MaxIOSColors.HairlineDark, MaxIOSShapes.ShapeMedium)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .offset(y = dot1.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxIOSColors.TextSecondaryDark)
                )
                Box(
                    modifier = Modifier
                        .offset(y = dot2.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxIOSColors.TextSecondaryDark)
                )
                Box(
                    modifier = Modifier
                        .offset(y = dot3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxIOSColors.TextSecondaryDark)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// QUICK ACTIONS DRAWER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSQuickActionsCard(
    onSelectPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    MaxGlassSurface(
        shape = MaxIOSShapes.ShapeLarge,
        backgroundColor = MaxIOSColors.DarkSurfaceElevated,
        borderColor = MaxIOSColors.HairlineDark,
        borderWidth = 0.5.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Quick Actions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxIOSColors.TextPrimaryDark
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaxIOSColors.TextSecondaryDark, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val quickItems = listOf(
                "💬 Message" to "Send a WhatsApp message",
                "🗺️ Split Screen Apps" to "Open Maps and Instagram in split screen",
                "🔍 Vision" to "Read what's on my screen",
                "⏰ Alarm" to "Set an alarm for 7:00 AM",
                "📝 Memory" to "Save a note in memory"
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quickItems.forEach { (title, prompt) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaxIOSShapes.ShapeMedium)
                            .clickable { onSelectPrompt(prompt) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            color = MaxIOSColors.TextPrimaryDark
                        )
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaxIOSColors.SystemBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARKDOWN TEXT RENDERER & CLIPBOARD HELPER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownChatText(text: String) {
    val cleanText = text.trim()
    val lines = cleanText.lines()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("```") -> {
                    // Skip markdown code fence markers
                }
                trimmedLine.startsWith("# ") -> {
                    Text(
                        text = trimmedLine.removePrefix("# "),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxIOSColors.TextPrimaryDark,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                trimmedLine.startsWith("## ") -> {
                    Text(
                        text = trimmedLine.removePrefix("## "),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxIOSColors.TextPrimaryDark,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(text = "•", color = MaxIOSColors.SystemBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = buildAnnotatedInline(trimmedLine.substring(2)),
                            color = MaxIOSColors.TextPrimaryDark,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
                else -> {
                    if (trimmedLine.isNotEmpty()) {
                        Text(
                            text = buildAnnotatedInline(trimmedLine),
                            color = MaxIOSColors.TextPrimaryDark,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
            }
        }
    }
}

private fun buildAnnotatedInline(text: String) = buildAnnotatedString {
    var cursor = 0
    val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
    val matches = boldRegex.findAll(text)

    for (match in matches) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaxIOSColors.TextPrimaryDark)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("MAX Response", text)
    clipboard?.setPrimaryClip(clip)
}
