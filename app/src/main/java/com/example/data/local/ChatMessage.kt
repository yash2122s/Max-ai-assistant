package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String, // "user" or "jarvis"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false
)
