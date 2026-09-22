package com.agentcore.skills.intent

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.base.validate
import com.agentcore.util.checkCancellation
import com.agentcore.util.mapWithCancellation

/**
 * Input for IntentDetectSkill.
 */
@Immutable
data class IntentDetectInput(
    /**
     * The text to analyze for intent.
     */
    val text: String,
    
    /**
     * Maximum number of intents to return.
     */
    val maxResults: Int = 3,
    
    /**
     * Minimum confidence threshold.
     */
    val minConfidence: Float = 0.3f,
    
    /**
     * Context hints to help with detection.
     */
    val contextHints: Map<String, Any> = emptyMap(),
)

/**
 * A single detected intent with confidence.
 */
@Immutable
data class DetectedIntent(
    /**
     * The detected intent name.
     */
    val intent: String,
    
    /**
     * Confidence score (0.0 to 1.0).
     */
    val confidence: Float,
    
    /**
     * The rule that matched.
     */
    val ruleId: String,
    
    /**
     * Additional metadata from the rule.
     */
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Output from IntentDetectSkill.
 */
@Immutable
data class IntentDetectOutput(
    /**
     * The original input text.
     */
    val originalText: String,
    
    /**
     * List of detected intents, sorted by confidence.
     */
    val intents: List<DetectedIntent>,
    
    /**
     * The primary (highest confidence) intent, if any.
     */
    val primaryIntent: DetectedIntent? = intents.firstOrNull(),
    
    /**
     * Whether any intent was detected.
     */
    val hasIntent: Boolean = intents.isNotEmpty(),
) {
    /**
     * Gets the primary intent name, or null if none detected.
     */
    val intentName: String?
        get() = primaryIntent?.intent
    
    /**
     * Gets the primary intent confidence, or 0 if none detected.
     */
    val confidence: Float
        get() = primaryIntent?.confidence ?: 0f
}

/**
 * Rule-based intent detection skill.
 * 
 * This skill analyzes text input and detects user intents using a
 * configurable set of rules. It's designed as a stub that can be
 * extended with AI-based detection in the future.
 * 
 * Common intents:
 * - greeting: User greetings
 * - farewell: User goodbyes
 * - help: User asking for help
 * - search: User wants to search
 * - navigate: User wants to go somewhere
 * - confirm: User confirms an action
 * - cancel: User cancels an action
 * - unknown: No intent detected
 */
class IntentDetectSkill(
    private val rules: List<IntentRule> = defaultRules,
    private val fallbackIntent: String = INTENT_UNKNOWN,
) : BaseSkill<IntentDetectInput, IntentDetectOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.intent.detect"
        name = "Intent Detect"
        description = "Detects user intent from text input using rule-based matching"
        category = SkillCategory.INTENT
        tags("intent", "nlp", "core")
        inputType = IntentDetectInput::class
        outputType = IntentDetectOutput::class
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun validate(input: IntentDetectInput): SkillResult<Unit> = validate {
        requireNotBlank(input.text, "text", "Input text cannot be blank")
        require(input.maxResults >= 1, "maxResults") { "maxResults must be at least 1" }
        requireInRange(input.minConfidence, 0f..1f, "minConfidence", 
            "minConfidence must be between 0.0 and 1.0")
    }
    
    override suspend fun doExecute(
        input: IntentDetectInput,
        context: SkillContext,
    ): SkillResult<IntentDetectOutput> {
        // Sort rules by priority (higher first)
        val sortedRules = rules.sortedByDescending { it.priority }
        
        // Evaluate all rules
        val detectedIntents = mutableListOf<DetectedIntent>()
        
        for (rule in sortedRules) {
            val confidence = rule.evaluate(input.text)
            
            if (confidence >= input.minConfidence) {
                detectedIntents.add(
                    DetectedIntent(
                        intent = rule.intent,
                        confidence = confidence,
                        ruleId = rule.id,
                        metadata = rule.metadata,
                    )
                )
            }
        }
        
        // Sort by confidence (highest first) and limit results
        val topIntents = detectedIntents
            .sortedByDescending { it.confidence }
            .take(input.maxResults)
        
        return SkillResult.success(
            IntentDetectOutput(
                originalText = input.text,
                intents = topIntents,
            )
        )
    }
    
    companion object {
        const val INTENT_GREETING = "greeting"
        const val INTENT_FAREWELL = "farewell"
        const val INTENT_HELP = "help"
        const val INTENT_SEARCH = "search"
        const val INTENT_NAVIGATE = "navigate"
        const val INTENT_CONFIRM = "confirm"
        const val INTENT_CANCEL = "cancel"
        const val INTENT_UNKNOWN = "unknown"
        
        /**
         * Default intent rules covering common intents.
         */
        val defaultRules = listOf(
            intentRule(INTENT_GREETING) {
                id("rule_greeting")
                keywords("hello", "hi", "hey", "good morning", "good afternoon", "good evening", "howdy", "greetings")
                patterns("^(hi|hello|hey)\\b", "^good\\s+(morning|afternoon|evening)")
                priority(10)
            },
            
            intentRule(INTENT_FAREWELL) {
                id("rule_farewell")
                keywords("bye", "goodbye", "see you", "later", "farewell", "take care", "good night")
                patterns("\\b(bye|goodbye|farewell)\\b", "see\\s+you\\s+(later|soon)")
                priority(10)
            },
            
            intentRule(INTENT_HELP) {
                id("rule_help")
                keywords("help", "assist", "support", "how do i", "how to", "what is", "explain", "guide", "stuck")
                patterns("\\b(help|assist|support)\\b", "how\\s+(do|can|to)\\s+", "what\\s+(is|are)\\s+")
                priority(8)
            },
            
            intentRule(INTENT_SEARCH) {
                id("rule_search")
                keywords("search", "find", "look for", "looking for", "where is", "locate", "discover")
                patterns("\\b(search|find|locate)\\b", "(look|looking)\\s+for", "where\\s+(is|are)")
                priority(7)
            },
            
            intentRule(INTENT_NAVIGATE) {
                id("rule_navigate")
                keywords("go to", "open", "show", "take me to", "navigate", "visit", "launch")
                patterns("(go|take\\s+me)\\s+to\\b", "\\b(open|show|launch|visit)\\b")
                priority(6)
            },
            
            intentRule(INTENT_CONFIRM) {
                id("rule_confirm")
                keywords("yes", "ok", "okay", "sure", "confirm", "agree", "correct", "right", "yep", "yeah", "absolutely")
                patterns("^(yes|ok|okay|sure|yep|yeah)$", "\\b(confirm|agree)\\b")
                priority(9)
            },
            
            intentRule(INTENT_CANCEL) {
                id("rule_cancel")
                keywords("no", "cancel", "stop", "abort", "nevermind", "forget it", "nope", "nah", "back")
                patterns("^(no|nope|nah)$", "\\b(cancel|stop|abort)\\b", "never\\s*mind")
                priority(9)
            },
        )
        
        /**
         * Creates a skill with custom rules.
         */
        fun withRules(
            rules: List<IntentRule>,
            fallbackIntent: String = INTENT_UNKNOWN,
        ): IntentDetectSkill = IntentDetectSkill(rules, fallbackIntent)
        
        /**
         * Creates a skill by adding rules to the default set.
         */
        fun withAdditionalRules(
            additionalRules: List<IntentRule>,
        ): IntentDetectSkill = IntentDetectSkill(defaultRules + additionalRules)
    }
}

/**
 * Helper to create IntentDetectInput.
 */
fun intentDetectInput(
    text: String,
    maxResults: Int = 3,
    minConfidence: Float = 0.3f,
): IntentDetectInput = IntentDetectInput(
    text = text,
    maxResults = maxResults,
    minConfidence = minConfidence,
)
