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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Properties
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val json = Json { ignoreUnknownKeys = true }
    private val configFile = File(System.getProperty("user.home"), ".max-ai/config.properties")

    var selectedTab by mutableStateOf(MaxDesktopTab.ASSISTANT)
    var isRuntimeEnabled by mutableStateOf(true)
    var assistantState by mutableStateOf(MaxAssistantState.ONLINE)
    var operatingMode by mutableStateOf(MaxOperatingMode.AUTO)
    var isDarkTheme by mutableStateOf(false)
    var voiceReplyEnabled by mutableStateOf(true)
    var geminiApiKey by mutableStateOf("")
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
        loadConfig()
        appendLog("CORE", "MAX Desktop initialized (${operatingMode.label} / ${operatingMode.modelId})")
        if (geminiApiKey.isBlank()) {
            appendLog("HINT", "Go to Settings tab and paste your Gemini API Key to chat with MAX AI.")
        }
    }

    private fun loadConfig() {
        if (!configFile.exists()) return
        try {
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            geminiApiKey = props.getProperty("GEMINI_API_KEY", "")
            bridgeEndpoint = props.getProperty("BRIDGE_ENDPOINT", "http://127.0.0.1:8000")
            voiceReplyEnabled = props.getProperty("VOICE_REPLY", "true").toBoolean()
        } catch (_: Exception) {
        }
    }

    fun saveConfig(newApiKey: String, newBridgeUrl: String, speakReplies: Boolean) {
        geminiApiKey = newApiKey.trim()
        bridgeEndpoint = newBridgeUrl.trim().ifEmpty { "http://127.0.0.1:8000" }
        voiceReplyEnabled = speakReplies
        try {
            configFile.parentFile?.mkdirs()
            val props = Properties().apply {
                setProperty("GEMINI_API_KEY", geminiApiKey)
                setProperty("BRIDGE_ENDPOINT", bridgeEndpoint)
                setProperty("VOICE_REPLY", voiceReplyEnabled.toString())
            }
            configFile.outputStream().use { props.store(it, "MAX AI Desktop Config") }
            appendLog("SETTINGS", "Saved config to ${configFile.absolutePath}")
        } catch (e: Exception) {
            appendLog("SETTINGS", "Failed to save config: ${e.message}", isError = true)
        }
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
        appendLog("USER", cmd.normalizedText)

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
                appendLog("MAX", "State=${assistantState.label}, Mode=${operatingMode.label}, Key=${if (geminiApiKey.isNotBlank()) "Set" else "Missing"}")
            }
            MaxIntentKind.TOGGLE_RUNTIME -> toggleRuntime(!isRuntimeEnabled)
            MaxIntentKind.WINDOWS_BRIDGE_PING -> pingBridge()
            MaxIntentKind.CRYPTO_VAULT_HASH -> {
                val target = cmd.normalizedText.removePrefix("/hash ").trim()
                val hash = MaxCrypto.sha256Hex(target)
                lastVaultHash = hash
                appendLog("CRYPTO", "SHA-256: $hash")
            }
            MaxIntentKind.GENERAL_PROMPT -> askGemini(cmd.normalizedText)
        }
    }

    private fun askGemini(prompt: String) {
        val key = geminiApiKey.trim()
        if (key.isEmpty()) {
            appendLog("MAX", "No Gemini API Key set. Open Settings tab and paste your key.", isError = true)
            return
        }
        assistantState = MaxAssistantState.THINKING
        scope.launch(Dispatchers.IO) {
            val reply = try {
                val bodyJson = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", JsonPrimitive("You are MAX AI Desktop Assistant. Be concise and helpful. User: $prompt"))
                                })
                            })
                        })
                    })
                }.toString()

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key"
                val req = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}: ${rawBody.take(180)}")
                    }
                    val root = json.parseToJsonElement(rawBody).jsonObject
                    val candidates = root["candidates"]?.jsonArray
                    val first = candidates?.firstOrNull()?.jsonObject
                    val content = first?.get("content")?.jsonObject
                    val parts = content?.get("parts")?.jsonArray
                    parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: "No text returned."
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            scope.launch(Dispatchers.Main) {
                val isErr = reply.startsWith("Error:")
                appendLog("MAX", reply, isError = isErr)
                if (!isErr && voiceReplyEnabled) {
                    speakText(reply)
                } else {
                    assistantState = if (isRuntimeEnabled) MaxAssistantState.ONLINE else MaxAssistantState.OFF
                }
            }
        }
    }

    fun speakText(text: String) {
        assistantState = MaxAssistantState.SPEAKING
        scope.launch(Dispatchers.IO) {
            try {
                val safe = text.replace("'", " ").replace("\"", " ").take(400)
                val psCmd = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Speak('$safe')"
                ProcessBuilder("powershell", "-NoProfile", "-Command", psCmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (_: Exception) {
            }
            scope.launch(Dispatchers.Main) {
                assistantState = if (isRuntimeEnabled) MaxAssistantState.ONLINE else MaxAssistantState.OFF
            }
        }
    }

    fun listenVoiceOnce() {
        if (!isRuntimeEnabled) {
            appendLog("VOICE", "Turn MAX ON before using voice.", isError = true)
            return
        }
        assistantState = MaxAssistantState.LISTENING
        appendLog("VOICE", "Listening via Windows Speech Recognition (speak now)...")
        scope.launch(Dispatchers.IO) {
            val recognized = try {
                val psScript = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine
                    ${'$'}rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
                    ${'$'}rec.SetInputToDefaultAudioDevice()
                    ${'$'}res = ${'$'}rec.Recognize([TimeSpan]::FromSeconds(6))
                    if (${'$'}res -ne ${'$'}null) { ${'$'}res.Text }
                """.trimIndent()
                val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                out
            } catch (e: Exception) {
                "VOICE_ERR:${e.message}"
            }
            scope.launch(Dispatchers.Main) {
                assistantState = MaxAssistantState.ONLINE
                if (recognized.isBlank() || recognized.startsWith("VOICE_ERR:")) {
                    appendLog("VOICE", "No speech detected (or mic unavailable). You can type below.", isError = recognized.startsWith("VOICE_ERR:"))
                } else {
                    submitCommand(recognized)
                }
            }
        }
    }

    fun launchWindowsApp(appName: String, executable: String) {
        appendLog("CONTROL", "Launching $appName ($executable)...")
        scope.launch(Dispatchers.IO) {
            try {
                ProcessBuilder("cmd", "/c", "start", "", executable).start()
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) {
                    appendLog("CONTROL", "Failed to launch $appName: ${e.message}", isError = true)
                }
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
