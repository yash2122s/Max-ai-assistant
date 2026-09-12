package com.example.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPrivacy: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            MaxTopBar(
                title = "About System",
                onBackClick = onNavigateBack
            )
        },
        containerColor = MaxBgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
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
                                    listOf(MaxPrimary, MaxSecondary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "M",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

                    Text(
                        text = "M A X",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = MaxTextPrimary,
                        letterSpacing = 4.sp
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Autonomous Personal AI Assistant",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaxPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Version 3.0.0 • Build 36 • Android 14+",
                        fontSize = 12.sp,
                        color = MaxTextMuted
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
                            .background(MaxPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaxPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Engineered by Yaswanth",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaxTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Full-stack on-device architecture & neural agents",
                            fontSize = 12.sp,
                            color = MaxTextSecondary
                        )
                    }
                }
            }

            // System Capabilities Section
            Text(
                text = "SYSTEM ARCHITECTURE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaxTextMuted,
                letterSpacing = 1.sp
            )

            FeatureCard(
                icon = Icons.Default.Mic,
                title = "Gemini Live Realtime Voice",
                description = "Bi-directional PCM audio streaming with sub-second audio turn completion over WebSocket.",
                tint = MaxPrimary
            )

            FeatureCard(
                icon = Icons.Default.DesktopWindows,
                title = "Windows Companion PC Bridge",
                description = "Cross-device clipboard sync, remote terminal control, app launching, and desktop vision.",
                tint = MaxSecondary
            )

            FeatureCard(
                icon = Icons.Default.Psychology,
                title = "Local Encrypted Memory Vault",
                description = "AES-256 encrypted Room vector embeddings for persistent context with zero cloud leaks.",
                tint = MaxPrimary
            )

            FeatureCard(
                icon = Icons.Default.PhoneInTalk,
                title = "Incoming Call Assistant",
                description = "Announces caller identity and answers or rejects incoming calls hands-free via voice.",
                tint = MaxSuccess
            )

            FeatureCard(
                icon = Icons.Default.Shield,
                title = "Local-First Privacy Architecture",
                description = "Zero proprietary cloud servers, zero telemetry collectors, and zero trackers.",
                tint = Color(0xFFFFB300)
            )

            Spacer(modifier = Modifier.height(MaxDimensions.spaceSm))

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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaxTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaxTextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
