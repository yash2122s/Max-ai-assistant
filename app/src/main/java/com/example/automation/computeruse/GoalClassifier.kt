package com.example.automation.computeruse

import android.util.Log

data class TaskClassification(
    val intentType: TaskIntentType,
    val targetApp: String?,
    val searchQuery: String = "",
    val navigationSteps: List<NavigationStep> = emptyList(),
    val normalizedGoal: String = ""
)

object GoalClassifier {
    private const val TAG = "GoalClassifier"

    fun classifyGoal(goal: String, explicitApp: String? = null): TaskClassification {
        val trimmed = goal.trim()
        val targetApp = explicitApp ?: ComputerUseEngine.extractTargetApp(trimmed)
        val searchQuery = ComputerUseEngine.extractSearchTarget(trimmed)

        val lower = trimmed.lowercase()
        val isExplicitSearch = lower.contains("search") || lower.contains("find") || lower.contains("look for") ||
            trimmed.contains("సెర్చ్") || trimmed.contains("వెతుకు")

        // 1. Search Check
        if (searchQuery.isNotBlank() && isExplicitSearch) {
            val cleanQuery = if (targetApp != null) {
                searchQuery.replace(Regex("""(?i)\s+(?:on|in|lo)\s+${Regex.escape(targetApp)}$"""), "").trim()
            } else searchQuery

            val classification = TaskClassification(
                intentType = TaskIntentType.SEARCH,
                targetApp = targetApp,
                searchQuery = cleanQuery,
                normalizedGoal = "Search '$cleanQuery'${if (targetApp != null) " on $targetApp" else ""}"
            )
            logClassification(classification)
            return classification
        }

        // 2. Direct UI Action without app (e.g. "Tap Search", "Search ni click chey")
        if (targetApp == null) {
            val fromToMatch = extractFromToTargets(trimmed)
            if (fromToMatch.isNotEmpty()) {
                val steps = fromToMatch.map { NavigationStep(NavigationActionType.CLICK_ELEMENT, it) }
                val classification = TaskClassification(
                    intentType = TaskIntentType.COMPOUND_UI_NAVIGATION,
                    targetApp = null,
                    navigationSteps = steps,
                    normalizedGoal = "Navigate -> " + fromToMatch.joinToString(" -> ")
                )
                logClassification(classification)
                return classification
            }

            val clickMatch = Regex("""(?i)(?:tap|click|press)\s+(?:on\s+)?([a-zA-Z0-9_\s]+)""").find(trimmed)
                ?: Regex("""(?i)([a-zA-Z0-9_\s]+?)(?:\s+ni|\s+lo|\s+meeda)?\s+(?:click|tap|press)""").find(trimmed)
            if (clickMatch != null) {
                val target = clickMatch.groupValues[1].trim()
                if (target.isNotBlank()) {
                    val classification = TaskClassification(
                        intentType = TaskIntentType.UI_ACTION,
                        targetApp = null,
                        navigationSteps = listOf(NavigationStep(NavigationActionType.CLICK_ELEMENT, target)),
                        normalizedGoal = "Click '$target'"
                    )
                    logClassification(classification)
                    return classification
                }
            }
        }

        // 3. App-targeted: Distinguish OPEN_APP_ONLY vs COMPOUND_UI_NAVIGATION
        if (targetApp != null) {
            var remainder = trimmed
            // Strip app name
            remainder = remainder.replace(Regex("""(?i)\b${Regex.escape(targetApp)}\b"""), " ")
            // Strip common open verbs in English and Telugu
            remainder = remainder.replace(Regex("""(?i)\b(?:open\s+chesi|open\s+chey|open\s+cheyyi|launch\s+chesi|launch|open|ఓపెన్\s+చేసి|ఓపెన్\s+చేయి|ఓపెన్\s+చేయ్|ఓపెన్)\b"""), " ")
            remainder = remainder.replace(Regex("""^[,\.\-;\s]+"""), "").trim()

            // If nothing meaningful remains, it is OPEN_APP_ONLY
            if (remainder.isBlank() || remainder.matches(Regex("""(?i)^(?:app|please|chesuko|cheyyi|chey|చేయి|చేయ్)*$"""))) {
                val classification = TaskClassification(
                    intentType = TaskIntentType.OPEN_APP_ONLY,
                    targetApp = targetApp,
                    navigationSteps = emptyList(),
                    normalizedGoal = "Open $targetApp"
                )
                logClassification(classification)
                return classification
            }

            // Remainder contains navigation targets
            val targets = extractNavigationTargets(remainder)
            if (targets.isNotEmpty()) {
                val steps = targets.map { NavigationStep(NavigationActionType.CLICK_ELEMENT, it) }
                val classification = TaskClassification(
                    intentType = TaskIntentType.COMPOUND_UI_NAVIGATION,
                    targetApp = targetApp,
                    navigationSteps = steps,
                    normalizedGoal = "Open $targetApp -> " + targets.joinToString(" -> ")
                )
                logClassification(classification)
                return classification
            }
        }

        // Fallback for general navigation
        val fallbackTargets = extractNavigationTargets(trimmed)
        if (fallbackTargets.isNotEmpty()) {
            val steps = fallbackTargets.map { NavigationStep(NavigationActionType.CLICK_ELEMENT, it) }
            val classification = TaskClassification(
                intentType = TaskIntentType.COMPOUND_UI_NAVIGATION,
                targetApp = targetApp,
                navigationSteps = steps,
                normalizedGoal = "Navigate -> " + fallbackTargets.joinToString(" -> ")
            )
            logClassification(classification)
            return classification
        }

        val fallback = TaskClassification(
            intentType = if (targetApp != null) TaskIntentType.OPEN_APP_ONLY else TaskIntentType.UI_ACTION,
            targetApp = targetApp,
            normalizedGoal = trimmed
        )
        logClassification(fallback)
        return fallback
    }

