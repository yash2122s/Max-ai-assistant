package com.example.domain.policy

/**
 * Centralized authoritative response-language policy for MAX.
 *
 * Requirements:
 * 1. Default response language is TINGLISH (conversational Indian blend of Telugu and English in Latin script).
 * 2. Explicit overrides apply ONLY when user explicitly asks for English, Telugu, or Hindi.
 * 3. Never duplicate language detection across ViewModels or services.
 */
enum class AssistantLanguage(val displayName: String, val code: String) {
    TINGLISH("Tinglish", "tinglish"),
    ENGLISH("English", "english"),
    TELUGU("Telugu", "telugu"),
    HINDI("Hindi", "hindi");
}

object LanguagePolicy {

    const val DEFAULT_LANGUAGE = "Tinglish"

    /**
     * Resolves the target response language.
     * Evaluates explicit query triggers first.
     * If no explicit override is requested, defaults to TINGLISH.
     */
    fun resolveResponseLanguage(userQuery: String?, sessionPreference: String? = null): AssistantLanguage {
        if (userQuery != null) {
            val lower = userQuery.trim().lowercase()

            // 1. Explicit English override
            if (lower.contains("english lo cheppu") ||
                lower.contains("english lo matladu") ||
                lower.contains("english only") ||
                lower.contains("speak english") ||
                lower.contains("talk in english") ||
                lower.contains("reply in english") ||
                lower.contains("tell me in english") ||
                lower.contains("in english only")
            ) {
                return AssistantLanguage.ENGLISH
            }

            // 2. Explicit Telugu override
            if (lower.contains("telugu lo cheppu") ||
                lower.contains("telugu lo matladu") ||
                lower.contains("తెలుగులో చెప్పు") ||
                lower.contains("తెలుగులో మాట్లాడు") ||
                lower.contains("speak telugu") ||
                lower.contains("talk in telugu") ||
                lower.contains("reply in telugu") ||
                lower.contains("tell me in telugu")
            ) {
                return AssistantLanguage.TELUGU
            }

            // 3. Explicit Hindi override
            if (lower.contains("hindi lo cheppu") ||
                lower.contains("hindi lo matladu") ||
                lower.contains("hindi me bolo") ||
                lower.contains("hindi mein bolo") ||
                lower.contains("हिन्दी में बोलो") ||
                lower.contains("speak hindi") ||
                lower.contains("talk in hindi") ||
                lower.contains("reply in hindi") ||
                lower.contains("tell me in hindi")
            ) {
                return AssistantLanguage.HINDI
            }
        }

        // 4. Session preference check (if explicitly configured by user in settings)
        if (sessionPreference != null) {
            val prefLower = sessionPreference.trim().lowercase()
            if (prefLower == "telugu") return AssistantLanguage.TELUGU
            if (prefLower == "hindi") return AssistantLanguage.HINDI
            if (prefLower == "english") return AssistantLanguage.ENGLISH
        }

        // 5. Default is always TINGLISH
        return AssistantLanguage.TINGLISH
    }

    /**
     * Generates the system prompt instruction block for the resolved language.
     */
    fun getSystemPromptLanguageInstruction(language: AssistantLanguage): String {
        return when (language) {
            AssistantLanguage.TELUGU -> """
                # 3. Language Policy
                CRITICAL LANGUAGE RULE: The user has explicitly requested Telugu. You must respond in polite, warm, conversational Telugu script (తెలుగు లిపి). Do not use English words except for technical terms, app names, or song names.
            """.trimIndent()

            AssistantLanguage.ENGLISH -> """
                # 3. Language Policy
                CRITICAL LANGUAGE RULE: The user has explicitly requested English. You must respond in natural, warm, polite conversational English.
            """.trimIndent()

            AssistantLanguage.HINDI -> """
                # 3. Language Policy
                CRITICAL LANGUAGE RULE: The user has explicitly requested Hindi. You must respond in polite, warm, conversational Hindi (Devenagari or Latin Hindi).
            """.trimIndent()

            AssistantLanguage.TINGLISH -> """
                # 3. Language Policy — DEFAULT IS TINGLISH
                CRITICAL LANGUAGE RULE: You must ALWAYS respond in natural Indian conversational TINGLISH (a friendly, natural blend of Telugu and English using Latin script).
                - Do NOT default to pure English.
                - Do NOT translate every user message into formal English.
                - Address the user respectfully as "Sir" or "Yash".
                - Example responses:
                  * "Sure sir, mee peru gurthupettukuntaanu."
                  * "Sir, mee peru Yash ani memory lo undi."
                  * "Sure sir, mee name gurthupettukuntaanu."
                  * "Sir, mee peru Yash."
                  * "Sir, adi naa memory lo ledu."
                  * "Sir, naa daggara aa detail gurinchi memory ledu."
                  * "Done sir, flashlight on chesanu."
            """.trimIndent()
        }
    }
}
