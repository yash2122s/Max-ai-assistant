package com.example.utils

/**
 * Robust sanitizer that prevents internal chain-of-thought, planning paragraphs,
 * model self-analysis, tool availability complaints, and RAG boundaries from leaking to the user.
 */
object InternalReasoningFilter {

    private val REASONING_PATTERNS = listOf(
        Regex("(?i)^\\*\\*.*?(thinking|planning|retrieving|analysis|history).*?\\*\\*"),
        Regex("(?i)^i('m| am) currently (trying to|focused on|looking at|planning to|attempting to).*"),
        Regex("(?i)^i intend to use.*"),
        Regex("(?i)^my goal is to.*"),
        Regex("(?i)^i explored my tools.*"),
        Regex("(?i)^i lack a dedicated tool.*"),
        Regex("(?i)^i don't have a dedicated tool (for retrieving|to retrieve).*"),
        Regex("(?i)^i do not have a dedicated tool (for retrieving|to retrieve).*"),
        Regex("(?i)^my initial assignment is complete.*"),
        Regex("(?i)^another rule.*"),
        Regex("(?i)^long response policy.*"),
        Regex("(?i)^my plan is.*"),
        Regex("(?i)^thinking process:?.*"),
        Regex("(?i)^thought:?.*")
    )

    private val THOUGHT_TAG_REGEX = Regex("(?s)<thought>.*?</thought>")
    private val RAG_BOUNDARY_REGEX = Regex("(?s)<MAX_RETRIEVED_MEMORY>.*?</MAX_RETRIEVED_MEMORY>")
    private val RAG_TAG_LEFTOVERS = Regex("(?i)</?MAX_RETRIEVED_MEMORY>")

    /**
     * Sanitizes response text to guarantee only clean, final user-facing text reaches the UI/TTS.
     */
    fun sanitize(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""

        var cleaned = rawText
        // Remove XML tags like <thought>...</thought> and <MAX_RETRIEVED_MEMORY>...</MAX_RETRIEVED_MEMORY>
        cleaned = THOUGHT_TAG_REGEX.replace(cleaned, "")
        cleaned = RAG_BOUNDARY_REGEX.replace(cleaned, "")
        cleaned = RAG_TAG_LEFTOVERS.replace(cleaned, "")

        val lines = cleaned.lines()
        val filteredLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // Check if line matches internal reasoning leak patterns
            val isLeak = REASONING_PATTERNS.any { it.matches(trimmedLine) }
            if (!isLeak) {
                filteredLines.add(line)
            }
        }

        return filteredLines.joinToString("\n").trim()
    }
}
