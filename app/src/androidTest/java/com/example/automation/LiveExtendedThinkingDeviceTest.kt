package com.example.automation

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.preferences.SettingsManager
import com.example.network.ConnectionState
import com.example.network.GeminiWebSocketClient
import com.example.network.model.GeminiLiveModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Uses the configured key on-device. Never prints credentials, responses, or private thoughts. */
@RunWith(AndroidJUnit4::class)
class LiveExtendedThinkingDeviceTest {
    @Test fun configuredAccountConnectsToPrimaryAndReturnsPublicResponse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SettingsManager(context).geminiApiKey
        assertTrue("Configure a Gemini API key on the device before this test", key.isNotBlank())
        val ready = CountDownLatch(1)
        val response = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val started = System.nanoTime()
        val firstResponse = AtomicLong(0)
        val client = GeminiWebSocketClient(key, preferredModel = GeminiLiveModels.DEEP,
            onMessageReceived = { if (it.isNotBlank()) { firstResponse.compareAndSet(0, System.nanoTime()); response.countDown() } },
            onAudioReceived = { firstResponse.compareAndSet(0, System.nanoTime()); response.countDown() },
            onConnectionError = { Log.i("MaxLiveDeviceTest", "connection_error type=${it.javaClass.simpleName}") },
            onConnectionStateChanged = { if (it == ConnectionState.CONNECTED) ready.countDown() },
            onExecuteAutomation = { """{"success":false,"executionState":"BLOCKED","reason":"Connectivity test permits no actions"}""" },
            onTurnComplete = { idle.countDown() })
        try {
            client.connect()
            assertTrue("No Live setupComplete within 35 seconds", ready.await(35, TimeUnit.SECONDS))
            assertEquals("A fallback connection does not validate the primary model", GeminiLiveModels.DEEP, client.connectedModelName)
            Log.i("MaxLiveDeviceTest", "setup_ms=${(System.nanoTime() - started) / 1_000_000}")
            val sent = System.nanoTime()
            client.sendText("Please say hello in one short sentence. Do not use tools.")
            assertTrue("No public response within 35 seconds", response.await(35, TimeUnit.SECONDS))
            Log.i("MaxLiveDeviceTest", "first_response_ms=${(firstResponse.get() - sent) / 1_000_000}")
            assertTrue("No interaction IDLE after the response", idle.await(35, TimeUnit.SECONDS))
        } finally { client.disconnect() }
    }
}