    private fun logClassification(classification: TaskClassification) {
        Log.i(
            TAG,
            "[TASK_CLASSIFIED] intent=${classification.intentType} targetApp=${classification.targetApp} steps=${classification.navigationSteps.map { it.target }}"
        )
    }

    fun extractNavigationTargets(text: String): List<String> {
        val targets = mutableListOf<String>()

        // 1. From X to Y / X nundi Y ki vellu / X nunchi Y ki vellu
        val fromTo = extractFromToTargets(text)
        if (fromTo.isNotEmpty()) {
            return fromTo
        }

        // Clean leading conjunctions like "and ", "then ", "tarvatha "
        val cleanedText = text.replace(Regex("""(?i)^(?:and\s+then|then|tarvatha|తర్వాత|and)\s+"""), "").trim()

        // 2. Multi-step splitting: "then", "tarvatha", "and", ","
        val segments = cleanedText.split(Regex("""(?i)\s+(?:and\s+then|then|tarvatha|తర్వాత|and)\s+|,"""))
        for (seg in segments) {
            val cleanSeg = seg.trim().replace(Regex("""(?i)^(?:and\s+then|then|tarvatha|తర్వాత|and)\s+"""), "").trim()
            if (cleanSeg.isBlank()) continue

            // English: "go to X", "navigate to X", "switch to X", "click X", "tap X"
            val goToMatch = Regex("""(?i)(?:go\s+to|navigate\s+to|switch\s+to|click\s+on|tap\s+on|click|tap)\s+([a-zA-Z0-9_\s]+)""").find(cleanSeg)
            if (goToMatch != null) {
                val t = cleanTarget(goToMatch.groupValues[1])
                if (t.isNotBlank() && !targets.contains(t)) {
                    targets.add(t)
                }
                continue
            }

            // Telugu: "X ki vellu", "X ki vell", "X ki po", "X click chey", "X open chey", "X కి వెళ్ళు"
            val teluguMatch = Regex("""(?i)([a-zA-Z0-9_\s]+?)\s+(?:ki\s+vellu|ki\s+vell|ki\s+po|click\s+chey|tap\s+chey|open\s+chey|కి\s+వెళ్ళు|కి\s+వెళ్ళు|ఓపెన్\s+చేయ్)""").find(cleanSeg)
            if (teluguMatch != null) {
                val t = cleanTarget(teluguMatch.groupValues[1])
                if (t.isNotBlank() && !targets.contains(t)) {
                    targets.add(t)
                }
                continue
            }
        }

        // 3. Single segment fallback if regexes above didn't match (e.g. "Library ki vellu", "Downloads ki vell")
        if (targets.isEmpty()) {
            val singleTelugu = Regex("""(?i)([a-zA-Z0-9_\s]+?)\s+(?:ki\s+vellu|ki\s+vell|ki\s+po|కి\s+వెళ్ళు)""").find(cleanedText)
            if (singleTelugu != null) {
                val t = cleanTarget(singleTelugu.groupValues[1])
                if (t.isNotBlank()) targets.add(t)
            }
        }

        return targets
    }

    private fun extractFromToTargets(text: String): List<String> {
        val targets = mutableListOf<String>()

        // 1. English: "from X to Y" (must have "from")
        val englishFromTo = Regex("""(?i)\bfrom\s+([a-zA-Z0-9_\s]+?)\s+to\s+([a-zA-Z0-9_\s]+?)(?:\s+and|$|\.|\,)""").find(text)
        if (englishFromTo != null) {
            val t1 = cleanTarget(englishFromTo.groupValues[1])
            val t2 = cleanTarget(englishFromTo.groupValues[2])
            if (t1.isNotBlank()) targets.add(t1)
            if (t2.isNotBlank()) targets.add(t2)
            if (targets.isNotEmpty()) return targets
        }

        // 2. Telugu: "X nundi Y ki vellu" / "X nunchi Y ki vellu"
        val teluguFromTo = Regex("""(?i)([a-zA-Z0-9_\s]+?)\s+(?:nundi|nunchi|నుండి|నుంచి)\s+([a-zA-Z0-9_\s]+?)(?:\s+ki\s+vellu|\s+ki\s+vell|\s+ki\s+po|\s+ki\s+ra|\s+కి\s+వెళ్ళు|\s+vellu|\s+ki|$|\.|\,)""").find(text)
        if (teluguFromTo != null) {
            val t1 = cleanTarget(teluguFromTo.groupValues[1])
            val t2 = cleanTarget(teluguFromTo.groupValues[2])
            if (t1.isNotBlank()) targets.add(t1)
            if (t2.isNotBlank()) targets.add(t2)
            if (targets.isNotEmpty()) return targets
        }

        return targets
    }

    private fun cleanTarget(raw: String): String {
        var t = raw.trim()
        while (true) {
            val prev = t
            t = t.replace(Regex("""(?i)^(?:from|to|and|then|tarvatha|తర్వాత|go|click|tap|switch)\s+"""), "")
            t = t.replace(Regex("""(?i)\s+(?:ki|lo|nundi|nunchi|meeda|vellu|vell|po|కి|నుండి|నుంచి|వెళ్ళు)$"""), "")
            if (t == prev) break
        }
        return t.trim()
    }
}
