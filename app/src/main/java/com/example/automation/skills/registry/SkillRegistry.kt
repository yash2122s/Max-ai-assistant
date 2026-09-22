package com.example.automation.skills.registry

import android.content.Context
import android.util.Log
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.parser.SkillParser
import com.example.automation.skills.storage.SkillStorage
import com.example.automation.skills.validation.SkillValidator

/**
 * Result returned upon importing/registering a skill.
 */
data class SkillImportResult(
    val success: Boolean,
    val skill: SkillDefinition? = null,
    val isEphemeral: Boolean = false,
    val documentationMarkdown: String = "",
    val errorMessage: String? = null
)

/**
 * Dedicated Skill Registry for MAX Automation Skills.
 * Manages both Installed (persistent filesystem) and Ephemeral (in-memory prompt-attached) skills.
 * Respects the frozen ToolRegistry barrier by serving as an independent data repository.
 */
class SkillRegistry(private val storage: SkillStorage) {

    constructor(context: Context) : this(SkillStorage(context))

    private val TAG = "SkillRegistry"
    private val installedSkills = mutableMapOf<String, SkillDefinition>()
    private val disabledSkillIds = mutableSetOf<String>()
    private val ephemeralSkills = mutableMapOf<String, SkillDefinition>()

    init {
        refreshFromStorage()
    }

    /**
     * Reloads all installed skills from storage into in-memory cache.
     */
    @Synchronized
    fun refreshFromStorage() {
        installedSkills.clear()
        disabledSkillIds.clear()
        val loaded = storage.listInstalled()
        for (skill in loaded) {
            installedSkills[skill.skillId] = skill
            if (!storage.isSkillEnabled(skill.skillId)) {
                disabledSkillIds.add(skill.skillId)
            }
        }
        Log.i(TAG, "Initialized SkillRegistry with ${installedSkills.size} installed skills (${disabledSkillIds.size} disabled).")
    }

    /**
     * Installs a skill permanently into storage and registers it.
     */
    @Synchronized
    fun install(rawContent: String, targetPlatform: String = "android"): SkillImportResult {
        val parseResult = SkillParser.parse(rawContent)
        if (!parseResult.success || parseResult.skill == null) {
            return SkillImportResult(
                success = false,
                errorMessage = parseResult.errorMessage ?: "Failed to parse skill content."
            )
        }

        val skill = parseResult.skill
        val validation = SkillValidator.validate(skill, targetPlatform = targetPlatform)
        if (!validation.isValid) {
            storage.quarantineRaw(skill.skillId, rawContent, "Validation error: ${validation.errorSummary()}")
            return SkillImportResult(
                success = false,
                errorMessage = "Skill validation rejected: ${validation.errorSummary()}"
            )
        }

        val storageResult = storage.install(skill, rawContent, targetPlatform)
        if (!storageResult.success) {
            return SkillImportResult(
                success = false,
                errorMessage = storageResult.errorMessage ?: "Storage install failed."
            )
        }

        installedSkills[skill.skillId] = skill
        disabledSkillIds.remove(skill.skillId)
        return SkillImportResult(
            success = true,
            skill = skill,
            isEphemeral = false,
            documentationMarkdown = parseResult.documentationMarkdown
        )
    }

    /**
     * Registers an Ephemeral Skill (in-memory execution only without saving to disk).
     * Used when a user attaches or embeds a skill directly inside a prompt.
     */
    @Synchronized
    fun registerEphemeral(rawContent: String, targetPlatform: String = "android"): SkillImportResult {
        val parseResult = SkillParser.parse(rawContent)
        if (!parseResult.success || parseResult.skill == null) {
            return SkillImportResult(
                success = false,
                errorMessage = parseResult.errorMessage ?: "Failed to parse ephemeral skill."
            )
        }

        val skill = parseResult.skill
        val validation = SkillValidator.validate(skill, targetPlatform = targetPlatform)
        if (!validation.isValid) {
            return SkillImportResult(
                success = false,
                errorMessage = "Ephemeral skill validation failed: ${validation.errorSummary()}"
            )
        }

        ephemeralSkills[skill.skillId] = skill
        Log.i(TAG, "Registered ephemeral skill '${skill.skillId}'.")
        return SkillImportResult(
            success = true,
            skill = skill,
            isEphemeral = true,
            documentationMarkdown = parseResult.documentationMarkdown
        )
    }

    /**
     * Retrieves a skill by skillId (checks ephemeral first, then active installed skills).
     */
    @Synchronized
    fun get(skillId: String): SkillDefinition? {
        ephemeralSkills[skillId]?.let { return it }
        if (disabledSkillIds.contains(skillId)) return null
        return installedSkills[skillId]
    }

    /**
     * Lists all active or all installed skills.
     */
    @Synchronized
    fun listInstalled(includeDisabled: Boolean = false): List<SkillDefinition> {
        return if (includeDisabled) {
            installedSkills.values.toList()
        } else {
            installedSkills.values.filter { !disabledSkillIds.contains(it.skillId) }
        }
    }

    /**
     * Lists all currently registered ephemeral skills.
     */
    @Synchronized
    fun listEphemeral(): List<SkillDefinition> = ephemeralSkills.values.toList()

    /**
     * Checks if a skill is available (installed or ephemeral).
     */
    @Synchronized
    fun contains(skillId: String): Boolean {
        return ephemeralSkills.containsKey(skillId) || (installedSkills.containsKey(skillId) && !disabledSkillIds.contains(skillId))
    }

    /**
     * Disables an installed skill from being resolved or executed.
     */
    @Synchronized
    fun disable(skillId: String): Boolean {
        if (installedSkills.containsKey(skillId)) {
            disabledSkillIds.add(skillId)
            storage.setSkillEnabled(skillId, false)
            return true
        }
        return false
    }

    /**
     * Enables a previously disabled skill.
     */
    @Synchronized
    fun enable(skillId: String): Boolean {
        if (installedSkills.containsKey(skillId)) {
            disabledSkillIds.remove(skillId)
            storage.setSkillEnabled(skillId, true)
            return true
        }
        return false
    }

    /**
     * Checks if a skill is currently enabled.
     */
    @Synchronized
    fun isSkillEnabled(skillId: String): Boolean {
        return installedSkills.containsKey(skillId) && !disabledSkillIds.contains(skillId)
    }

    /**
     * Deletes a skill permanently from disk and memory.
     */
    @Synchronized
    fun delete(skillId: String): Boolean {
        ephemeralSkills.remove(skillId)
        disabledSkillIds.remove(skillId)
        installedSkills.remove(skillId)
        return storage.delete(skillId)
    }

    /**
     * Clears all registered ephemeral skills.
     */
    @Synchronized
    fun clearEphemeral() {
        ephemeralSkills.clear()
    }
}
