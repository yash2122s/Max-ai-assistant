package com.example.automation.skills.runtime

import android.util.Log
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.registry.SkillRegistry

/**
 * Result of resolving a user prompt against the Skill Registry.
 */
data class SkillResolutionMatch(
    val matched: Boolean,
    val skill: SkillDefinition? = null,
    val extractedInputs: Map<String, Any?> = emptyMap(),
    val confidence: Float = 0f,
    val matchType: String = "NONE",
    val reason: String = ""
)

/**
 * Upstream Natural Language Intent Resolver for MAX Automation Skills.
 * Matches user voice/text queries against installed and ephemeral skills
 * and extracts dynamic slot arguments.
 */
class SkillResolver(private val registry: SkillRegistry) {

    private val TAG = "SkillResolver"

    /**
     * Resolves a user prompt to the best matching skill.
     */
    fun resolvePrompt(prompt: String): SkillResolutionMatch {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            return SkillResolutionMatch(false, reason = "Prompt is empty")
        }

        // 1. Explicit Embedded / Attached Skill Check
        val yamlIdx = cleanPrompt.indexOf("---")
        if (yamlIdx != -1 && (cleanPrompt.contains("skill_id:") || cleanPrompt.contains("schema_version:"))) {
            val skillBlock = cleanPrompt.substring(yamlIdx).trim()
            val importResult = registry.registerEphemeral(skillBlock)
            if (importResult.success && importResult.skill != null) {
                val extracted = extractInputsFromPrompt(cleanPrompt, importResult.skill)
                return SkillResolutionMatch(
                    matched = true,
                    skill = importResult.skill,
                    extractedInputs = extracted,
                    confidence = 1.0f,
                    matchType = "EXPLICIT_EMBEDDED",
                    reason = "Parsed explicit ephemeral skill from prompt"
                )
            }
        }

        val availableSkills = registry.listInstalled() + registry.listEphemeral()
        val normalizedPrompt = cleanPrompt.lowercase()

        var bestMatch: SkillDefinition? = null
        var bestScore = 0f
        var bestMatchType = "NONE"
        var bestExtracted = emptyMap<String, Any?>()

        for (skill in availableSkills) {
            // Tier 1: Exact Phrase Match
            for (phrase in skill.intents.exactPhrases) {
                val cleanPhrase = phrase.trim().lowercase()
                if (normalizedPrompt == cleanPhrase || normalizedPrompt.startsWith("$cleanPhrase ") || normalizedPrompt.contains(cleanPhrase)) {
                    val score = 0.95f
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = skill
                        bestMatchType = "EXACT_PHRASE"
                        bestExtracted = extractInputsFromPrompt(cleanPrompt, skill)
                    }
                }
            }

            // Tier 2: Trigger Keyword Combination
            val keywords = skill.intents.triggerKeywords.map { it.trim().lowercase() }
            if (keywords.isNotEmpty()) {
                val matchedCount = keywords.count { normalizedPrompt.contains(it) }
                if (matchedCount >= 2 || (keywords.size == 1 && matchedCount == 1)) {
                    val score = 0.80f + (matchedCount.toFloat() / keywords.size.toFloat() * 0.10f)
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = skill
                        bestMatchType = "TRIGGER_KEYWORDS"
                        bestExtracted = extractInputsFromPrompt(cleanPrompt, skill)
                    }
                }
            }
        }

        if (bestMatch != null && bestScore >= 0.75f) {
            Log.i(TAG, "✓ Resolved prompt '$prompt' to skill '${bestMatch.skillId}' (score=$bestScore, matchType=$bestMatchType)")
            return SkillResolutionMatch(
                matched = true,
                skill = bestMatch,
                extractedInputs = bestExtracted,
                confidence = bestScore,
                matchType = bestMatchType,
                reason = "Matched skill '${bestMatch.name}' via $bestMatchType"
            )
        }

        return SkillResolutionMatch(matched = false, reason = "No matching skill found for prompt")
    }

    /**
     * Extracts dynamic slot inputs from the prompt based on declared skill inputs.
     */
    fun extractInputsFromPrompt(prompt: String, skill: SkillDefinition): Map<String, Any?> {
        val extracted = mutableMapOf<String, Any?>()
        val normalized = prompt.lowercase()

        for (inputDef in skill.inputs) {
            val name = inputDef.name.lowercase()

            // Heuristics for common entity extraction (username, contact, message, search query)
            if (name.contains("user") || name.contains("contact") || name.contains("account") || name.contains("name")) {
                val username = extractTargetUser(prompt)
                if (username.isNotBlank()) {
                    extracted[inputDef.name] = username
                }
            } else if (name.contains("message") || name.contains("text")) {
                val messageText = extractMessageText(prompt)
                if (messageText.isNotBlank()) {
                    extracted[inputDef.name] = messageText
                }
            }
        }

        return extracted
    }

    private fun extractTargetUser(prompt: String): String {
        // Look for patterns like: "for user john_wick", "for yaswanth.1432", "with rahul123", "lo yaswanth.1432 chat", "to yaswanth"
        val regexes = listOf(
            Regex("""(?:for\s+user|user|contact|for|with|to)\s+([a-zA-Z0-9_.]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:lo|ki|ku)\s+([a-zA-Z0-9_.]+)\s+(?:chat|message|dm)""", RegexOption.IGNORE_CASE),
            Regex("""(?:instagram|insta)\s+(?:lo\s+)?([a-zA-Z0-9_.]+)\s+(?:chat|dm|profile)""", RegexOption.IGNORE_CASE)
        )

        for (regex in regexes) {
            val match = regex.find(prompt)
            if (match != null && match.groupValues.size > 1) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotEmpty() && !candidate.equals("chat", ignoreCase = true) && !candidate.equals("message", ignoreCase = true)) {
                    return candidate
                }
            }
        }

        // Fallback: Check if there's any token containing letters and numbers/dots
        val tokens = prompt.split(Regex("\\s+"))
        for (token in tokens) {
            val clean = token.trim(',', '.', '!', '?', '"', '\'')
            if (clean.contains(".") || (clean.any { it.isDigit() } && clean.any { it.isLetter() })) {
                return clean
            }
        }

        return ""
    }

    private fun extractMessageText(prompt: String): String {
        val sayingRegex = Regex("""(?:saying|says|text|message)\s+["']?([^"']+)["']?""", RegexOption.IGNORE_CASE)
        val match = sayingRegex.find(prompt)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }
}
