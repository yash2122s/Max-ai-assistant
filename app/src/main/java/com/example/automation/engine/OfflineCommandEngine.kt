package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.tools.ToolResult
import com.google.gson.JsonObject

object OfflineCommandEngine {
    private const val TAG = "OfflineCommandEngine"

    suspend fun executeIfMatched(context: Context, rawText: String): ToolResult? {
        val query = rawText.trim().lowercase()
        if (query.isBlank()) return null
        val cleanedText = query.replace(Regex("[.,!?]"), "")

        Log.d(TAG, "Evaluating query for offline command match: '$query'")

        // 0. Pending Auto-Reply Confirmation Check
        if (com.example.automation.AutoReplyController.hasPendingConfirmation()) {
            val response = com.example.automation.AutoReplyController.handleVoiceConfirmationCommand(context, rawText)
            if (response != null) {
                Log.d(TAG, "Processed pending auto-reply voice response: '$response'")
                return ToolResult(
                    success = !response.contains("Cancelled") && !response.contains("requires explicit"),
                    toolName = "auto_reply_confirmation",
                    message = response
                )
            }
        }

        // 0.1. Fast-Path DAG Plan Resolution via Canonical GoalPlanner Pipeline
        val fastPathPlan = GoalPlanner.resolveFastPathPlan(rawText, context)
        if (fastPathPlan != null) {
            Log.d(TAG, "Fast-path resolved plan (${fastPathPlan.size} steps) for: '$rawText'. Delegating to canonical GoalPlanner.")
            val planResult = GoalPlanner.planAndExecute(context, rawText)
            return ToolResult(
                success = planResult.success,
                toolName = if (fastPathPlan.size == 1) fastPathPlan[0].actionType else "UI_AUTOMATION",
                message = planResult.errorMessage ?: if (planResult.success) "Action completed." else "Action failed."
            )
        }

        // 0.5. Home Navigation Patterns (English, Telugu, Tenglish)
        val homePattern = Regex("^(?:go\\s+to\\s+home(?:\\s+screen)?|go\\s+home|home(?:\\s+screen)?|home\\s+ki\\s+(?:po|vellu|vellandi)|home\\s+vellu|intiki\\s+vellu)$")
        if (homePattern.containsMatchIn(cleanedText) || cleanedText == "home" || cleanedText == "go home" || cleanedText == "home screen") {
            Log.d(TAG, "Matched Offline Action: SYSTEM_ACTION (home)")
            val args = JsonObject().apply { addProperty("system_action_str", "home") }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
        }

        // 1. Diagnostics Patterns
        val diagnosticsPattern = Regex("(?:show\\s+registered\\s+tools|max\\s+diagnostics|diagnostics|registered\\s+tools)")
        if (diagnosticsPattern.containsMatchIn(cleanedText)) {
            Log.d(TAG, "Matched Offline Action: MAX_DIAGNOSTICS")
            return ExecutionEngine.execute(context, ExecutionRequest(action = "MAX_DIAGNOSTICS"))
        }

        // 2. Phone Unlock Patterns — P0-2: unlock NEVER silently auto-executes.
        // The offline path must route through the same explicit-confirmation
        // boundary (ApprovalCoordinator) as voice/plan paths — never straight
        // to ExecutionEngine. Confirmation replies ("Allow"/"yes") are handled
        // by ApprovalCoordinator.parseReply in AssistantEngine / HomeViewModel,
        // which re-dispatch the stored payload through ActionDispatcher.
        if (cleanedText.contains("unlock my phone") || cleanedText.contains("unlock phone") ||
            cleanedText.contains("unlock device") || cleanedText.contains("open sesame") ||
            cleanedText.contains("phone unlock") || cleanedText.contains("unlock mobile")) {
            Log.d(TAG, "[UNLOCK_AUTH] offline unlock command requires explicit confirmation")
            val payload = org.json.JSONObject().apply { put("action", "UNLOCK_DEVICE") }
            com.example.automation.approval.ApprovalCoordinator.requestApproval(
                context = context,
                action = "UNLOCK_DEVICE",
                target = "device",
                payload = payload
            )
            return ToolResult(
                success = false,
                toolName = "UNLOCK_DEVICE",
                message = "Unlock confirmation requested. Reply \"Allow\" to proceed."
            )
        }

        // 3. Flashlight Turn ON Patterns
        if (cleanedText.contains("turn on flashlight") || cleanedText.contains("flashlight on") ||
            cleanedText.contains("torch on") || cleanedText.contains("light on") || cleanedText.contains("turn on torch")) {
            Log.d(TAG, "Matched Offline Action: FLASHLIGHT_ON")
            val args = JsonObject().apply { addProperty("enabled", true) }
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_ON", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Flashlight turned on.") else res
        }

