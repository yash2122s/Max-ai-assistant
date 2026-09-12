package com.example.voice.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun TranscriptBubble(
    userTranscript: String,
    geminiResponse: String,
    modifier: Modifier = Modifier
) {
    val showBubble = userTranscript.isNotEmpty() || geminiResponse.isNotEmpty()

    AnimatedVisibility(
        visible = showBubble,
        enter = fadeIn() + slideInVertically(initialOffsetY = { 30 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { 30 }),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SlateSurface.copy(alpha = 0.95f))
                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (userTranscript.isNotEmpty()) {
                Column {
                    Text(
                        text = "YOU",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = userTranscript,
                        fontSize = 14.sp,
                        color = TextLight,
                        lineHeight = 19.sp
                    )
                }
            }
            
            if (geminiResponse.isNotEmpty()) {
                Column {
                    Text(
                        text = "MAX AI",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentIndigo,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = geminiResponse,
                        fontSize = 14.sp,
                        color = TextLight,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

