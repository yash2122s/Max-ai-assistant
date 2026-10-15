package com.example.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.network.ConnectionState
import com.example.ui.components.MaxInputDock
import com.example.ui.theme.MaxIOSColors
import com.example.ui.theme.MaxTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Synthetic component preview. No ViewModel, databases, audio or automation. */
@Composable
internal fun HomePreviewContent(phase: AssistantPhase, onStop: () -> Unit = {}) {
    val presentation = assistantPresentation(when (phase) {
        AssistantPhase.STREAMING -> AssistantSnapshot(streaming = true)
        AssistantPhase.WAITING_FOR_APPROVAL -> AssistantSnapshot(approvalQuestion = "Allow MAX to create this reminder?")
        AssistantPhase.FAILED -> AssistantSnapshot(terminal = phase, detail = "Connection lost. Reconnect and try again.")
        AssistantPhase.CANCELLING -> AssistantSnapshot(cancelling = true)
        AssistantPhase.IDLE -> AssistantSnapshot()
        else -> AssistantSnapshot(terminal = phase)
    })
    var draft by remember { mutableStateOf("") }
    MaxTheme {
        Column(Modifier.fillMaxSize().background(MaxIOSColors.DarkBase)) {
            IOSTopBar(
                isMaxEnabled = true,
                connectionState = ConnectionState.CONNECTED,
                isDarkMode = true,
                onToggleDarkMode = {},
                onMenuClick = {},
                onToggleMax = {},
                onClearChat = {},
                onNavigateToSettings = {}
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (phase == AssistantPhase.IDLE) {
                    IOSDashboardEmptyState(
                        onPromptSelected = { draft = it },
                        onSendTextMessage = {},
                        onMicClick = {},
                        presentation = presentation,
                        audioRms = remember { MutableStateFlow(0f) },
                        onNavigateTo = {}
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        item { IOSUserMessageBubble(ChatMessageItem(1, "user", "Help me plan a calmer morning.", 0L), onCopyText = {}) }
                        item {
                            IOSMaxMessageBubble(ChatMessageItem(2, "jarvis",
                                "## A little room to breathe\nStart with **one small step**.\n\n- Take a short walk\n- Choose today's priority\n\n```kotlin\nval focus = \"One thing at a time\"\n```",
                                0L, isPending = phase == AssistantPhase.STREAMING), onCopyText = {})
                        }
                    }
                }
            }
            AssistantStatusStrip(presentation)
            MaxInputDock(draft, { draft = it }, { draft = "" }, {}, {},
                isListening = false, isSpeaking = false, onStopSpeaking = onStop,
                isBusy = phase == AssistantPhase.STREAMING,
                isCancelling = phase == AssistantPhase.CANCELLING, onStopTask = onStop,
                placeholder = if (phase == AssistantPhase.WAITING_FOR_APPROVAL) "Reply Allow or Cancel…" else "Message MAX…")
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(name = "Ready", widthDp = 390, heightDp = 844)
@Composable internal fun ReadyPreview() = HomePreviewContent(AssistantPhase.IDLE)

@Preview(name = "Streaming · compact", widthDp = 320, heightDp = 640)
@Composable internal fun StreamingPreview() = HomePreviewContent(AssistantPhase.STREAMING)

@Preview(name = "Approval", widthDp = 390, heightDp = 844)
@Composable internal fun ApprovalPreview() = HomePreviewContent(AssistantPhase.WAITING_FOR_APPROVAL)

@Preview(name = "Error", widthDp = 320, heightDp = 640)
@Composable internal fun ErrorPreview() = HomePreviewContent(AssistantPhase.FAILED)

@Preview(name = "Cancelled", widthDp = 390, heightDp = 844)
@Composable internal fun CancelledPreview() = HomePreviewContent(AssistantPhase.CANCELLED)
