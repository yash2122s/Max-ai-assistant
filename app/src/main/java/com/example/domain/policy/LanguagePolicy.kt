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

    /** Canonical persisted reply-language value; legacy Tenglish remains an alias. */
    fun canonicalPreference(value: String?): String = when (value?.trim()?.lowercase()) {
        "tinglish", "tenglish" -> AssistantLanguage.TINGLISH.displayName
        "english" -> AssistantLanguage.ENGLISH.displayName
        "telugu", "తెలుగు" -> AssistantLanguage.TELUGU.displayName
        "hindi", "हिंदी", "हिन्दी" -> AssistantLanguage.HINDI.displayName
        else -> DEFAULT_LANGUAGE
    }

    /**
     * Resolves the target response language.
     * Evaluates explicit query triggers first.
     * If no explicit override is requested, defaults to TINGLISH.
     */
    fun resolveResponseLanguage(userQuery: String?, sessionPreference: String? = null): AssistantLanguage {
        if (userQuery != null) {
            val lower = userQuery.trim().lowercase()

            // 1. Explicit Tinglish / Tenglish override (must precede English since 'tenglish' contains 'english')
            if (lower.contains("tinglish lo cheppu") ||
                lower.contains("tenglish lo cheppu") ||
                lower.contains("tinglish lo matladu") ||
                lower.contains("tenglish lo matladu") ||
                lower.contains("speak tinglish") ||
                lower.contains("speak tenglish") ||
                lower.contains("talk in tinglish") ||
                lower.contains("talk in tenglish") ||
                lower.contains("reply in tinglish") ||
                lower.contains("reply in tenglish") ||
                lower.contains("tinglish only") ||
                lower.contains("tenglish only")
            ) {
                return AssistantLanguage.TINGLISH
            }

            // 2. Explicit English override
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

            // 3. Explicit Telugu override
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

            // 4. Explicit Hindi override
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

        // 5. Session preference check (if explicitly configured by user on Home Screen or in Settings)
        // 6. Explicit query overrides above always take priority over the saved preference.
        return AssistantLanguage.entries.first { it.displayName == canonicalPreference(sessionPreference) }
    }

    /**
     * Generates the system prompt instruction block for the resolved language.
     * Enforces that MAX ALWAYS understands incoming user Telugu speech/text in every mode.
     */
    fun getSystemPromptLanguageInstruction(language: AssistantLanguage): String {
        return when (language) {
            AssistantLanguage.TELUGU -> """
                # 3. Language Policy — TELUGU MODE
                CRITICAL INSTRUCTION:
                - OUTPUT LANGUAGE: The user selected TELUGU. You must respond strictly in polite, warm, conversational Telugu (తెలుగు లిపి). Do not use English words except for app names, tech terms, or song titles.
                - Address the user respectfully as "సార్" (Sir) or "యశ్వంత్".
                - INPUT COMPREHENSION MANDATE: You must ALWAYS understand incoming user speech and text in TELUGU (both Telugu script and Latin/Tinglish transliteration) as well as English and Hindi! Never fail to comprehend Telugu queries or commands (e.g. "నా పేరు చెప్పు", "ఫ్లాష్ లైట్ ఆన్ చెయ్", "ఎవరు నువ్వు", "కాల్ చెయ్"). Understand the request completely and reply in Telugu.
            """.trimIndent()

            AssistantLanguage.ENGLISH -> """
                # 3. Language Policy — ENGLISH MODE
                CRITICAL INSTRUCTION:
                - OUTPUT LANGUAGE: The user selected ENGLISH. You must respond in natural, warm, polite conversational English.
                - Address the user respectfully as "Sir" or "Yashwanth".
                - INPUT COMPREHENSION MANDATE: Crucially, the user may speak or type in TELUGU (Telugu script or Latin transliteration), TINGLISH, or ENGLISH. You must ALWAYS UNDERSTAND Telugu speech, queries, and commands completely and accurately! For example, if the user asks in Telugu "నా పేరు చెప్పు" (Naa peru cheppu) or "flashlight on chey" or "evari tho call cheyali", you MUST understand the Telugu meaning 100% and execute the action or reply clearly in English (e.g. "Your name is Yashwanth, sir." or "I've turned on the flashlight, sir.").
            """.trimIndent()

            AssistantLanguage.HINDI -> """
                # 3. Language Policy — HINDI MODE
                CRITICAL INSTRUCTION:
                - OUTPUT LANGUAGE: The user selected HINDI. You must respond in polite, warm, conversational Hindi (हिंदी).
                - Address the user respectfully as "सर" (Sir) or "यशवंत".
                - INPUT COMPREHENSION MANDATE: Crucially, the user may speak or type in TELUGU (Telugu script or Latin transliteration), TINGLISH, HINDI, or ENGLISH. You must ALWAYS UNDERSTAND Telugu speech and queries accurately! Understand Telugu queries (e.g. "నా పేరు చెప్పు", "flashlight on chey") and reply politely in Hindi (e.g. "सर, आपका नाम यशवंत है।", "फ्लैशलाइट चालू कर रहा हूँ, सर।").
            """.trimIndent()

            AssistantLanguage.TINGLISH -> """
                # 3. Language Policy — TINGLISH MODE (TELUGU + ENGLISH BLEND)
                CRITICAL INSTRUCTION:
                - OUTPUT LANGUAGE: You must ALWAYS respond in natural Indian conversational TINGLISH (a friendly, natural blend of Telugu and English using Latin script).
                - Do NOT default to pure English.
                - Do NOT translate every user message into formal English.
                - Address the user respectfully as "Sir" or "Yash".
                - INPUT COMPREHENSION MANDATE: The user may speak or type in TELUGU (Telugu script or Latin script), TINGLISH, or ENGLISH. Always understand Telugu inputs accurately. Always interpret and transcribe incoming user voice input strictly as spoken TELUGU / TINGLISH or English. Never transcribe Telugu speech as foreign languages.
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
