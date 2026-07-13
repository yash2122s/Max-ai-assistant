package com.example.voice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.example.ui.theme.BgDark
import com.example.ui.theme.TextLight

@Composable
fun TranscriptBubble(
    userTranscript: String,
    geminiResponse: String,
    modifier: Modifier = Modifier
) {
    val showBubble = userTranscript.isNotEmpty() || geminiResponse.isNotEmpty()

    AnimatedVisibility(
        visible = showBubble,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BgDark.copy(alpha = 0.88f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (userTranscript.isNotEmpty()) {
                Column {
                    Text(
                        text = "YOU",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF93C5FD),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = userTranscript,
                        fontSize = 14.sp,
                        color = TextLight,
                        lineHeight = 18.sp
                    )
                }
            }
            
            if (geminiResponse.isNotEmpty()) {
                Column {
                    Text(
                        text = "MAX",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC084FC),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = geminiResponse,
                        fontSize = 14.sp,
                        color = TextLight,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
