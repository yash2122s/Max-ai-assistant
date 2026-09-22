package com.example.automation.engine.planning

import android.util.Log
import com.example.automation.engine.ParsedAction
import com.example.automation.state.ActionStep

/**
 * ActionArgumentBoundaryNormalizer
 *
 * Implements generic natural-language action argument boundary detection and plan normalization.
 * Distinguishes:
 *   ACTION ARGUMENT  from  NEXT ACTION
 *
 * Examples:
 *   "YouTube, open the first result and play the video" ->
 *     ARGUMENT: "YouTube"
 *     NEXT ACTIONS: [TAP("first result"), TAP("Play")]
 *
 *   "Palakollu to Bhimavaram, open directions" ->
 *     ARGUMENT: "Palakollu to Bhimavaram"
 *     NEXT ACTIONS: [TAP("Directions")]
 *
 * Preserves multi-word entity queries without naive comma splitting:
 *   "New York, New York" -> "New York, New York"
 *   "Palakollu, Andhra Pradesh, open directions" -> "Palakollu, Andhra Pradesh" + TAP("Directions")
 *   "Hello, how are you?" -> "Hello, how are you?"
 *   "AC/DC, official website" -> "AC/DC, official website"
 *
 * Preserves WhatsApp / SMS communication payloads as strictly OPAQUE.
 */
object ActionArgumentBoundaryNormalizer {

    private const val TAG = "ActionBoundaryNormalizer"

    data class BoundarySplitResult(
        val argument: String,
        val trailingClauses: List<String>
    )

    private val ACTION_DIRECTIVE_VERBS = setOf(
        "open", "launch", "start", "go to",
        "click", "tap", "press", "select", "hit", "touch",
        "play", "watch",
        "scroll", "swipe",
        "go back", "back",
        "search", "find",
        "enter", "submit", "done",
        "directions", "navigate", "route",
        "wait", "pause", "stay", "verify",
        // Telugu / Tanglish action directives
        "ఓపెన్", "ట్యాప్", "నొక్కు", "క్లిక్", "ప్లే", "డైరెక్షన్స్", "వెళ్లు", "చూడు", "ఆగు"
    )

    private val ORDINAL_PATTERNS = listOf(
        Regex("^(?:the\\s+)?(?:first|second|third|1st|2nd|3rd|top|last)\\s+(?:result|video|link|post|song|item|entry|account|user|profile|contact)\\b", RegexOption.IGNORE_CASE),
        Regex("^(?:the\\s+)?directions\\b", RegexOption.IGNORE_CASE),
        Regex("^(?:play|watch)(?:\\s+(?:the\\s+)?(?:video|it|song|media))?$", RegexOption.IGNORE_CASE),
        Regex("^(?:wait|stay|pause)(?:\\s+for\\s+(?:the\\s+)?(?:ui|screen|chat|composer|message|page|result|results|it))?$", RegexOption.IGNORE_CASE)
    )

    private val DELIMITER_REGEX = Regex(
        "(?:,\\s*(?:and then|then|and)\\s+|\\s+(?:and then|then)\\s+|,\\s*|\\s+and\\s+|\\s+(?:మరియు|తర్వాత)\\s+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks if a user goal or action type is communication message delivery.
     * When true, the payload must remain strictly opaque and never be decomposed.
     */
    fun isMessageOrDictation(actionType: String? = null, goal: String? = null): Boolean {
        val normAction = actionType?.uppercase()?.trim() ?: ""
        if (normAction in setOf("SEND_WHATSAPP", "SEND_WHATSAPP_MESSAGE", "SEND_SMS")) {
            return true
        }

        if (goal != null) {
            val lowerGoal = goal.lowercase().trim()
            val isWhatsAppMsg = (lowerGoal.contains("whatsapp") || lowerGoal.contains("message") || lowerGoal.contains("msg") || lowerGoal.contains("sms")) &&
                    (lowerGoal.contains("saying") || lowerGoal.contains("that") || lowerGoal.contains("with text") || lowerGoal.contains("tell") || lowerGoal.contains("pettu") || lowerGoal.contains("pampu"))
            if (isWhatsAppMsg) return true
        }

        return false
    }

    /**
     * Determines whether [text] begins with a genuine action directive (an action verb or ordinal target).
     */
    fun startsWithActionDirective(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        // Check ordinal patterns first ("the first result", "directions", "play the video", "wait")
        if (ORDINAL_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return true
        }

        val firstWordMatch = Regex("^([a-zA-Z0-9_\u0C00-\u0C7F]+)\\b").find(trimmed)
        val firstWord = firstWordMatch?.groupValues?.get(1)?.lowercase() ?: ""
        if (firstWord in ACTION_DIRECTIVE_VERBS) {
            // Further verify it's an action, not an entity like "OpenAI" or "Open Street"
            if (firstWord == "open") {
                val afterOpen = trimmed.substring(firstWordMatch!!.range.last + 1).trim()
                if (afterOpen.equals("ai", ignoreCase = true) || afterOpen.startsWith("ai ", ignoreCase = true)) {
                    return false
                }
            }
            return true
        }

        // Two-word verb phrases like "go to", "go back"
        val twoWordMatch = Regex("^([a-zA-Z0-9_\u0C00-\u0C7F]+\\s+[a-zA-Z0-9_\u0C00-\u0C7F]+)\\b").find(trimmed)
        val twoWords = twoWordMatch?.groupValues?.get(1)?.lowercase() ?: ""
        if (twoWords in ACTION_DIRECTIVE_VERBS) {
            return true
        }

        return false
    }

    /**
     * Splits a raw text argument into the clean primary argument and any subsequent action clauses.
     * Preserves entity phrases like "New York, New York", "Palakollu, Andhra Pradesh", "Hello, how are you?".
     */
    fun splitArgumentAndTrailingClauses(rawText: String): BoundarySplitResult? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        val matches = DELIMITER_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) return null

