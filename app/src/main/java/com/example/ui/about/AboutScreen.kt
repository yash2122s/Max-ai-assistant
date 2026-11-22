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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToWebDashboard: () -> Unit = {}
) {
    MaxScreen(
        title = "About System",
        eyebrow = "SYSTEM PROFILE",
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
            // Hero Logo & Title Card
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaxDimensions.spaceLg, horizontal = MaxDimensions.spaceMd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(MaxDimensions.radiusLg))
                            .background(
                                Brush.linearGradient(
                                    listOf(MaxSemanticColors.accentCyan, MaxSemanticColors.primaryRoyal)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "M",
                            style = MaxType.Display,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                    Text(
                        text = "M A X",
                        style = MaxType.Title,
                        fontWeight = FontWeight.Black,
                        color = MaxSemanticColors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Autonomous Personal AI Assistant",
                        style = MaxType.Body,
                        fontWeight = FontWeight.Bold,
                        color = MaxSemanticColors.accentCyan
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Version 3.0.0 • Build 36 • Android 14+",
                        style = MaxType.Caption,
                        color = MaxSemanticColors.textMuted
                    )
                }
            }

            // Creator Card
            MaxCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaxDimensions.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(MaxDimensions.radiusMd))
                            .background(MaxSemanticColors.accentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaxSemanticColors.accentCyan,
                            modifier = Modifier.size(MaxIconTokens.Large)
                        )
                    }

                    Column {
                        Text(
                            text = "Engineered by Yaswanth",
                            style = MaxType.Headline,
                            fontWeight = FontWeight.Bold,
                            color = MaxSemanticColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Full-stack on-device architecture & neural agents",
                            style = MaxType.Caption,
                            color = MaxSemanticColors.textSecondary
                        )
                    }
                }
            }

            // System Capabilities Section
            Text(
                text = "SYSTEM ARCHITECTURE",
                style = MaxType.Eyebrow,
                color = MaxSemanticColors.textMuted
            )

            FeatureCard(
                icon = Icons.Default.Mic,
                title = "Realtime Voice Intelligence",
                description = "Ultra-low latency conversational neural voice processing with seamless hands-free interaction.",
                tint = MaxSemanticColors.accentCyan
            )

            FeatureCard(
                icon = Icons.Default.DesktopWindows,
                title = "Windows Companion PC Bridge",
                description = "Cross-device clipboard sync, remote terminal control, app launching, and desktop vision.",
                tint = MaxSemanticColors.primaryRoyal
            )

            FeatureCard(
                icon = Icons.Default.Psychology,
                title = "Local Encrypted Memory Vault",
                description = "AES-256 encrypted Room vector embeddings for persistent context with zero cloud leaks.",
                tint = MaxSemanticColors.accentCyan
            )

            FeatureCard(
                icon = Icons.Default.PhoneInTalk,
                title = "Incoming Call Assistant",
                description = "Announces caller identity and answers or rejects incoming calls hands-free via voice.",
                tint = MaxSemanticColors.success
            )

            FeatureCard(
                icon = Icons.Default.Shield,
                title = "Local-First Privacy Architecture",
                description = "Zero proprietary cloud servers, zero telemetry collectors, and zero trackers.",
                tint = MaxSemanticColors.warning
            )

            FeatureCard(
                icon = Icons.Default.Language,
                title = "MAX Official Website & Portal",
                description = "Official 3D web experience with real-time Three.js WebGL animations at max-ai-agent-official.vercel.app.",
                tint = MaxSemanticColors.accentCyan
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

            MaxButton(
                text = "Open Official Website & Portal",
                onClick = onNavigateToWebDashboard,
                variant = MaxButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXs))

            MaxButton(
                text = "View Privacy Policy",
                onClick = onNavigateToPrivacy,
                variant = MaxButtonVariant.OUTLINE,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceXl))
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    MaxCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaxDimensions.spaceMd),
            horizontalArrangement = Arrangement.spacedBy(MaxDimensions.spaceSm),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(MaxDimensions.radiusSm))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaxType.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxSemanticColors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaxType.Caption,
                    color = MaxSemanticColors.textSecondary
                )
            }
        }
    }
}
