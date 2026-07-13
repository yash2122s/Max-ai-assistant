package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Phase 0 — ActionReward entity for the Reinforcement Learning reward store.
 * 
 * Records the outcome of each agentic loop run so future runs can
 * learn from past successes and avoid known-bad paths.
 *
 * Schema is finalized here to avoid DB migration churn later.
 */
@Entity(tableName = "action_rewards")
data class ActionReward(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /**
     * Goal category enum key (e.g. "send_whatsapp", "open_app").
     * Stored as String for Room compatibility, but always produced
     * from GoalCategory.key — never hand-typed.
     */
    val goalCategory: String,

    /** The original user command text. */
    val goalText: String,

    /**
     * JSON array of serialized AgenticAction objects taken during the loop.
     * Each element is the toJson() output of an AgenticAction.
     */
    val actionSequence: String,

    /**
     * JSON array of compact screen summaries at each step.
     * PII (phone numbers, digits) MUST be redacted before insert.
     */
    val screenContexts: String,

    /** Terminal outcome key from LoopOutcome enum. */
    val outcome: String,

    /**
     * Numeric reward: 1.0=success, 0.5=partial, 0.2=max-iter, 0.0=failure.
     * Matches LoopOutcome.rewardValue.
     */
    val reward: Float,

    /** Number of loop iterations used (1..maxIterations). */
    val iterationCount: Int,

    /** Total wall-clock time from loop start to finish, in milliseconds. */
    val totalTimeMs: Long,

    /** Unix timestamp of when this run completed. */
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface RewardDao {

    /**
     * Fetch the best-performing past strategies for a goal category.
     * Ordered by reward DESC, then fewest iterations (efficiency).
     * Limited to 5 to keep prompt injection surface small.
     */
    @Query("""
        SELECT * FROM action_rewards 
        WHERE goalCategory = :category 
        ORDER BY reward DESC, iterationCount ASC 
        LIMIT 5
    """)
    fun getBestStrategies(category: String): List<ActionReward>

    /**
     * Fetch recent failures for a goal category so the AI can
     * avoid known-bad action sequences.
     */
    @Query("""
        SELECT * FROM action_rewards 
        WHERE goalCategory = :category AND outcome IN ('STUCK_ABORT', 'AI_ABORT', 'AI_ERROR') 
        ORDER BY timestamp DESC 
        LIMIT 3
    """)
    fun getRecentFailures(category: String): List<ActionReward>

    @Insert
    fun insertReward(reward: ActionReward): Long

    /**
     * Count rows for a category — used by retention/pruning logic
     * to cap storage per category.
     */
    @Query("SELECT COUNT(*) FROM action_rewards WHERE goalCategory = :category")
    fun countByCategory(category: String): Int

    /**
     * Delete the oldest rows for a category beyond a retention limit.
     * Called after insert to keep the table bounded.
     */
    @Query("""
        DELETE FROM action_rewards 
        WHERE id IN (
            SELECT id FROM action_rewards 
            WHERE goalCategory = :category 
            ORDER BY timestamp ASC 
            LIMIT :excess
        )
    """)
    fun pruneOldest(category: String, excess: Int)

    /** Delete all reward data (for settings/debug). */
    @Query("DELETE FROM action_rewards")
    fun clearAll()
}
