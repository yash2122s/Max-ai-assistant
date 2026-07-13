package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About MAX", color = TextLight, fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "MAX",
                color = NeonBlue,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 48.sp,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Advanced Local AI Assistant",
                color = TextLight,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Version 1.0.0",
                color = TextLight.copy(alpha = 0.5f),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Built with MAX AI Agent Platform",
                color = NeonPink,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
