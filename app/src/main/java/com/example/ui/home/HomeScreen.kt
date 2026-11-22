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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.firebase.manager.EntitlementGate
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val edgeRevealTrigger by viewModel.edgeRevealTrigger.collectAsStateWithLifecycle()
    val cloudLicense by viewModel.cloudLicense.collectAsStateWithLifecycle()
    val dailyUsage by viewModel.dailyUsage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigate by rememberUpdatedState(onNavigateTo)

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
    LaunchedEffect(viewModel, lifecycleOwner) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is HomeUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is HomeUiEvent.RequestPermission -> micPermissionLauncher.launch(event.permission)
                is HomeUiEvent.NavigateTo -> navigate(event.route)
                // The feed follows actual content changes only while near the
                // bottom; a token must not pull someone away from older text.
                is HomeUiEvent.ScrollToBottom -> Unit
            }
        }
      }
    }

    // Auto-connect on screen display
    LaunchedEffect(Unit) {
        viewModel.ensureConnected()
    }

    MaxScreen(
        auroraMode = AuroraMode.SUBTLE,
        showTopBar = false,
        scrollable = false,
        modifier = modifier
    ) {
        HomeScreenContent(
            uiState = uiState,
            isDarkMode = isDarkMode,
            edgeRevealTrigger = edgeRevealTrigger,
            cloudLicense = cloudLicense,
            dailyUsage = dailyUsage,
            audioRms = viewModel.audioRms,
            onOpenDrawer = onOpenDrawer,
            onNavigateTo = onNavigateTo,
            onToggleDarkMode = viewModel::toggleDarkMode,
            onSetRuntimeEnabled = viewModel::setRuntimeEnabled,
            onClearChatHistory = viewModel::clearChatHistory,
            onClearError = viewModel::clearError,
            onCancelActiveTask = viewModel::cancelActiveTask,
            onSendTextMessage = viewModel::sendTextMessage,
            onStopRecording = viewModel::stopRecording,
            onInterruptSpeech = viewModel::interruptSpeech,
            onSelectLanguage = viewModel::selectLanguage,
            onRequestMicPermission = { micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxSize(),
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
internal fun HomeScreenContent(
    uiState: HomeUiState,
    isDarkMode: Boolean,
    edgeRevealTrigger: Long?,
    cloudLicense: com.example.firebase.model.CloudLicense,
    dailyUsage: Long,
    audioRms: kotlinx.coroutines.flow.StateFlow<Float>,
    onOpenDrawer: () -> Unit,
    onNavigateTo: (String) -> Unit,
    onToggleDarkMode: () -> Unit,
    onSetRuntimeEnabled: (Boolean) -> Unit,
    onClearChatHistory: () -> Unit,
    onClearError: () -> Unit,
    onCancelActiveTask: () -> Unit,
    onSendTextMessage: (String) -> Unit,
    onStopRecording: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onSelectLanguage: (String) -> Unit = {},
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val activityState = uiState.activity
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var showQuickPrompts by rememberSaveable { mutableStateOf(false) }
    val presentation = uiState.presentation
    val isScrolledUp by remember {
        derivedStateOf {
            listState.canScrollForward &&
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) <
                listState.layoutInfo.totalItemsCount - 2
        }
    }
    val lastMessage = uiState.messages.lastOrNull()
    LaunchedEffect(uiState.messages.size, lastMessage?.text, presentation.phase) {
        if (!isScrolledUp && !listState.isScrollInProgress) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .edgeRevealAnimation(triggerKey = edgeRevealTrigger),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            IOSTopBar(
                isMaxEnabled = uiState.isMaxEnabled,
                connectionState = uiState.connectionState,
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode,
                onMenuClick = onOpenDrawer,
                onToggleMax = onSetRuntimeEnabled,
                onClearChat = onClearChatHistory,
                onNavigateToSettings = { onNavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            TrialQuotaBanner(
                license = cloudLicense,
                dailyUsage = dailyUsage,
                onClick = { onNavigateTo(com.example.ui.navigation.MaxDestinations.LICENSE) }
            )

            LanguageSelectorBar(
                selectedLanguage = uiState.selectedLanguage,
                onSelectLanguage = onSelectLanguage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Main Message Feed or Empty State
            if (uiState.messages.isEmpty() && !presentation.canStop) {
                IOSDashboardEmptyState(
                    onPromptSelected = { prompt: String ->
                        inputText = prompt
                    },
                    onSendTextMessage = onSendTextMessage,
                    onMicClick = onRequestMicPermission,
                    presentation = presentation,
                    audioRms = audioRms,
                    onNavigateTo = onNavigateTo,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = uiState.messages,
                        key = { _, msg -> msg.id }
                    ) { index, msg ->
                        val showIdentity = index == 0 || uiState.messages[index - 1].isUser != msg.isUser
                        if (msg.isUser) {
                            IOSUserMessageBubble(
                                message = msg,
                                showIdentity = showIdentity,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        } else {
                            IOSMaxMessageBubble(
                                message = msg,
                                showIdentity = showIdentity,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        }
                    }

                    // The pending chat item is the only streaming text surface.
                    if (presentation.phase == AssistantPhase.THINKING) {
                        item(key = "typing_indicator") {
                            IOSTypingIndicator()
                        }
                    }
                }
            }

            // Floating "Scroll to Bottom" Pill
            androidx.compose.animation.AnimatedVisibility(
                visible = isScrolledUp && uiState.messages.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            val last = listState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) listState.animateScrollToItem(last)
                        }
                    },
                    shape = MaxShapes.pill,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(MaxStroke.hairline, MaxColors.extra.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Scroll down",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MaxIconTokens.Small)
                        )
                        Text(
                            text = "New messages",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
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
                    .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
            ) {
                IOSQuickActionsCard(
                    onSelectPrompt = { prompt ->
                        showQuickPrompts = false
                        inputText = prompt
                    },
                    onDismiss = { showQuickPrompts = false }
                )
            }

            }
            // Measured layout, not an overlay: the composer never covers chat.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .padding(bottom = 8.dp)
            ) {
                if (uiState.error != null) {
                    AssistantStatusStrip(presentation, uiState.isMaxEnabled,
                        onDismissError = onClearError)
                }
                if (presentation.phase == AssistantPhase.LISTENING && uiState.liveUserCaption.isNotBlank()) {
                    Text(uiState.liveUserCaption, color = MaxSemanticColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                }
                // Live Agent Task Execution Activity Card with Stop Control
                androidx.compose.animation.AnimatedVisibility(
                    visible = activityState.active || (presentation.phase in setOf(
                        AssistantPhase.COMPLETED, AssistantPhase.CANCELLED, AssistantPhase.FAILED,
                        AssistantPhase.BLOCKED, AssistantPhase.UNVERIFIED) && !activityState.goal.isNullOrBlank()),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    com.example.ui.components.AgentActivityCard(
                        modifier = Modifier.fillMaxWidth(),
                        presentation = presentation,
                        activityState = activityState,
                        onDismiss = { com.example.automation.activity.AgentActivityTracker.reset() },
                        onStopTask = onCancelActiveTask
                    )
                }

                MaxInputDock(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        val textToSend = inputText.trim()
                        if (textToSend.isNotEmpty()) {
                            onSendTextMessage(textToSend)
                            inputText = ""
                            keyboardController?.hide()
                        }
                    },
                    onMicClick = {
                        if (uiState.isRecording) onStopRecording()
                        else onRequestMicPermission()
                    },
                    onPlusClick = {
                        showQuickPrompts = !showQuickPrompts
                    },
                    isListening = presentation.phase == AssistantPhase.LISTENING,
                    isSpeaking = presentation.phase == AssistantPhase.SPEAKING,
                    isBusy = presentation.canStop && presentation.phase !in setOf(
                        AssistantPhase.WAITING_FOR_INPUT, AssistantPhase.WAITING_FOR_APPROVAL,
                        AssistantPhase.LISTENING),
                    isCancelling = presentation.phase == AssistantPhase.CANCELLING,
                    onStopTask = onCancelActiveTask,
                    enabled = uiState.isMaxEnabled,
                    placeholder = if (presentation.phase == AssistantPhase.WAITING_FOR_APPROVAL) "Reply Allow or Cancel…"
                        else if (presentation.phase == AssistantPhase.WAITING_FOR_INPUT) "Your answer…" else "Message MAX…",
                    onStopSpeaking = {
                        onStopRecording()
                        onInterruptSpeech()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
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
    val isLimitReached = dailyUsage >= EntitlementGate.DEFAULT_DAILY_LIMIT
    val remainingDays = license.remainingTrialDays()
    val isDark = com.example.ui.theme.LocalIsDarkMode.current

    val banner = when {
        isExpired -> {
            TrialBannerData(
                if (isDark) listOf(MaxSemanticColors.destructiveSurface, MaxSemanticColors.destructiveSurface.copy(alpha = 0.6f))
                else listOf(MaxDestructiveApple.copy(alpha = 0.12f), MaxDestructiveApple.copy(alpha = 0.05f)),
                "Free Trial Expired",
                "Your 7-day trial has ended. Tap to activate Lifetime Premium.",
                Icons.Rounded.ErrorOutline,
                MaxDestructiveApple
            )
        }
        isLimitReached -> {
            TrialBannerData(
                if (isDark) listOf(MaxSemanticColors.warningSurface, MaxSemanticColors.warningSurface.copy(alpha = 0.6f))
                else listOf(MaxWarningApple.copy(alpha = 0.15f), MaxWarningApple.copy(alpha = 0.08f)),
                "Daily Free Limit Reached (${EntitlementGate.DEFAULT_DAILY_LIMIT}/${EntitlementGate.DEFAULT_DAILY_LIMIT})",
                "Daily quota used. Tap to activate Lifetime Premium.",
                Icons.Rounded.Warning,
                MaxWarningApple
            )
        }
        else -> {
            TrialBannerData(
                if (isDark) listOf(MaxSemanticColors.primaryRoyal.copy(alpha = 0.25f), MaxSemanticColors.surfaceVariant)
                else listOf(com.example.ui.theme.SoftRoseTint, Color.White),
                "7-Day Free Trial Active",
                "$remainingDays days remaining • $dailyUsage/${EntitlementGate.DEFAULT_DAILY_LIMIT} messages used today",
                Icons.Rounded.HourglassTop,
                if (isDark) MaxColors.extra.link else MaterialTheme.colorScheme.primary
            )
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaxShapes.card,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            MaxStroke.hairline,
            if (isDark) banner.iconColor.copy(alpha = 0.4f) else MaxColors.extra.borderStrong
        )
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(banner.iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = banner.icon,
                        contentDescription = null,
                        tint = banner.iconColor,
                        modifier = Modifier.size(MaxIconTokens.Medium)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = banner.text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = banner.subtext,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaxColors.extra.textSecondary
                    )
                }

                Box(
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaxShapes.pill,
                        color = if (isDark) banner.iconColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(
                            MaxStroke.hairline,
                            if (isDark) banner.iconColor.copy(alpha = 0.5f) else MaxColors.extra.borderStrong
                        )
                    ) {
                        Text(
                            text = "UPGRADE",
                            color = if (isDark) banner.iconColor else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LANGUAGE SELECTOR BAR (Tinglish, Telugu, English, Hindi)
// ─────────────────────────────────────────────────────────────────────────────

data class HomeLanguageOption(
    val id: String,
    val label: String,
    val badge: String
)

val HOME_LANGUAGE_OPTIONS = listOf(
    HomeLanguageOption("Tinglish", "Tinglish", "TE+EN"),
    HomeLanguageOption("Telugu", "తెలుగు", "Telugu"),
    HomeLanguageOption("English", "English", "English"),
    HomeLanguageOption("Hindi", "हिंदी", "Hindi")
)

@Composable
internal fun LanguageSelectorBar(
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        border = androidx.compose.foundation.BorderStroke(MaxStroke.hairline, MaxColors.extra.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HOME_LANGUAGE_OPTIONS.forEach { opt ->
                val isSelected = selectedLanguage.equals(opt.id, ignoreCase = true) ||
                        (opt.id.equals("Tinglish", ignoreCase = true) && selectedLanguage.equals("Tenglish", ignoreCase = true))

                val animatedBg by animateColorAsState(
                    targetValue = if (isSelected) MaxSemanticColors.accentCyan.copy(alpha = 0.22f) else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "langBg"
                )
                val animatedBorderColor by animateColorAsState(
                    targetValue = if (isSelected) MaxSemanticColors.accentCyan.copy(alpha = 0.70f) else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "langBorder"
                )
                val animatedTextColor by animateColorAsState(
                    targetValue = if (isSelected) MaxSemanticColors.accentCyan else Color.White.copy(alpha = 0.75f),
                    animationSpec = tween(durationMillis = 200),
                    label = "langText"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(animatedBg)
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = animatedBorderColor,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelectLanguage(opt.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = opt.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp
                        ),
                        color = animatedTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR (Minimalist iOS Header)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun IOSTopBar(
    isMaxEnabled: Boolean,
    connectionState: ConnectionState,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onMenuClick: () -> Unit,
    onToggleMax: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeTopPadding = if (statusBarTop > 0.dp) statusBarTop else 24.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(top = safeTopPadding)
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
                    .size(48.dp)
                    .clip(MaxShapes.card)
                    .background(MaxSurfaceElevated)
                    .border(MaxStroke.hairline, MaxBorderHairline, MaxShapes.card)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open Drawer",
                    tint = Color.White,
                    modifier = Modifier.size(MaxIconTokens.Medium)
                )
            }

            // Center Assistant Brand Title (Jarvis Minimal Luxury)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isMaxEnabled) Color.White.copy(alpha = 0.4f)
                            else when (connectionState) {
                                ConnectionState.CONNECTED -> MaxColors.extra.glow
                                ConnectionState.CONNECTING -> MaxWarningApple
                                else -> Color.White.copy(alpha = 0.4f)
                            }
                        )
                )
                Text(
                    text = "MAX",
                    style = MaxType.Title,
                    color = MaxSemanticColors.accentCyan
                )
            }

            // Right Actions: Day/Night Mode Switch + Options Menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Day / Night Mode Toggle Icon Button
                IconButton(
                    onClick = onToggleDarkMode,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaxShapes.card)
                        .background(MaxSurfaceElevated)
                        .border(MaxStroke.hairline, MaxBorderHairline, MaxShapes.card)
                ) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay,
                        contentDescription = if (isDarkMode) "Switch to Day Mode" else "Switch to Night Mode",
                        tint = if (isDarkMode) MaxRoyalGold else Color.White,
                        modifier = Modifier.size(MaxIconTokens.Medium)
                    )
                }

                // Options Dropdown Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaxShapes.card)
                            .background(MaxSurfaceElevated)
                            .border(MaxStroke.hairline, MaxBorderHairline, MaxShapes.card)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White,
                            modifier = Modifier.size(MaxIconTokens.Medium)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (isMaxEnabled) "MAX Active" else "MAX Paused",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium,
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
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            },
                            onClick = { onToggleMax(!isMaxEnabled) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = MaxStroke.hairline)

                        DropdownMenuItem(
                            text = { Text("Clear Chat History", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Clear Chat History", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MaxIconTokens.Medium))
                            },
                            onClick = {
                                onClearChat()
                                showMenu = false
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = MaxStroke.hairline)

                        DropdownMenuItem(
                            text = { Text("Settings", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MaxIconTokens.Medium))
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
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD EMPTY STATE (Greeting + Reactive Orb + Quick Action Pills)
// ─────────────────────────────────────────────────────────────────────────────

enum class PromptMode { EXECUTE, PREFILL }

data class QuickPrompt(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val prompt: String,
    val mode: PromptMode
)

val quickPrompts = listOf(
    QuickPrompt("Open Maps", androidx.compose.material.icons.Icons.Rounded.Explore, "Open Maps", PromptMode.EXECUTE),
    QuickPrompt("Alarm at 7 AM", androidx.compose.material.icons.Icons.Rounded.Alarm, "Set an alarm for 7 AM", PromptMode.EXECUTE),
    QuickPrompt("Read screen", androidx.compose.material.icons.Icons.Rounded.Visibility, "Read my screen", PromptMode.EXECUTE),
    QuickPrompt("Summarize chats", androidx.compose.material.icons.Icons.Rounded.ChatBubble, "Summarize my chats from ", PromptMode.PREFILL),
    QuickPrompt("Search web", androidx.compose.material.icons.Icons.Rounded.Search, "Search the web for ", PromptMode.PREFILL)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IOSDashboardEmptyState(
    onPromptSelected: (String) -> Unit,
    onSendTextMessage: (String) -> Unit = {},
    onMicClick: () -> Unit,
    presentation: AssistantPresentation,
    audioRms: kotlinx.coroutines.flow.StateFlow<Float>,
    onNavigateTo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCapabilitiesSheet by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 32.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Assistant Orb Hero with Luminous Telemetry
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Clean Greeting & Subtitle
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Hello Yash",
                        style = MaxType.Title,
                        color = MaxSemanticColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Your Autonomous AI Partner",
                        style = MaxType.Body,
                        color = MaxSemanticColors.textSecondary
                    )
                }

                LiveAssistantOrb(presentation, audioRms, onMicClick)

                RotatingSuggestions(
                    suggestions = listOf(
                        "Open Chrome",
                        "Send a WhatsApp message",
                        "Set an alarm for 7 AM",
                        "Split screen apps",
                        "Read my screen"
                    ),
                    onSuggestionClick = onPromptSelected
                )

                Text(
                    text = "Tap the mic to talk to MAX",
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )
            }
        }

        // 2x2 Quick Automations Grid
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "QUICK AUTOMATIONS",
                        style = MaxType.Eyebrow,
                        color = MaxSemanticColors.textSecondary
                    )
                    Text(
                        text = "View all →",
                        style = MaxType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxSemanticColors.accentCyan,
                        modifier = Modifier.clickable { showCapabilitiesSheet = true }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MaxQuickAutomationCard(
                        title = "WhatsApp AI",
                        subtitle = "Automated chat flows",
                        badge = "Flow",
                        icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Chat,
                        onClick = { onPromptSelected("Send a WhatsApp message") },
                        modifier = Modifier.weight(1f)
                    )

                    MaxQuickAutomationCard(
                        title = "Screen Vision",
                        subtitle = "Analyze active screen",
                        badge = "Vision",
                        icon = androidx.compose.material.icons.Icons.Rounded.Visibility,
                        onClick = { onSendTextMessage("Read my screen") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MaxQuickAutomationCard(
                        title = "Device Control",
                        subtitle = "Automate settings & apps",
                        badge = "Tools",
                        icon = androidx.compose.material.icons.Icons.Rounded.Tune,
                        onClick = { showCapabilitiesSheet = true },
                        modifier = Modifier.weight(1f)
                    )

                    MaxQuickAutomationCard(
                        title = "Live Search",
                        subtitle = "Real-time AI research",
                        badge = "Search",
                        icon = androidx.compose.material.icons.Icons.Rounded.Search,
                        onClick = { onPromptSelected("Search the web for news") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Actions Row using TouchChip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPrompts.forEach { p ->
                    TouchChip(
                        label = p.label,
                        onClick = {
                            when (p.mode) {
                                PromptMode.EXECUTE -> onSendTextMessage(p.prompt)
                                PromptMode.PREFILL -> onPromptSelected(p.prompt)
                            }
                        },
                        leading = {
                            Icon(
                                imageVector = p.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MaxIconTokens.Small)
                            )
                        },
                        trailing = if (p.mode == PromptMode.PREFILL) {
                            {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.Edit,
                                    contentDescription = "Edit prompt before sending",
                                    tint = MaxColors.extra.textSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }

    if (showCapabilitiesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCapabilitiesSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Capabilities",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                MaxIOSSettingsRow(
                    title = "Make things happen",
                    subtitle = "Split-screen, open apps, control settings",
                    icon = androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                    iconBg = MaterialTheme.colorScheme.primary,
                    onClick = {
                        showCapabilitiesSheet = false
                        onNavigateTo(com.example.ui.navigation.MaxDestinations.CAPABILITIES)
                    },
                    showDivider = true
                )
                MaxIOSSettingsRow(
                    title = "Your memory vault",
                    subtitle = "Search & manage personal AI knowledge",
                    icon = androidx.compose.material.icons.Icons.Rounded.FolderShared,
                    iconBg = MaterialTheme.colorScheme.primary,
                    onClick = {
                        showCapabilitiesSheet = false
                        onNavigateTo(com.example.ui.navigation.MaxDestinations.MEMORY)
                    },
                    showDivider = true
                )
                MaxIOSSettingsRow(
                    title = "Stay a step ahead",
                    subtitle = "Manage scheduled tasks and automated workflows",
                    icon = androidx.compose.material.icons.Icons.Rounded.Alarm,
                    iconBg = MaterialTheme.colorScheme.tertiary,
                    onClick = {
                        showCapabilitiesSheet = false
                        onNavigateTo(com.example.ui.navigation.MaxDestinations.ROUTINES)
                    },
                    showDivider = false
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// USER MESSAGE BUBBLE (Compact Right-Aligned iOS Pill)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun IOSUserMessageBubble(
    message: ChatMessageItem,
    showIdentity: Boolean = true,
    onCopyText: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }
    val isDark = LocalIsDarkMode.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 560.dp)
        ) {
            if (showIdentity) Text(
                "YOU",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, bottom = 6.dp)
            )
            Box(
                modifier = Modifier
                    .clip(MaxShapes.bubbleUser)
                    .background(if (isDark) MaxSemanticColors.surfaceVariant else com.example.ui.theme.NeonPink)
                    .border(
                        MaxStroke.hairline,
                        if (isDark) MaxSemanticColors.borderStrong else com.example.ui.theme.MagentaRose,
                        MaxShapes.bubbleUser
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MaxMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAX MESSAGE BUBBLE (Stitch Luminous Neural Glassmorphic Left-Aligned Layout)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun IOSMaxMessageBubble(
    message: ChatMessageItem,
    showIdentity: Boolean = true,
    onCopyText: () -> Unit
) {
    val isDark = com.example.ui.theme.LocalIsDarkMode.current
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 640.dp)
        ) {
            if (showIdentity) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color.Black else com.example.ui.theme.SoftRoseTint)
                        .border(1.dp, if (isDark) com.example.ui.theme.NeonPink.copy(alpha = 0.5f) else com.example.ui.theme.SoftRoseBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    "MAX",
                    color = MaxSemanticColors.accentCyan,
                    style = MaxType.Eyebrow
                )
            }
            MaxGlassSurface(
                shape = MaxShapes.bubbleMax,
                backgroundColor = if (isDark) MaxSemanticColors.surfaceVariant else Color.White,
                borderColor = if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else com.example.ui.theme.SoftRoseBorder,
                borderWidth = MaxStroke.hairline,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        PremiumMessageText(text = message.text)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = if (message.isPending) "Streaming…" else formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MaxMono,
                color = if (message.isPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
              )
              Spacer(Modifier.weight(1f))
              if (!message.isPending) IconButton(onClick = onCopyText) {
                  Icon(
                      Icons.Rounded.ContentCopy,
                      "Copy response",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(MaxIconTokens.Small)
                  )
              }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TYPING INDICATOR (Stitch Aether HUD Aligned)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IOSTypingIndicator() {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        }
        Text(
            "MAX is processing…",
            color = MaxColors.extra.textSecondary,
            fontFamily = MaxMono,
            style = MaterialTheme.typography.bodySmall
        )
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
    val quickItems = remember {
        listOf(
            Triple(Icons.AutoMirrored.Rounded.Send, "Send message", "Send a WhatsApp message"),
            Triple(Icons.Rounded.ViewAgenda, "Split screen", "Open Maps and Instagram in split screen"),
            Triple(Icons.AutoMirrored.Rounded.StickyNote2, "Take note", "Save a note in memory")
        )
    }

    MaxGlassSurface(
        shape = MaxShapes.card,
        backgroundColor = MaxSemanticColors.surfaceElevated,
        borderColor = MaxColors.extra.borderSubtle,
        borderWidth = MaxStroke.hairline,
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MaxIconTokens.Medium)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quickItems.forEach { (icon, title, prompt) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(MaxShapes.card)
                            .clickable { onSelectPrompt(prompt) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MaxIconTokens.Medium)
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MaxIconTokens.Small)
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("MAX Response", text)
    clipboard?.setPrimaryClip(clip)
}

// ─────────────────────────────────────────────────────────────────────────────
// PREVIEW
// ─────────────────────────────────────────────────────────────────────────────

// Change preview visibility from private to internal so Compose Preview reflection can locate the method
@Preview(name = "HomeScreen Preview", showBackground = true)
@Composable
internal fun HomeScreenPreview() {
    MaxTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isMaxEnabled = true,
                connectionState = ConnectionState.CONNECTED,
                messages = listOf(
                    ChatMessageItem(id = 1, sender = "user", text = "Hello MAX, how are you?", timestamp = System.currentTimeMillis()),
                    ChatMessageItem(id = 2, sender = "max", text = "Hello! I am online and ready to assist you.", timestamp = System.currentTimeMillis())
                )
            ),
            isDarkMode = true,
            edgeRevealTrigger = null,
            cloudLicense = com.example.firebase.model.CloudLicense(userId = "preview_user"),
            dailyUsage = 2L,
            audioRms = remember { MutableStateFlow(0f) },
            onOpenDrawer = {},
            onNavigateTo = {},
            onToggleDarkMode = {},
            onSetRuntimeEnabled = {},
            onClearChatHistory = {},
            onClearError = {},
            onCancelActiveTask = {},
            onSendTextMessage = {},
            onStopRecording = {},
            onInterruptSpeech = {},
            onRequestMicPermission = {}
        )
    }
}