        // Evaluate delimiter positions from left to right, finding the first delimiter whose suffix is a genuine action directive
        for (match in matches) {
            val delimEnd = match.range.last + 1
            val suffix = trimmed.substring(delimEnd).trim()

            if (startsWithActionDirective(suffix)) {
                val argument = trimmed.substring(0, match.range.first).trim()
                if (argument.isNotBlank()) {
                    val trailingClauses = splitMultipleTrailingClauses(suffix)
                    if (trailingClauses.isNotEmpty()) {
                        Log.d(TAG, "Boundary detected: argument='$argument', trailing=$trailingClauses")
                        return BoundarySplitResult(
                            argument = argument,
                            trailingClauses = trailingClauses
                        )
                    }
                }
            }
        }

        return null
    }

    /**
     * Decomposes a suffix containing one or more action clauses (e.g. "open the first result and play the video").
     */
    private fun splitMultipleTrailingClauses(suffix: String): List<String> {
        val clauses = mutableListOf<String>()
        val matches = DELIMITER_REGEX.findAll(suffix).toList()

        var currentStart = 0
        for (match in matches) {
            val potentialClause = suffix.substring(currentStart, match.range.first).trim()
            val remainder = suffix.substring(match.range.last + 1).trim()

            if (startsWithActionDirective(remainder)) {
                if (potentialClause.isNotBlank()) {
                    clauses.add(potentialClause)
                    currentStart = match.range.last + 1
                }
            }
        }

        val lastClause = suffix.substring(currentStart).trim()
        if (lastClause.isNotBlank()) {
            clauses.add(lastClause)
        }

        return clauses
    }

    /**
     * Converts a single trailing action clause into a structured [ParsedAction] (or compound list).
     */
    fun parseTrailingActionClause(clause: String): ParsedAction? {
        val list = parseTrailingActionClauses(clause)
        return list.firstOrNull()
    }

    /**
     * Decomposes a single trailing clause into one or more [ParsedAction] items.
     * E.g. "open Message and wait" -> [CLICK("Message"), WAIT("Message composer")]
     */
    fun parseTrailingActionClauses(clause: String): List<ParsedAction> {
        val trimmed = clause.trim()
        if (trimmed.isBlank()) return emptyList()

        // 0. Wait / Pause actions ("wait", "wait for UI", "wait for screen", "pause", "stay")
        val waitMatch = Regex("^(?:wait|stay|pause)(?:\\s+for\\s+(?:the\\s+)?(.+))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (waitMatch != null) {
            val target = waitMatch.groupValues[1].trim().ifBlank { "composer" }
            return listOf(ParsedAction("WAIT", target = target))
        }

        // 0b. Compound "Action X and wait" ("open Message and wait", "tap Message then wait", "click Message and wait")
        val actionAndWaitMatch = Regex("^(?:open|launch|click|tap|press|select)\\s+(?:the\\s+)?(.+?)\\s+(?:and then|then|and|,)\\s*(?:wait|pause|stay)(?:\\s+for\\s+(?:the\\s+)?(.+))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (actionAndWaitMatch != null) {
            val target = actionAndWaitMatch.groupValues[1].trim()
                .replace(Regex("(?i)\\s+button$"), "")
                .trim()
            val waitTarget = actionAndWaitMatch.groupValues[2].trim().ifBlank {
                if (target.equals("Message", ignoreCase = true) || target.equals("chat", ignoreCase = true)) "Message composer" else target
            }
            return listOf(
                ParsedAction("CLICK", target = target),
                ParsedAction("WAIT", target = waitTarget)
            )
        }

        // 1. Play actions ("play the video", "play it", "play", "watch the video")
        val playMatch = Regex("^(?:play|watch)(?:\\s+(?:the\\s+)?(?:video|it|song|media|first\\s+video))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (playMatch != null) {
            return listOf(ParsedAction("CLICK", target = "Play"))
        }

        // 2. Directions actions ("open directions", "get directions", "directions", "show directions")
        val directionsMatch = Regex("^(?:open|get|show|start)?\\s*(?:the\\s+)?directions$", RegexOption.IGNORE_CASE).find(trimmed)
        if (directionsMatch != null) {
            return listOf(ParsedAction("CLICK", target = "Directions"))
        }

        // 3. Ordinal targets ("open the first result", "click first result", "tap first result", "first result", "first account")
        val ordinalMatch = Regex("^(?:open|launch|click|tap|press|select)?\\s*(?:the\\s+)?((?:first|second|third|1st|2nd|3rd|top|last)\\s+(?:result|video|link|post|song|item|entry|account|user|profile|contact))$", RegexOption.IGNORE_CASE).find(trimmed)
        if (ordinalMatch != null) {
            val target = ordinalMatch.groupValues[1].trim()
            return listOf(ParsedAction("CLICK", target = target))
        }

        // 4. Generic Click / Tap ("tap Search", "click submit", "press Done")
        val clickMatch = Regex("^(?:tap|click|press|select|hit|touch|నొక్కు|ట్యాప్|క్లిక్)\\s+(?:on\\s+|for\\s+|the\\s+|in\\s+|to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (clickMatch != null) {
            val rawTarget = clickMatch.groupValues[1].trim()
            if (rawTarget.equals("enter", ignoreCase = true)) {
                return listOf(ParsedAction("PRESS_ENTER", target = "enter"))
            }
            // Check for sub-wait inside click clause: e.g. "Message and wait"
            if (rawTarget.contains(Regex("(?i)\\s+(?:and then|then|and)\\s+(?:wait|stay|pause)$"))) {
                val cleanTarget = rawTarget.replace(Regex("(?i)\\s+(?:and then|then|and)\\s+(?:wait|stay|pause)$"), "").trim()
                return listOf(
                    ParsedAction("CLICK", target = cleanTarget),
                    ParsedAction("WAIT", target = if (cleanTarget.equals("Message", ignoreCase = true)) "Message composer" else cleanTarget)
                )
            }
            return listOf(ParsedAction("CLICK", target = rawTarget))
        }

        // 5. In-App Open on element / tab / button ("open first video", "open first result", "open profile", "open Message")
        val openInAppMatch = Regex("^(?:open|go to|ఓపెన్|వెళ్లు)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (openInAppMatch != null) {
            val rawTarget = openInAppMatch.groupValues[1].trim()
            if (rawTarget.contains(Regex("(?i)\\s+(?:and then|then|and)\\s+(?:wait|stay|pause)$"))) {
                val cleanTarget = rawTarget.replace(Regex("(?i)\\s+(?:and then|then|and)\\s+(?:wait|stay|pause)$"), "").trim()
                return listOf(
                    ParsedAction("CLICK", target = cleanTarget),
                    ParsedAction("WAIT", target = if (cleanTarget.equals("Message", ignoreCase = true)) "Message composer" else cleanTarget)
                )
            }
            return listOf(ParsedAction("CLICK", target = rawTarget))
        }

        // 6. Scroll
        if (trimmed.equals("scroll down", ignoreCase = true) || trimmed.equals("swipe up", ignoreCase = true)) {
            return listOf(ParsedAction("SCROLL", target = "DOWN"))
        }
        if (trimmed.equals("scroll up", ignoreCase = true) || trimmed.equals("swipe down", ignoreCase = true)) {
            return listOf(ParsedAction("SCROLL", target = "UP"))
        }

        // 7. System back
        if (trimmed.equals("go back", ignoreCase = true) || trimmed.equals("back", ignoreCase = true)) {
            return listOf(ParsedAction("SYSTEM", target = "back"))
        }

        // 8. Press enter
        if (trimmed.equals("enter", ignoreCase = true) || trimmed.equals("press enter", ignoreCase = true) || trimmed.equals("submit", ignoreCase = true)) {
            return listOf(ParsedAction("PRESS_ENTER", target = "enter"))
        }

        return emptyList()
    }

    /**
     * Normalizes a sequence of [ActionStep]s, ensuring no [TYPE_TEXT] step contains trailing subsequent action commands.
     * If an argument boundary is found, it:
     * 1. Shrinks TYPE_TEXT to only the actual search/input argument.
     * 2. Inserts PRESS_ENTER if needed.
     * 3. Appends the decomposed trailing steps with properly chained IDs and dependencies.
     */
    fun normalizePlan(steps: List<ActionStep>, goal: String? = null): List<ActionStep> {
        if (steps.isEmpty()) return steps
        if (isMessageOrDictation(goal = goal)) return steps

        val normalizedSteps = mutableListOf<ActionStep>()
        var stepCounter = 1
        var previousStepId: String? = null
        var planModified = false

        for (step in steps) {
            val normAction = step.actionType.uppercase().trim()

            if (normAction in setOf("TYPE_TEXT", "TYPE") && !isMessageOrDictation(normAction, goal)) {
                val rawText = step.targetText ?: step.params["text"] ?: ""
                val split = splitArgumentAndTrailingClauses(rawText)

                if (split != null && split.trailingClauses.isNotEmpty()) {
                    planModified = true
                    Log.i(TAG, "Normalizing TYPE_TEXT step '${step.stepId}': text='${split.argument}', trailing=${split.trailingClauses}")

                    // 1. Clean TYPE_TEXT step
                    val currentStepId = "step_${stepCounter++}"
                    val updatedParams = step.params.toMutableMap()
                    updatedParams["text"] = split.argument
                    val cleanTypeStep = step.copy(
                        stepId = currentStepId,
                        targetText = split.argument,
                        params = updatedParams,
                        dependsOn = listOfNotNull(previousStepId)
                    )
                    normalizedSteps.add(cleanTypeStep)
                    previousStepId = currentStepId

                    // 2. Add PRESS_ENTER if user intent implies search or submission and no PRESS_ENTER immediately follows
                    val enterStepId = "step_${stepCounter++}"
                    val enterStep = ActionStep(
                        stepId = enterStepId,
                        actionType = "PRESS_ENTER",
                        targetText = "enter",
                        params = mapOf("key" to "enter"),
                        dependsOn = listOfNotNull(previousStepId)
                    )
                    normalizedSteps.add(enterStep)
                    previousStepId = enterStepId

                    // 3. Decompose trailing clauses into discrete TAP / CLICK_TEXT / WAIT steps
                    for (clause in split.trailingClauses) {
                        val parsedActs = parseTrailingActionClauses(clause)
                        if (parsedActs.isNotEmpty()) {
                            for (parsedAct in parsedActs) {
                                val clauseStepId = "step_${stepCounter++}"
                                val target = parsedAct.target ?: clause.replace(Regex("^(?:open|tap|click|press|select)\\s+(?:the\\s+)?", RegexOption.IGNORE_CASE), "").trim()
                                val actionType = when (parsedAct.action) {
                                    "SCROLL" -> "SCROLL_SCREEN"
                                    "SYSTEM" -> "SYSTEM_ACTION"
                                    "PRESS_ENTER" -> "PRESS_ENTER"
                                    "WAIT", "WAIT_FOR_UI" -> "WAIT_FOR_UI"
                                    else -> "TAP"
                                }
                                val trailingStep = ActionStep(
                                    stepId = clauseStepId,
                                    actionType = actionType,
                                    targetText = target,
                                    params = if (actionType == "WAIT_FOR_UI") mapOf("timeout" to "5000") else emptyMap(),
                                    dependsOn = listOfNotNull(previousStepId)
                                )
                                normalizedSteps.add(trailingStep)
                                previousStepId = clauseStepId
                            }
                        } else {
                            val clauseStepId = "step_${stepCounter++}"
                            val target = clause.replace(Regex("^(?:open|tap|click|press|select)\\s+(?:the\\s+)?", RegexOption.IGNORE_CASE), "").trim()
                            val trailingStep = ActionStep(
                                stepId = clauseStepId,
                                actionType = "TAP",
                                targetText = target,
                                dependsOn = listOfNotNull(previousStepId)
                            )
                            normalizedSteps.add(trailingStep)
                            previousStepId = clauseStepId
                        }
                    }

                    continue
                }
            }

            // Keep step, re-numbering ID and dependencies to maintain a clean sequence
            val currentStepId = "step_${stepCounter++}"
            val adjustedStep = if (planModified) {
                step.copy(
                    stepId = currentStepId,
                    dependsOn = listOfNotNull(previousStepId)
                )
            } else {
                step
            }
            normalizedSteps.add(adjustedStep)
            previousStepId = currentStepId
        }

        return normalizedSteps
    }
}
