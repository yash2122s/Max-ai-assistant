package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

data class PrivacyItem(
    val title: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onNavigateBack: () -> Unit) {
    val privacyItems = listOf(
        PrivacyItem(
            title = "Data on your device",
            description = "MAX stores everything locally on YOUR phone: your settings, long-term memories, generated websites, and chat context. Nothing is uploaded to any server owned by us - we don't have servers."
        ),
        PrivacyItem(
            title = "Your Gemini API key",
            description = "Your API key is stored in encrypted storage (Android Keystore-backed EncryptedSharedPreferences) on the device and is only used to connect directly to Google's Gemini API."
        ),
        PrivacyItem(
            title = "Voice & screen data",
            description = "When you talk to MAX, your microphone audio (and, only if you turn on screen share, screen frames) are streamed directly to the Google Gemini API to generate responses. They are processed under Google's API terms and are not stored by this app."
        ),
        PrivacyItem(
            title = "Notifications, contacts & calls",
            description = "Notification content, contact names/numbers, and call state are read ON-DEVICE only when you ask MAX to use them (or when she announces a call/notification). They are never uploaded or shared, except the minimal text MAX needs to answer you within your Gemini session."
        ),
        PrivacyItem(
            title = "Analytics & ads",
            description = "This app has no analytics; no trackers, and no ads."
        ),
        PrivacyItem(
            title = "Your control",
            description = "You can delete MAX's memories anytime (ask her to forget), clear app data from Android settings, or revoke any permission from the Permissions screen. Everything MAX can do is opt-in via permissions."
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", color = TextLight, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { innerPadding ->
        // The outer glowing border for the entire list area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .border(
                    width = 2.dp,
                    brush = Brush.verticalGradient(GradientBorderColors),
                    shape = RoundedCornerShape(16.dp)
                )
                .clip(RoundedCornerShape(16.dp))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp)
            ) {
                items(privacyItems) { item ->
                    PrivacyCard(item = item)
                }
            }
        }
    }
}

@Composable
fun PrivacyCard(item: PrivacyItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = item.title,
                color = NeonPink,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.description,
                color = TextLight.copy(alpha = 0.8f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
