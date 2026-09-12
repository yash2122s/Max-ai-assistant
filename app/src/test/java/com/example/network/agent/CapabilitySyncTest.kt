package com.example.network.agent

import com.example.automation.tools.FlashlightTool
import com.example.automation.tools.VolumeTool
import com.example.automation.tools.ToolRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CapabilitySyncTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        try {
            ToolRegistry.register(FlashlightTool())
            ToolRegistry.register(com.example.automation.tools.SaveMemoryTool())
        } catch (e: Exception) {
            // Allowed if already registered
        }
    }

    @Test
    fun test01_toolRegistryCapabilityExtraction() {
        val caps = ToolRegistry.getAllCapabilities()
        assertTrue("Capabilities must contain FLASHLIGHT_ON", caps.contains("FLASHLIGHT_ON"))
        assertTrue("Capabilities must contain FLASHLIGHT_OFF", caps.contains("FLASHLIGHT_OFF"))
        assertTrue("Capabilities must contain SAVE_MEMORY", caps.contains("SAVE_MEMORY"))
    }

    @Test
    fun test02_typedDeviceCapabilitySerialization() {
        val typedCap = WindowsAgentClient.DeviceCapability(
            capability = "FLASHLIGHT_ON",
            available = true,
            version = "1",
            metadata = mapOf("has_flash" to true),
            lastUpdated = 1724000000L
        )

        val json = gson.toJson(typedCap)
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("FLASHLIGHT_ON", parsed["capability"])
        assertEquals(true, parsed["available"])
        assertEquals("1", parsed["version"])
        assertEquals(1724000000.0, parsed["last_updated"])
    }

    @Test
    fun test03_capabilitiesUpdateEventSerialization() {
        val caps = listOf(
            WindowsAgentClient.DeviceCapability("FLASHLIGHT_ON", true),
            WindowsAgentClient.DeviceCapability("TAKE_SCREENSHOT", true),
            WindowsAgentClient.DeviceCapability("CALL_PHONE", false)
        )

        val envelope = WindowsAgentClient.Envelope(
            protocol_version = "2.1",
            id = "cap_evt_001",
            type = "event",
            timestamp = 1724000000L,
            source = WindowsAgentClient.Source("dev_phone_1", "android"),
            target = WindowsAgentClient.Target("windows-main"),
            payload = mapOf(
                "event_name" to "capabilities:update",
                "device_id" to "dev_phone_1",
                "capabilities" to caps
            )
        )

        val json = gson.toJson(envelope)
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("2.1", parsed["protocol_version"])
        assertEquals("event", parsed["type"])

        val payload = parsed["payload"] as Map<*, *>
        assertEquals("capabilities:update", payload["event_name"])
        assertEquals("dev_phone_1", payload["device_id"])

        val capsList = payload["capabilities"] as List<*>
        assertEquals(3, capsList.size)
    }

    @Test
    fun test04_helloPayloadWithCapabilities() {
        val caps = listOf("FLASHLIGHT_ON", "OPEN_APP", "SET_VOLUME")
        val payload = WindowsAgentClient.HelloPayload(
            deviceName = "Redmi K20 Pro",
            appVersion = "1.0",
            deviceType = "phone",
            model = "Redmi K20 Pro",
            osVersion = "13.0",
            capabilities = caps
        )

        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("Redmi K20 Pro", parsed["deviceName"])
        assertEquals("phone", parsed["deviceType"])
        val parsedCaps = parsed["capabilities"] as List<*>
        assertEquals(3, parsedCaps.size)
        assertTrue(parsedCaps.contains("FLASHLIGHT_ON"))
    }

    @Test
    fun test05_duplicateCapabilitiesDetection() {
        val capsList1 = listOf(
            WindowsAgentClient.DeviceCapability("FLASHLIGHT_ON", true),
            WindowsAgentClient.DeviceCapability("OPEN_APP", true)
        )
        val capsList2 = listOf(
            WindowsAgentClient.DeviceCapability("FLASHLIGHT_ON", true),
            WindowsAgentClient.DeviceCapability("OPEN_APP", true)
        )
        val capsList3 = listOf(
            WindowsAgentClient.DeviceCapability("FLASHLIGHT_ON", true),
            WindowsAgentClient.DeviceCapability("OPEN_APP", true),
            WindowsAgentClient.DeviceCapability("SET_VOLUME", true)
        )

        val json1 = gson.toJson(capsList1.map { it.capability to it.available })
        val json2 = gson.toJson(capsList2.map { it.capability to it.available })
        val json3 = gson.toJson(capsList3.map { it.capability to it.available })

        assertEquals("Identical capability sets must produce matching serialized fingerprints", json1, json2)
        assertNotEquals("Different capability sets must produce distinct fingerprints", json1, json3)
    }

    @Test
    fun test06_unknownCapabilityFiltering() {
        val rawCaps = listOf("FLASHLIGHT_ON", "INVALID;SQL", "OPEN_APP", "<script>", "VALID_CAP")
        val filtered = rawCaps.filter { it.matches(Regex("^[A-Z0-9_]{1,60}$")) }

        assertEquals(3, filtered.size)
        assertTrue(filtered.contains("FLASHLIGHT_ON"))
        assertTrue(filtered.contains("OPEN_APP"))
        assertTrue(filtered.contains("VALID_CAP"))
        assertFalse(filtered.contains("INVALID;SQL"))
        assertFalse(filtered.contains("<script>"))
    }
}
