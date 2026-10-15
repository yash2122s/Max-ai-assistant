package com.example.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun PrivacyScreen(
    onNavigateBack: () -> Unit
) {
    val privacyItems = listOf(
        "Data Stored on Your Device" to "MAX stores everything locally on YOUR phone: settings, long-term memories, cycle logs, reminders, and conversation history. Nothing is uploaded to any proprietary server — MAX has no servers.",
        "Your AI API Key" to "Your API key is stored strictly on-device in Android Keystore-backed encrypted storage and is only used to establish a secure direct TLS connection with the configured AI provider.",
        "Voice & Screen Capture" to "When interacting via voice, microphone audio (and screen capture frames, strictly when explicitly requested) stream directly to the neural assistant engine for response synthesis. No audio is persisted to disk.",
        "Notifications, Contacts & Calls" to "Notification content, contact identities, and phone call state are inspected ON-DEVICE only when an action requires them (such as announcing an incoming call). Disabled apps are dropped instantly in memory.",
        "Zero Analytics & Zero Ads" to "This application contains zero tracking SDKs, zero telemetry collectors, zero third-party analytics libraries, and zero advertising frameworks.",
        "Absolute User Control" to "You can wipe MAX memories at any time (via Memory Wallet or voice command), clear health logs, clear application data from Android Settings, or revoke any runtime permission at will."
    )

    MaxScreen(
        title = "Privacy Policy",
        eyebrow = "TRUST & TRANSPARENCY",
        auroraMode = AuroraMode.SUBTLE,
        scrollable = true,
        onBack = onNavigateBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MaxDimensions.spaceMd, vertical = MaxDimensions.spaceSm),
            verticalArrangement = Arrangement.spacedBy(MaxDimensions.spaceMd)
        ) {
            // Guarantee Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                    .background(MaxSemanticColors.accentCyan.copy(alpha = 0.1f))
                    .padding(MaxDimensions.spaceMd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaxSemanticColors.accentCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Local-First • Zero-Telemetry • Keystore Encrypted",
                        style = MaxType.Body,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.accentCyan
                    )
                }
            }

            // Privacy Points
            privacyItems.forEach { (title, desc) ->
                MaxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(MaxDimensions.spaceMd)) {
                        Text(
                            text = title,
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(MaxDimensions.space2xs))
                        Text(
                            text = desc,
                            style = MaxType.Body,
                            color = MaxSemanticColors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
        }
    }
}
