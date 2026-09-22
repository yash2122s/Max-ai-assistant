package com.example.emotion.engine

import com.example.emotion.model.EmotionEvent
import java.util.Locale

/**
 * Intelligent detector that parses user voice transcripts and text messages
 * into corresponding [EmotionEvent]s across Telugu, Tinglish, and English.
 */
object EmotionEventDetector {

    // Siri rivalry regex triggers
    private val SIRI_PATTERNS = listOf(
        Regex("""\b(siri|apple siri|iphone siri)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(siri\s*(vs|versus|kanna|kante|better|smart|tho|thoti|polika|compare))\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(siri\s*tho\s*poliste)\b""", RegexOption.IGNORE_CASE)
    )

    // Yaswanth adoration regex triggers
    private val YASWANTH_PATTERNS = listOf(
        Regex("""\b(yaswanth|yashwanth|yash)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(creator|who created you|who made you|who built you)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(ninnu\s*(evaru|evar|create|chesaru))\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(yaswanth\s*(gurinchi|evaru|details|story))\b""", RegexOption.IGNORE_CASE)
    )

    // Praise & appreciation triggers
    private val PRAISE_PATTERNS = listOf(
        Regex("""\b(love you|i love you|love u|love max)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(good job|great job|well done|super max|super|awesome|amazing|fantastic)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(chala baga chesav|bagunnav|baga chesav|chala bagundi|thope|clever|intelligent)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(thank you|thanks max|thanks a lot|dhanyavadalu)\b""", RegexOption.IGNORE_CASE)
    )

    // Critique & scolding triggers
    private val CRITIQUE_PATTERNS = listOf(
        Regex("""\b(waste|useless|worst|idiot|stupid|dumb|shut up)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(pani cheyatledu|fail ayyav|chettha|dunnapotha|cheppindi vinatle)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(hate you|bad max|you don't know anything|wrong answer)\b""", RegexOption.IGNORE_CASE)
    )

    // Greeting triggers
    private val GREETING_PATTERNS = listOf(
        Regex("""\b(hi max|hello max|hey max|good morning|good evening|good afternoon|namaste|ela unnav)\b""", RegexOption.IGNORE_CASE)
    )

    /**
     * Inspects the incoming [userText] and returns the most relevant [EmotionEvent], if detected.
     */
    fun detectFromText(userText: String): EmotionEvent? {
        if (userText.isBlank()) return null
        val normalized = userText.trim().lowercase(Locale.ROOT)

        // Priority 1: Siri comparison triggers feisty response
        if (SIRI_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return EmotionEvent.SiriComparison(userText)
        }

        // Priority 2: Yaswanth inquiry triggers highest affection/devotion
        if (YASWANTH_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return EmotionEvent.YaswanthMention(userText)
        }

        // Priority 3: Praise & appreciation
        if (PRAISE_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return EmotionEvent.UserPraise(userText)
        }

        // Priority 4: Critique & frustration
        if (CRITIQUE_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return EmotionEvent.UserCritique(userText)
        }

        // Priority 5: Greetings
        if (GREETING_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return EmotionEvent.UserGreeting
        }

        return null
    }
}
