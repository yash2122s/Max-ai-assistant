package com.example.automation.engine

import com.example.automation.registry.PlannerToolDiscovery
import com.example.automation.registry.ToolRiskLevel

object ActionAuthorizer {

    data class AuthorizationResult(
        val authorized: Boolean,
        val reason: String
    )

    // ------------------------------------------------------------------
    // P1.2 — MAX-native risk-aware approval policy.
    //
    // Extends the EXISTING authorizer; it does NOT create a second security
    // framework and it NEVER weakens existing authorization. Every action
    // still passes through [authorizeAction] first — this policy only decides
    // whether an ALREADY-authorized action may run automatically, needs a
    // user confirmation, or is hard-blocked for autonomous execution.
    // ------------------------------------------------------------------

    enum class ApprovalMode {
        MANUAL,      // every SAFE/CAUTION action requires confirmation
        SAFE_AUTO,   // SAFE actions auto-run; CAUTION requires confirmation
        FULL_AUTO    // SAFE auto; CAUTION auto ONLY when policy allows
    }

    enum class ApprovalDecision {
        APPROVED,          // may execute now
        NEEDS_CONFIRMATION, // surface Allow/Cancel to the user first
        BLOCKED             // must not execute autonomously (hard security)
    }

    data class ApprovalEvaluation(
        val decision: ApprovalDecision,
        val reason: String
    )

    /** CRITICAL action keywords that must NEVER be auto-approved in any mode. */
    private val NEVER_AUTO_KEYWORDS = listOf(
        "UPI", "PIN", "PASSWORD", "PAYMENT", "PAY", "OTP", "CREDENTIAL",
        "FACTORY_RESET", "WIPE", "FORMAT", "RECOVERY", "UNLOCK"
    )

    /** External side effects are never silently auto-approved. */
    private val HIGH_IMPACT_ACTIONS = setOf(
        "CALL_PHONE", "CALL_CONTACT", "SEND_WHATSAPP", "SEND_WHATSAPP_MESSAGE", "SEND_SMS",
        "DELETE_MEMORY", "DELETE_CONTACT", "DELETE_FILE", "PURCHASE", "ACCOUNT_CHANGE",
        "TRANSFER_FUNDS", "SEND_PAYMENT", "WHATSAPP_CALL"
    )

    /**
     * P0-2 — Security-sensitive actions that require an explicit user
     * confirmation in EVERY approval mode (MANUAL, SAFE_AUTO, FULL_AUTO),
     * even when a direct-sounding command is present. Device unlock is the
     * canonical example: it must never silently auto-execute from any path
     * (voice model call, offline phrase, screen/memory/prompt injection).
     */
    fun requiresEveryModeConfirmation(actionType: String): Boolean {
        return actionType.uppercase().contains("UNLOCK")
    }

    /** Actions explicitly allowlisted by the user may auto-run under SAFE_AUTO / FULL_AUTO. */
    @Volatile
    private var cautionAllowlist: Set<String> = emptySet()

