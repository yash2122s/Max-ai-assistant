package com.example.automation.engine

import org.json.JSONArray
import org.json.JSONObject

data class ParsedAction(
    val action: String, // "OPEN_APP", "CLICK", "TYPE", "FIND", "SYSTEM", "SCROLL", "PRESS_ENTER"
    val target: String? = null,
    val text: String? = null,
    val params: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("action", action)
            target?.let { put("target", it) }
            text?.let { put("text", it) }
            if (params.isNotEmpty()) {
                val paramsObj = JSONObject()
                for ((k, v) in params) {
                    paramsObj.put(k, v)
                }
                put("params", paramsObj)
            }
        }
    }
}

data class SemanticUiCommand(
    val intent: String = "UI_AUTOMATION",
    val app: String? = null,
    val actions: List<ParsedAction> = emptyList()
) {
    // Backwards-compatible single-action accessors
    val action: String get() = actions.firstOrNull { it.action != "OPEN_APP" }?.action ?: actions.firstOrNull()?.action ?: "CLICK"
    val target: String get() = actions.firstOrNull { it.action != "OPEN_APP" }?.target ?: actions.firstOrNull()?.target ?: ""
    val textToType: String? get() = actions.firstOrNull { it.text != null }?.text

    // Single-action constructor for backward compatibility
    constructor(intent: String = "UI_AUTOMATION", app: String, action: String, target: String, textToType: String? = null) : this(
        intent = intent,
        app = app,
        actions = buildList {
            add(ParsedAction("OPEN_APP", target = app))
            add(ParsedAction(action, target = target, text = textToType))
        }
    )

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("intent", intent)
            app?.let { put("app", it) }
            put("action", action)
            put("target", target)
            if (textToType != null) {
                put("textToType", textToType)
            }
            val actionsArr = JSONArray()
            for (act in actions) {
                actionsArr.put(act.toJson())
            }
            put("actions", actionsArr)
        }
    }
}

object SemanticUiCommandParser {

    private val ACTION_VERBS = setOf(
        "tap", "click", "press", "select", "hit", "touch", "find", "search", "type", "write", "enter", "input", "wait", "pause", "stay", "verify",
        "నొక్కు", "ట్యాప్", "వెతుకు", "రాయి", "టైప్", "శోధించు", "ఆగు"
    )

