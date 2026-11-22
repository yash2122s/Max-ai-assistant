package com.example.network.agent

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionRequestLifecycleTest {
    private val client = WindowsAgentClient(ApplicationProvider.getApplicationContext())
    private val errors = mutableListOf<String>()

    private fun sendUnansweredRequest() {
        val socket = object : WebSocket {
            override fun request(): Request = error("Unused")
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() = Unit
        }
        WindowsAgentClient::class.java.getDeclaredField("webSocket").apply {
            isAccessible = true
            set(client, socket)
        }
        client.sendToolRequest("fixture", "example", emptyMap(), object : WindowsAgentClient.ToolResponseCallback {
            override fun onProgress(message: String) = Unit
            override fun onResponse(status: String, output: String) = error("Unexpected response")
            override fun onError(error: String) { errors += error }
        })
    }

    @Test fun aSingleUnansweredRequestTimesOutWithoutAnotherRequest() {
        sendUnansweredRequest()
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        client.disconnect()
        assertEquals(listOf("Request timed out"), errors)
    }

    @Test fun disconnectCompletesTheRequestExactlyOnce() {
        sendUnansweredRequest()
        client.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(listOf("Connection closed"), errors)
    }
}
