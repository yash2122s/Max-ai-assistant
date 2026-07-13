package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auto_reply_rules")
data class AutoReplyRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val triggerKeyword: String,
    val replyMessage: String,
    val targetContact: String? = null,
    val enabled: Boolean = true
)
