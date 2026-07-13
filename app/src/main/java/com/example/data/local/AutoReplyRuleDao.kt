package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoReplyRuleDao {
    @Query("SELECT * FROM auto_reply_rules ORDER BY id DESC")
    fun getAllRulesFlow(): Flow<List<AutoReplyRule>>

    @Query("SELECT * FROM auto_reply_rules")
    suspend fun getAllRules(): List<AutoReplyRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AutoReplyRule): Long

    @Query("DELETE FROM auto_reply_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Int)
}
