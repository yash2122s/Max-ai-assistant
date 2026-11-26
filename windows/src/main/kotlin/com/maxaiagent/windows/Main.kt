package com.maxaiagent.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.maxaiagent.core.MaxAssistantState
import com.maxaiagent.core.MaxOperatingMode
import com.maxaiagent.core.MaxThemeTokens

fun main() = application {
    val controller = remember { MaxDesktopController() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "MAX AI Desktop",
        state = rememberWindowState(width = 1080.dp, height = 720.dp)
    ) {
        MaxDesktopTheme(darkTheme = controller.isDarkTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(controller)
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(controller)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp)) {
                            when (controller.selectedTab) {
                                MaxDesktopTab.ASSISTANT -> AssistantPane(controller)
                                MaxDesktopTab.BRIDGE -> BridgePane(controller)
                                MaxDesktopTab.VAULT -> VaultPane(controller)
                                MaxDesktopTab.SETTINGS -> SettingsPane(controller)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(controller: MaxDesktopController) {
    Column(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White)
            }
            Column {
                Text("MAX AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Windows Desktop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        MaxDesktopTab.entries.forEach { tab ->
            val selected = controller.selectedTab == tab
            val icon = when (tab) {
                MaxDesktopTab.ASSISTANT -> Icons.Default.SmartToy
                MaxDesktopTab.BRIDGE -> Icons.Default.DesktopWindows
                MaxDesktopTab.VAULT -> Icons.Default.Lock
                MaxDesktopTab.SETTINGS -> Icons.Default.Settings
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { controller.selectedTab = tab }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    tab.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        val stateColor = when (controller.assistantState) {
            MaxAssistantState.ONLINE, MaxAssistantState.SPEAKING -> Color(MaxThemeTokens.SUCCESS)
            MaxAssistantState.THINKING, MaxAssistantState.CONNECTING -> Color(MaxThemeTokens.WARNING)
            MaxAssistantState.ERROR -> Color(MaxThemeTokens.ERROR)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(stateColor))
            Text(
                "State: ${controller.assistantState.label}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TopBar(controller: MaxDesktopController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mode:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MaxOperatingMode.entries.forEach { mode ->
                FilterChip(
                    selected = controller.operatingMode == mode,
                    onClick = { controller.selectMode(mode) },
                    label = { Text(mode.label) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.isDarkTheme = !controller.isDarkTheme }) {
                Icon(
                    if (controller.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle theme"
                )
            }
            Text("MAX", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Switch(
                checked = controller.isRuntimeEnabled,
                onCheckedChange = { controller.toggleRuntime(it) }
            )
        }
    }
}

@Composable
private fun AssistantPane(controller: MaxDesktopController) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.submitCommand("/status") }) { Text("/status") }
            OutlinedButton(onClick = { controller.submitCommand("/ping") }) { Text("/ping bridge") }
            OutlinedButton(onClick = { controller.submitCommand("Go to home screen") }) { Text("Home Intent") }
            OutlinedButton(onClick = { controller.submitCommand("/hash max-desktop") }) { Text("SHA-256 Sample") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = controller.commandInput,
                onValueChange = { controller.commandInput = it },
                placeholder = { Text("Enter MAX command or prompt (supports English & Telugu)...") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) {
                            controller.submitCommand()
                            true
                        } else {
                            false
                        }
                    }
            )
            Button(
                onClick = { controller.submitCommand() },
                modifier = Modifier.height(52.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        }

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(controller.logs) { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            entry.timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "[${entry.source}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.isError) Color(MaxThemeTokens.ERROR) else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.isError) Color(MaxThemeTokens.ERROR) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgePane(controller: MaxDesktopController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Windows Agent Bridge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Connects MAX Desktop to the local windows-agent service (windows-agent/main.py).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.bridgeEndpoint,
                onValueChange = { controller.bridgeEndpoint = it },
                label = { Text("Bridge URL") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { controller.pingBridge() }, modifier = Modifier.height(52.dp)) {
                Text("Ping /health")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Endpoint: ${controller.bridgeStatus.endpoint}", fontFamily = FontFamily.Monospace)
                Text("Reachable: ${controller.bridgeStatus.reachable}", fontFamily = FontFamily.Monospace)
                Text("Detail: ${controller.bridgeStatus.detail}", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun VaultPane(controller: MaxDesktopController) {
    var passphrase by remember { mutableStateOf("max-local-vault") }
    var plaintext by remember { mutableStateOf("MAX shared AES-256-GCM memory entry") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Shared :core Crypto & Deduplication", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase (PBKDF2WithHmacSHA256)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = plaintext,
            onValueChange = { plaintext = it },
            label = { Text("Plaintext") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.encryptVaultSample(passphrase, plaintext) }) {
                Text("Encrypt (AES-256-GCM)")
            }
            OutlinedButton(onClick = { controller.decryptVaultSample(passphrase) }) {
                Text("Decrypt")
            }
        }
        if (controller.lastVaultHash.isNotEmpty()) {
            Text("SHA-256: ${controller.lastVaultHash}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (controller.lastEncryptedPayload.isNotEmpty()) {
            Text("Ciphertext: ${controller.lastEncryptedPayload}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (controller.lastDecryptedPayload.isNotEmpty()) {
            Text("Decrypted: ${controller.lastDecryptedPayload}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsPane(controller: MaxDesktopController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Desktop Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Active Model: ${controller.operatingMode.modelId}", style = MaterialTheme.typography.bodyMedium)
        Text("Shared Engine: :core (JVM 11 bytecode, shared with :app)", style = MaterialTheme.typography.bodyMedium)
        Text("Runtime Packaging: jpackage self-contained Windows x64 image", style = MaterialTheme.typography.bodyMedium)
    }
}
