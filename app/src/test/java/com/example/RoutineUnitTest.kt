package com.example

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoutineUnitTest {

    data class Step(val action: String, val arguments: JSONObject)

    private fun normalizeName(name: String): String {
        return name.trim().lowercase().replace("\\s+".toRegex(), "_")
    }

    private fun serializeSteps(steps: List<Step>): String {
        val array = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("action", step.action)
            obj.put("arguments", step.arguments)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeSteps(jsonStr: String): List<Step> {
        val list = mutableListOf<Step>()
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val action = obj.getString("action")
            val arguments = obj.optJSONObject("arguments") ?: JSONObject()
            list.add(Step(action, arguments))
        }
        return list
    }

    @Test
    fun testNameNormalization() {
        assertEquals("sleep", normalizeName("Sleep"))
        assertEquals("sleep", normalizeName("  sleep  "))
        assertEquals("morning_routine", normalizeName("Morning Routine"))
        assertEquals("morning_routine_test", normalizeName("Morning   Routine   Test"))
    }

    @Test
    fun testSerializationDeserialization() {
        val steps = listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 10))
        )

        val serialized = serializeSteps(steps)
        val deserialized = deserializeSteps(serialized)

        assertEquals(2, deserialized.size)
        assertEquals("SET_DND", deserialized[0].action)
        assertTrue(deserialized[0].arguments.getBoolean("dndEnabled"))
        assertEquals("SET_BRIGHTNESS", deserialized[1].action)
        assertEquals(10, deserialized[1].arguments.getInt("percent"))
    }

    @Test
    fun testStepValidationSimulated() {
        // Unregistered / supported tools checklist simulate
        val registeredActions = setOf(
            "SET_DND", "GET_DND", "SET_BRIGHTNESS", "SET_VOLUME",
            "SET_BLUETOOTH", "GET_DEVICE_STATUS", "YOUTUBE_SEARCH"
        )

        val validSteps = listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 80))
        )

        val invalidSteps = listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("TURN_OFF_ALIENS", JSONObject()) // Invalid/hallucinated action
        )

        // Validate valid steps
        val allValid = validSteps.all { it.action in registeredActions }
        assertTrue(allValid)

        // Validate invalid steps
        val containsInvalid = invalidSteps.any { it.action !in registeredActions }
        assertTrue(containsInvalid)
    }

    @Test
    fun testDryRunTraceSimulation() {
        // Built-in presets map simulate
        val steps = listOf(
            Step("SET_DND", JSONObject().put("dndEnabled", true)),
            Step("SET_BRIGHTNESS", JSONObject().put("percent", 0)),
            Step("SET_BLUETOOTH", JSONObject().put("enabled", false))
        )

        val traceArray = JSONArray()
        for ((index, step) in steps.withIndex()) {
            val stepTrace = JSONObject().apply {
                put("step", index + 1)
                put("action", step.action)
                put("arguments", step.arguments)
                put("status", "validated")
            }
            traceArray.put(stepTrace)
        }

        assertEquals(3, traceArray.length())
        assertEquals(1, traceArray.getJSONObject(0).getInt("step"))
        assertEquals("SET_DND", traceArray.getJSONObject(0).getString("action"))
        assertEquals("validated", traceArray.getJSONObject(0).getString("status"))
        
        assertEquals(2, traceArray.getJSONObject(1).getInt("step"))
        assertEquals("SET_BRIGHTNESS", traceArray.getJSONObject(1).getString("action"))
        
        assertEquals(3, traceArray.getJSONObject(2).getInt("step"))
        assertEquals("SET_BLUETOOTH", traceArray.getJSONObject(2).getString("action"))
    }
}
