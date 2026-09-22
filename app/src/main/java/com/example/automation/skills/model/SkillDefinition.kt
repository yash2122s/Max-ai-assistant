package com.example.automation.skills.model

import com.google.gson.annotations.SerializedName

/**
 * Canonical Skill Definition model representing a runtime-importable automation skill.
 * Implements the MAX Cross-Platform Automation Specification v1.0.0.
 */
data class SkillDefinition(
    @SerializedName("skill_id")
    val skillId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("version")
    val version: String = "1.0.0",

    @SerializedName("schema_version")
    val schemaVersion: String = "1.0.0",

    @SerializedName("author")
    val author: String = "user",

    @SerializedName("trust_level")
    val trustLevel: TrustLevel = TrustLevel.LOCAL_USER,

    @SerializedName("checksum")
    val checksum: String = "",

    @SerializedName("signature")
    val signature: String? = null,

    @SerializedName("supported_platforms")
    val supportedPlatforms: List<String> = listOf("android"),

    @SerializedName("requirements")
    val requirements: SkillRequirements = SkillRequirements(),

    @SerializedName("allowed_capabilities")
    val allowedCapabilities: List<String> = emptyList(),

    @SerializedName("intents")
    val intents: SkillIntents = SkillIntents(),

    @SerializedName("inputs")
    val inputs: List<SkillInput> = emptyList(),

    @SerializedName("preconditions")
    val preconditions: List<SkillPrecondition> = emptyList(),

    @SerializedName("steps")
    val steps: List<SkillStep> = emptyList(),

    @SerializedName("failure_policy")
    val failurePolicy: FailurePolicy = FailurePolicy.ABORT
)

enum class TrustLevel {
    @SerializedName("BUILTIN")
    BUILTIN,

    @SerializedName("LOCAL_USER")
    LOCAL_USER,

    @SerializedName("VERIFIED_COMMUNITY")
    VERIFIED_COMMUNITY,

    @SerializedName("UNTRUSTED")
    UNTRUSTED
}

enum class FailurePolicy {
    @SerializedName("ABORT")
    ABORT,

    @SerializedName("CONTINUE")
    CONTINUE,

    @SerializedName("ASK_USER")
    ASK_USER
}

data class SkillRequirements(
    @SerializedName("common")
    val common: PlatformRequirementBlock = PlatformRequirementBlock(),

    @SerializedName("android")
    val android: PlatformRequirementBlock = PlatformRequirementBlock(),

    @SerializedName("windows")
    val windows: PlatformRequirementBlock = PlatformRequirementBlock()
)

data class PlatformRequirementBlock(
    @SerializedName("capabilities")
    val capabilities: List<String> = emptyList(),

    @SerializedName("permissions")
    val permissions: List<String> = emptyList()
)

data class SkillIntents(
    @SerializedName("exact_phrases")
    val exactPhrases: List<String> = emptyList(),

    @SerializedName("trigger_keywords")
    val triggerKeywords: List<String> = emptyList(),

    @SerializedName("category")
    val category: String = "general"
)

data class SkillInput(
    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String, // "string", "number", "boolean"

    @SerializedName("required")
    val required: Boolean = false,

    @SerializedName("default_value")
    val defaultValue: Any? = null,

    @SerializedName("description")
    val description: String = ""
)

data class SkillPrecondition(
    @SerializedName("app_identifier")
    val appIdentifier: String? = null,

    @SerializedName("window_state")
    val windowState: String = "any"
)

data class SkillStep(
    @SerializedName("step_id")
    val stepId: String,

    @SerializedName("action")
    val action: String, // Canonical verb (e.g., "app.open", "ui.click")

    @SerializedName("target")
    val target: String = "",

    @SerializedName("depends_on")
    val dependsOn: List<String> = emptyList(),

    @SerializedName("arguments")
    val arguments: Map<String, Any?> = emptyMap(),

    @SerializedName("platform_overrides")
    val platformOverrides: PlatformOverrides = PlatformOverrides(),

    @SerializedName("timeout_ms")
    val timeoutMs: Long = 10000L,

    @SerializedName("retry_policy")
    val retryPolicy: StepRetryPolicy = StepRetryPolicy(),

    @SerializedName("verification")
    val verification: StepVerification = StepVerification()
)

data class PlatformOverrides(
    @SerializedName("android")
    val android: AndroidStepOverride? = null,

    @SerializedName("windows")
    val windows: WindowsStepOverride? = null
)

data class AndroidStepOverride(
    @SerializedName("package_name")
    val packageName: String? = null,

    @SerializedName("resource_id")
    val resourceId: String? = null,

    @SerializedName("content_description")
    val contentDescription: String? = null,

    @SerializedName("bounds")
    val bounds: List<Int>? = null
)

data class WindowsStepOverride(
    @SerializedName("process_name")
    val processName: String? = null,

    @SerializedName("window_title")
    val windowTitle: String? = null,

    @SerializedName("automation_id")
    val automationId: String? = null,

    @SerializedName("control_type")
    val controlType: String? = null
)

data class StepRetryPolicy(
    @SerializedName("max_retries")
    val maxRetries: Int = 0,

    @SerializedName("delay_ms")
    val delayMs: Long = 500L
)

data class StepVerification(
    @SerializedName("type")
    val type: String = "NONE", // "ELEMENT_VISIBLE", "APP_FOREGROUND", "TEXT_MATCH", "NONE"

    @SerializedName("expected_value")
    val expectedValue: String? = null,

    @SerializedName("timeout_ms")
    val timeoutMs: Long = 3000L
)
