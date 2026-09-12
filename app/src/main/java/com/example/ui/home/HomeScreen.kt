package com.example.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.activity.AgentActivityState
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.activity.AgentStage
import com.example.network.ConnectionState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.Emerald400
import com.example.ui.theme.MaxAccent
import com.example.ui.theme.MaxBgDark
import com.example.ui.theme.MaxBorder
import com.example.ui.theme.MaxPrimary
import com.example.ui.theme.MaxSurface
import com.example.ui.theme.MaxSurfaceDark
import com.example.ui.theme.MaxSurfaceVariant
import com.example.ui.theme.MaxTextMuted
import com.example.ui.theme.MaxTextPrimary
import com.example.ui.theme.MaxTextSecondary
import com.example.ui.theme.NeonGold
import com.example.ui.theme.NeonPink
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Instagram-DM-Style AI Chat Interface for MAX.
 * Features:
 * - Conversation-first layout with smooth LazyColumn feed
 * - Compact top bar with live connection indicator
 * - Right-aligned user bubbles & left-aligned MAX bubbles with Markdown rendering
 * - Three-dot typing indicator (no neural orb)
 * - Compact inline expandable Agent Activity card
 * - Floating rounded pill composer with smooth send/mic transition & quick actions
 * - Integrated live voice mode in the composer area with audio visualizer
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
    val agentActivity by AgentActivityTracker.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf("") }
    var showQuickPrompts by remember { mutableStateOf(false) }

    // Floating "Scroll to bottom" badge detection
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

    // Auto-scroll when new messages arrive if near bottom
    LaunchedEffect(uiState.messages.size, uiState.liveAiCaption) {
        if (uiState.messages.isNotEmpty() && !isScrolledUp) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaxBgDark),
        containerColor = MaxBgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            InstagramDmTopBar(
                isMaxEnabled = uiState.isMaxEnabled,
                connectionState = uiState.connectionState,
                onMenuClick = onOpenDrawer,
                onToggleMax = { viewModel.setRuntimeEnabled(it) },
                onClearChat = { viewModel.clearChatHistory() },
                onNavigateToSettings = { onNavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Main Message Feed
            if (uiState.messages.isEmpty() && liveAiCaption.isBlank() && !uiState.isThinking) {
                InstagramDmEmptyState(
                    onPromptSelected = { prompt ->
                        viewModel.sendTextMessage(prompt)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { msg ->
                        if (msg.isUser) {
                            UserMessageBubble(
                                message = msg,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        } else {
                            MaxMessageBubble(
                                message = msg,
                                onCopyText = { copyToClipboard(context, msg.text) }
                            )
                        }
                    }

                    // Compact Inline Agent Activity Card (when executing actions)
                    if (agentActivity.stage != AgentStage.IDLE && !agentActivity.goal.isNullOrBlank()) {
                        item(key = "agent_activity_inline") {
                            InlineAgentActivityCard(
                                activityState = agentActivity,
                                onCancel = {
                                    viewModel.clearPendingAutomation()
                                    viewModel.interruptSpeech()
                                }
                            )
                        }
                    }

                    // Three-Dot Typing Indicator (when model is thinking)
                    if (uiState.isThinking && !uiState.isAiSpeaking) {
                        item(key = "typing_indicator") {
                            InstagramDmTypingIndicator()
                        }
                    }
                }
            }

            // Floating "Scroll to Bottom" Pill
            AnimatedVisibility(
                visible = isScrolledUp,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            if (uiState.messages.isNotEmpty()) {
                                listState.animateScrollToItem(uiState.messages.size - 1)
                            }
                        }
                    },
                    shape = CircleShape,
                    color = MaxSurface,
                    shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Scroll down",
                            tint = MaxPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New messages",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaxTextPrimary
                        )
                    }
                }
            }

            // Quick Prompt Suggestions Drawer (Opened by Plus Button)
            AnimatedVisibility(
                visible = showQuickPrompts,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 85.dp, start = 14.dp, end = 14.dp)
            ) {
                QuickPromptsCard(
                    onSelectPrompt = { prompt ->
                        showQuickPrompts = false
                        viewModel.sendTextMessage(prompt)
                    },
                    onDismiss = { showQuickPrompts = false }
                )
            }

            // Instagram-DM-Style Floating Rounded Composer with bottom background scrim
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaxBgDark.copy(alpha = 0.75f),
                                MaxBgDark,
                                MaxBgDark
                            )
                        )
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
            ) {
                InstagramDmComposer(
                    inputText = inputText,
                    onInputTextChanged = { inputText = it },
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
                    isRecording = uiState.isRecording,
                    isAiSpeaking = uiState.isAiSpeaking,
                    isThinking = uiState.isThinking,
                    audioRms = audioRms,
                    liveUserCaption = liveUserCaption,
                    liveAiCaption = liveAiCaption,
                    onStopVoice = {
                        viewModel.stopRecording()
                        viewModel.interruptSpeech()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR (Instagram DM Style: Compact, Clean, Subtle Status)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramDmTopBar(
    isMaxEnabled: Boolean,
    connectionState: ConnectionState,
    onMenuClick: () -> Unit,
    onToggleMax: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaxBgDark,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Menu Icon
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = "Open Drawer",
                        tint = MaxTextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Title & Live Status
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "MAX",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary,
                            letterSpacing = 0.5.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (!isMaxEnabled) MaxTextMuted
                                    else when (connectionState) {
                                        ConnectionState.CONNECTED -> Emerald400
                                        ConnectionState.CONNECTING -> NeonGold
                                        else -> MaxTextMuted
                                    }
                                )
                        )
                    }

                    Text(
                        text = if (!isMaxEnabled) "Paused"
                        else when (connectionState) {
                            ConnectionState.CONNECTED -> "Active now"
                            ConnectionState.CONNECTING -> "Connecting..."
                            else -> "Offline"
                        },
                        fontSize = 11.sp,
                        color = MaxTextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Options Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaxTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(MaxSurface)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (isMaxEnabled) "MAX Enabled" else "MAX Disabled",
                                        color = MaxTextPrimary,
                                        fontSize = 14.sp
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
                                            checkedTrackColor = MaxPrimary
                                        )
                                    )
                                }
                            },
                            onClick = { onToggleMax(!isMaxEnabled) }
                        )

                        HorizontalDivider(color = MaxBorder)

                        DropdownMenuItem(
                            text = { Text("Clear Chat History", color = MaxTextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaxTextMuted, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                onClearChat()
                                showMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Settings", color = MaxTextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = MaxTextMuted, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                onNavigateToSettings()
                                showMenu = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaxBorder,
                thickness = 1.dp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// USER MESSAGE BUBBLE (Right-Aligned, Rounded Gradient Pill)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
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
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0066FF), Color(0xFF2563EB))
                        )
                    )
                    .clickable { onCopyText() }
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            Text(
                text = formattedTime,
                fontSize = 10.sp,
                color = MaxTextMuted,
                modifier = Modifier.padding(top = 3.dp, end = 4.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAX MESSAGE BUBBLE (Left-Aligned, Clean White Surface, Markdown)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MaxMessageBubble(
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
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
                color = MaxSurface,
                shadowElevation = 1.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder),
                modifier = Modifier.clickable { onCopyText() }
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        MarkdownChatText(text = message.text)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp, start = 4.dp)
            ) {
                Text(
                    text = if (message.isPending) "MAX • Streaming..." else "MAX • $formattedTime",
                    fontSize = 10.sp,
                    color = if (message.isPending) MaxPrimary else MaxTextMuted
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STREAMING AI BUBBLE (Live Text Response)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MaxStreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = MaxSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaxPrimary.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                MarkdownChatText(text = text)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// THREE-DOT TYPING INDICATOR (Instagram DM Style)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramDmTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing_dots")
    val dot1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 150, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 300, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaxSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .offset(y = dot1.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxTextMuted)
                )
                Box(
                    modifier = Modifier
                        .offset(y = dot2.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxTextMuted)
                )
                Box(
                    modifier = Modifier
                        .offset(y = dot3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaxTextMuted)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INLINE COMPACT AGENT ACTIVITY CARD (Multi-Step Execution in Feed)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InlineAgentActivityCard(
    activityState: AgentActivityState,
    onCancel: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val stageColor = when (activityState.stage) {
        AgentStage.COMPLETED -> Emerald400
        AgentStage.FAILED -> NeonPink
        AgentStage.EXECUTING, AgentStage.VERIFYING -> NeonGold
        else -> MaxPrimary
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaxSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, stageColor.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stageColor)
                )

                Text(
                    text = if (activityState.stage == AgentStage.COMPLETED) "Completed"
                    else "Working on your request...",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextPrimary,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaxTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Goal Title
            activityState.goal?.let { goal ->
                Text(
                    text = goal,
                    fontSize = 12.sp,
                    color = MaxTextSecondary,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                )
            }

            // Expanded Step Details
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activityState.steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (step.status) {
                                "COMPLETED" -> Text("✓", color = Emerald400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                "RUNNING", "VERIFYING" -> Text("⏳", fontSize = 11.sp)
                                "FAILED" -> Text("✕", color = NeonPink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                else -> Text("•", color = MaxTextMuted, fontSize = 12.sp)
                            }
                            Text(
                                text = step.actionType + (if (!step.targetText.isNullOrBlank()) ": ${step.targetText}" else ""),
                                fontSize = 12.sp,
                                color = if (step.status == "RUNNING") MaxTextPrimary else MaxTextMuted
                            )
                        }
                    }

                    if (activityState.stage !in listOf(AgentStage.COMPLETED, AgentStage.FAILED, AgentStage.IDLE)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cancel",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeonPink,
                            modifier = Modifier
                                .clickable { onCancel() }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPOSER (Instagram DM Floating Rounded Pill Bar)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramDmComposer(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onPlusClick: () -> Unit,
    isRecording: Boolean,
    isAiSpeaking: Boolean,
    isThinking: Boolean,
    audioRms: Float,
    liveUserCaption: String,
    liveAiCaption: String,
    onStopVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVoiceActive = isRecording || isAiSpeaking

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaxSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder),
        shadowElevation = 8.dp,
        modifier = modifier.animateContentSize()
    ) {
        if (isVoiceActive) {
            // Live Voice Mode Panel inside Composer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Waveform Indicator
                VoiceWaveformBar(audioRms = audioRms, isSpeaking = isAiSpeaking)

                // Subtitle / Status Text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAiSpeaking) "MAX is speaking..." else "Listening...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAiSpeaking) MaxAccent else MaxPrimary
                    )
                    val caption = if (isAiSpeaking) liveAiCaption else liveUserCaption
                    if (caption.isNotBlank()) {
                        Text(
                            text = caption,
                            fontSize = 11.sp,
                            color = MaxTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Close Voice Button
                IconButton(
                    onClick = onStopVoice,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaxSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop voice",
                        tint = MaxTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            // Standard Text Composer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Plus Button (Quick Prompts)
                IconButton(
                    onClick = onPlusClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Quick Actions",
                        tint = MaxPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Multiline Input Text Field
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Message MAX...",
                            fontSize = 14.5.sp,
                            color = MaxTextMuted
                        )
                    }

                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputTextChanged,
                        textStyle = TextStyle(
                            color = MaxTextPrimary,
                            fontSize = 14.5.sp,
                            lineHeight = 19.sp
                        ),
                        cursorBrush = SolidColor(MaxPrimary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { onSend() }
                        ),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Send or Mic Button (Smooth Transition)
                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(MaxPrimary, MaxAccent)
                                )
                            )
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
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = MaxPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VOICE WAVEFORM BARS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceWaveformBar(audioRms: Float, isSpeaking: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val waveAnim by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave_anim"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(24.dp)
    ) {
        val barColor = if (isSpeaking) MaxAccent else MaxPrimary
        val normalizedRms = (audioRms / 10f).coerceIn(0.15f, 1f)

        listOf(0.4f, 0.8f, 1.0f, 0.7f, 0.3f).forEachIndexed { i, factor ->
            val heightRatio = if (isSpeaking) {
                ((waveAnim * factor) * 18).coerceIn(4f, 20f)
            } else {
                ((normalizedRms * factor + waveAnim * 0.2f) * 18).coerceIn(4f, 20f)
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(heightRatio.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EMPTY STATE (Minimal, No Hero Graphics, Prompt Chips)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InstagramDmEmptyState(
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Minimal MAX Badge
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaxPrimary.copy(alpha = 0.15f), MaxAccent.copy(alpha = 0.15f))
                    )
                )
                .border(1.dp, MaxPrimary.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaxPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "MAX",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaxTextPrimary
        )

        Text(
            text = "Your personal AI assistant.\nAsk MAX anything or tell MAX what to do.",
            fontSize = 13.sp,
            color = MaxTextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // Suggestion Chips
        val suggestions = listOf(
            "Send a WhatsApp message to Mom",
            "Set an alarm for 7 AM",
            "Read what's on my screen",
            "Check today's reminders"
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { prompt ->
                Surface(
                    onClick = { onPromptSelected(prompt) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaxSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = prompt,
                            fontSize = 13.sp,
                            color = MaxTextPrimary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaxPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// QUICK PROMPTS CARD (Popup from Plus Button)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickPromptsCard(
    onSelectPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaxSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaxBorder),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Quick Actions",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaxTextPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaxTextMuted, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val quickItems = listOf(
                "💬 Message" to "Send a WhatsApp message to ",
                "⏰ Alarm" to "Set an alarm for 7:00 AM",
                "📅 Calendar" to "What is on my calendar today?",
                "👁️ Vision" to "Read what is on my screen"
            )

            quickItems.forEach { (label, prompt) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectPrompt(prompt) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaxPrimary,
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        text = prompt,
                        fontSize = 12.sp,
                        color = MaxTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LIGHTWEIGHT MARKDOWN TEXT PARSER FOR CHAT
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownChatText(text: String) {
    val annotatedString = remember(text) {
        buildAnnotatedString {
            val lines = text.split("\n")
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("### ")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = MaxPrimary)) {
                        append(trimmed.removePrefix("### "))
                    }
                } else if (trimmed.startsWith("## ")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaxPrimary)) {
                        append(trimmed.removePrefix("## "))
                    }
                } else if (trimmed.startsWith("# ")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaxPrimary)) {
                        append(trimmed.removePrefix("# "))
                    }
                } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    withStyle(SpanStyle(color = MaxPrimary, fontWeight = FontWeight.Bold)) {
                        append("• ")
                    }
                    appendFormattedInline(trimmed.substring(2))
                } else {
                    appendFormattedInline(line)
                }
                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    Text(
        text = annotatedString,
        color = MaxTextPrimary,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendFormattedInline(line: String) {
    val parts = line.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaxTextPrimary)) {
                append(part)
            }
        } else {
            // Check for inline code `...`
            val codeParts = part.split("`")
            codeParts.forEachIndexed { ci, cpart ->
                if (ci % 2 == 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaxSurfaceVariant, color = MaxPrimary)) {
                        append(" $cpart ")
                    }
                } else {
                    append(cpart)
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("MAX Message", text)
    clipboard?.setPrimaryClip(clip)
}
