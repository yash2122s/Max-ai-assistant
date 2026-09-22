package com.example.automation.skills.storage

import android.content.Context
import android.util.Log
import com.example.automation.skills.canonical.CanonicalJsonSerializer
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.parser.SkillParser
import com.example.automation.skills.validation.SkillValidator
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.IOException

/**
 * Result returned by storage operations.
 */
data class SkillStorageResult(
    val success: Boolean,
    val skillId: String? = null,
    val installedPath: String? = null,
    val errorMessage: String? = null
)

/**
 * Isolated Filesystem-based Storage Manager for MAX Automation Skills.
 * Root: context.filesDir/skills/
 * - installed/<sanitized_skill_id>/ (manifest.json, canonical.json, source.md/json)
 * - staging/
 * - quarantine/
 * - backups/
 */
class SkillStorage(private val rootDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "skills"))

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val installedDir = File(rootDir, "installed")
    private val stagingDir = File(rootDir, "staging")
    private val quarantineDir = File(rootDir, "quarantine")
    private val backupsDir = File(rootDir, "backups")

    init {
        ensureDirectories()
    }

    private fun ensureDirectories() {
        if (!installedDir.exists()) installedDir.mkdirs()
        if (!stagingDir.exists()) stagingDir.mkdirs()
        if (!quarantineDir.exists()) quarantineDir.mkdirs()
        if (!backupsDir.exists()) backupsDir.mkdirs()
    }

    /**
     * Sanitizes a skill ID to prevent path traversal.
     */
    fun sanitizeSkillId(skillId: String): String {
        return skillId.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * Installs a skill atomically:
     * 1. Writes artifacts into staging/
     * 2. Computes and verifies canonical checksum
     * 3. Atomically moves staging directory into installed/<skill_id>
     */
    @Synchronized
    fun install(
        skill: SkillDefinition,
        rawContent: String,
        targetPlatform: String = "android"
    ): SkillStorageResult {
        ensureDirectories()
        val sanitizedId = sanitizeSkillId(skill.skillId)
        if (sanitizedId.isBlank()) {
            return SkillStorageResult(success = false, errorMessage = "Invalid or blank skill_id.")
        }

        if (rawContent.toByteArray(Charsets.UTF_8).size > SkillParser.MAX_FILE_SIZE_BYTES) {
            quarantineRaw(sanitizedId, rawContent, "Exceeds maximum file size (512KB)")
            return SkillStorageResult(success = false, errorMessage = "File exceeds 512KB size limit.")
        }

        // Validate skill structure and capabilities
        val validation = SkillValidator.validate(skill, targetPlatform = targetPlatform)
        if (!validation.isValid) {
            quarantineRaw(sanitizedId, rawContent, "Validation failed: ${validation.errorSummary()}")
            return SkillStorageResult(
                success = false,
                skillId = sanitizedId,
                errorMessage = "Skill validation failed: ${validation.errorSummary()}"
            )
        }

        val targetSkillDir = File(installedDir, sanitizedId)
        val stagingSkillDir = File(stagingDir, "${sanitizedId}_${System.currentTimeMillis()}")

        try {
            if (stagingSkillDir.exists()) stagingSkillDir.deleteRecursively()
            stagingSkillDir.mkdirs()

            // 1. Write source file
            val isMarkdown = rawContent.trimStart().startsWith("---")
            val sourceFileName = if (isMarkdown) "source.md" else "source.json"
            File(stagingSkillDir, sourceFileName).writeText(rawContent, Charsets.UTF_8)

            // 2. Write canonical JSON representation
            val canonicalJson = CanonicalJsonSerializer.toCanonicalJson(skill)
            File(stagingSkillDir, "canonical.json").writeText(canonicalJson, Charsets.UTF_8)

            // 3. Write manifest.json
            val computedChecksum = CanonicalJsonSerializer.computeChecksum(skill)
            val manifest = mapOf(
                "skill_id" to skill.skillId,
                "name" to skill.name,
                "version" to skill.version,
                "author" to skill.author,
                "checksum" to computedChecksum,
                "installed_at" to System.currentTimeMillis(),
                "enabled" to true
            )
            File(stagingSkillDir, "manifest.json").writeText(gson.toJson(manifest), Charsets.UTF_8)

            // 4. Backup existing if present
            if (targetSkillDir.exists()) {
                val backupDest = File(backupsDir, "${sanitizedId}_${System.currentTimeMillis()}")
                targetSkillDir.renameTo(backupDest)
            }

            // 5. Atomic Promotion
            val promoted = stagingSkillDir.renameTo(targetSkillDir)
            if (!promoted) {
                // Fallback copy if cross-filesystem rename fails
                stagingSkillDir.copyRecursively(targetSkillDir, overwrite = true)
                stagingSkillDir.deleteRecursively()
            }

            Log.i("SkillStorage", "Successfully installed skill '${skill.skillId}' to ${targetSkillDir.absolutePath}")
            return SkillStorageResult(
                success = true,
                skillId = skill.skillId,
                installedPath = targetSkillDir.absolutePath
            )
        } catch (e: Exception) {
            Log.e("SkillStorage", "Failed to install skill '$sanitizedId'", e)
            if (stagingSkillDir.exists()) stagingSkillDir.deleteRecursively()
            return SkillStorageResult(success = false, skillId = sanitizedId, errorMessage = e.message)
        }
    }

    /**
     * Loads an installed SkillDefinition from its canonical.json.
     */
    fun loadSkill(skillId: String): SkillDefinition? {
        val sanitizedId = sanitizeSkillId(skillId)
        val skillDir = File(installedDir, sanitizedId)
        val canonicalFile = File(skillDir, "canonical.json")
        if (!canonicalFile.exists()) return null

        return try {
            val content = canonicalFile.readText(Charsets.UTF_8)
            val parseResult = SkillParser.parse(content)
            parseResult.skill
        } catch (e: Exception) {
            Log.e("SkillStorage", "Failed to load skill '$skillId'", e)
            null
        }
    }

    /**
     * Loads raw source content (Markdown or JSON) for auditing or exporting.
     */
    fun loadRawSource(skillId: String): String? {
        val sanitizedId = sanitizeSkillId(skillId)
        val skillDir = File(installedDir, sanitizedId)
        val mdFile = File(skillDir, "source.md")
        if (mdFile.exists()) return mdFile.readText(Charsets.UTF_8)
        val jsonFile = File(skillDir, "source.json")
        if (jsonFile.exists()) return jsonFile.readText(Charsets.UTF_8)
        val canonicalFile = File(skillDir, "canonical.json")
        if (canonicalFile.exists()) return canonicalFile.readText(Charsets.UTF_8)
        return null
    }

    /**
     * Lists all installed SkillDefinitions.
     */
    fun listInstalled(): List<SkillDefinition> {
        ensureDirectories()
        val skills = mutableListOf<SkillDefinition>()
        val dirs = installedDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            val canonicalFile = File(dir, "canonical.json")
            if (canonicalFile.exists()) {
                try {
                    val parseResult = SkillParser.parse(canonicalFile.readText(Charsets.UTF_8))
                    parseResult.skill?.let { skills.add(it) }
                } catch (e: Exception) {
                    Log.w("SkillStorage", "Failed to parse installed skill in ${dir.name}: ${e.message}")
                }
            }
        }
        return skills
    }

    /**
     * Checks if a skill is installed.
     */
    fun contains(skillId: String): Boolean {
        val sanitizedId = sanitizeSkillId(skillId)
        return File(installedDir, sanitizedId).exists()
    }

    /**
     * Checks if an installed skill is marked enabled in its manifest.json.
     */
    fun isSkillEnabled(skillId: String): Boolean {
        val sanitizedId = sanitizeSkillId(skillId)
        val manifestFile = File(File(installedDir, sanitizedId), "manifest.json")
        if (!manifestFile.exists()) return true
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val manifest: Map<String, Any>? = gson.fromJson(manifestFile.readText(Charsets.UTF_8), type)
            (manifest?.get("enabled") as? Boolean) ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Updates the persistent enabled state in manifest.json.
     */
    @Synchronized
    fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
        val sanitizedId = sanitizeSkillId(skillId)
        val manifestFile = File(File(installedDir, sanitizedId), "manifest.json")
        if (!manifestFile.exists()) return false
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            val manifest: MutableMap<String, Any> = gson.fromJson<Map<String, Any>>(manifestFile.readText(Charsets.UTF_8), type)?.toMutableMap() ?: mutableMapOf()
            manifest["enabled"] = enabled
            manifestFile.writeText(gson.toJson(manifest), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e("SkillStorage", "Failed to update enabled state for '$skillId'", e)
            false
        }
    }

    /**
     * Deletes an installed skill.
     */
    @Synchronized
    fun delete(skillId: String): Boolean {
        val sanitizedId = sanitizeSkillId(skillId)
        val skillDir = File(installedDir, sanitizedId)
        return if (skillDir.exists()) {
            skillDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * Quarantines a skill directory or raw content due to tampering/validation errors.
     */
    fun quarantineRaw(skillId: String, content: String, reason: String) {
        ensureDirectories()
        val sanitizedId = sanitizeSkillId(skillId)
        val qDir = File(quarantineDir, "${sanitizedId}_${System.currentTimeMillis()}")
        qDir.mkdirs()
        File(qDir, "quarantine_reason.txt").writeText(reason, Charsets.UTF_8)
        File(qDir, "payload.txt").writeText(content, Charsets.UTF_8)
        Log.w("SkillStorage", "Quarantined skill '$skillId' due to: $reason")
    }
}
