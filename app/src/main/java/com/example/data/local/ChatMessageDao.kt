package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE ownerKey = :ownerKey ORDER BY timestamp ASC")
    fun getAllMessagesFlow(ownerKey: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun deleteMessageById(id: Int, ownerKey: String)

    @Query("DELETE FROM chat_messages WHERE ownerKey = :ownerKey")
    suspend fun clearAllMessages(ownerKey: String)
}
