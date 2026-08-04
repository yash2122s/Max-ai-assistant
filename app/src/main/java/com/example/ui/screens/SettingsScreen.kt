package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.*
import com.example.network.agent.WindowsToolExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun SettingsSectionCard(
    title: String,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarker.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(icon, fontSize = 16.sp)
                Text(title, color = NeonPink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentApiKey: String,
    onSaveApiKey: (String) -> Unit,
    currentVoice: String,
    onSaveVoice: (String) -> Unit,
    currentLanguage: String,
    onSaveLanguage: (String) -> Unit,
    currentModel: String = "Auto",
    onSaveModel: (String) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey) }
    
    val geminiModels = listOf(
        "gemini-3.1-flash-live-preview" to "Gemini 3.1 Flash Live Preview"
    )
    var selectedModel by remember { mutableStateOf(currentModel) }
    
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
    var isDesktopEnabled by remember { mutableStateOf(WindowsToolExecutor.isDesktopConnectionEnabled(context)) }
    var isClipboardSyncEnabled by remember { mutableStateOf(WindowsToolExecutor.isClipboardSyncEnabled(context)) }
    var agentIp by remember { mutableStateOf(agentPrefs.getString("agent_ip", "192.168.1.100") ?: "192.168.1.100") }
    var agentPort by remember { mutableStateOf(agentPrefs.getInt("agent_port", 9000).toString()) }
    var pairingCode by remember { mutableStateOf("") }
    var pairStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { com.example.data.preferences.SettingsManager(context) }
    val securitySettings = remember { com.example.security.SecuritySettings(context) }
    var autoUnlockEnabled by remember { mutableStateOf(securitySettings.autoUnlockEnabled) }
    var devicePin by remember { mutableStateOf(securitySettings.getDecryptedPin()) }
    var voiceCodeWord by remember { mutableStateOf(securitySettings.voiceUnlockCodeWord) }
    var isTelegramEnabled by remember { mutableStateOf(settingsManager.isTelegramBotEnabled) }
    var telegramBotToken by remember { mutableStateOf(settingsManager.telegramBotToken) }
    var telegramChatId by remember { mutableStateOf(settingsManager.telegramChatId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextLight, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // API Configuration Card
            SettingsSectionCard(title = "API Configuration", icon = "🔑") {
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
            }

            // Voice Configuration Card
            SettingsSectionCard(title = "Voice Configuration (Female)", icon = "🎙️") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    femaleVoices.forEach { (voiceId, voiceLabel) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedVoice = voiceId },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedVoice == voiceId) NeonBlue.copy(alpha = 0.15f) else SurfaceDark
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selectedVoice == voiceId) NeonBlue else BorderDark
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = voiceLabel,
                                color = TextLight,
                                modifier = Modifier.padding(14.dp),
                                fontWeight = if (selectedVoice == voiceId) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Language Configuration Card
            SettingsSectionCard(title = "Language Configuration", icon = "🌐") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { (langId, langLabel) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLanguage = langId },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedLanguage == langId) NeonBlue.copy(alpha = 0.15f) else SurfaceDark
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selectedLanguage == langId) NeonBlue else BorderDark
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = langLabel,
                                color = TextLight,
                                modifier = Modifier.padding(14.dp),
                                fontWeight = if (selectedLanguage == langId) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Windows Desktop Agent Card
            SettingsSectionCard(title = "Windows Agent Configuration", icon = "💻") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Enable Desktop Connection", color = TextLight)
                    Switch(
                        checked = isDesktopEnabled,
                        onCheckedChange = { enabled ->
                            isDesktopEnabled = enabled
                            WindowsToolExecutor.setDesktopConnectionEnabled(context, enabled)
                            if (enabled) {
                                Toast.makeText(context, "Desktop Connection Service Started", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Desktop Connection Service Stopped", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonPink,
                            checkedTrackColor = NeonPink.copy(alpha = 0.5f)
                        )
                    )
                }

                if (isDesktopEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("Auto-Sync Phone/PC Clipboard", color = TextLight)
                        Switch(
                            checked = isClipboardSyncEnabled,
                            onCheckedChange = { enabled ->
                                isClipboardSyncEnabled = enabled
                                WindowsToolExecutor.setClipboardSyncEnabled(context, enabled)
                                Toast.makeText(context, if (enabled) "Clipboard Auto-Sync Enabled" else "Clipboard Auto-Sync Disabled", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonBlue,
                                checkedTrackColor = NeonBlue.copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                Button(
                    onClick = {
                        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                            .build()
                        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                val rawValue = barcode.rawValue ?: return@addOnSuccessListener
                                try {
                                    val json = org.json.JSONObject(rawValue)
                                    val ip = json.getString("ip")
                                    val port = json.getInt("port")
                                    val code = json.getString("pairing_code")
                                    val platform = json.optString("platform", "Windows")
                                    val name = json.optString("device_name", "PC")
                                    val devId = json.optString("device_id", "")
                                    val certFingerprint = json.optString("cert_fingerprint", "")
                                    
                                    val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                                    prefs.edit()
                                        .putString("agent_ip", ip)
                                        .putInt("agent_port", port)
                                        .putString("paired_device_id", devId)
                                        .putString("cert_fingerprint", certFingerprint)
                                        .apply()

                                    WindowsToolExecutor.saveConfig(context, ip, port)
                                    agentIp = ip
                                    agentPort = port.toString()
                                    pairingCode = code
                                    
                                    // Enable desktop connection and start service
                                    isDesktopEnabled = true
                                    WindowsToolExecutor.setDesktopConnectionEnabled(context, true)
                                    
                                    pairStatus = "Connecting to $name..."
                                    coroutineScope.launch {
                                        var attempts = 0
                                        while (attempts < 12 && !WindowsToolExecutor.isAgentAvailable()) {
                                            delay(500)
                                            attempts++
                                        }
                                        
                                        if (WindowsToolExecutor.isAgentAvailable()) {
                                            pairStatus = "Pairing..."
                                            val client = WindowsToolExecutor.getClient()
                                            client?.pair(code) { success, error ->
                                                if (success) {
                                                    pairStatus = "Paired with $name Success!"
                                                } else {
                                                    pairStatus = "Pairing failed: $error"
                                                }
                                            }
                                        } else {
                                            pairStatus = "Could not reach PC"
                                        }
                                    }
                                } catch (e: Exception) {
                                    pairStatus = "Invalid QR code format"
                                }
                            }
                            .addOnFailureListener { e ->
                                pairStatus = "Scan cancelled/failed"
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, NeonBlue)
                ) {
                    Text("📷 Scan PC Companion QR Code", color = NeonBlue, fontWeight = FontWeight.Bold)
                }

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
                        val prefs = context.getSharedPreferences("windows_agent_prefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("agent_ip", agentIp)
                            .putInt("agent_port", portInt)
                            .remove("paired_device_id")
                            .remove("cert_fingerprint")
                            .apply()

                        WindowsToolExecutor.saveConfig(context, agentIp, portInt)
                        
                        // Enable desktop connection and start service
                        isDesktopEnabled = true
                        WindowsToolExecutor.setDesktopConnectionEnabled(context, true)
                        
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
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Telegram Bot Configuration", color = NeonPink, fontWeight = FontWeight.Bold)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("Enable Telegram Bot Connection", color = TextLight)
                Switch(
                    checked = isTelegramEnabled,
                    onCheckedChange = { isTelegramEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonPink,
                        checkedTrackColor = NeonPink.copy(alpha = 0.5f)
                    )
                )
            }
            
            OutlinedTextField(
                value = telegramBotToken,
                onValueChange = { telegramBotToken = it },
                label = { Text("Telegram Bot Token", color = TextLight) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                )
            )
            
            OutlinedTextField(
                value = telegramChatId,
                onValueChange = { telegramChatId = it },
                label = { Text("Authorized Chat ID", color = TextLight) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlue,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                )
            )
        }

            // Security & Phone Unlock Card
            SettingsSectionCard(title = "Security & Phone Unlock", icon = "🔓") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Auto-Unlock", color = TextLight, fontWeight = FontWeight.SemiBold)
                            Text("Allows MAX to wake and unlock phone for tasks & voice commands", color = TextLight.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoUnlockEnabled,
                            onCheckedChange = { autoUnlockEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonBlue, checkedTrackColor = SurfaceDark)
                        )
                    }

                    if (autoUnlockEnabled) {
                        OutlinedTextField(
                            value = devicePin,
                            onValueChange = { devicePin = it },
                            label = { Text("Stored Device PIN Code", color = TextLight) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight
                            )
                        )

                        OutlinedTextField(
                            value = voiceCodeWord,
                            onValueChange = { voiceCodeWord = it },
                            label = { Text("Voice Unlock Codeword", color = TextLight) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonBlue,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    onSaveApiKey(apiKey)
                    onSaveVoice(selectedVoice)
                    onSaveLanguage(selectedLanguage)
                    onSaveModel(selectedModel)

                    securitySettings.autoUnlockEnabled = autoUnlockEnabled
                    securitySettings.encryptedPin = devicePin.trim()
                    securitySettings.voiceUnlockCodeWord = voiceCodeWord.trim()

                    if (isTelegramEnabled && (telegramBotToken.isBlank() || telegramChatId.isBlank())) {
                        android.widget.Toast.makeText(
                            context,
                            "Telegram requires both Bot Token and Authorized Chat ID",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    settingsManager.isTelegramBotEnabled = isTelegramEnabled
                    settingsManager.telegramBotToken = telegramBotToken.trim()
                    settingsManager.telegramChatId = telegramChatId.trim()

                    val serviceIntent = android.content.Intent(context, com.example.service.TelegramBotService::class.java)
                    if (isTelegramEnabled) {
                        try {
                            context.stopService(serviceIntent)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        try {
                            context.stopService(serviceIntent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
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
