package com.example.network.agent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RemoteCommandProtocolTest {

    private val gson = Gson()

    @Test
    fun test01_commandRequestSerialization() {
        val payload = WindowsAgentClient.CommandRequestPayload(
            commandId = "cmd_01J8K9L0M1N2",
            tool = "flashlight",
            action = "FLASHLIGHT_ON",
            arguments = mapOf("enabled" to true),
            timeoutMs = 5000
        )

        val envelope = WindowsAgentClient.Envelope(
            protocol_version = "2.1",
            id = "cmd_01J8K9L0M1N2",
            type = "command_request",
            timestamp = 1718000000L,
            source = WindowsAgentClient.Source("windows-main", "windows"),
            target = WindowsAgentClient.Target("android-dev-1"),
            payload = payload
        )

        val json = gson.toJson(envelope)
        val map = gson.fromJson(json, Map::class.java) as Map<*, *>

        assertEquals("2.1", map["protocol_version"])
        assertEquals("cmd_01J8K9L0M1N2", map["id"])
        assertEquals("command_request", map["type"])

        val payloadMap = map["payload"] as Map<*, *>
        assertEquals("cmd_01J8K9L0M1N2", payloadMap["command_id"])
        assertEquals("flashlight", payloadMap["tool"])
        assertEquals("FLASHLIGHT_ON", payloadMap["action"])
        assertEquals(5000.0, (payloadMap["timeout_ms"] as Number).toDouble(), 0.001)
    }

    @Test
    fun test02_commandRequestDeserialization() {
        val json = """
        {
            "protocol_version": "2.1",
            "id": "cmd_abc123",
            "type": "command_request",
            "timestamp": 1718000000,
            "source": { "device_id": "windows-main", "platform": "windows" },
            "target": { "device_id": "android-dev-1" },
            "payload": {
                "command_id": "cmd_abc123",
                "tool": "volume",
                "action": "SET_VOLUME",
                "arguments": { "volume": 80 },
                "timeout_ms": 3000
            }
        }
        """.trimIndent()

        val envelope = gson.fromJson(json, WindowsAgentClient.Envelope::class.java)
        assertEquals("2.1", envelope.protocol_version)
        assertEquals("cmd_abc123", envelope.id)
        assertEquals("command_request", envelope.type)

        val payloadJson = gson.toJson(envelope.payload)
        val payload = gson.fromJson(payloadJson, WindowsAgentClient.CommandRequestPayload::class.java)

        assertEquals("cmd_abc123", payload.commandId)
        assertEquals("volume", payload.tool)
        assertEquals("SET_VOLUME", payload.action)
        assertEquals(3000, payload.timeoutMs)
    }

    @Test
    fun test03_commandResultSerialization() {
        val resultPayload = WindowsAgentClient.CommandResultPayload(
            commandId = "cmd_abc123",
            status = "success",
            action = "SET_VOLUME",
            data = mapOf("current_volume" to 80),
            error = null,
            executionTimeMs = 45
        )

        val envelope = WindowsAgentClient.Envelope(
            protocol_version = "2.1",
            id = "res_xyz789",
            type = "command_result",
            timestamp = 1718000001L,
            source = WindowsAgentClient.Source("android-dev-1", "android"),
            target = WindowsAgentClient.Target("windows-main"),
            payload = resultPayload
        )

        val json = gson.toJson(envelope)
        val map = gson.fromJson(json, Map::class.java) as Map<*, *>

        assertEquals("command_result", map["type"])
        val payloadMap = map["payload"] as Map<*, *>
        assertEquals("cmd_abc123", payloadMap["command_id"])
        assertEquals("success", payloadMap["status"])
        assertEquals("SET_VOLUME", payloadMap["action"])
        assertNull(payloadMap["error"])
        assertEquals(45.0, (payloadMap["execution_time_ms"] as Number).toDouble(), 0.001)
    }

    @Test
    fun test04_commandResultDeserializationWithError() {
        val json = """
        {
            "protocol_version": "2.1",
            "id": "res_err01",
            "type": "command_result",
            "timestamp": 1718000001,
            "source": { "device_id": "android-dev-1", "platform": "android" },
            "target": { "device_id": "windows-main" },
            "payload": {
                "command_id": "cmd_err01",
                "status": "rejected",
                "action": "FLASHLIGHT_ON",
                "data": {},
                "error": {
                    "code": "DANGEROUS_PAYLOAD",
                    "message": "Payload contains disallowed dangerous pattern"
                },
                "execution_time_ms": 0
            }
        }
        """.trimIndent()

        val envelope = gson.fromJson(json, WindowsAgentClient.Envelope::class.java)
        val payloadJson = gson.toJson(envelope.payload)
        val payload = gson.fromJson(payloadJson, WindowsAgentClient.CommandResultPayload::class.java)

        assertEquals("cmd_err01", payload.commandId)
        assertEquals("rejected", payload.status)
        assertNotNull(payload.error)
        assertEquals("DANGEROUS_PAYLOAD", payload.error?.code)
        assertEquals("Payload contains disallowed dangerous pattern", payload.error?.message)
    }

    @Test
    fun test05_correlationIdPreserved() {
        val originalCommandId = "cmd_unique_" + UUID.randomUUID().toString().take(8)
        val resultPayload = WindowsAgentClient.CommandResultPayload(
            commandId = originalCommandId,
            status = "acknowledged",
            action = "OPEN_APP",
            data = mapOf("status" to "acknowledged"),
            error = null,
            executionTimeMs = 0
        )

        assertEquals(originalCommandId, resultPayload.commandId)
    }

    @Test
    fun test06_dangerousShellPatternDetection() {
        // Dangerous tools/actions must be detected
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("powershell -c calc", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("sh", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("sh -c whoami", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("bash", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("bash -c id", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("cmd.exe", "UNKNOWN"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "Runtime.exec(\"reboot\")"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "Runtime.getRuntime().exec(\"reboot\")"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "eval(malicious)"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "exec(malicious)"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "__import__('os')"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "<script>alert(1)</script>"))
        assertNotNull(WindowsAgentClient.scanForDangerousPayload("system", "SEND_WHATSAPP", mapOf("message" to "sh -c rm -rf /")))
    }

    @Test
    fun test07_legitimateCommandsPassSecurityValidation() {
        // Legitimate commands MUST NOT be rejected because of substring matches (e.g. 'sh' in FLASHLIGHT)
        val legitimateActions = listOf(
            "FLASHLIGHT_ON" to "flashlight",
            "FLASHLIGHT_OFF" to "flashlight",
            "SET_VOLUME" to "volume",
            "SET_BRIGHTNESS" to "brightness",
            "OPEN_APP" to "open_app",
            "GET_BATTERY_STATUS" to "battery",
            "TAKE_SCREENSHOT" to "system",
            "GET_SCREEN_CONTEXT" to "ScreenContextTool",
            "SEND_WHATSAPP" to "send_whatsapp_message"
        )

        for ((action, tool) in legitimateActions) {
            val match = WindowsAgentClient.scanForDangerousPayload(tool, action)
            assertNull("Legitimate action '$action' with tool '$tool' should NOT match dangerous patterns, got: $match", match)
        }

        // Test arguments with words containing 'sh' (e.g. Sharma, flashlight, screenshot, fresh)
        val safeArgs = mapOf(
            "message" to "Hello Sharma, I bought a fresh flashlight and took a screenshot of the shop.",
            "app" to "FashionApp",
            "target" to "PushNotification"
        )
        val argMatch = WindowsAgentClient.scanForDangerousPayload("send_whatsapp_message", "SEND_WHATSAPP", safeArgs)
        assertNull("Legitimate arguments with words containing 'sh' should not be rejected, got: $argMatch", argMatch)
    }

    @Test
    fun test08_timeoutRangeValidation() {
        val validTimeout = 5000
        val lowTimeout = 500
        val highTimeout = 60000

        assertTrue(validTimeout in 1000..30000)
        assertFalse(lowTimeout in 1000..30000)
        assertFalse(highTimeout in 1000..30000)
    }

    @Test
    fun test09_backwardCompatibilityWithExistingEnvelopes() {
        // Verify pair_request and hello envelopes continue to serialize cleanly
        val helloPayload = WindowsAgentClient.HelloPayload(
            deviceName = "Redmi K20 Pro",
            appVersion = "1.0",
            deviceType = "phone",
            model = "Redmi K20 Pro",
            osVersion = "13.0",
            capabilities = listOf("OPEN_APP", "FLASHLIGHT_ON")
        )

        val helloEnvelope = WindowsAgentClient.Envelope(
            id = "hello-1",
            type = "hello",
            source = WindowsAgentClient.Source("dev-1", "android"),
            target = WindowsAgentClient.Target("windows-main"),
            payload = helloPayload
        )
        val json = gson.toJson(helloEnvelope)
        assertTrue(json.contains("hello"))
        assertTrue(json.contains("FLASHLIGHT_ON"))
    }

    @Test
    fun test10_systemToolCapabilitiesIncludeScreenshot() {
        val systemAction = com.example.automation.actions.SystemAction()
        val systemTool = com.example.automation.tools.SystemTool(systemAction)
        assertEquals("system", systemTool.name)
        assertTrue(systemTool.supportedActions.contains("TAKE_SCREENSHOT"))
        assertTrue(systemTool.supportedActions.contains("SCREENSHOT"))
        assertTrue(systemTool.supportedActions.contains("PERFORM_HOME"))
        assertTrue(systemTool.supportedActions.contains("PERFORM_BACK"))
        assertTrue(systemTool.supportedActions.contains("CLOSE_APP"))
    }

    @Test
    fun test11_volumeBrightnessRingerDndYoutubeSupportedActions() {
        val volumeTool = com.example.automation.tools.VolumeTool(com.example.automation.actions.VolumeAction())
        assertTrue(volumeTool.supportedActions.contains("SET_VOLUME"))
        assertTrue(volumeTool.supportedActions.contains("VOLUME_UP"))
        assertTrue(volumeTool.supportedActions.contains("VOLUME_DOWN"))

        val brightnessTool = com.example.automation.tools.BrightnessTool(com.example.automation.actions.BrightnessAction())
        assertTrue(brightnessTool.supportedActions.contains("SET_BRIGHTNESS"))
        assertTrue(brightnessTool.supportedActions.contains("BRIGHTNESS_UP"))
        assertTrue(brightnessTool.supportedActions.contains("BRIGHTNESS_DOWN"))

        val ringerTool = com.example.automation.tools.RingerTool(com.example.automation.actions.RingerAction())
        assertTrue(ringerTool.supportedActions.contains("SET_RINGER_MODE"))
        assertTrue(ringerTool.supportedActions.contains("SILENT_MODE_ON"))
        assertTrue(ringerTool.supportedActions.contains("VIBRATE_MODE_ON"))

        val dndTool = com.example.automation.tools.DndTool(com.example.automation.actions.DndAction())
        assertTrue(dndTool.supportedActions.contains("SET_DND"))
        assertTrue(dndTool.supportedActions.contains("DND_ON"))
        assertTrue(dndTool.supportedActions.contains("DND_OFF"))

        val youtubeTool = com.example.automation.tools.YoutubeTool()
        assertEquals("youtube", youtubeTool.name)
        assertTrue(youtubeTool.supportedActions.contains("YOUTUBE_SEARCH"))
        assertTrue(youtubeTool.supportedActions.contains("OPEN_YOUTUBE"))

        val whatsAppTool = com.example.automation.tools.WhatsAppTool()
        assertTrue(whatsAppTool.supportedActions.contains("SEND_WHATSAPP"))
        assertTrue(whatsAppTool.supportedActions.contains("OPEN_WHATSAPP_CHAT"))
    }

    @Test
    fun test12_commandResultPayloadStrictContractSerialization() {
        val payload = WindowsAgentClient.CommandResultPayload(
            commandId = "cmd_12345",
            status = "success",
            action = "SET_VOLUME",
            tool = "volume",
            deviceId = "dev-raphael",
            deviceName = "Redmi K20 Pro",
            data = mapOf("volume" to 80, "message" to "Volume set to 80%"),
            error = null,
            executionTimeMs = 42
        )

        val json = gson.toJson(payload)
        assertTrue(json.contains("\"command_id\":\"cmd_12345\""))
        assertTrue(json.contains("\"status\":\"success\""))
        assertTrue(json.contains("\"action\":\"SET_VOLUME\""))
        assertTrue(json.contains("\"tool\":\"volume\""))
        assertTrue(json.contains("\"device_id\":\"dev-raphael\""))
        assertTrue(json.contains("\"device_name\":\"Redmi K20 Pro\""))
    }
}
