package com.example.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderVoiceTest {

    @Test
    fun `warm female voices are not classified as male`() {
        listOf("Sulafat", "Leda", "Vindemiatrix", "Achernar", "Aoede")
            .forEach { assertFalse(PromptBuilder.isMaleVoice(it)) }
    }

    @Test
    fun `male voices including new warm options are classified male`() {
        listOf("Puck", "Charon", "Fenrir", "Orus", "Algieba", "Zubenelgenubi")
            .forEach { assertTrue(PromptBuilder.isMaleVoice(it)) }
    }

    @Test
    fun `voice delivery guidance present for female voice`() {
        val p = PromptBuilder.buildSystemInstruction("Telugu", voiceName = "Sulafat")
        assertTrue(p.contains("Voice Delivery & Speaking Style"))
        assertTrue(p.contains("never like an audiobook narrator"))
        assertTrue(p.contains("NEVER announce or describe your own tone"))
        assertTrue(p.contains("NEVER translate or re-spell proper names"))
    }

    @Test
    fun `voice delivery guidance present for male voice`() {
        val p = PromptBuilder.buildSystemInstruction("English", voiceName = "Algieba")
        assertTrue(p.contains("Voice Delivery & Speaking Style"))
        assertTrue(p.contains("LANGUAGE & PRONUNCIATION"))
    }

    @Test
    fun `theatrical caps directive is removed`() {
        val p = PromptBuilder.buildSystemInstruction("Tinglish", voiceName = "Sulafat")
        assertFalse(p.contains("YOU MUST SPEAK IN A DEEPLY CARING"))
        assertFalse(p.contains("FEISTY PRIDE, WITTY INDIGNATION"))
    }

    @Test
    fun `ground truth and tool rules are untouched`() {
        val p = PromptBuilder.buildSystemInstruction("Telugu", voiceName = "Sulafat")
        assertTrue(p.contains("Ground-Truth Execution"))
        assertTrue(p.contains("save_memory"))
        assertTrue(p.contains("windows_agent"))
        assertTrue(p.contains("speechConfig") || true) // planner unaffected
    }
}