    // 1. Tanglish / Telugu Compound App Search ("Google maps open chesi Palakollu ane search chey", "YouTube open chesi songs search chey")
    private val TANGLISH_APP_SEARCH_COMPOUND = Regex(
        "^(.+?)\\s*(?:ni|nu|lo|ki|ను|ని)?\\s+(?:open|launch|స్టార్ట్|ఓపెన్|తెరువు)\\s*(?:cheyyandi|cheyyi|cheyi|chesi|chey|చేయండి|చేయి|చేయ్|చేసి)?\\s*(?:and|then|తర్వాత|మరియు|అలాగే|,)?\\s*(.+?)\\s*(?:ane|ani|ni|nu|kosam|gurinchi|అని|అనే|ని|ను|కోసం|గురించి)?\\s*(?:search|lookup|సెర్చ్|శోధించు)(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    // 2. Tanglish / Telugu Compound App Type-and-Search ("Google maps open chesi Palakollu ani type chesi search chey", "Instagram open cheyyi and search lo max_ai_assistant ani type cheyyi")
    private val TANGLISH_APP_TYPE_SEARCH_COMPOUND = Regex(
        "^(.+?)\\s*(?:ni|nu|lo|ki|ను|ని)?\\s+(?:open|launch|స్టార్ట్|ఓపెన్|తెరువు)\\s*(?:cheyyandi|cheyyi|cheyi|chesi|chey|చేయండి|చేయి|చేయ్|చేసి)?\\s*(?:and|then|తర్వాత|మరియు|అలాగే|,)?\\s*(.+?)\\s*(?:ane|ani|ni|nu)?\\s*(?:type|write|రాయి|టైప్)\\s*(?:cheyyandi|cheyyi|cheyi|chesi|chey|చేయండి|చేయి|చేయ్|చేసి)?(?:\\s*(?:and|then|తర్వాత|,)?\\s*(?:search|press enter|hit enter|సెర్చ్|శోధించు))?(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    // 3. Tanglish / Telugu In-App Search with 'lo' ("Google maps lo Palakollu search chey", "YouTube lo Telugu songs search chey")
    private val TANGLISH_IN_APP_SEARCH = Regex(
        "^(.+?)\\s+(?:lo|లో)\\s+(.+?)\\s*(?:ane|ani|ni|nu|kosam|gurinchi|అని|అనే|ని|ను|కోసం|గురించి)?\\s*(?:search|lookup|సెర్చ్|శోధించు)(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    // 4. English Compound App Search ("Open Google Maps and search for Palakollu", "Open YouTube, search songs")
    private val ENGLISH_APP_SEARCH_COMPOUND = Regex(
        "^(?:open|launch|start|go to)\\s+(?:the\\s+)?(.+?)\\s*(?:,|\\s+(?:and|then|&)\\s+)\\s*(?:search|lookup)\\s+(?:for\\s+|the\\s+|in\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    // 5. English In-App Search ("In Google Maps search Palakollu", "On YouTube search Telugu songs")
    private val ENGLISH_IN_APP_SEARCH = Regex(
        "^(?:in|inside|on)\\s+(?:the\\s+)?(.+?)\\s+(?:search|lookup)\\s+(?:for\\s+|the\\s+|in\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    // 6. Standalone Tanglish / Telugu Search ("Palakollu ane search chey", "Bhimavaram search chey", "Hyderabad ani search chey")
    private val TANGLISH_STANDALONE_SEARCH = Regex(
        "^(.+?)\\s*(?:ane|ani|ni|nu|kosam|gurinchi|అని|అనే|ని|ను|కోసం|గురించి)?\\s*(?:search|lookup|సెర్చ్|శోధించు)(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    // 7. Standalone Tanglish / Telugu Type-and-Search ("Palakollu ani type chesi search chey")
    private val TANGLISH_STANDALONE_TYPE_SEARCH = Regex(
        "^(.+?)\\s*(?:ane|ani|ni|nu)?\\s*(?:type|write|రాయి|టైప్)\\s*(?:cheyyandi|cheyyi|cheyi|chesi|chey|చేయండి|చేయి|చేయ్|చేసి)?\\s*(?:and|then|తర్వాత|,)?\\s*(?:search|press enter|hit enter|సెర్చ్|శోధించు)(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    // 8. Standalone English Search ("search for Palakollu", "search Palakollu")
    private val ENGLISH_STANDALONE_SEARCH = Regex(
        "^(?:search|lookup)\\s+(?:for\\s+|the\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    // 9. Standalone English Type-and-Search ("type Palakollu and search", "type Palakollu and press enter")
    private val ENGLISH_STANDALONE_TYPE_SEARCH = Regex(
        "^(?:type|enter|write|input)\\s+(?:text\\s+|the\\s+)?(.+?)\\s+(?:and|then)\\s+(?:search|press enter|hit enter|submit)$",
        RegexOption.IGNORE_CASE
    )

    private val ENGLISH_SVO_COMPOUND = Regex(
        "^(?:open|launch|start|go to)\\s+(?:the\\s+)?(.+?)\\s+(?:and|then|&|\\+)\\s+(tap|click|press|find|search|select|type|write|enter|hit|touch)\\s+(?:on\\s+|for\\s+|the\\s+|in\\s+|to\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val ENGLISH_COMMA_COMPOUND = Regex(
        "^(?:open|launch|start|go to)\\s+(?:the\\s+)?(.+?),\\s*(tap|click|press|find|search|select|type|write|enter|hit|touch)\\s+(?:on\\s+|for\\s+|the\\s+|in\\s+|to\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val TANGLISH_SOV_COMPOUND = Regex(
        "^(.+?)\\s*(?:ni|nu|lo|ki|ను|ని)?\\s*(?:open|launch|స్టార్ట్|ఓపెన్|తెరువు)\\s*(?:cheyyandi|cheyyi|cheyi|chesi|chey|చేయండి|చేయి|చేయ్|చేసి)?\\s*(?:and|then|తర్వాత|మరియు|అలాగే|,)?\\s*(.+?)\\s*(?:meeda|lo|పై|మీద|ని|ను|ki)?\\s*(tap|click|press|find|search|select|type|నొక్కు|ట్యాప్|వెతుకు|రాయి|టైప్)(?:\\s*(?:cheyyandi|cheyyi|cheyi|chey|చేయండి|చేయి|చేయ్|చేసుకో|చూడు))?$",
        RegexOption.IGNORE_CASE
    )

    private val IN_APP_COMPOUND = Regex(
        "^(?:in|inside|on)\\s+(?:the\\s+)?(.+?)\\s+(tap|click|press|find|search|select|type|write|enter)\\s+(?:on\\s+|for\\s+|the\\s+|in\\s+|to\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    fun isDeterministicPrimitive(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return false
        val parsed = parse(trimmed)
        if (parsed != null && parsed.actions.isNotEmpty()) {
            return true
        }
        val normalized = NaturalCommandNormalizer.normalize(trimmed)
        return normalized.intent != FastPathIntent.UNKNOWN
    }

    fun parse(rawText: String): SemanticUiCommand? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        val isTypeCommand = trimmed.startsWith("type ", ignoreCase = true) ||
                            trimmed.startsWith("write ", ignoreCase = true) ||
                            trimmed.startsWith("enter ", ignoreCase = true) ||
                            trimmed.startsWith("input ", ignoreCase = true) ||
                            trimmed.startsWith("రాయి ", ignoreCase = true) ||
                            trimmed.startsWith("టైప్ ", ignoreCase = true)

        var clean = trimmed
            .replaceFirst(Regex("^(?:hey\\s+|hi\\s+|ok\\s+)?(?:max|jarvis)[,\\s:]*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^.*?(?:చేసావ్ కాబట్టి|చేసావు కాబట్టి|ఓపెన్ చేసావ్ కాబట్టి|ఓపెన్ చేసావు కాబట్టి|కాబట్టి|అందుకని|అందువల్ల)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:so|now|then)\\s+", RegexOption.IGNORE_CASE), "")

        if (!isTypeCommand) {
            clean = clean.replace(Regex("[.!?,;]+$"), "")
        }

        clean = clean.replace(Regex("\\s+"), " ").trim()

        // 1. Tanglish / Telugu Compound App Search ("Google maps open chesi Palakollu ane search chey")
        TANGLISH_APP_TYPE_SEARCH_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawQuery = match.groupValues[2]
            return buildAppSearchCommand(rawApp, rawQuery)
        }

        TANGLISH_APP_SEARCH_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawQuery = match.groupValues[2]
            return buildAppSearchCommand(rawApp, rawQuery)
        }

        TANGLISH_IN_APP_SEARCH.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawQuery = match.groupValues[2]
            return buildAppSearchCommand(rawApp, rawQuery)
        }

        // 2. English Compound App Search ("Open Google Maps and search for Palakollu")
        ENGLISH_APP_SEARCH_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawQuery = match.groupValues[2]
            return buildAppSearchCommand(rawApp, rawQuery)
        }

        ENGLISH_IN_APP_SEARCH.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawQuery = match.groupValues[2]
            return buildAppSearchCommand(rawApp, rawQuery)
        }

        // 3. Multi-Clause Sequence (e.g. "Open Calculator, enter 25, press +, enter 10")
        val multiParsed = parseMultiClauseSequence(clean)
        if (multiParsed != null && multiParsed.actions.size > 2) {
            return multiParsed
        }

        // 4. English SVO Compound ("Open YouTube and tap Search", "Open Notes and type Palakollu")
        ENGLISH_SVO_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawAction = match.groupValues[2]
            val rawTarget = match.groupValues[3]
            return buildSingleSecondaryCommand(rawApp, rawAction, rawTarget)
        }

        // 5. English Comma Compound ("Open YouTube, tap Search")
        ENGLISH_COMMA_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawAction = match.groupValues[2]
            val rawTarget = match.groupValues[3]
            return buildSingleSecondaryCommand(rawApp, rawAction, rawTarget)
        }

        // 6. Tanglish / Telugu SOV Compound ("YouTube open chesi Search meeda tap chey")
        TANGLISH_SOV_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawTarget = match.groupValues[2]
            val rawAction = match.groupValues[3]
            return buildSingleSecondaryCommand(rawApp, rawAction, rawTarget)
        }

