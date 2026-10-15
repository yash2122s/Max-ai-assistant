package com.example.memory.skill

import android.content.Context
import android.util.Log
import com.example.automation.state.ActionStep
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.memory.security.MemoryCryptography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists and retrieves automation "skills" — named sequences of [ActionStep]s that
 * were executed successfully and can be replayed verbatim for similar future goals.
 *
 * Storage: private JSONL file `skills_memory.jsonl` in the app's files directory.
 * One JSON object per line; atomic append for new skills; full-rewrite for updates.
 *
 * Thread-safety: all I/O is dispatched to [Dispatchers.IO].
 */
class SkillMemoryRepository(context: Context, private val boundSession: MemorySession? = null) {

    companion object {
        private const val TAG = "SkillMemoryRepository"
        private const val FILE_NAME = "skills_memory.jsonl"
        /** Jaccard similarity threshold above which we consider a skill a match. */
        private const val MATCH_THRESHOLD = 0.6
        /** Similarity threshold for deciding whether to update an existing skill. */
        private const val UPDATE_THRESHOLD = 0.8

        private val STOP_WORDS = setOf(
            "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by",
            "from", "go", "turn", "open", "my", "please", "can", "you", "me", "just",
            "i", "want", "help", "okay"
        )
    }

    private val filesDir = context.applicationContext.filesDir
    private fun session() = boundSession ?: MemoryAccountScope.capture()
    private fun skillsFile(session: MemorySession): File =
        File(File(filesDir, "memory-accounts/${MemoryAccountScope.directoryName(session)}"), FILE_NAME)

    // ── Keyword normalisation & similarity ────────────────────────────────────

    internal fun extractKeywords(text: String): List<String> {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .distinct()
    }

    internal fun jaccardSimilarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    // ── IO helpers ────────────────────────────────────────────────────────────

    private suspend fun loadAll(session: MemorySession): MutableList<SavedSkill> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val list = mutableListOf<SavedSkill>()
        val skillsFile = skillsFile(session)
        if (!skillsFile.exists()) return@withContext list
        try {
            skillsFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        list.add(SavedSkill.fromJson(JSONObject(MemoryCryptography.decrypt(trimmed))))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed skill line: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read skills file: ${e.message}")
        }
        MemoryAccountScope.requireCurrent(session)
        list
    }

    private suspend fun saveAll(skills: List<SavedSkill>, session: MemorySession) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        try {
            val skillsFile = skillsFile(session)
            skillsFile.parentFile?.mkdirs()
            val content = skills.joinToString("\n") { MemoryCryptography.encrypt(it.toJson().toString()) }
            MemoryAccountScope.requireCurrent(session)
            skillsFile.writeText(if (content.isNotEmpty()) "$content\n" else "")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skills file: ${e.message}")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Searches for a skill whose task keywords match [goal] with a Jaccard similarity
     * above [MATCH_THRESHOLD].  Returns the best matching [SavedSkill], or null if none
     * qualifies.
     */
    suspend fun findSkill(goal: String, session: MemorySession = session()): SavedSkill? = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val queryKeywords = extractKeywords(goal)
        if (queryKeywords.isEmpty()) return@withContext null

        val skills = loadAll(session)
        var bestMatch: SavedSkill? = null
        var highestSim = 0.0

        for (skill in skills) {
            val sim = jaccardSimilarity(queryKeywords, skill.taskKeywords)
            if (sim > highestSim) {
                highestSim = sim
                bestMatch = skill
            }
        }

        MemoryAccountScope.requireCurrent(session)
        if (highestSim > MATCH_THRESHOLD) {
            Log.d(TAG, "Skill match found for '$goal': '${bestMatch?.task}' (sim=${"%.2f".format(highestSim)})")
            bestMatch
        } else {
            Log.d(TAG, "No skill match above threshold for '$goal' (best sim=${"%.2f".format(highestSim)})")
            null
        }
    }

    /**
     * Saves a successfully executed skill.
     *
     * - If a very similar skill already exists (≥ [UPDATE_THRESHOLD]) AND the new
     *   sequence is shorter or equal, the existing skill is updated.
     * - A failed execution must NOT be passed here; use [recordFailure] instead.
     * - Only successful executions should call this method.
     */
    suspend fun saveSkill(goal: String, steps: List<ActionStep>, session: MemorySession = session()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        if (steps.isEmpty()) return@withContext
        val queryKeywords = extractKeywords(goal)
        val skills = loadAll(session)
        val now = System.currentTimeMillis()

        // Check for a near-duplicate existing skill
        var updated = false
        val updatedList = skills.map { existing ->
            val sim = jaccardSimilarity(queryKeywords, existing.taskKeywords)
            if (sim >= UPDATE_THRESHOLD) {
                updated = true
                val betterSteps = if (steps.size <= existing.steps.size) steps else existing.steps
                existing.copy(
                    successCount = existing.successCount + 1,
                    lastUsed = now,
                    steps = betterSteps
                )
            } else {
                existing
            }
        }

        if (updated) {
            saveAll(updatedList, session)
            Log.i(TAG, "Updated existing skill for: '$goal'")
        } else {
            val newSkill = SavedSkill(
                id = UUID.randomUUID().toString(),
                task = goal,
                taskKeywords = queryKeywords,
                successCount = 1,
                failCount = 0,
                lastUsed = now,
                steps = steps
            )
            // Atomic append: avoid full rewrite when just adding
            try {
                val skillsFile = skillsFile(session)
                skillsFile.parentFile?.mkdirs()
                MemoryAccountScope.requireCurrent(session)
                skillsFile.appendText("${MemoryCryptography.encrypt(newSkill.toJson().toString())}\n")
                Log.i(TAG, "Saved new skill for: '$goal' (${steps.size} steps)")
            } catch (e: Exception) {
                Log.e(TAG, "Append failed, falling back to full rewrite: ${e.message}")
                saveAll(skills + newSkill, session)
            }
        }
    }

    /**
     * Increments the failure counter for a skill with the given [skillId].
     * Does not remove or overwrite the skill; only updates its failure count.
     */
    suspend fun recordFailure(skillId: String, session: MemorySession = session()) = withContext(Dispatchers.IO) {
        val skills = loadAll(session)
        val updated = skills.map { skill ->
            if (skill.id == skillId) skill.copy(failCount = skill.failCount + 1) else skill
        }
        saveAll(updated, session)
        Log.d(TAG, "Recorded failure for skill: $skillId")
    }

    /** Returns all stored skills (for diagnostics / UI). */
    suspend fun getAllSkills(session: MemorySession = session()): List<SavedSkill> = loadAll(session)

    /** Removes all stored skills. */
    suspend fun clearAllSkills(session: MemorySession = session()) = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        try {
            val skillsFile = skillsFile(session)
            if (skillsFile.exists()) skillsFile.delete()
            Log.i(TAG, "Skill memory cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear skill memory: ${e.message}")
        }
    }
}