    /** Configures the allowlist (comma separated). Empty by default. */
    fun setCautionAllowlist(actions: Set<String>) {
        cautionAllowlist = actions.map { it.uppercase().trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun getCautionAllowlist(): Set<String> = cautionAllowlist

    /** Informational risk metadata — enforcement lives in [evaluateApproval] + [authorizeAction]. */
    fun classifyRisk(actionType: String): ToolRiskLevel = PlannerToolDiscovery.classifyRisk(actionType)

    /** True when the action touches a security domain that can never run autonomously. */
    fun isNeverAutoAction(actionType: String): Boolean {
        val key = actionType.uppercase()
        return NEVER_AUTO_KEYWORDS.any { key.contains(it) }
    }

    /**
     * Evaluates whether an already-authorized action may auto-execute.
     *
     * Security invariants (never violated):
     *  - CRITICAL / never-auto actions are BLOCKED for autonomous execution in
     *    every mode. Only an explicit, on-the-record user command (the caller
     *    passes the live user intent that [authorizeAction] accepted) keeps
     *    existing interactive behavior working — an autonomous agent with no
     *    explicit user intent can never trigger them.
     *  - Existing authorization is NOT weakened: callers must still honor
     *    [authorizeAction]; this function assumes it already passed.
     */
    /** Words that mark an utterance as a direct command (not chatter). */
    private val DIRECT_COMMAND_VERBS = listOf(
        "run", "send", "pay", "enter", "type", "execute", "open", "create",
        "start", "launch", "do ", "command", "shell", "adb", "set", "call",
        "message", "sms", "whatsapp", "వాట్సాప్", "unlock", "submit", "transfer", "enable",
        "disable", "turn", "play", "search", "delete", "click", "scroll", "install",
        "pampu", "pettu", "rayi", "chey", "cheyyi", "vellu", "ఓపెన్",
        "wait", "sleep", "delay", "aagu", "aagali", "ఆగు", "వెయిట్"
    )

    private fun looksLikeDirectCommand(intent: String?): Boolean {
        if (intent.isNullOrBlank()) return false
        val lower = intent.lowercase()
        return DIRECT_COMMAND_VERBS.any { lower.contains(it) }
    }

    fun evaluateApproval(
        mode: ApprovalMode,
        actionType: String,
        userIntent: String?,
        target: String? = null,
        allowCautionAutoInFullAuto: Boolean = false
    ): ApprovalEvaluation {
        val risk = classifyRisk(actionType)
        val highImpact = actionType.uppercase() in HIGH_IMPACT_ACTIONS
        val isNeverAuto = isNeverAutoAction(actionType)
        val explicitUserCommand = !userIntent.isNullOrBlank() &&
            looksLikeDirectCommand(userIntent) &&
            authorizeAction(userIntent, actionType, target).authorized

        // Hard security domains (payments, credentials, destructive wipes) are
        // ALWAYS blocked for autonomous execution in every mode, even with an
        // explicit-looking command — they require a dedicated consent flow.
        if (isNeverAuto) {
            // P0-2 — UNLOCK must never hard-block a legitimate explicit request;
            // instead it ALWAYS requires a conversational confirmation
            // (ApprovalCoordinator) in every mode.
            if (requiresEveryModeConfirmation(actionType)) {
                return ApprovalEvaluation(
                    ApprovalDecision.NEEDS_CONFIRMATION,
                    "Action '$actionType' requires explicit user confirmation in every approval mode."
                )
            }
            return ApprovalEvaluation(
                ApprovalDecision.BLOCKED,
                "Action '$actionType' is in a hard security domain and can never be auto-approved."
            )
        }

        if (highImpact) {
            return ApprovalEvaluation(
                ApprovalDecision.NEEDS_CONFIRMATION,
                "High-impact action '$actionType' requires task-scoped explicit confirmation."
            )
        }

        if (risk == ToolRiskLevel.CRITICAL) {
            return if (explicitUserCommand && mode != ApprovalMode.MANUAL) {
                ApprovalEvaluation(ApprovalDecision.APPROVED, "Explicit direct user command present for '$actionType'.")
            } else {
                ApprovalEvaluation(
                    ApprovalDecision.BLOCKED,
                    "Action '$actionType' is CRITICAL and cannot be auto-approved. It requires an explicit direct user command."
                )
            }
        }

        val isAllowlisted = actionType.uppercase() in cautionAllowlist

        return when (mode) {
            ApprovalMode.MANUAL ->
                ApprovalEvaluation(ApprovalDecision.NEEDS_CONFIRMATION, "Mode MANUAL requires confirmation for '$actionType'.")

            ApprovalMode.SAFE_AUTO -> when (risk) {
                ToolRiskLevel.SAFE -> ApprovalEvaluation(ApprovalDecision.APPROVED, "SAFE action auto-approved under SAFE_AUTO.")
                ToolRiskLevel.CAUTION -> if (isAllowlisted) {
                    ApprovalEvaluation(ApprovalDecision.APPROVED, "Allowlisted CAUTION action auto-approved under SAFE_AUTO.")
                } else {
                    ApprovalEvaluation(ApprovalDecision.NEEDS_CONFIRMATION, "CAUTION action '$actionType' requires confirmation under SAFE_AUTO.")
                }
                ToolRiskLevel.CRITICAL -> ApprovalEvaluation(ApprovalDecision.BLOCKED, "CRITICAL action cannot be auto-approved.")
            }

            ApprovalMode.FULL_AUTO -> when (risk) {
                ToolRiskLevel.SAFE -> ApprovalEvaluation(ApprovalDecision.APPROVED, "SAFE action auto-approved under FULL_AUTO.")
                ToolRiskLevel.CAUTION -> if (isAllowlisted || allowCautionAutoInFullAuto) {
                    ApprovalEvaluation(ApprovalDecision.APPROVED, "CAUTION action auto-approved under FULL_AUTO (policy allows).")
                } else {
                    ApprovalEvaluation(ApprovalDecision.NEEDS_CONFIRMATION, "CAUTION action '$actionType' requires confirmation under FULL_AUTO (policy does not allow silent auto-run).")
                }
                ToolRiskLevel.CRITICAL -> ApprovalEvaluation(ApprovalDecision.BLOCKED, "CRITICAL action cannot be auto-approved.")
            }
        }
    }

    fun authorizeAction(userIntent: String?, actionType: String, target: String? = null): AuthorizationResult {
        if (userIntent.isNullOrBlank()) {
            return AuthorizationResult(false, "No user intent provided to authorize action '$actionType'")
        }

        val intent = userIntent.trim().lowercase()
        val action = actionType.uppercase()
        val targetLower = (target ?: "").trim().lowercase()

        // 1. Memory and cognitive actions are internal assistant state operations, NOT invasive device UI automations.
        val isMemoryAction = action in setOf(
            "SAVE_MEMORY", "SEARCH_MEMORY", "RECALL_MEMORY", 
            "GET_USER_PREFERENCE", "STORE_PREFERENCE", "FORGET_MEMORY", "DELETE_MEMORY"
        )

        // Pure conversational intents should NEVER execute unsolicited device actions (screen, calls, whatsapp, apps, clicks)
        if (!isMemoryAction && isPureConversation(intent)) {
            return AuthorizationResult(false, "Action '$action' is not required for conversational request '$userIntent'.")
        }

        // 2. Action-specific intent validation
        return when (action) {
            "GET_SCREEN_CONTEXT", "TAKE_SCREENSHOT" -> {
                val screenKeywords = listOf(
                    "screen", "display", "what's on", "what is on", "read this", "read screen",
                    "screenshot", "looking at", "which app", "what app", "visible", "స్క్రీన్", "డిస్ప్లే", "చూడు",
                    "inspect", "identify", "search", "profile", "verify", "open", "cheyyi", "ui", "results"
                )
                val isScreenRequested = screenKeywords.any { intent.contains(it) }
                if (isScreenRequested) {
                    AuthorizationResult(true, "Screen inspection explicitly requested by user.")
                } else {
                    AuthorizationResult(false, "Screen inspection not requested in '$userIntent'.")
                }
            }

            "YOUTUBE_SEARCH" -> {
                val ytKeywords = listOf("youtube", "play", "song", "music", "video", "recipe", "పాట", "సాంగ్", "ప్లే", "యూట్యూబ్")
                val isYtRequested = ytKeywords.any { intent.contains(it) }
                if (isYtRequested) {
                    AuthorizationResult(true, "YouTube/media action requested by user.")
                } else {
                    AuthorizationResult(false, "YouTube action not requested in '$userIntent'.")
                }
            }

            "SYSTEM_ACTION" -> {
                when (targetLower) {
                    "notifications", "notification" -> {
                        val notifKeywords = listOf("notification", "notifications", "నోటిఫికేషన్")
                        if (notifKeywords.any { intent.contains(it) }) {
                            AuthorizationResult(true, "Notification shade requested by user.")
                        } else {
                            AuthorizationResult(false, "Notification panel not requested in '$userIntent'.")
                        }
                    }
                    "quick_settings" -> {
                        val qsKeywords = listOf("quick settings", "quick setting", "qs", "క్విక్ సెట్టింగ్స్")
                        if (qsKeywords.any { intent.contains(it) }) {
                            AuthorizationResult(true, "Quick settings requested by user.")
                        } else {
                            AuthorizationResult(false, "Quick settings not requested in '$userIntent'.")
                        }
                    }
                    "home" -> {
                        val homeKeywords = listOf("home", "homescreen", "హోమ్", "హోం", "హోమ్ స్క్రీన్")
                        if (homeKeywords.any { intent.contains(it) || userIntent.contains(it) }) {
                            AuthorizationResult(true, "Home navigation requested by user.")
                        } else {
                            AuthorizationResult(false, "Home navigation not requested in '$userIntent'.")
                        }
                    }
                    "back" -> {
                        val backKeywords = listOf("back", "go back", "venakki", "venaka", "వెనక్కి", "గో బ్యాక్", "వెనుకకు")
                        if (backKeywords.any { intent.contains(it) }) {
                            AuthorizationResult(true, "Back navigation requested by user.")
                        } else {
                            AuthorizationResult(false, "Back navigation not requested in '$userIntent'.")
                        }
                    }
                    "recent", "recents" -> {
                        val recentKeywords = listOf("recent", "recents", "రీసెంట్")
                        if (recentKeywords.any { intent.contains(it) }) {
                            AuthorizationResult(true, "Recent apps requested by user.")
                        } else {
                            AuthorizationResult(false, "Recent apps not requested in '$userIntent'.")
                        }
                    }
                    "screenshot" -> {
                        val ssKeywords = listOf("screenshot", "take screenshot", "capture screen picture", "స్క్రీన్‌షాట్", "स्क्रीनशॉट", "स्क्रीन शॉट", "screen shot")
                        if (ssKeywords.any { intent.contains(it) }) {
                            AuthorizationResult(true, "Screenshot requested by user.")
                        } else {
                            AuthorizationResult(false, "Screenshot not requested in '$userIntent'.")
                        }
                    }
                    else -> AuthorizationResult(true, "System action authorized.")
                }
            }

            "OPEN_APP" -> {
                val openKeywords = listOf("open", "launch", "start", "go to", "ఓపెన్", "స్టార్ట్", "వెళ్లు", "తెరువు")
                val containsOpenVerb = openKeywords.any { intent.contains(it) }

                // Canonical entity resolution through InstalledAppsRepository
                val plannedPackage = com.example.knowledge.apps.InstalledAppsRepository.findApp(null, targetLower) ?: targetLower
                val requestedPackages = com.example.knowledge.apps.InstalledAppsRepository.extractAllMatchedApps(null, intent)

                if (requestedPackages.isNotEmpty() && plannedPackage.isNotBlank()) {
                    if (requestedPackages.any { plannedPackage.equals(it, ignoreCase = true) }) {
                        return AuthorizationResult(true, "Canonical app entity '$plannedPackage' authorized by user intent.")
                    } else if (intent.contains(targetLower) || (plannedPackage.contains("instagram") && (intent.contains("instagram") || intent.contains("insta")))) {
                        return AuthorizationResult(true, "App entity '$target' explicitly mentioned in user intent.")
                    } else {
                        return AuthorizationResult(false, "Opening '$target' not requested in '$userIntent' (conflicts with requested '${requestedPackages.joinToString()}').")
                    }
                }

                if (intent.contains(targetLower) || (containsOpenVerb && !isPureSystemTarget(intent))) {
                    AuthorizationResult(true, "App open requested by user.")
                } else {
                    AuthorizationResult(false, "Opening '$target' not requested in '$userIntent'.")
                }
            }

            "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "FIND_CONTACT" -> {
                val actionKeywords = listOf("click", "tap", "press", "select", "send", "message", "type", "search", "find", "నొక్కు", "ట్యాప్", "క్లిక్", "కొట్టు", "పంపు", "వెతుకు")
                val isRequested = actionKeywords.any { intent.contains(it) } || (targetLower.isNotBlank() && intent.contains(targetLower))
                if (isRequested) {
                    AuthorizationResult(true, "UI interaction requested by user.")
                } else {
                    AuthorizationResult(false, "UI click not requested in '$userIntent'.")
                }
            }

            "PRESS_ENTER", "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS" -> {
                val enterKeywords = listOf("enter", "submit", "done", "search", "కొట్టు", "నొక్కు", "ఎంటర్", "key", "press")
                if (enterKeywords.any { intent.contains(it) } || looksLikeDirectCommand(userIntent)) {
                    AuthorizationResult(true, "Keyboard action requested by user.")
                } else {
                    AuthorizationResult(false, "Keyboard action not requested in '$userIntent'.")
                }
            }

            "SCROLL_SCREEN", "SCROLL_DOWN", "SCROLL_UP" -> {
                val scrollKeywords = listOf("scroll", "swipe", "kindha", "paina", "కింద", "పైన", "స్క్రోల్")
                if (scrollKeywords.any { intent.contains(it) }) {
                    AuthorizationResult(true, "Screen scroll requested by user.")
                } else {
                    AuthorizationResult(false, "Scroll not requested in '$userIntent'.")
                }
            }

            "TYPE_TEXT" -> {
                val typeKeywords = listOf("type", "write", "enter", "send", "message", "say", "search", "find", "రాయి", "రాయండి", "టైప్", "పంపు", "వెతుకు", "శోధించు")
                if (typeKeywords.any { intent.contains(it) }) {
                    AuthorizationResult(true, "Text entry requested by user.")
                } else {
                    AuthorizationResult(false, "Text typing not requested in '$userIntent'.")
                }
            }

            "SEND_WHATSAPP_MESSAGE", "SEND_WHATSAPP" -> {
                val isExplicitOpenOnly = intent in listOf("open whatsapp", "open whatsapp.", "open whatsapp app", "launch whatsapp", "whatsapp open chey", "వాట్సాప్ ఓపెన్ చెయ్")
                if (isExplicitOpenOnly) {
                    return AuthorizationResult(false, "App opening requested, not messaging.")
                }

                val msgKeywords = listOf(
                    "send", "message", "msg", "text", "pettu", "pettandi", "pampu", "pampinchu", "rayi", "rayandi",
                    "హలో", "మెసేజ్", "మెసేజ్ పెట్టు", "చెప్పు", "టైప్", "type", "say", "hello"
                )
                val isWaMessagingConstruct = (intent.contains("whatsapp lo") || intent.contains("వాట్సాప్‌లో") || intent.contains("వాట్సాప్ లో")) &&
                    (intent.contains(" ki") || intent.contains("ki ") || intent.contains("ani") || intent.contains("message") || intent.contains("pampu") || intent.contains("pettu"))

                if (msgKeywords.any { intent.contains(it) } || isWaMessagingConstruct) {
                    AuthorizationResult(true, "WhatsApp messaging requested by user.")
                } else {
                    AuthorizationResult(false, "Messaging not requested in '$userIntent'.")
                }
            }

            "OPEN_WHATSAPP_CHAT" -> {
                val isExplicitOpenOnly = intent in listOf("open whatsapp", "open whatsapp.", "open whatsapp app", "launch whatsapp", "whatsapp open chey", "వాట్సాప్ ఓపెన్ చెయ్")
                if (isExplicitOpenOnly) {
                    return AuthorizationResult(false, "App opening requested, not opening specific chat.")
                }

                val chatKeywords = listOf("chat", "conversation", "చాట్")
                val isWaChatConstruct = (intent.contains("whatsapp") || intent.contains("వాట్సాప్")) &&
                    (intent.contains("chat") || intent.contains("conversation") || intent.contains("చాట్"))

                if (chatKeywords.any { intent.contains(it) } || isWaChatConstruct || (targetLower.isNotBlank() && intent.contains(targetLower))) {
                    AuthorizationResult(true, "WhatsApp chat open requested by user.")
                } else {
                    AuthorizationResult(false, "WhatsApp chat open not requested in '$userIntent'.")
                }
            }

            "NAVIGATE_WHATSAPP", "OPEN_WHATSAPP_SCREEN", "WHATSAPP_NAVIGATE" -> {
                val isExplicitOpenOnly = intent in listOf("open whatsapp", "open whatsapp.", "open whatsapp app", "launch whatsapp", "whatsapp open chey", "వాట్సాప్ ఓపెన్ చెయ్")
                if (isExplicitOpenOnly) {
                    return AuthorizationResult(false, "Main app open requested, not specific screen navigation.")
                }

                val navKeywords = listOf("status", "updates", "calls", "chats", "communities", "settings", "starred", "linked", "స్టేటస్", "కాల్స్", "సెట్టింగ్స్", "చాట్స్", "అప్‌డేట్స్")
                if (navKeywords.any { intent.contains(it) }) {
                    AuthorizationResult(true, "WhatsApp navigation requested by user.")
                } else {
                    AuthorizationResult(false, "WhatsApp navigation not requested in '$userIntent'.")
                }
            }

            "SAVE_MEMORY" -> {
                val normalizedIntent = intent.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

                val isQuery = normalizedIntent.startsWith("what is") ||
                              normalizedIntent.startsWith("what s") ||
                              normalizedIntent.startsWith("who is") ||
                              (normalizedIntent.startsWith("tell me") && (normalizedIntent.contains("what") || normalizedIntent.contains("who"))) ||
                              intent.endsWith("?") ||
                              normalizedIntent.endsWith("enti") ||
                              normalizedIntent.endsWith("telusa") ||
                              (normalizedIntent.endsWith("cheppu") && !normalizedIntent.contains("save") && !normalizedIntent.contains("gurthu") && !normalizedIntent.contains("gurtu"))

                if (isQuery) {
                    return AuthorizationResult(false, "Utterance is a query, not a memory storage command: '$userIntent'.")
                }

                val isUnrelatedSave = normalizedIntent.startsWith("save the ") || 
                                     normalizedIntent.contains("save the file") || 
                                     normalizedIntent.contains("save the screenshot") ||
                                     normalizedIntent.contains("save this document") ||
                                     normalizedIntent.contains("save the document")

                if (isUnrelatedSave) {
                    return AuthorizationResult(false, "Unrelated file/document save intent: '$userIntent'.")
                }

                val explicitMemoryPhrases = listOf(
                    // English
                    "remember",
                    "remember this",
                    "remember that",
                    "remind",
                    "note this",
                    "note down",
                    "keep in mind",
                    "save memory",
                    "save this",
                    "save that",
                    "save it",
                    "store in memory",
                    "save my",
                    "save info",

                    // Personal identity / facts statements
                    "my name is",
                    "naa peru",
                    "na peru",
                    "mera naam",
                    "call me",
                    "i am ",
                    "nenu ",

                    // Romanized Telugu / Tanglish
                    "save chey",
                    "save cheyi",
                    "save chesko",
                    "save chesuko",
                    "save cheyyi",
                    "save cheyandi",
                    "gurthupettuko",
                    "gurtupettuko",
                    "gurthunchuko",
                    "gurtunchuko",
                    "gurthupettukonna",
                    "gurtupettukonna",
                    "gurthupettukoni",
                    "gurtupettukoni",
                    "gurthupettukovali",
                    "gurtupettukovali",
                    "gurthupettu",
                    "gurtupettu",
                    "gurthundi",
                    "gurtundi",
                    "gurthu",
                    "gurtu",

                    // Telugu Script
                    "గుర్తు",
                    "గుర్తుపెట్టుకో",
                    "గుర్తుంచుకో",
                    "నోట్ చేసుకో",
                    "సేవ్ చేయి",
                    "సేవ్ చేసుకో",
                    "మెమరీలో సేవ్ చేయి",
                    "దీన్ని గుర్తుపెట్టుకో",
                    "దీన్ని సేవ్ చేయి",
                    "నా పేరు"
                )

                val isMemoryContext = normalizedIntent.contains("memory") && (
                    normalizedIntent.contains("save") ||
                    normalizedIntent.contains("chesko") ||
                    normalizedIntent.contains("chesuko") ||
                    normalizedIntent.contains("chey") ||
                    normalizedIntent.contains("pettu") ||
                    normalizedIntent.contains("pettuko") ||
                    normalizedIntent.contains("unchu") ||
                    normalizedIntent.contains("unchuko") ||
                    normalizedIntent.contains("store") ||
                    normalizedIntent.contains("add") ||
                    normalizedIntent.contains("note")
                )

                val isTeluguRemember = normalizedIntent.contains("gurthu") || 
                                       normalizedIntent.contains("gurtu") || 
                                       intent.contains("గుర్తు")

                val isExplicitMemory = isMemoryContext ||
                                       isTeluguRemember ||
                                       explicitMemoryPhrases.any { phrase ->
                                           intent.contains(phrase) || normalizedIntent.contains(phrase)
                                       }

                if (isExplicitMemory) {
                    AuthorizationResult(true, "Memory creation explicitly requested by user.")
                } else {
                    AuthorizationResult(false, "Memory creation not requested in '$userIntent'.")
                }
            }

            "FORGET_MEMORY", "DELETE_MEMORY" -> {
                val forgetKeywords = listOf(
                    "forget", "delete memory", "remove memory", "erase", "clear memory",
                    "మర్చిపో", "డిలీట్ చేయి", "రిమూవ్ చేయి"
                )
                if (forgetKeywords.any { intent.contains(it) }) {
                    AuthorizationResult(true, "Memory deletion explicitly requested by user.")
                } else {
                    AuthorizationResult(false, "Memory deletion not requested in '$userIntent'.")
                }
            }

            "WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP", "WAIT_FOR_UI", "WAIT_FOR_ELEMENT" -> {
                AuthorizationResult(true, "Wait/delay step authorized as part of execution workflow.")
            }

            "TASK_INTENT" -> {
                AuthorizationResult(true, "Structured task intent authorized.")
            }

            "CALL_PHONE" -> {
                if (com.example.automation.engine.ActionDispatcher.isWhatsAppCallUtterance(intent)) {
                    return AuthorizationResult(false, "CALL_PHONE rejected: user intent is a WhatsApp call, not a cellular phone call.")
                }
                val isExplicitVoiceCall = com.example.automation.engine.ActionDispatcher.isExplicitCallUtterance(intent)
                val isMessagingUtterance = com.example.automation.engine.ActionDispatcher.isMessagingUtterance(intent)

                if (isMessagingUtterance && !isExplicitVoiceCall) {
                    AuthorizationResult(false, "CALL_PHONE rejected: user intent is a messaging command, not a phone call.")
                } else if (isExplicitVoiceCall || intent.contains("call") || intent.contains("dial")) {
                    AuthorizationResult(true, "Phone call requested by user.")
                } else {
                    AuthorizationResult(false, "Phone call not requested in '$userIntent'.")
                }
            }

            else -> {
                AuthorizationResult(true, "Standard action authorized.")
            }
        }
    }

    private fun isPureSystemTarget(intent: String): Boolean {
        val systemTargets = listOf("notification", "notifications", "quick settings", "home", "recent", "screenshot")
        return systemTargets.any { intent.contains(it) }
    }

    fun isPureConversation(intent: String): Boolean {
        val lower = intent.trim().lowercase()

        // Visual or screen queries are actionable screen analysis, NOT pure conversation
        val screenKeywords = listOf("screen", "display", "what's on", "what is on", "read this", "screenshot", "looking at", "which app", "visible", "స్క్రీన్", "డిస్ప్లే", "చూడు")
        if (screenKeywords.any { lower.contains(it) }) {
            return false
        }

        val hasActionVerb = listOf(
            "open", "search", "play", "send", "click", "tap", "type", "enter", "clear", "press", "scroll", "swipe",
            "turn on", "turn off", "turn", "set", "call", "whatsapp", "వాట్సాప్", "message", "msg", "pampu",
            "pampinchu", "pettu", "rayi", "chey", "cheyyi", "vellu", "kottu", "తెరువు", "పంపు", "పెట్టు", "రాయి",
            "ఓపెన్", "గొట్టు", "కొట్టు", "టైప్", "ఎంటర్", "flashlight", "torch", "wifi", "bluetooth",
            "dnd", "alarm", "timer", "reminder", "brightness", "volume", "routine", "unlock", "lock",
            "wait", "sleep", "delay", "aagu", "ఆగు",
            "contact", "sms", "notification", "quick settings", "home", "recent", "back"
        ).any { lower.contains(it) }
        if (hasActionVerb) return false

        // Assistant comparison, personality, feelings, creator inquiries
        val personalityKeywords = listOf(
            "siri", "alexa", "google assistant", "yaswanth", "better", "vs", "compare",
            "feeling", "how are you", "who are you", "what are you", "tell me about yourself",
            "cheppu", "cheppava", "bavunnava", "bagunnava", "nuvvu", "neeku", "naaku",
            "prema", "ishta", "kopam", "smart", "intelligent", "cute", "love"
        )
        if (personalityKeywords.any { lower.contains(it) }) {
            return true
        }

        val conversationalPhrases = listOf(
            "tell me", "tell a", "tell", "say", "write", "sing", "explain", "recommend", "suggest",
            "give me", "help me", "what is", "who is", "where is", "why is", "why", "how to", "how do",
            "how is", "how does", "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "what's the weather", "what is the weather", "weather today", "forecast",
            "can you", "do you", "are you", "should i", "which is", "joke", "story", "poem", "song",
            "quote", "advice", "tips", "meaning", "definition", "translate", "summarize",
            "చెప్పు", "ఎలా", "ఎవరు", "ఏంటి", "బాగున్నావా", "హలో", "నమస్తే", "ధన్యవాదాలు",
            "batao", "kaise", "kaun", "kya", "namaste", "thanks", "thank you", "ok", "okay"
        )
        if (conversationalPhrases.any { lower.contains(it) }) {
            return true
        }

        // Generic interrogative sentences or non-action statements
        if (lower.endsWith("?") || lower.contains(" better aa") || lower.contains(" aa?") || lower.contains(" kante ") || lower.contains(" kanna ")) {
            return true
        }

        // Default to pure conversation when no explicit device action or screen keywords are present
        return true
    }
}