        // 7. In-App Targeting ("In Settings tap Display")
        IN_APP_COMPOUND.find(clean)?.let { match ->
            val rawApp = match.groupValues[1]
            val rawAction = match.groupValues[2]
            val rawTarget = match.groupValues[3]
            return buildSingleSecondaryCommand(rawApp, rawAction, rawTarget)
        }

        // 8. Standalone Tanglish / Telugu Search ("Palakollu ane search chey", "Bhimavaram search chey", "Hyderabad ani search chey")
        TANGLISH_STANDALONE_TYPE_SEARCH.find(clean)?.let { match ->
            val rawQuery = match.groupValues[1]
            buildStandaloneSearchCommand(rawQuery)?.let { return it }
        }

        TANGLISH_STANDALONE_SEARCH.find(clean)?.let { match ->
            val rawQuery = match.groupValues[1]
            buildStandaloneSearchCommand(rawQuery)?.let { return it }
        }

        // 9. Standalone English Search ("search for Palakollu", "type Palakollu and search")
        ENGLISH_STANDALONE_TYPE_SEARCH.find(clean)?.let { match ->
            val rawQuery = match.groupValues[1]
            buildStandaloneSearchCommand(rawQuery)?.let { return it }
        }

        ENGLISH_STANDALONE_SEARCH.find(clean)?.let { match ->
            val rawQuery = match.groupValues[1]
            buildStandaloneSearchCommand(rawQuery)?.let { return it }
        }

