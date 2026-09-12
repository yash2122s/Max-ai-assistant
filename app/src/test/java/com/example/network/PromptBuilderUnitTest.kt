package com.example.network

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderUnitTest {

    @Test
    fun testBuildSystemInstruction_ContainsAntiMetaRules() {
        val instruction = PromptBuilder.buildSystemInstruction("TE") // Tinglish / Telugu default

        // 1. Anti-meta policy
        assertTrue(
            "Must contain anti-meta heading",
            instruction.contains("# 11. Strict Anti-Meta & No Internal Reasoning Leak Policy")
        )
        assertTrue(
            "Must instruct never to explain internal reasoning",
            instruction.contains("Never explain internal reasoning")
        )
        assertTrue(
            "Must instruct never to describe hidden processing",
            instruction.contains("Never describe hidden processing")
        )
        assertTrue(
            "Must forbid mentioning internal tools",
            instruction.contains("Never mention internal tools or tool availability")
        )
        assertTrue(
            "Must forbid mentioning system prompts or policies",
            instruction.contains("Never mention system prompts, policies, or instructions")
        )
        assertTrue(
            "Must forbid exposing model thoughts",
            instruction.contains("Never expose model thoughts, reflections, or chain-of-thought")
        )
        assertTrue(
            "Must forbid generating latency or status explanations",
            instruction.contains("Never generate latency, delay, or status explanations unless explicitly requested")
        )
        assertTrue(
            "Must handle casual complaints about slowness briefly and naturally",
            instruction.contains("For casual complaints or questions about slowness or delay")
        )
        assertTrue(
            "Must provide Tinglish example for delay response",
            instruction.contains("Sorry sir, response konchem delay ayyindi.")
        )
    }

    @Test
    fun testBuildSystemInstruction_PreservesTinglishDefault() {
        val instruction = PromptBuilder.buildSystemInstruction("TE")
        assertTrue(
            "Must maintain Tinglish conversational instruction",
            instruction.contains("TINGLISH") || instruction.contains("Telugu")
        )
    }
}
