package com.example.network.agent

import com.example.automation.tools.Tool
import com.example.automation.tools.ToolRegistry
import com.example.automation.tools.ToolResult
import com.example.automation.engine.ExecutionRequest
import android.content.Context
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSerializationTest {

    @Test
    fun testPairRequestSerialization() {
        val gson = Gson()
        
        val payload = WindowsAgentClient.PairRequestPayload(
            pairingCode = "123456",
            deviceName = "Redmi K20 Pro"
        )
        
        val envelope = WindowsAgentClient.Envelope(
            protocol_version = "2.1",
            id = "test-id",
            type = "pair_request",
            timestamp = 1718000000L,
            source = WindowsAgentClient.Source("android-device", "android"),
            target = WindowsAgentClient.Target("windows-main"),
            payload = payload
        )
        
        val json = gson.toJson(envelope)
        
        // Parse it back as a generic map to verify key names and casing
        val parsedMap = gson.fromJson(json, Map::class.java) as Map<*, *>
        
        assertEquals("2.1", parsedMap["protocol_version"])
        assertEquals("test-id", parsedMap["id"])
        assertEquals("pair_request", parsedMap["type"])
        
        val payloadMap = parsedMap["payload"] as Map<*, *>
        assertEquals("123456", payloadMap["pairing_code"])
        assertEquals("Redmi K20 Pro", payloadMap["device_name"])
    }

    @Test
    fun testHelloPayloadWithCapabilitiesSerialization() {
        val gson = Gson()

        val payload = WindowsAgentClient.HelloPayload(
            deviceName = "Pixel 8 Pro",
            appVersion = "1.0",
            deviceType = "phone",
            model = "Pixel 8 Pro",
            osVersion = "14.0",
            capabilities = listOf("OPEN_APP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "SEND_WHATSAPP")
        )

        val envelope = WindowsAgentClient.Envelope(
            protocol_version = "2.1",
            id = "hello-id",
            type = "hello",
            timestamp = 1718000000L,
            source = WindowsAgentClient.Source("pixel-uuid-1", "android"),
            target = WindowsAgentClient.Target("windows-main"),
            payload = payload
        )

        val json = gson.toJson(envelope)
        val parsedMap = gson.fromJson(json, Map::class.java) as Map<*, *>

        assertEquals("2.1", parsedMap["protocol_version"])
        assertEquals("hello-id", parsedMap["id"])
        assertEquals("hello", parsedMap["type"])

        val payloadMap = parsedMap["payload"] as Map<*, *>
        assertEquals("Pixel 8 Pro", payloadMap["deviceName"])
        assertEquals("1.0", payloadMap["appVersion"])
        assertEquals("phone", payloadMap["deviceType"])
        assertEquals("Pixel 8 Pro", payloadMap["model"])
        assertEquals("14.0", payloadMap["osVersion"])

        val caps = payloadMap["capabilities"] as List<*>
        assertEquals(4, caps.size)
        assertTrue(caps.contains("FLASHLIGHT_ON"))
        assertTrue(caps.contains("SEND_WHATSAPP"))
    }

    @Test
    fun testToolRegistryCapabilitiesExtraction() {
        val flashlightTool = com.example.automation.tools.FlashlightTool()

        try {
            ToolRegistry.register(flashlightTool)
        } catch (e: Exception) {
            // If already registered, ignore
        }

        val allCaps = ToolRegistry.getAllCapabilities()
        assertTrue(allCaps.contains("FLASHLIGHT_ON"))
        assertTrue(allCaps.contains("FLASHLIGHT_OFF"))
    }

    @Test
    fun testPairResponseSuccessDeserialization() {
        val gson = Gson()
        val json = """
            {
                "status": "success",
                "token": "test_auth_token_123",
                "session_token": "test_session_token_456",
                "cert_fingerprint": "a1b2c3d4e5f6"
            }
        """.trimIndent()

        val payload = gson.fromJson(json, WindowsAgentClient.PairResponsePayload::class.java)
        assertEquals("success", payload.status)
        assertEquals("test_auth_token_123", payload.token)
        assertEquals("test_session_token_456", payload.session_token)
        assertEquals("a1b2c3d4e5f6", payload.cert_fingerprint)
    }

    @Test
    fun testPairResponseFailureDeserialization() {
        val gson = Gson()
        val json = """
            {
                "status": "error",
                "message": "Incorrect pairing code"
            }
        """.trimIndent()

        val payload = gson.fromJson(json, WindowsAgentClient.PairResponsePayload::class.java)
        assertEquals("error", payload.status)
        assertEquals("Incorrect pairing code", payload.message)
    }
}

