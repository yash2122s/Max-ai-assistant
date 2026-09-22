package com.agentcore.skills.intent

/**
 * Represents an intent detection rule.
 * Rules are evaluated in priority order.
 */
data class IntentRule(
    /**
     * Unique identifier for this rule.
     */
    val id: String,
    
    /**
     * The intent this rule detects.
     */
    val intent: String,
    
    /**
     * Keywords that trigger this intent (any match).
     */
    val keywords: Set<String> = emptySet(),
    
    /**
     * Patterns (regex) that trigger this intent.
     */
    val patterns: List<Regex> = emptyList(),
    
    /**
     * Required keywords (all must match).
     */
    val requiredKeywords: Set<String> = emptySet(),
    
    /**
     * Keywords that should NOT be present.
     */
    val excludeKeywords: Set<String> = emptySet(),
    
    /**
     * Minimum confidence threshold for this rule.
     */
    val minConfidence: Float = 0.5f,
    
    /**
     * Priority for rule evaluation (higher = first).
     */
    val priority: Int = 0,
    
    /**
     * Whether this rule is case-sensitive.
     */
    val caseSensitive: Boolean = false,
    
    /**
     * Custom matcher function for complex logic.
     */
    val customMatcher: ((String) -> Float)? = null,
    
    /**
     * Metadata for this rule.
     */
    val metadata: Map<String, Any> = emptyMap(),
) {
    /**
     * Evaluates this rule against input text.
     * Returns confidence score (0.0 to 1.0) or 0 if no match.
     */
    fun evaluate(input: String): Float {
        val normalizedInput = if (caseSensitive) input else input.lowercase()
        
        // Check exclude keywords first
        if (excludeKeywords.isNotEmpty()) {
            val normalizedExcludes = if (caseSensitive) excludeKeywords else excludeKeywords.map { it.lowercase() }.toSet()
            if (normalizedExcludes.any { normalizedInput.contains(it) }) {
                return 0f
            }
        }
        
        // Check required keywords (all must match)
        if (requiredKeywords.isNotEmpty()) {
            val normalizedRequired = if (caseSensitive) requiredKeywords else requiredKeywords.map { it.lowercase() }.toSet()
            if (!normalizedRequired.all { normalizedInput.contains(it) }) {
                return 0f
            }
        }
        
        var confidence = 0f
        var matchCount = 0
        var totalChecks = 0
        
        // Check keywords (any match)
        if (keywords.isNotEmpty()) {
            val normalizedKeywords = if (caseSensitive) keywords else keywords.map { it.lowercase() }.toSet()
            val keywordMatches = normalizedKeywords.count { normalizedInput.contains(it) }
            if (keywordMatches > 0) {
                confidence += keywordMatches.toFloat() / normalizedKeywords.size
                matchCount++
            }
            totalChecks++
        }
        
        // Check patterns
        if (patterns.isNotEmpty()) {
            val patternMatches = patterns.count { it.containsMatchIn(input) }
            if (patternMatches > 0) {
                confidence += patternMatches.toFloat() / patterns.size
                matchCount++
            }
            totalChecks++
        }
        
        // Check custom matcher
        customMatcher?.let { matcher ->
            val customScore = matcher(input)
            if (customScore > 0) {
                confidence += customScore
                matchCount++
            }
            totalChecks++
        }
        
        // Calculate final confidence
        return if (matchCount > 0 && totalChecks > 0) {
            val finalConfidence = confidence / totalChecks
            if (finalConfidence >= minConfidence) finalConfidence else 0f
        } else {
            0f
        }
    }
}

/**
 * Builder for IntentRule with DSL-style API.
 */
class IntentRuleBuilder(private val intent: String) {
    private var id: String = ""
    private val keywords = mutableSetOf<String>()
    private val patterns = mutableListOf<Regex>()
    private val requiredKeywords = mutableSetOf<String>()
    private val excludeKeywords = mutableSetOf<String>()
    private var minConfidence: Float = 0.5f
    private var priority: Int = 0
    private var caseSensitive: Boolean = false
    private var customMatcher: ((String) -> Float)? = null
    private val metadata = mutableMapOf<String, Any>()
    
    fun id(value: String) = apply { this.id = value }
    fun keywords(vararg values: String) = apply { keywords.addAll(values) }
    fun patterns(vararg values: String) = apply { patterns.addAll(values.map { it.toRegex(RegexOption.IGNORE_CASE) }) }
    fun patternsRegex(vararg values: Regex) = apply { patterns.addAll(values) }
    fun required(vararg values: String) = apply { requiredKeywords.addAll(values) }
    fun exclude(vararg values: String) = apply { excludeKeywords.addAll(values) }
    fun minConfidence(value: Float) = apply { this.minConfidence = value }
    fun priority(value: Int) = apply { this.priority = value }
    fun caseSensitive(value: Boolean = true) = apply { this.caseSensitive = value }
    fun customMatcher(matcher: (String) -> Float) = apply { this.customMatcher = matcher }
    fun metadata(key: String, value: Any) = apply { this.metadata[key] = value }
    
    fun build(): IntentRule = IntentRule(
        id = id.ifBlank { "rule_$intent" },
        intent = intent,
        keywords = keywords.toSet(),
        patterns = patterns.toList(),
        requiredKeywords = requiredKeywords.toSet(),
        excludeKeywords = excludeKeywords.toSet(),
        minConfidence = minConfidence,
        priority = priority,
        caseSensitive = caseSensitive,
        customMatcher = customMatcher,
        metadata = metadata.toMap(),
    )
}

/**
 * DSL function for creating IntentRule.
 */
fun intentRule(intent: String, block: IntentRuleBuilder.() -> Unit): IntentRule =
    IntentRuleBuilder(intent).apply(block).build()

/**
 * Creates a simple keyword-based rule.
 */
fun simpleIntentRule(
    intent: String,
    vararg keywords: String,
    priority: Int = 0,
): IntentRule = IntentRule(
    id = "rule_$intent",
    intent = intent,
    keywords = keywords.toSet(),
    priority = priority,
)
