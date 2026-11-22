package com.example.ui.home

import org.junit.Assert.*
import org.junit.Test

class PremiumMessageTextTest {
    @Test fun incompleteCodeFenceKeepsIndentationAndBlankLines() {
        val blocks = messageBlocks("Hello\n```kotlin\n    val x = 1\n\n    println(x)")
        assertEquals(2, blocks.size)
        assertFalse(blocks[0].code)
        assertEquals("kotlin", blocks[1].language)
        assertTrue(blocks[1].code)
        assertEquals("    val x = 1\n\n    println(x)", blocks[1].text)
    }

    @Test fun closedCodeReturnsToProseWithoutDroppingUnicode() {
        val blocks = messageBlocks("```\nprintln(\"హలో\")\n```\n**Done** ✓")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].code)
        assertEquals("println(\"హలో\")", blocks[0].text)
        assertFalse(blocks[1].code)
        assertEquals("**Done** ✓", blocks[1].text)
    }

    @Test fun plainTextPreservesParagraphsAndLists() {
        val text = "# Heading\n\n- first\n- second with `code`"
        assertEquals(listOf(MessageBlock(text)), messageBlocks(text))
    }

    @Test fun longerFencePreservesShorterFencesInsideExample() {
        val blocks = messageBlocks("````markdown\n```kt\nval x = 1\n```\n````\nDone")
        assertEquals(listOf(MessageBlock("```kt\nval x = 1\n```", "markdown", true), MessageBlock("Done")), blocks)
    }

    @Test fun fencedCodeClosesOnlyOnAnEligibleDelimiterLine() {
        val code = "``` still code\n    ```\n~~~"
        val blocks = messageBlocks("```kt\n$code\n  ````  \nహలో")
        assertEquals(listOf(MessageBlock(code, "kt", true), MessageBlock("హలో")), blocks)
    }

    @Test fun tildeFenceDoesNotCloseOnBackticksDuringStreaming() {
        val blocks = messageBlocks("~~~text\n```\nవివరణ")
        assertEquals(listOf(MessageBlock("```\nవివరణ", "text", true)), blocks)
    }
}
