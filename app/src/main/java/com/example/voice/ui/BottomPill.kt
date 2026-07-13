package com.example.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PrimaryLight
import com.example.ui.theme.SurfaceDarker
import com.example.ui.theme.TextLight

@Composable
fun BottomPill(
    isRecording: Boolean,
    statusText: String,
    visualizerRms: Float,
    onToggleMic: () -> Unit,
    onSendMessage: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textInput by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceDarker.copy(alpha = 0.9f))
            .glowBorder(RoundedCornerShape(28.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onAddClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add shortcut",
                tint = TextLight
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = textInput,
                onValueChange = { textInput = it },
                textStyle = TextStyle(color = TextLight, fontSize = 15.sp),
                cursorBrush = SolidColor(PrimaryLight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput)
                        textInput = ""
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (textInput.isEmpty()) {
                        Text(
                            text = if (isRecording) "Listening..." else "Ask MAX...",
                            color = TextLight.copy(alpha = 0.4f),
                            fontSize = 15.sp
                        )
                    }
                    innerTextField()
                }
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (textInput.isNotBlank()) {
            IconButton(
                onClick = {
                    onSendMessage(textInput)
                    textInput = ""
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send message",
                    tint = PrimaryLight
                )
            }
        } else {
            IconButton(
                onClick = onToggleMic,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Toggle microphone",
                    tint = if (isRecording) PrimaryLight else TextLight.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                SoundWave(
                    rms = visualizerRms,
                    isRecording = isRecording
                )
            }
        }
    }
}