        // 4. Flashlight Turn OFF Patterns
        if (cleanedText.contains("turn off flashlight") || cleanedText.contains("flashlight off") ||
            cleanedText.contains("torch off") || cleanedText.contains("light off") || cleanedText.contains("turn off torch")) {
            Log.d(TAG, "Matched Offline Action: FLASHLIGHT_OFF")
            val args = JsonObject().apply { addProperty("enabled", false) }
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_OFF", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Flashlight turned off.") else res
        }

        // 5. Volume Controls (Percent & Direction in English, Telugu, Tenglish)
        val volumePatternPercent = Regex("set\\s+volume\\s+to\\s+(\\d+)(?:%|\\s+percent)?")
        val volumePatternUp = Regex("(?:increase|raise|volume|sound)\\s+(?:up|volume|sound|pencu|penchandi)")
        val volumePatternDown = Regex("(?:decrease|lower|volume|sound)\\s+(?:down|volume|sound|taggincu|tagginchandi)")

        val volMatchPercent = volumePatternPercent.find(cleanedText)
        if (volMatchPercent != null) {
            val percentVal = volMatchPercent.groupValues[1].toIntOrNull() ?: 50
            val args = JsonObject().apply { addProperty("percent", percentVal) }
            Log.d(TAG, "Matched Offline Action: SET_VOLUME ($percentVal%)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_VOLUME", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Volume set to $percentVal%.") else res
        } else if (volumePatternUp.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("direction", "up") }
            Log.d(TAG, "Matched Offline Action: SET_VOLUME (up)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_VOLUME", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Volume increased.") else res
        } else if (volumePatternDown.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("direction", "down") }
            Log.d(TAG, "Matched Offline Action: SET_VOLUME (down)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_VOLUME", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Volume decreased.") else res
        }

        // 6. Brightness Controls (Percent & Direction in English, Telugu, Tenglish)
        val brightnessPatternPercent = Regex("set\\s+brightness\\s+to\\s+(\\d+)(?:%|\\s+percent)?")
        val brightnessPatternUp = Regex("(?:increase|raise|brightness|light)\\s+(?:up|brightness|light|pencu|penchandi)")
        val brightnessPatternDown = Regex("(?:decrease|lower|brightness|light)\\s+(?:down|brightness|light|taggincu|tagginchandi)")

        val brightMatchPercent = brightnessPatternPercent.find(cleanedText)
        if (brightMatchPercent != null) {
            val percentVal = brightMatchPercent.groupValues[1].toIntOrNull() ?: 50
            val args = JsonObject().apply { addProperty("percent", percentVal) }
            Log.d(TAG, "Matched Offline Action: SET_BRIGHTNESS ($percentVal%)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_BRIGHTNESS", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Brightness set to $percentVal%.") else res
        } else if (brightnessPatternUp.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("direction", "up") }
            Log.d(TAG, "Matched Offline Action: SET_BRIGHTNESS (up)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_BRIGHTNESS", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Brightness increased.") else res
        } else if (brightnessPatternDown.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("direction", "down") }
            Log.d(TAG, "Matched Offline Action: SET_BRIGHTNESS (down)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_BRIGHTNESS", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Brightness decreased.") else res
        }

        // 7. Ringer Mode & DND (Silent, Vibrate, Normal, DND in English & Telugu)
        val ringerPatternSilentOn = Regex("(?:silent|mute|nisshabdam)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)")
        val ringerPatternSilentOff = Regex("(?:silent|mute|nisshabdam)\\s+(?:mode\\s+)?(?:off|deactivate|teesey|teeseyyi)")
        val ringerPatternVibrate = Regex("(?:vibrate|vibration|kampanam)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)?")
        val ringerPatternNormalOn = Regex("(?:ring|ringer|normal|sound|general)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi|pettu)?|(?:normal|ring|ringer)\\s+mode")
        val ringerPatternDndOn = Regex("(?:dnd|do not disturb|disturb cheyoddu)\\s+(?:mode\\s+)?(?:on|chey|active|cheyyi)")
        val ringerPatternDndOff = Regex("(?:dnd|do not disturb|disturb cheyoddu)\\s+(?:mode\\s+)?(?:off|deactivate|teesey|teeseyyi)")

        if (ringerPatternSilentOn.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("mode", "silent") }
            Log.d(TAG, "Matched Offline Action: SET_RINGER_MODE (silent)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_RINGER_MODE", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Silent mode enabled.") else res
        } else if (ringerPatternSilentOff.containsMatchIn(cleanedText) || ringerPatternNormalOn.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("mode", "normal") }
            Log.d(TAG, "Matched Offline Action: SET_RINGER_MODE (normal)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_RINGER_MODE", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Ringer mode set to normal.") else res
        } else if (ringerPatternVibrate.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("mode", "vibrate") }
            Log.d(TAG, "Matched Offline Action: SET_RINGER_MODE (vibrate)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_RINGER_MODE", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Vibrate mode enabled.") else res
        } else if (ringerPatternDndOn.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("mode", "dnd_on"); addProperty("enabled", true) }
            Log.d(TAG, "Matched Offline Action: SET_DND (on)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_DND", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Do Not Disturb enabled.") else res
        } else if (ringerPatternDndOff.containsMatchIn(cleanedText)) {
            val args = JsonObject().apply { addProperty("mode", "dnd_off"); addProperty("enabled", false) }
            Log.d(TAG, "Matched Offline Action: SET_DND (off)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SET_DND", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Do Not Disturb disabled.") else res
        }

        // 8. Windows PC Companion Commands
        val isPcTarget = cleanedText.contains("pc") || cleanedText.contains("laptop") || cleanedText.contains("computer") || cleanedText.contains("ల్యాప్‌టాప్")
        if (isPcTarget) {
            val args = JsonObject()
            var pcMsg = ""
            if (cleanedText.contains("lock")) {
                args.addProperty("agent_action", "core.terminal:run")
                args.addProperty("command", "rundll32.exe user32.dll,LockWorkStation")
                pcMsg = "Locking PC..."
            } else if (cleanedText.contains("chrome")) {
                args.addProperty("agent_action", "core.app:launch")
                args.addProperty("app_name", "chrome")
                pcMsg = "Opening Chrome on PC..."
            } else if (cleanedText.contains("notepad")) {
                args.addProperty("agent_action", "core.app:launch")
                args.addProperty("app_name", "notepad")
                pcMsg = "Opening Notepad on PC..."
            } else if (cleanedText.contains("calculator") || cleanedText.contains("calc")) {
                args.addProperty("agent_action", "core.app:launch")
                args.addProperty("app_name", "calc")
                pcMsg = "Opening Calculator on PC..."
            } else if (cleanedText.contains("open")) {
                val appToOpen = when {
                    cleanedText.contains("edge") -> "msedge"
                    cleanedText.contains("code") || cleanedText.contains("vs code") -> "code"
                    cleanedText.contains("spotify") -> "spotify"
                    cleanedText.contains("cmd") || cleanedText.contains("terminal") -> "cmd"
                    cleanedText.contains("explorer") -> "explorer"
                    else -> "chrome"
                }
                args.addProperty("agent_action", "core.app:launch")
                args.addProperty("app_name", appToOpen)
                pcMsg = "Launching $appToOpen on PC..."
            } else if (cleanedText.contains("screenshot")) {
                args.addProperty("agent_action", "core.vision:capture")
                pcMsg = "Taking screenshot of PC..."
            } else {
                null
            }

            if (args.has("agent_action")) {
                Log.d(TAG, "Matched Offline Action: WINDOWS_AGENT ($args)")
                val res = ExecutionEngine.execute(context, ExecutionRequest(action = "WINDOWS_AGENT", arguments = args))
                return if (res.message.isNullOrEmpty()) res.copy(message = pcMsg) else res
            }
        }

        // 9. WhatsApp Send Message & Chat Open Commands (English, Telugu, Tanglish) - Must precede Phone Call matching
        val waSendResult = parseAndExecuteWhatsApp(context, rawText, cleanedText)
        if (waSendResult != null) {
            return waSendResult
        }

        val waChatPattern1 = Regex("(.+?)\\s+(?:chat\\s+open\\s+chey|chat\\s+open\\s+cheyyi|chat\\s+open|chat\\s+chudu)")
        val waChatPattern2 = Regex("open\\s+(?:whatsapp\\s+)?chat\\s+(?:for|with|of)?\\s*(.+?)(?:\\s+on\\s+whatsapp)?$")
        val waChatPattern3 = Regex("open\\s+(.+?)\\s+chat(?:\\s+on\\s+whatsapp)?$")
        
        val matchWaChat = waChatPattern1.find(cleanedText) ?: waChatPattern2.find(cleanedText) ?: waChatPattern3.find(cleanedText)
        if (matchWaChat != null) {
            val contactName = matchWaChat.groupValues[1].trim()
            if (contactName.isNotBlank() && contactName !in listOf("whatsapp", "telegram", "instagram", "app", "settings")) {
                Log.d(TAG, "Matched Offline Action: OPEN_WHATSAPP_CHAT ($contactName)")
                val args = JsonObject().apply { addProperty("contact", contactName) }
                val res = ExecutionEngine.execute(context, ExecutionRequest(action = "OPEN_WHATSAPP_CHAT", arguments = args))
                return if (res.message.isNullOrEmpty()) res.copy(message = "Opening WhatsApp chat for $contactName...") else res
            }
        }

        // 10. Phone Call Commands (Must be strictly anchored and not match message dictation)
        val isMessagingUtterance = cleanedText.contains("whatsapp") || 
            cleanedText.contains("message") || 
            cleanedText.contains("msg") || 
            cleanedText.contains("text ") ||
            cleanedText.contains("saying ") ||
            cleanedText.contains("that i ") ||
            cleanedText.contains("tell ")
        val isExplicitWhatsAppCall = cleanedText.contains("on whatsapp") || cleanedText.contains("whatsapp call")

        if (!isMessagingUtterance || isExplicitWhatsAppCall) {
            val callPattern1 = Regex("^(?:call|dial|make\\s+a\\s+call\\s+to|phone)\\s+(.+)$", RegexOption.IGNORE_CASE)
            val callPattern2 = Regex("^(.+?)\\s*(?:ki\\s+)?(?:call\\s+chey|call\\s+cheyyi|phone\\s+chey|phone\\s+cheyyi)$", RegexOption.IGNORE_CASE)
            val callMatch1 = callPattern1.find(cleanedText)
            val callMatch2 = if (callMatch1 == null) callPattern2.find(cleanedText) else null
            val contactToCall = callMatch1?.groupValues?.get(1) ?: callMatch2?.groupValues?.get(1)

            val invalidContactTokens = setOf(
                "settings", "notification", "notifications", "back later", "later", "you later",
                "tomorrow", "yesterday", "meeting", "busy", "now"
            )

            if (contactToCall != null && !invalidContactTokens.any { contactToCall.trim().startsWith(it) }) {
                val cleanContact = contactToCall.replace(Regex("\\s+on\\s+whatsapp", RegexOption.IGNORE_CASE), "").trim()
                if (cleanContact.isNotBlank() && cleanContact !in invalidContactTokens) {
                    val args = JsonObject().apply { addProperty("contact", cleanContact) }
                    Log.d(TAG, "Matched Offline Action: CALL_PHONE ($cleanContact)")
                    val res = ExecutionEngine.execute(context, ExecutionRequest(action = "CALL_PHONE", arguments = args))
                    return if (res.message.isNullOrEmpty()) res.copy(message = "Calling $cleanContact...") else res
                }
            }
        }

        // 11. Create Contact Commands
        val createContactPattern = Regex("(?:create\\s+contact|add\\s+contact|new\\s+contact)\\s*(?:named|for)?\\s+(.+?)\\s+(?:with|number|phone)?\\s+(\\d+)")
        val createContactMatch = createContactPattern.find(cleanedText)
        if (createContactMatch != null) {
            val contactName = createContactMatch.groupValues[1].trim()
            val phoneNumber = createContactMatch.groupValues[2].trim()
            val args = JsonObject().apply {
                addProperty("name", contactName)
                addProperty("phone", phoneNumber)
            }
            Log.d(TAG, "Matched Offline Action: CREATE_CONTACT ($contactName, $phoneNumber)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "CREATE_CONTACT", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Opening contact creator for $contactName...") else res
        }

        // 12. YouTube Search / Play
        val youtubePattern1 = Regex("(?:play|search)\\s+(.+?)\\s+on\\s+youtube")
        val youtubePattern2 = Regex("youtube\\s+(?:lo|search)\\s+(.+?)(?:\\s+play\\s+chey|\\s+play\\s+cheyyi|\\s+open\\s+chey|\\s+open\\s+cheyyi)?")
        val youtubePattern3 = Regex("యూట్యూబ్‌లో\\s+(.+?)\\s+ప్లే\\s+చేయి")

        val matchYt = youtubePattern1.find(cleanedText) ?: youtubePattern2.find(cleanedText) ?: youtubePattern3.find(cleanedText)
        if (matchYt != null) {
            val youtubeQuery = matchYt.groupValues[1].trim()
            val args = JsonObject().apply { addProperty("query", youtubeQuery) }
            Log.d(TAG, "Matched Offline Action: YOUTUBE_SEARCH ($youtubeQuery)")
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "YOUTUBE_SEARCH", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Searching YouTube for '$youtubeQuery'...") else res
        }

        // 13. Open App Patterns (Single app only, compound commands handled by GoalPlanner)
        if (cleanedText.startsWith("open ") || cleanedText.startsWith("launch ")) {
            val compoundSeparators = listOf(" and ", " then ", " tap ", " click ", " find ", " search ", " meeda ", " lo ")
            if (!compoundSeparators.any { cleanedText.contains(it) }) {
                val appName = cleanedText.substringAfter("open ").substringAfter("launch ").trim()
                if (appName.isNotBlank() && appName !in listOf("sesame", "lock")) {
                    Log.d(TAG, "Matched Offline Action: OPEN_APP ($appName)")
                    val args = JsonObject().apply { addProperty("app_name", appName) }
                    return ExecutionEngine.execute(context, ExecutionRequest(action = "OPEN_APP", arguments = args))
                }
            }
        }

        // 13. System Navigation Actions (English, Telugu, Tenglish)
        val isHomeCommand = cleanedText in listOf(
            "go to home screen", "go to the home screen", "go home", "home screen", "home", "take me home", "press the home button", "return to the home screen",
            "home screen ki vellu", "home screen vellu", "home ki vellu", "home vellu", "home open chey", "home open cheyyi",
            "హోమ్", "హోం", "హోమ్ స్క్రీన్", "హోమ్ స్క్రీన్‌", "హోమ్ స్క్రీన్ కి",
            "హోమ్కి వెళ్ళు", "హోమ్‌కి వెళ్ళు", "హోమ్ కి వెళ్ళు", "హోమ్ కి వెళ్లు", "హోమ్ వెళ్ళు", "హోమ్ వెళ్లు",
            "హోమ్ స్క్రీన్కి వెళ్ళు", "హోమ్ స్క్రీన్‌కి వెళ్ళు", "హోమ్ స్క్రీన్ కి వెళ్ళు", "హోమ్ స్క్రీన్ కి వెళ్లు",
            "హోమ్ స్క్రీన్ వెళ్ళు", "హోమ్ స్క్రీన్ వెళ్లు",
            "హోమ్ ఓపెన్ చేయి", "హోమ్ ఓపెన్ చెయ్", "హోమ్ స్క్రీన్ ఓపెన్ చేయి", "హోమ్ స్క్రీన్ ఓపెన్ చెయ్"
        ) || cleanedText.startsWith("హోమ్") || (cleanedText.contains("హోమ్") && (cleanedText.contains("వెళ్ళు") || cleanedText.contains("వెళ్లు") || cleanedText.contains("చేయి") || cleanedText.contains("ఓపెన్")))
        val isBackCommand = cleanedText in listOf(
            "go back", "back", "back ki velli", "వెనుకకు వెళ్ళు", "వెనక్కి వెళ్ళు", "back vellu"
        )
        val isRecentsCommand = cleanedText in listOf(
            "open recent apps", "recent apps open chey", "recent apps", "రీసెంట్ యాప్స్ ఓపెన్ చెయ్", "recents open chey", "recents", "recent"
        )
        val isNotificationsCommand = cleanedText in listOf(
            "open notifications", "show notifications", "notifications", "notifications open chey", "నోటిఫికేషన్స్ ఓపెన్ చెయ్"
        )
        val isQuickSettingsCommand = cleanedText in listOf(
            "open quick settings", "quick settings", "quick settings open chey", "క్విక్ సెట్టింగ్స్ ఓపెన్ చెయ్"
        )
        val isScreenshotCommand = cleanedText in listOf(
            "take screenshot", "screenshot", "take a screenshot", "screenshot teeyi", "screenshot chey", "screenshot cheyyi", "స్క్రీన్‌షాట్ తీయి", "స్క్రీన్ షాట్ తీయి"
        )

        when {
            isHomeCommand -> {
                val args = JsonObject().apply { addProperty("system_action_str", "home") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            isBackCommand -> {
                val args = JsonObject().apply { addProperty("system_action_str", "back") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            isRecentsCommand -> {
                val args = JsonObject().apply { addProperty("system_action_str", "recent") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            isNotificationsCommand -> {
                val args = JsonObject().apply { addProperty("system_action_str", "notifications") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            isQuickSettingsCommand -> {
                val args = JsonObject().apply { addProperty("system_action_str", "quick_settings") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            isScreenshotCommand -> {
                return ExecutionEngine.execute(context, ExecutionRequest(action = "TAKE_SCREENSHOT"))
            }
        }

        // 14. Battery Status Patterns
        if (cleanedText.contains("battery") || cleanedText.contains("charge percentage") || cleanedText.contains("battery level")) {
            Log.d(TAG, "Matched Offline Action: GET_BATTERY_STATUS")
            return ExecutionEngine.execute(context, ExecutionRequest(action = "GET_BATTERY_STATUS"))
        }

        // 16. Generic UI Scroll Commands (e.g. "scroll down", "scroll up", "kindha scroll chey", "paina scroll chey")
        val isScrollDown = cleanedText in listOf(
            "scroll down", "scroll down chey", "scroll down cheyyi", "kindha scroll chey", "kindha scroll cheyyi", "scroll down please", "swipe up"
        )
        val isScrollUp = cleanedText in listOf(
            "scroll up", "scroll up chey", "scroll up cheyyi", "paina scroll chey", "paina scroll cheyyi", "scroll up please", "swipe down"
        )

        if (isScrollDown) {
            Log.d(TAG, "Matched Offline Action: SCROLL_DOWN")
            val args = JsonObject().apply { addProperty("direction", "down") }
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SCROLL_DOWN", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Scrolling down...") else res
        }

        if (isScrollUp) {
            Log.d(TAG, "Matched Offline Action: SCROLL_UP")
            val args = JsonObject().apply { addProperty("direction", "up") }
            val res = ExecutionEngine.execute(context, ExecutionRequest(action = "SCROLL_UP", arguments = args))
            return if (res.message.isNullOrEmpty()) res.copy(message = "Scrolling up...") else res
        }

        // 17. Generic UI Click Commands (e.g. "click Wi-Fi", "Wi-Fi meeda click chey", "tap Done", "Done meeda tap chey")
        val clickPattern1 = Regex("^(?:click|tap)\\s+(?:on\\s+)?(.+?)$")
        val clickPattern2 = Regex("^(.+?)\\s+meeda\\s+(?:click|tap)(?:\\s+chey|\\s+cheyyi)?$")
        val clickPattern3 = Regex("^(.+?)\\s+(?:click|tap)\\s+(?:chey|cheyyi)$")

        val matchClick = clickPattern1.find(cleanedText) ?: clickPattern2.find(cleanedText) ?: clickPattern3.find(cleanedText)
        if (matchClick != null) {
            val target = matchClick.groupValues[1].trim()
            if (target.isNotBlank() && target !in listOf("photo", "picture", "screenshot", "video", "selfie", "app", "settings", "whatsapp")) {
                Log.d(TAG, "Matched Offline Action: CLICK_TEXT ($target)")
                val args = JsonObject().apply { addProperty("text", target) }
                val res = ExecutionEngine.execute(context, ExecutionRequest(action = "CLICK_TEXT", arguments = args))
                return if (res.message.isNullOrEmpty()) res.copy(message = "Clicking on $target...") else res
            }
        }

        Log.d(TAG, "No offline command pattern matched for: '$query'")
        return null
    }

    private suspend fun parseAndExecuteWhatsApp(context: Context, rawText: String, cleanedText: String): ToolResult? {
        val lower = cleanedText.trim()

        // 1. Tanglish / Telugu: "whatsapp lo <contact> ki <msg> ani (message) pettu" / "<contact> ki whatsapp lo <msg> ani (message) pettu"
        val tanglishWa1 = Regex("^(?:whatsapp\\s+lo|వాట్సాప్‌లో)\\s+(.+?)\\s+ki\\s+(.+?)\\s*(?:ani|ane|అని|అనే)?\\s*(?:message|msg|మెసేజ్)?\\s*(?:pettu|pettandi|chey|cheyyi|పెట్టు|పంపు)$", RegexOption.IGNORE_CASE)
        val tanglishWa2 = Regex("^(.+?)\\s+ki\\s+(?:whatsapp\\s+lo|వాట్సాప్‌లో|whatsapp\\s+message|వాట్సాప్)\\s+(.+?)\\s*(?:ani|ane|అని|అనే)?\\s*(?:message|msg|మెసేజ్)?\\s*(?:pettu|pettandi|chey|cheyyi|పెట్టు|పంపు)$", RegexOption.IGNORE_CASE)
        val tanglishWa3 = Regex("^(?:whatsapp\\s+lo|వాట్సాప్‌లో)\\s+(.+?)\\s*(?:ki)?\\s+(?:message|msg|మెసేజ్)\\s*(?:pettu|chey):?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val tanglishWa4 = Regex("^(.+?)\\s+ki\\s+(?:message|msg|మెసేజ్)\\s*(?:pettu|chey):?\\s*(.+)$", RegexOption.IGNORE_CASE)

        // 2. English: "send (a) whatsapp message to <contact> saying <msg>" / "send message to <contact> on whatsapp: <msg>"
        val engWa1 = Regex("^(?:send\\s+(?:a\\s+)?(?:whatsapp\\s+)?message\\s+to|send\\s+whatsapp\\s+to|message|text)\\s+(.+?)\\s+(?:saying|that|as|with\\s+text|message|msg|:)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val engWa2 = Regex("^(?:send\\s+(?:a\\s+)?message\\s+to)\\s+(.+?)\\s+on\\s+whatsapp(?:\\s+saying|:)?\\s+(.+)$", RegexOption.IGNORE_CASE)
        val engWa3 = Regex("^whatsapp\\s+(.+?)\\s+(?:saying|msg|message|:)\\s+(.+)$", RegexOption.IGNORE_CASE)

        val match = tanglishWa1.find(lower)
            ?: tanglishWa2.find(lower)
            ?: tanglishWa3.find(lower)
            ?: tanglishWa4.find(lower)
            ?: engWa1.find(lower)
            ?: engWa2.find(lower)
            ?: engWa3.find(lower)

        if (match != null) {
            val contact = match.groupValues[1].replace(Regex("^(?:the|to)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val message = match.groupValues[2].replace(Regex("^(?:saying|that|text|as)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (contact.isNotBlank() && message.isNotBlank() && contact !in listOf("chat", "open", "message", "msg", "app")) {
                Log.d(TAG, "Matched Offline Action: SEND_WHATSAPP ($contact, '$message')")
                val args = JsonObject().apply {
                    addProperty("contact", contact)
                    addProperty("message", message)
                    addProperty("text", message)
                }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SEND_WHATSAPP", arguments = args))
            }
        }
        return null
    }
}
