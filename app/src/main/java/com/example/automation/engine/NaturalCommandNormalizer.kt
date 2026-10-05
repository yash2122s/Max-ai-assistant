package com.example.automation.engine

enum class FastPathIntent {
    OPEN_APP,
    UI_AUTOMATION,
    FLASHLIGHT_ON,
    FLASHLIGHT_OFF,
    NAV_HOME,
    NAV_BACK,
    NAV_RECENTS,
    NAV_NOTIFICATIONS,
    NAV_QUICK_SETTINGS,
    SCREENSHOT,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    UNMUTE,
    BRIGHTNESS_UP,
    BRIGHTNESS_DOWN,
    DND_ON,
    DND_OFF,
    GET_BATTERY,
    GET_DEVICE_STATUS,
    UNKNOWN
}

data class NormalizedCommand(
    val intent: FastPathIntent,
    val target: String? = null,
    val secondaryAction: String? = null,
    val secondaryTarget: String? = null,
    val isCompound: Boolean = false
)

object NaturalCommandNormalizer {

    private val MULTI_STEP_SEPARATORS = listOf(",", " and ", " then ", " తర్వాత ", " మరియు ", " అలాగే ", " & ")
    
    // Participial chaining or secondary action verbs that indicate multi-step / UI workflows
    private val COMPOUND_ACTION_INDICATORS = listOf(
        " search ", " send ", " type ", " click ", " tap ", " find ", " message ", " call ",
        " search for ", " play ", " వెతుకు ", " రాయి ", " పంపు ", " నొక్కు ", " ట్యాప్ ",
        " chesi ", " petti ", " chusi ", " చేసి ", " పెట్టి ", " చూసి ", " వెళ్లి "
    )

    private val LEADING_FILLERS = listOf(
        "please ", "can you please ", "could you please ", "can you ", "could you ", "would you ",
        "i want to ", "just ", "help me to ", "help me ", "oka sari ", "okasari ", "konchem ", "koncham ",
        "దయచేసి ", "కాస్త ", "ఒకసారి ", "ఓకసారి "
    )

    private val TRAILING_FILLERS = listOf(
        " for me", " please", " now", " right now", " chesi pettu", " chesi ivvu",
        " చేసి పెట్టు", " చేయి", " చేయ్", " చేసుకో", " పెట్టు", " చూడు",
        " cheyyandi", " cheyyi", " chesi", " cheyi", " chesko", " chesuko", " chey",
        " teesuko", " teesko", " ivvu", " pettandi", " pettu"
    )

