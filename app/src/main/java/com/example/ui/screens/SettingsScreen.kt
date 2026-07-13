package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.network.agent.WindowsToolExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentApiKey: String,
    onSaveApiKey: (String) -> Unit,
    currentVoice: String,
    onSaveVoice: (String) -> Unit,
    currentLanguage: String,
    onSaveLanguage: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey) }
    
    // Only Female voices: Aoede & Kore
    val femaleVoices = listOf(
        "Aoede" to "Aoede (Warm Female Voice)",
        "Kore" to "Kore (Soft Female Voice)"
    )
    var selectedVoice by remember { mutableStateOf(currentVoice) }
    
    val languages = listOf(
        "English" to "English Only",
        "Telugu" to "Telugu Only (తెలుగు)",
        "Tenglish" to "Tenglish (English + Telugu)"
    )
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val agentPrefs = remember { context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE) }
    var agentIp by remember { mutableStateOf(agentPrefs.getString("agent_ip", "192.168.1.100") ?: "192.168.1.100") }
    var agentPort by remember { mutableStateOf(agentPrefs.getInt("agent_port", 9000).toString()) }
    var pairingCode by remember { mutableStateOf("") }
    var pairStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextLight, fontWeight = FontWeight.Bold) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("API Configuration", color = NeonPink, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Gemini API Key", color = TextLight) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                )
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            Text("Voice Configuration (Female)", color = NeonPink, fontWeight = FontWeight.Bold)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                femaleVoices.forEach { (voiceId, voiceLabel) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedVoice = voiceId },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedVoice == voiceId) NeonBlue.copy(alpha = 0.1f) else SurfaceDark
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selectedVoice == voiceId) NeonBlue else BorderDark
                        )
                    ) {
                        Text(
                            text = voiceLabel,
                            color = TextLight,
                            modifier = Modifier.padding(16.dp),
                            fontWeight = if (selectedVoice == voiceId) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            Text("Language Configuration", color = NeonPink, fontWeight = FontWeight.Bold)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { (langId, langLabel) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLanguage = langId },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedLanguage == langId) NeonBlue.copy(alpha = 0.1f) else SurfaceDark
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selectedLanguage == langId) NeonBlue else BorderDark
                        )
                    ) {
                        Text(
                            text = langLabel,
                            color = TextLight,
                            modifier = Modifier.padding(16.dp),
                            fontWeight = if (selectedLanguage == langId) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Windows Agent Configuration", color = NeonPink, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = agentIp,
                onValueChange = { agentIp = it },
                label = { Text("Agent IP Address", color = TextLight) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                )
            )
            
            OutlinedTextField(
                value = agentPort,
                onValueChange = { agentPort = it },
                label = { Text("Agent Port", color = TextLight) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it },
                    label = { Text("Pairing Code", color = TextLight) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )

                Button(
                    onClick = {
                        val portInt = agentPort.toIntOrNull() ?: 9000
                        WindowsToolExecutor.saveConfig(context, agentIp, portInt)
                        
                        val client = WindowsToolExecutor.getClient()
                        if (client == null) {
                            Toast.makeText(context, "Executor client not initialized", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        pairStatus = "Connecting to agent..."
                        coroutineScope.launch {
                            var attempts = 0
                            while (attempts < 12 && !WindowsToolExecutor.isAgentAvailable()) {
                                delay(500)
                                attempts++
                            }
                            
                            if (WindowsToolExecutor.isAgentAvailable()) {
                                pairStatus = "Sending pairing code..."
                                client.pair(pairingCode) { success, error ->
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        if (success) {
                                            pairStatus = "Paired Successfully!"
                                            Toast.makeText(context, "Successfully paired with Windows laptop!", Toast.LENGTH_LONG).show()
                                        } else {
                                            pairStatus = "Pairing Failed"
                                            Toast.makeText(context, "Pairing failed: ${error ?: "Incorrect code"}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            } else {
                                pairStatus = "Connection Timeout"
                                Toast.makeText(context, "Could not connect to laptop agent.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                ) {
                    Text("Pair", color = OnPrimaryDark, fontWeight = FontWeight.Bold)
                }
            }
            
            if (pairStatus.isNotEmpty()) {
                Text(
                    text = pairStatus,
                    color = if (pairStatus.contains("Success")) NeonGreen else NeonPink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { 
                    onSaveApiKey(apiKey)
                    onSaveVoice(selectedVoice)
                    onSaveLanguage(selectedLanguage)
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
            ) {
                Text("Save Settings", color = OnPrimaryDark, fontWeight = FontWeight.Bold)
            }
        }
    }
}
