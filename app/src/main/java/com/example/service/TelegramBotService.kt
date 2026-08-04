package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.preferences.SettingsManager
import com.example.automation.engine.ActionDispatcher
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramBotService : Service() {
    private val TAG = "TelegramBotService"
    private val CHANNEL_ID = "TelegramBotServiceChannel"
    private val NOTIFICATION_ID = 1002

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    private var lastUpdateId = 0

    companion object {
        /** Commands that may be invoked remotely. Fail closed for anything else. */
        private val ALLOWED_ACTIONS = setOf(
            "FLASHLIGHT_ON",
            "FLASHLIGHT_OFF",
            "GET_LOCATION",
            "TAKE_SCREENSHOT",
            "HELP"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, getNotification("Starting Telegram Bot Service..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, getNotification("Starting Telegram Bot Service..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(NOTIFICATION_ID, getNotification("Starting Telegram Bot Service..."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "TelegramBotService starting...")

        val settings = SettingsManager(this)
        val token = settings.telegramBotToken
        val chatId = settings.telegramChatId

        if (token.isBlank() || chatId.isBlank()) {
            Log.w(TAG, "Bot token or authorized Chat ID is blank. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "Telegram Polling active for bot token: ...${token.takeLast(6)}")
            updateNotification("Bot Active - Waiting for commands")

            var errorCount = 0
            while (isActive) {
                try {
                    pollUpdates(token, chatId)
                    errorCount = 0
                } catch (e: Exception) {
                    errorCount++
                    val backoff = (1000L * (1 shl (errorCount.coerceAtMost(6)))).coerceAtLeast(5000L)
                    Log.e(TAG, "Error polling updates (attempt $errorCount). Backing off for ${backoff / 1000}s", e)
                    delay(backoff)
                }
            }
        }

        return START_STICKY
    }

    private suspend fun pollUpdates(token: String, targetChatId: String) {
        val url = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Response failed: ${response.code}")
                delay(5000)
                return
            }

            val bodyStr = response.body?.string() ?: return
            val json = JSONObject(bodyStr)
            val ok = json.optBoolean("ok", false)
            if (!ok) return

            val result = json.optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val update = result.optJSONObject(i) ?: continue
                val updateId = update.optInt("update_id")
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId
                }

                handleUpdate(update, token, targetChatId)
            }
        }
    }

    private fun handleUpdate(update: JSONObject, token: String, targetChatId: String) {
        try {
            val message = update.optJSONObject("message")
            val callbackQuery = update.optJSONObject("callback_query")

            if (message != null) {
                val chat = message.optJSONObject("chat") ?: return
                val chatId = chat.optLong("id").toString()
                val text = message.optString("text", "").trim()

                // Fail closed: blank targetChatId is rejected at service start; still enforce match.
                if (chatId != targetChatId) {
                    Log.w(TAG, "Blocked unauthorized message from chat: $chatId")
                    sendTelegramMessage(token, chatId, "Access Denied. You are not authorized to control this device.")
                    return
                }

                if (text.isNotBlank()) {
                    processBotCommand(text, token, chatId)
                }
            } else if (callbackQuery != null) {
                val messageObj = callbackQuery.optJSONObject("message")
                val chat = messageObj?.optJSONObject("chat") ?: return
                val chatId = chat.optLong("id").toString()
                val data = callbackQuery.optString("data", "").trim()

                if (chatId != targetChatId) {
                    return
                }

                if (data.isNotBlank()) {
                    processBotCommand(data, token, chatId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle update", e)
        }
    }

    private fun processBotCommand(command: String, token: String, chatId: String) {
        scope.launch {
            val normalized = command.trim().uppercase().removePrefix("/")
            val actionName = when (normalized) {
                "START" -> "HELP"
                in ALLOWED_ACTIONS -> normalized
                else -> null
            }

            if (actionName == null) {
                sendTelegramMessage(
                    token,
                    chatId,
                    "Unknown command. Send /HELP for the list of allowed commands."
                )
                return@launch
            }

            if (actionName == "HELP") {
                val helpText = """
                    <b>MAX Device Controller Help</b>

                    Available Commands:
                    • GET_LOCATION — Get live location map link
                    • FLASHLIGHT_ON — Turn on flashlight
                    • FLASHLIGHT_OFF — Turn off flashlight
                    • TAKE_SCREENSHOT — Take a screenshot (requires accessibility)
                """.trimIndent()
                sendTelegramMessage(token, chatId, helpText)
                return@launch
            }

            sendTelegramMessage(token, chatId, "Executing command: $actionName...")

            val dispatchJson = JSONObject().apply {
                put("action", actionName)
            }

            val resultString = ActionDispatcher.dispatchWithResult(applicationContext, dispatchJson)
            val resultJson = JSONObject(resultString)
            val success = resultJson.optString("status") == "success"

            if (success) {
                val mapsLink = resultJson.optString("maps_link")
                if (mapsLink.isNotBlank()) {
                    sendTelegramMessage(
                        token,
                        chatId,
                        "Live Location: <a href=\"$mapsLink\">Open map</a>\n" +
                            "(Lat: ${resultJson.optDouble("latitude")}, Lon: ${resultJson.optDouble("longitude")})"
                    )
                } else {
                    sendTelegramMessage(token, chatId, "Command executed successfully.")
                }
            } else {
                val reason = resultJson.optString("reason", "Unknown execution error")
                sendTelegramMessage(token, chatId, "Execution Failed: $reason")
            }
        }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        scope.launch {
            try {
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "HTML")
                    put("disable_web_page_preview", false)
                }.toString().toRequestBody(mediaType)

                val request = Request.Builder().url(url).post(requestBody).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to send message: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Telegram message", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Exception) {
        }
        Log.d(TAG, "TelegramBotService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Telegram Bot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun getNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAX Bot Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, getNotification(text))
    }
}