    fun normalize(rawGoal: String): NormalizedCommand {
        val trimmed = rawGoal.trim()
        if (trimmed.isBlank()) {
            return NormalizedCommand(FastPathIntent.UNKNOWN)
        }

        // 0. Semantic UI Command Parser (Compound UI Automation)
        val parsedUi = SemanticUiCommandParser.parse(trimmed)
        if (parsedUi != null && parsedUi.actions.size > 1) {
            return NormalizedCommand(
                intent = FastPathIntent.UI_AUTOMATION,
                target = parsedUi.app,
                secondaryAction = parsedUi.action,
                secondaryTarget = parsedUi.target,
                isCompound = true
            )
        }

        // 1. Compound / Multi-Step Detection
        val lowerGoal = trimmed.lowercase()
        if (MULTI_STEP_SEPARATORS.any { lowerGoal.contains(it) }) {
            return NormalizedCommand(FastPathIntent.UNKNOWN, isCompound = true)
        }

        var clean = lowerGoal
            .replace(Regex("[.!?,;]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 2. Strip Leading Polite/Filler Words
        for (filler in LEADING_FILLERS) {
            if (clean.startsWith(filler)) {
                clean = clean.removePrefix(filler).trim()
            }
        }

        // 3. Strip Trailing Auxiliary/Filler Words first
        for (filler in TRAILING_FILLERS) {
            if (clean.endsWith(filler)) {
                clean = clean.removeSuffix(filler).trim()
            }
        }

        // 4. Check for Compound Action Verbs in the remaining string
        if (COMPOUND_ACTION_INDICATORS.any { clean.contains(it) }) {
            return NormalizedCommand(FastPathIntent.UNKNOWN, isCompound = true)
        }

        if (clean.isBlank()) {
            return NormalizedCommand(FastPathIntent.UNKNOWN)
        }

        // 5. FLASHLIGHT INTENT
        val flashOnPhrases = setOf(
            "turn on flashlight", "turn on torch", "turn on the flashlight", "turn on the torch",
            "flashlight on", "torch on", "lights on", "light on", "turn on light", "torch veyyi", "flashlight veyyi",
            "flashlight on chey", "torch on chey", "light on chey",
            "టార్చ్ ఆన్", "ఫ్లాష్‌లైట్ ఆన్", "లైట్స్ ఆన్", "లైట్ ఆన్", "లైట్ వెలిగించు"
        )
        val flashOffPhrases = setOf(
            "turn off flashlight", "turn off torch", "turn off the flashlight", "turn off the torch",
            "flashlight off", "torch off", "lights off", "light off", "turn off light", "torch aapu", "flashlight aapu",
            "flashlight off chey", "torch off chey", "light off chey",
            "టార్చ్ ఆఫ్", "ఫ్లాష్‌లైట్ ఆఫ్", "లైట్స్ ఆఫ్", "లైట్ ఆఫ్", "లైట్ ఆపు"
        )
        if (clean in flashOnPhrases) return NormalizedCommand(FastPathIntent.FLASHLIGHT_ON)
        if (clean in flashOffPhrases) return NormalizedCommand(FastPathIntent.FLASHLIGHT_OFF)

        // 6. SYSTEM NAVIGATION INTENT
        val homePhrases = setOf(
            "home", "go home", "go to home", "go to homescreen", "go to home screen", "go to the home screen",
            "open home", "open home screen", "homescreen", "home screen", "take me home", "return to home",
            "home ki vellu", "home screen ki vellu", "home screen vellu", "home vellu", "home open chey", "home open cheyyi",
            "హోమ్", "హోం", "హోమ్ స్క్రీన్", "హోమ్ స్క్రీన్‌", "హోమ్ స్క్రీన్ కి",
            "హోమ్కి వెళ్ళు", "హోమ్‌కి వెళ్ళు", "హోమ్ కి వెళ్ళు", "హోమ్ కి వెళ్లు", "హోమ్ వెళ్ళు", "హోమ్ వెళ్లు",
            "హోమ్ స్క్రీన్కి వెళ్ళు", "హోమ్ స్క్రీన్‌కి వెళ్ళు", "హోమ్ స్క్రీన్ కి వెళ్ళు", "హోమ్ స్క్రీన్ కి వెళ్లు",
            "హోమ్ స్క్రీన్ వెళ్ళు", "హోమ్ స్క్రీన్ వెళ్లు",
            "హోమ్ ఓపెన్ చేయి", "హోమ్ ఓపెన్ చెయ్", "హోమ్ స్క్రీన్ ఓపెన్ చేయి", "హోమ్ స్క్రీన్ ఓపెన్ చెయ్"
        )
        if (clean in homePhrases || clean.startsWith("హోమ్") || (clean.contains("హోమ్") && (clean.contains("వెళ్ళు") || clean.contains("వెళ్లు") || clean.contains("చేయి") || clean.contains("ఓపెన్")))) {
            return NormalizedCommand(FastPathIntent.NAV_HOME, target = "home")
        }

        val backPhrases = setOf("back", "go back", "వెనక్కి", "గో బ్యాక్")
        if (clean in backPhrases) return NormalizedCommand(FastPathIntent.NAV_BACK, target = "back")

        val recentPhrases = setOf("recent", "recents", "recent apps", "open recents", "open recent apps", "రీసెంట్")
        if (clean in recentPhrases) return NormalizedCommand(FastPathIntent.NAV_RECENTS, target = "recent")

        val notifPhrases = setOf(
            "notifications", "notification", "notification panel", "notification shade",
            "open notifications", "open notification panel", "open notification shade",
            "pull down notifications", "show notifications", "నోటిఫికేషన్"
        )
        if (clean in notifPhrases) return NormalizedCommand(FastPathIntent.NAV_NOTIFICATIONS, target = "notifications")

        val qsPhrases = setOf(
            "quick settings", "quick setting", "qs", "open quick settings",
            "show quick settings", "pull down quick settings", "క్విక్ సెట్టింగ్స్"
        )
        if (clean in qsPhrases) return NormalizedCommand(FastPathIntent.NAV_QUICK_SETTINGS, target = "quick_settings")

        val screenshotPhrases = setOf(
            "screenshot", "take screenshot", "take a screenshot", "capture screen picture",
            "capture screenshot", "స్క్రీన్‌షాట్"
        )
        if (clean in screenshotPhrases) return NormalizedCommand(FastPathIntent.SCREENSHOT, target = "screenshot")

        // 7. VOLUME & RINGER INTENT
        val volUpPhrases = setOf("volume up", "increase volume", "volume increase", "sound up")
        if (clean in volUpPhrases) return NormalizedCommand(FastPathIntent.VOLUME_UP, target = "up")

        val volDownPhrases = setOf("volume down", "decrease volume", "volume decrease", "sound down")
        if (clean in volDownPhrases) return NormalizedCommand(FastPathIntent.VOLUME_DOWN, target = "down")

        val mutePhrases = setOf("mute", "mute phone", "silent mode", "silent mode on", "turn on silent mode", "set to silent")
        if (clean in mutePhrases) return NormalizedCommand(FastPathIntent.MUTE, target = "silent")

        val unmutePhrases = setOf("unmute", "unmute phone", "normal mode", "normal mode on", "silent mode off", "turn off silent mode")
        if (clean in unmutePhrases) return NormalizedCommand(FastPathIntent.UNMUTE, target = "normal")

        // 8. BRIGHTNESS INTENT
        val brightUpPhrases = setOf("brightness up", "increase brightness", "brightness increase")
        if (clean in brightUpPhrases) return NormalizedCommand(FastPathIntent.BRIGHTNESS_UP, target = "up")

        val brightDownPhrases = setOf("brightness down", "decrease brightness", "brightness decrease")
        if (clean in brightDownPhrases) return NormalizedCommand(FastPathIntent.BRIGHTNESS_DOWN, target = "down")

        // 9. DND INTENT
        val dndOnPhrases = setOf("dnd on", "turn on dnd", "enable dnd", "do not disturb on", "turn on do not disturb")
        if (clean in dndOnPhrases) return NormalizedCommand(FastPathIntent.DND_ON, target = "true")

        val dndOffPhrases = setOf("dnd off", "turn off dnd", "disable dnd", "do not disturb off", "turn off do not disturb")
        if (clean in dndOffPhrases) return NormalizedCommand(FastPathIntent.DND_OFF, target = "false")

        // 10. STATUS INTENT
        val batteryPhrases = setOf("battery", "check battery", "battery status", "get battery status", "check battery status", "what is my battery")
        if (clean in batteryPhrases) return NormalizedCommand(FastPathIntent.GET_BATTERY)

        val deviceStatusPhrases = setOf("device status", "check device status", "get device status", "phone status")
        if (clean in deviceStatusPhrases) return NormalizedCommand(FastPathIntent.GET_DEVICE_STATUS)

        // 11. OPEN_APP INTENT (Natural Language SVO & SOV Extraction)
        val extractedTarget = extractAppTarget(clean)
        if (extractedTarget != null) {
            return NormalizedCommand(FastPathIntent.OPEN_APP, target = extractedTarget)
        }

        return NormalizedCommand(FastPathIntent.UNKNOWN)
    }

    private fun extractAppTarget(input: String): String? {
        // Pattern 1: English SVO ("open calculator", "launch chrome", "start settings", "open the camera")
        val englishSvoRegex = Regex("^(?:open|launch|start|go to)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        val svoMatch = englishSvoRegex.find(input)
        if (svoMatch != null) {
            val target = svoMatch.groupValues[1].trim()
            if (isValidAppTarget(target)) return cleanAppTarget(target)
        }

        // Pattern 2: Tanglish / Telugu SOV ("calculator open chey", "chrome ni open cheyi", "whatsapp open chesko", "settings open")
        val tanglishSovRegex = Regex("^(.+?)\\s*(?:ni|nu|lo|ki|ను|ని)?\\s*(?:open|launch|start|ఓపెన్|స్టార్ట్|తెరువు|ఓపెన్ చేయి|ఓపెన్ చేయ్|ఓపెన్ చేసుకో)(?:\\s*(?:chey|cheyi|chesko|చేయి|చేయ్|చేసుకో|చేసి|పెట్టు|చూడు))?$", RegexOption.IGNORE_CASE)
        val sovMatch = tanglishSovRegex.find(input)
        if (sovMatch != null) {
            val target = sovMatch.groupValues[1].trim()
            if (isValidAppTarget(target)) return cleanAppTarget(target)
        }

        // Pattern 3: Telugu native ("క్యాలిక్యులేటర్ తెరువు", "సెట్టింగ్స్ ఓపెన్ చేయి")
        val teluguRegex = Regex("^(.+?)\\s*(?:తెరువు|ఓపెన్ చేయి|ఓపెన్ చేయ్)$")
        val teluguMatch = teluguRegex.find(input)
        if (teluguMatch != null) {
            val target = teluguMatch.groupValues[1].trim()
            if (isValidAppTarget(target)) return cleanAppTarget(target)
        }

        return null
    }

    private fun isValidAppTarget(target: String): Boolean {
        if (target.isBlank() || target.length < 2) return false
        // Exclude prepositions indicating complex context (e.g., "open file on pc", "open for me")
        if (target.contains(" on ") || target.contains(" for ") || target.contains(" in ")) return false
        return true
    }

    private fun cleanAppTarget(target: String): String {
        return target
            .replace(Regex("^(?:the|an|a)\\s+"), "")
            .replace(Regex("\\s*(?:ni|nu|lo|ki|ను|ని)$"), "")
            .trim()
    }
}
