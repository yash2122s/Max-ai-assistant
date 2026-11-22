package com.example.automation.engine

/**
 * Spoken and written digit normalizer supporting English, Telugu script,
 * Telugu Latin transliteration, and Unicode digits.
 */
object SpokenDigits {

    private val DIGIT_WORDS: Map<String, String> = mapOf(
        // English
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",

        // Telugu Script
        "సున్నా" to "0",
        "ఒకటి" to "1",
        "రెండు" to "2",
        "మూడు" to "3",
        "నాలుగు" to "4",
        "ఐదు" to "5",
        "అయిదు" to "5",
        "ఆరు" to "6",
        "ఏడు" to "7",
        "ఎనిమిది" to "8",
        "తొమ్మిది" to "9",

        // Telugu Latin Transliterations
        "sunna" to "0",
        "okati" to "1",
        "rendu" to "2",
        "moodu" to "3",
        "nalugu" to "4",
        "aidu" to "5",
        "aaru" to "6",
        "edu" to "7",
        "enimidi" to "8",
        "tommidi" to "9"
    )

    private val TOKEN_REGEX = Regex("""[^\p{L}\p{M}\p{N}]+""")

    /**
     * Extracts digits from spoken or written text.
     * Tokenizes on punctuation/whitespace using `[^\p{L}\p{M}\p{N}]+`.
     * Maps English words (zero..nine), Telugu script (సున్నా..తొమ్మిది),
     * Telugu transliterations (sunna..tommidi), and Unicode decimal digits.
     */
    fun digitsOnly(text: String): String {
        if (text.isBlank()) return ""
        val tokens = text.split(TOKEN_REGEX).filter { it.isNotBlank() }
        val sb = StringBuilder()

        for (token in tokens) {
            val lower = token.lowercase()
            val mapped = DIGIT_WORDS[lower]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                for (ch in token) {
                    val digitVal = Character.digit(ch, 10)
                    if (digitVal in 0..9) {
                        sb.append(digitVal)
                    }
                }
            }
        }
        return sb.toString()
    }
}