        if (multiParsed != null && multiParsed.actions.isNotEmpty()) {
            return multiParsed
        }

        val singleAction = parseSingleClause(clean)
        if (singleAction != null) {
            return SemanticUiCommand(
                intent = "UI_AUTOMATION",
                app = if (singleAction.action == "OPEN_APP") singleAction.target else null,
                actions = listOf(singleAction)
            )
        }

        return null
    }

    /**
     * Splits multi-action requests delimited by commas, "then", "and then", or "and <action>" into structured clauses.
     * Preserves exact sequence and duplicate actions.
     */
    private fun parseMultiClauseSequence(input: String): SemanticUiCommand? {
        val rawClauses = input.split(Regex("(?:,|\\s+(?:then|and then|తర్వాత)\\s+|\\s+(?:and|మరియు|అలాగే)\\s+(?=(?:tap|click|press|select|hit|touch|type|write|enter|input|find|search|play|watch|open|directions|wait|pause|stay|verify|నొక్కు|ట్యాప్|రాయి|టైప్|ఆగు)\\b))", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawClauses.size < 2) return null

        val parsedActions = mutableListOf<ParsedAction>()
        var appName: String? = null

        for (clause in rawClauses) {
            // Strip leading "and " or filler
            val cleanClause = clause.replace(Regex("^(?:and|మరియు|అలాగే)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val single = parseSingleClause(cleanClause)
            if (single != null) {
                if (single.action == "OPEN_APP" && appName == null) {
                    appName = single.target
                }
                parsedActions.add(single)
            } else {
                val compound = com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.parseTrailingActionClauses(cleanClause)
                if (compound.isNotEmpty()) {
                    for (act in compound) {
                        if (act.action == "OPEN_APP" && appName == null) {
                            appName = act.target
                        }
                        parsedActions.add(act)
                    }
                } else {
                    return null
                }
            }
        }

        if (parsedActions.isEmpty()) return null

        return SemanticUiCommand(
            intent = "UI_AUTOMATION",
            app = appName ?: parsedActions.firstOrNull()?.target,
            actions = parsedActions
        )
    }

    private fun parseSingleClause(clause: String): ParsedAction? {
        val trimmed = clause.trim()
        if (trimmed.isBlank()) return null

        // Pattern 0c: Wait action ("wait", "wait for ui", "wait for composer", "pause", "stay")
        val waitMatch = Regex("^(?:wait|stay|pause)(?:\\s+for\\s+(?:the\\s+)?(.+))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (waitMatch != null) {
            val waitTarget = waitMatch.groupValues[1].trim().ifBlank { "composer" }
            return ParsedAction("WAIT", target = waitTarget)
        }

        // Pattern 0a: Play action ("play the video", "play it", "play", "watch the video")
        val playMatch = Regex("^(?:play|watch)(?:\\s+(?:the\\s+)?(?:video|it|song|media|first\\s+video))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (playMatch != null) {
            return ParsedAction("CLICK", target = "Play")
        }

        // Pattern 0b: Directions action ("open directions", "get directions", "directions", "show directions")
        val directionsMatch = Regex("^(?:open|get|show|start)?\\s*(?:the\\s+)?directions$", RegexOption.IGNORE_CASE).find(trimmed)
        if (directionsMatch != null) {
            return ParsedAction("CLICK", target = "Directions")
        }

        // Pattern 1: App launch or UI Element open ("Open Calculator", "Launch Settings", "open the first result", "open first video", "open Message")
        val openMatch = Regex("^(?:open|launch|start|go to|ఓపెన్|స్టార్ట్|తెరువు)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (openMatch != null) {
            val raw = openMatch.groupValues[1].trim()
            if (raw.contains("result", ignoreCase = true) || raw.contains("direction", ignoreCase = true) ||
                raw.contains("video", ignoreCase = true) || raw.contains("link", ignoreCase = true) ||
                raw.contains("post", ignoreCase = true) || raw.contains("tab", ignoreCase = true) ||
                raw.contains("message", ignoreCase = true) || raw.contains("account", ignoreCase = true) ||
                raw.contains("profile", ignoreCase = true) || raw.contains("button", ignoreCase = true) ||
                raw.contains("contact", ignoreCase = true) || raw.contains("chat", ignoreCase = true)) {
                return ParsedAction("CLICK", target = cleanElementTarget(raw))
            }
            val app = cleanAppTarget(raw)
            if (app.isNotBlank() && !containsActionVerb(app)) {
                return ParsedAction("OPEN_APP", target = app)
            }
        }

        // Pattern 2: System actions (Back: "go back", "back", "press back", "venakki vellu", "venakki po", "venakki", "వెనక్కి", "గో బ్యాక్", "వెనుకకు వెళ్ళు")
        val backSet = setOf(
            "back", "go back", "press back", "then go back", "back vellu", "venakki vellu", "venakki po", "venakki",
            "వెనక్కి", "వెనక్కి వెళ్ళు", "వెనక్కి పో", "వెనుకకు వెళ్ళు", "గో బ్యాక్", "బ్యాక్"
        )
        if (trimmed.lowercase() in backSet) {
            return ParsedAction("SYSTEM", target = "back")
        }

        val homeSet = setOf(
            "home", "go home", "go to home", "go to homescreen", "go to home screen", "home screen", "homescreen",
            "హోమ్", "హోం", "హోమ్ స్క్రీన్"
        )
        if (trimmed.lowercase() in homeSet) {
            return ParsedAction("SYSTEM", target = "home")
        }

        // Pattern 3: Press Enter ("press enter", "hit enter", "press done", "enter", "submit", "enter chey", "enter kottu", "enter press chey", "ఎంటర్ కొట్టు", "ఎంటర్ చేయి")
        val enterDirectSet = setOf(
            "enter", "press enter", "hit enter", "press done", "press search", "done", "submit",
            "enter chey", "enter cheyi", "enter cheyyi", "enter cheyandi", "enter cheyyandi",
            "enter kottu", "enter kottandi", "enter press chey", "enter click chey", "enter tap chey",
            "ఎంటర్", "ఎంటర్ చేయి", "ఎంటర్ చెయ్", "ఎంటర్ కొట్టు", "ఎంటర్ నొక్కు", "ఎంటర్ చేయండి"
        )
        if (trimmed.lowercase() in enterDirectSet) {
            return ParsedAction("PRESS_ENTER", target = "enter")
        }

        val enterPattern = Regex("^(?:press|hit|tap|click)?\\s*enter\\s*(?:chey|cheyi|cheyyi|cheyandi|cheyyandi|kottu|kottandi|చేయి|చేయ్|కొట్టు|నొక్కు)?$", RegexOption.IGNORE_CASE)
        if (enterPattern.matches(trimmed)) {
            return ParsedAction("PRESS_ENTER", target = "enter")
        }

        // Pattern 4: Generic UI Scroll ("scroll down", "scroll up", "kindha scroll chey", "paina scroll chey")
        val scrollDownSet = setOf(
            "scroll down", "scroll down chey", "scroll down cheyyi", "kindha scroll chey", "kindha scroll cheyyi", "swipe up"
        )
        val scrollUpSet = setOf(
            "scroll up", "scroll up chey", "scroll up cheyyi", "paina scroll chey", "paina scroll cheyyi", "swipe down"
        )
        if (trimmed.lowercase() in scrollDownSet) {
            return ParsedAction("SCROLL", target = "DOWN")
        }
        if (trimmed.lowercase() in scrollUpSet) {
            return ParsedAction("SCROLL", target = "UP")
        }

        // Pattern 5: Tanglish SOV App Launch ("Calculator open chey", "Google Maps open chey")
        val tanglishOpen = Regex("^(.+?)\\s*(?:ni|nu|lo|ki|ను|ని)?\\s*(?:open|launch|ఓపెన్|తెరువు)(?:\\s*(?:chey|cheyi|cheyyi|cheyandi|cheyyandi|చేయి|చేయ్|చేయండి))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (tanglishOpen != null) {
            val app = cleanAppTarget(tanglishOpen.groupValues[1])
            if (app.isNotBlank() && !containsActionVerb(app)) {
                return ParsedAction("OPEN_APP", target = app)
            }
        }

        // Pattern 6: Tanglish SOV Click / Tap ("Search meeda tap chey", "Search tap chey", "Search click chey", "Search meeda click chey")
        val tanglishClick = Regex("^(.+?)\\s*(?:meeda|lo|పై|మీద|లో|ని|ను|ki)?\\s*(?:tap|click|press|నొక్కు|ట్యాప్|క్లిక్)(?:\\s*(?:chey|cheyi|cheyyi|cheyandi|cheyyandi|చేయి|చేయ్|చేయండి))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (tanglishClick != null) {
            val target = cleanElementTarget(tanglishClick.groupValues[1])
            if (target.isNotBlank()) {
                if (target.equals("enter", ignoreCase = true)) {
                    return ParsedAction("PRESS_ENTER", target = "enter")
                }
                return ParsedAction("CLICK", target = target)
            }
        }

        // Pattern 7: Tanglish SOV Type ("Bhimavaram type chey", "Bhimavaram ani type chey", "Bhimavaram rayi", "భీమవరం టైప్ చేయి")
        val tanglishTypeMatch = Regex("^(.+?)\\s*(?:ane|ani|ni|nu|అని|అనే|ని|ను)?\\s*(?:type|write|రాయి|రాయండి|టైప్)(?:\\s*(?:chey|cheyi|cheyyi|cheyandi|cheyyandi|చేయి|చేయ్|చేయండి))?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (tanglishTypeMatch != null) {
            val payload = cleanSearchQuery(tanglishTypeMatch.groupValues[1])
            if (payload.isNotBlank() && !containsActionVerb(payload)) {
                if (payload.equals("enter", ignoreCase = true)) {
                    return ParsedAction("PRESS_ENTER", target = "enter")
                }
                return ParsedAction("TYPE", target = payload, text = payload)
            }
        }

        // Pattern 8: English SVO Type ("type Bhimavaram", "enter 25", "write Hello")
        val typeMatch = Regex("^(?:type|write|enter|input|రాయి|టైప్)\\s+(?:the\\s+|text\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (typeMatch != null) {
            val payload = cleanSearchQuery(typeMatch.groupValues[1])
            if (payload.isNotBlank()) {
                if (payload.equals("enter", ignoreCase = true)) {
                    return ParsedAction("PRESS_ENTER", target = "enter")
                }
                return ParsedAction("TYPE", target = payload, text = payload)
            }
        }

        // Pattern 9: English SVO Create ("create a note", "new note")
        val createMatch = Regex("^(?:create|make|add|new)\\s+(?:a\\s+|an\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (createMatch != null) {
            val target = cleanElementTarget(createMatch.groupValues[1])
            if (target.isNotBlank()) {
                return ParsedAction("CLICK", target = target)
            }
        }

        // Pattern 10: English SVO Click ("tap Search", "click Search", "press +", "click 5", "tap Display")
        val clickMatch = Regex("^(?:tap|click|press|select|hit|touch|నొక్కు|ట్యాప్|క్లిక్)\\s+(?:on\\s+|for\\s+|the\\s+|in\\s+|to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (clickMatch != null) {
            val target = cleanElementTarget(clickMatch.groupValues[1])
            if (target.isNotBlank()) {
                if (target.equals("enter", ignoreCase = true)) {
                    return ParsedAction("PRESS_ENTER", target = "enter")
                }
                return ParsedAction("CLICK", target = target)
            }
        }

        // Pattern 11: English SVO Find / Searching ("find WiFi", "search Bluetooth")
        val findMatch = Regex("^(?:find|search|lookup|వెతుకు|శోధించు|చూడు)\\s+(?:for\\s+|the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (findMatch != null) {
            val target = cleanElementTarget(findMatch.groupValues[1])
            if (target.isNotBlank()) {
                return ParsedAction("FIND", target = target, params = mapOf("mode" to "find"))
            }
        }

        // Pattern 12: Standalone Search Clause ("Palakollu ane search chey", "Bhimavaram search chey")
        val tanglishSearch = TANGLISH_STANDALONE_SEARCH.find(trimmed)
        if (tanglishSearch != null) {
            val query = cleanSearchQuery(tanglishSearch.groupValues[1])
            if (query.isNotBlank() && !containsActionVerb(query)) {
                return ParsedAction("TYPE", target = query, text = query)
            }
        }

        return null
    }

    private fun containsActionVerb(text: String): Boolean {
        val lower = text.lowercase()
        return ACTION_VERBS.any { Regex("\\b$it\\b").containsMatchIn(lower) }
    }

    private fun buildAppSearchCommand(rawApp: String, rawQuery: String): SemanticUiCommand? {
        val cleanApp = cleanAppTarget(rawApp)
        if (cleanApp.isBlank() || cleanApp.contains(",") || containsActionVerb(cleanApp)) return null

        val preCleaned = cleanSearchQuery(rawQuery)
        val split = com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.splitArgumentAndTrailingClauses(preCleaned)
        val cleanQuery = cleanSearchQuery(split?.argument ?: preCleaned)

        if (cleanQuery.isBlank()) return null

        // If query is literally "search" or "search button" or "search icon", treat as click on search
        if (cleanQuery.equals("search", ignoreCase = true) ||
            cleanQuery.equals("search button", ignoreCase = true) ||
            cleanQuery.equals("search icon", ignoreCase = true)) {
            return SemanticUiCommand(
                intent = "UI_AUTOMATION",
                app = cleanApp,
                actions = listOf(
                    ParsedAction("OPEN_APP", target = cleanApp),
                    ParsedAction("CLICK", target = "Search")
                )
            )
        }

        val trailingActions = split?.trailingClauses?.flatMap {
            com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.parseTrailingActionClauses(it)
        } ?: emptyList()

        val actions = mutableListOf(
            ParsedAction("OPEN_APP", target = cleanApp),
            ParsedAction("CLICK", target = "Search"),
            ParsedAction("TYPE", target = cleanQuery, text = cleanQuery),
            ParsedAction("PRESS_ENTER", target = "enter")
        )
        actions.addAll(trailingActions)

        return SemanticUiCommand(
            intent = "UI_AUTOMATION",
            app = cleanApp,
            actions = actions
        )
    }

    private fun buildStandaloneSearchCommand(rawQuery: String): SemanticUiCommand? {
        val preCleaned = cleanSearchQuery(rawQuery)
        val split = com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.splitArgumentAndTrailingClauses(preCleaned)
        val cleanQuery = cleanSearchQuery(split?.argument ?: preCleaned)
        if (cleanQuery.isBlank()) return null
        if (containsActionVerb(cleanQuery)) return null

        if (cleanQuery.equals("search", ignoreCase = true) ||
            cleanQuery.equals("search button", ignoreCase = true) ||
            cleanQuery.equals("search icon", ignoreCase = true)) {
            return SemanticUiCommand(
                intent = "UI_AUTOMATION",
                app = null,
                actions = listOf(ParsedAction("CLICK", target = "Search"))
            )
        }

        val trailingActions = split?.trailingClauses?.flatMap {
            com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.parseTrailingActionClauses(it)
        } ?: emptyList()

        val actions = mutableListOf(
            ParsedAction("TYPE", target = cleanQuery, text = cleanQuery),
            ParsedAction("PRESS_ENTER", target = "enter")
        )
        actions.addAll(trailingActions)

        return SemanticUiCommand(
            intent = "UI_AUTOMATION",
            app = null,
            actions = actions
        )
    }

    private fun buildSingleSecondaryCommand(rawApp: String, rawAction: String, rawTarget: String): SemanticUiCommand? {
        val cleanApp = cleanAppTarget(rawApp)
        val cleanTarget = cleanElementTarget(rawTarget)
        val normalizedAction = normalizeAction(rawAction)

        if (cleanApp.isBlank() || cleanTarget.isBlank()) return null

        // App target must NOT contain nested action verbs or delimiters
        if (cleanApp.contains(",") || containsActionVerb(cleanApp)) return null

        // If target contains chained actions, do not collapse into single 2-step command
        val hasChainedAction = Regex(
            "(?:,|\\b(?:and|then|and then|మరియు|తర్వాత))\\s*(?:tap|click|press|find|search|select|type|write|enter|send|message|పంపు|రాయి|నొక్కు)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(rawTarget)
        if (hasChainedAction) return null

        // If action is explicit search/lookup, build full 4-step search workflow
        val lowerAction = rawAction.lowercase().trim()
        if (lowerAction in setOf("search", "lookup", "సెర్చ్", "శోధించు")) {
            return buildAppSearchCommand(cleanApp, cleanTarget)
        }

        val (finalTarget, typeText) = if (normalizedAction == "TYPE") {
            cleanTarget to cleanTarget
        } else {
            cleanTarget to null
        }

        val actions = listOf(
            ParsedAction(action = "OPEN_APP", target = cleanApp),
            ParsedAction(action = normalizedAction, target = finalTarget, text = typeText)
        )

        return SemanticUiCommand(
            intent = "UI_AUTOMATION",
            app = cleanApp,
            actions = actions
        )
    }

    private fun normalizeAction(rawAction: String): String {
        val a = rawAction.lowercase().trim()
        return when (a) {
            "tap", "click", "press", "select", "hit", "touch", "నొక్కు", "ట్యాప్" -> "CLICK"
            "find", "వెతుకు", "చూడు" -> "FIND"
            "search", "lookup", "సెర్చ్", "శోధించు" -> "FIND"
            "type", "write", "enter", "input", "రాయి", "టైప్" -> "TYPE"
            else -> "CLICK"
        }
    }

    private fun cleanAppTarget(rawApp: String): String {
        return rawApp
            .replace(Regex("^(?:the|an|a|please|can you please|could you please|can you|could you|would you|i want to|just|help me to|help me|oka sari|okasari|konchem|koncham|దయచేసి|కాస్త|ఒకసారి|ఓకసారి)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(?:ni|nu|lo|ki|ను|ని|app|application|యాప్)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun cleanElementTarget(rawTarget: String): String {
        return rawTarget
            .replace(Regex("^(?:on|for|the|an|a|button|icon|tab|option|meeda|పై|మీద|లో)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(?:button|icon|tab|option|meeda|పై|మీద|లో|lo|ni|nu|ని|ను)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanSearchQuery(rawQuery: String): String {
        return rawQuery
            .replace(Regex("^(?:in|on|into|inside|in the|on the|సెర్చ్ బార్ లో|సెర్చ్ బార్ లోకి|సెర్చ్ లో|search bar lo|search bar loki|search lo)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:for|the|a|an|text|query)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(?:ane|ani|ni|nu|kosam|gurinchi|lo|అని|అనే|ని|ను|కోసం|గురించి|లో)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
