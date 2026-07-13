package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val message: String,
    val triggerAt: Long, // timestamp to fire in millis
    val status: String = "pending", // "pending" | "completed"
    val createdAt: Long = System.currentTimeMillis(),
    val automationType: String? = null, // "WHATSAPP", "TELEGRAM", etc.
    val automationTarget: String? = null, // contact or phone number
    val automationMessage: String? = null, // actual message content to send
    val repeatType: String? = null, // "ONCE", "DAILY", "WEEKLY"
    val isEnabled: Boolean = true
)
