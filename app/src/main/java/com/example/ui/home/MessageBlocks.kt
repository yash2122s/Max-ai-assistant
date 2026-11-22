package com.example.ui.home

internal data class MessageBlock(val text: String, val language: String? = null, val code: Boolean = false)

private val fenceLine = Regex(" {0,3}(`{3,}|~{3,})(.*)")

/** Keeps incomplete streaming fences visible and preserves code indentation. */
internal fun messageBlocks(text: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val lines = mutableListOf<String>()
    var fence: String? = null
    var language: String? = null
    fun flush() {
        if (lines.isNotEmpty()) blocks += MessageBlock(lines.joinToString("\n"), language, fence != null)
        lines.clear()
    }
    text.lines().forEach { line ->
        val marker = fenceLine.matchEntire(line)
        val run = marker?.groupValues?.get(1)
        val info = marker?.groupValues?.get(2).orEmpty().trim()
        val open = fence
        when {
            run == null -> lines += line
            open == null && (run.first() != '`' || '`' !in info) -> {
                flush()
                fence = run
                language = info.ifBlank { null }
            }
            open != null && run.first() == open.first() && run.length >= open.length && info.isEmpty() -> {
                flush()
                fence = null
                language = null
            }
            else -> lines += line
        }
    }
    flush()
    return blocks
}
