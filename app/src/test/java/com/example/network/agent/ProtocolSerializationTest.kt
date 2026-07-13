package com.example.network.agent

import com.google.gson.Gson
import org.junit.Assert.assertEquals
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
            protocol_version = 1,
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
        
        assertEquals(1.0, parsedMap["protocol_version"]) // Gson parses numbers as Double by default for untyped Maps
        assertEquals("test-id", parsedMap["id"])
        assertEquals("pair_request", parsedMap["type"])
        
        val payloadMap = parsedMap["payload"] as Map<*, *>
        assertEquals("123456", payloadMap["pairing_code"])
        assertEquals("Redmi K20 Pro", payloadMap["device_name"])
    }
}
