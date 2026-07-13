package com.example.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonPink
import com.example.voice.assistant.AssistantVoiceController
import com.example.voice.assistant.SessionState

@Composable
fun AssistantHud(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = remember { AssistantVoiceController() }
    val uiState by controller.uiState.collectAsState()
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        LiquidScreenEdges()

        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            uiState.error?.let { err ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = NeonPink.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Error: $err",
                        color = NeonPink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            TranscriptBubble(
                userTranscript = uiState.userTranscription,
                geminiResponse = uiState.geminiResponse
            )

            if (uiState.sessionState == SessionState.TOOL_EXECUTION) {
                Card(
                    modifier = Modifier.padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = uiState.statusText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            BottomPill(
                isRecording = uiState.isRecording,
                statusText = uiState.statusText,
                visualizerRms = uiState.visualizerRms,
                onToggleMic = { controller.toggleMic() },
                onSendMessage = { controller.sendTextMessage(it) },
                onAddClick = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launchIntent != null) {
                        launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        context.startActivity(launchIntent)
                    }
                    onDismiss()
                }
            )
        }
    }
}
