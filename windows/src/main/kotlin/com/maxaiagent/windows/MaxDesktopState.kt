package com.maxaiagent.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maxaiagent.core.MaxAssistantState
import com.maxaiagent.core.MaxBridgeStatus
import com.maxaiagent.core.MaxCommandNormalizer
import com.maxaiagent.core.MaxCrypto
import com.maxaiagent.core.MaxIntentKind
import com.maxaiagent.core.MaxLogEntry
import com.maxaiagent.core.MaxOperatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class MaxDesktopTab(val title: String) {
    ASSISTANT("Assistant"),
    BRIDGE("Windows Bridge"),
    VAULT("Memory & Crypto"),
    SETTINGS("Settings")
}

class MaxDesktopController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    var selectedTab by mutableStateOf(MaxDesktopTab.ASSISTANT)
    var isRuntimeEnabled by mutableStateOf(true)
    var assistantState by mutableStateOf(MaxAssistantState.ONLINE)
    var operatingMode by mutableStateOf(MaxOperatingMode.AUTO)
    var isDarkTheme by mutableStateOf(false)
    var bridgeEndpoint by mutableStateOf("http://127.0.0.1:8000")
    var bridgeStatus by mutableStateOf(
        MaxBridgeStatus(
            reachable = false,
            endpoint = "http://127.0.0.1:8000",
            detail = "Not checked yet"
        )
    )
    var commandInput by mutableStateOf("")
    var lastVaultHash by mutableStateOf("")
    var lastEncryptedPayload by mutableStateOf("")
    var lastDecryptedPayload by mutableStateOf("")
    var lastVaultSalt: ByteArray? = null

    val logs = mutableStateListOf<MaxLogEntry>()

    init {
        appendLog("CORE", "MAX Desktop initialized (${operatingMode.label} / ${operatingMode.modelId})")
    }

    fun toggleRuntime(enabled: Boolean) {
        isRuntimeEnabled = enabled
        assistantState = if (enabled) MaxAssistantState.ONLINE else MaxAssistantState.OFF
        appendLog("RUNTIME", "Global MAX state -> ${assistantState.label}")
    }

    fun selectMode(mode: MaxOperatingMode) {
        operatingMode = mode
        appendLog("ROUTER", "Switched operating mode to ${mode.label} (${mode.modelId})")
    }

    fun submitCommand(raw: String = commandInput) {
        val text = raw.trim()
        if (text.isEmpty()) return
        commandInput = ""

        val cmd = MaxCommandNormalizer.normalize(text)
        appendLog("USER", "${cmd.normalizedText} [${cmd.intent}] (${cmd.fingerprint.take(10)})")

        if (!isRuntimeEnabled && cmd.intent != MaxIntentKind.TOGGLE_RUNTIME && cmd.intent != MaxIntentKind.STATUS_QUERY) {
            appendLog("MAX", "Runtime is OFF. Toggle MAX ON or send /toggle to resume.", isError = true)
            return
        }

        when (cmd.intent) {
            MaxIntentKind.HOME_NAVIGATION -> {
                selectedTab = MaxDesktopTab.ASSISTANT
                appendLog("MAX", "Navigated to Assistant Home.")
            }
            MaxIntentKind.STATUS_QUERY -> {
                appendLog("MAX", "State=${assistantState.label}, Mode=${operatingMode.label}, Bridge=${bridgeStatus.reachable}")
            }
            MaxIntentKind.TOGGLE_RUNTIME -> {
                toggleRuntime(!isRuntimeEnabled)
            }
            MaxIntentKind.WINDOWS_BRIDGE_PING -> {
                pingBridge()
            }
            MaxIntentKind.CRYPTO_VAULT_HASH -> {
                val target = cmd.normalizedText.removePrefix("/hash ").trim()
                val hash = MaxCrypto.sha256Hex(target)
                lastVaultHash = hash
                appendLog("CRYPTO", "SHA-256: $hash")
            }
            MaxIntentKind.GENERAL_PROMPT -> {
                assistantState = MaxAssistantState.THINKING
                appendLog("MAX", "Normalized prompt queued for ${operatingMode.modelId}.")
                assistantState = MaxAssistantState.ONLINE
            }
        }
    }

    fun pingBridge() {
        val endpoint = bridgeEndpoint.trim().trimEnd('/')
        appendLog("BRIDGE", "Checking $endpoint/health ...")
        scope.launch(Dispatchers.IO) {
            val result = try {
                val req = Request.Builder().url("$endpoint/health").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    MaxBridgeStatus(
                        reachable = resp.isSuccessful,
                        endpoint = endpoint,
                        detail = "HTTP ${resp.code} ${resp.message}"
                    )
                }
            } catch (e: Exception) {
                MaxBridgeStatus(
                    reachable = false,
                    endpoint = endpoint,
                    detail = e.message ?: "Connection failed"
                )
            }
            scope.launch(Dispatchers.Main) {
                bridgeStatus = result
                appendLog("BRIDGE", "${result.endpoint} -> ${result.detail}", isError = !result.reachable)
            }
        }
    }

    fun encryptVaultSample(passphrase: String, plaintext: String) {
        if (passphrase.isBlank() || plaintext.isBlank()) return
        val salt = MaxCrypto.generateSalt()
        val key = MaxCrypto.deriveKey(passphrase, salt)
        lastVaultSalt = salt
        lastVaultHash = MaxCrypto.sha256Hex(plaintext)
        lastEncryptedPayload = MaxCrypto.encryptAesGcm(plaintext, key)
        lastDecryptedPayload = ""
        appendLog("CRYPTO", "Encrypted payload with AES-256-GCM (SHA-256=${lastVaultHash.take(12)}...)")
    }

    fun decryptVaultSample(passphrase: String) {
        val salt = lastVaultSalt ?: return
        val payload = lastEncryptedPayload
        if (passphrase.isBlank() || payload.isBlank()) return
        try {
            val key = MaxCrypto.deriveKey(passphrase, salt)
            lastDecryptedPayload = MaxCrypto.decryptAesGcm(payload, key)
            appendLog("CRYPTO", "AES-256-GCM decryption verified.")
        } catch (e: Exception) {
            lastDecryptedPayload = "Decryption failed: ${e.message}"
            appendLog("CRYPTO", lastDecryptedPayload, isError = true)
        }
    }

    private fun appendLog(source: String, message: String, isError: Boolean = false) {
        logs.add(
            0,
            MaxLogEntry(
                timestamp = LocalTime.now().format(timeFmt),
                source = source,
                message = message,
                isError = isError
            )
        )
        if (logs.size > 200) {
            logs.removeAt(logs.lastIndex)
        }
    }
}
