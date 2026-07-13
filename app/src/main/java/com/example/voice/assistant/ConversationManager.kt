package com.example.voice.assistant

import com.example.viewmodel.ChatMessage

class ConversationManager {
    private val messages = mutableListOf<ChatMessage>()
    private val MAX_TURNS = 15

    @Synchronized
    fun addMessage(role: String, content: String) {
        messages.add(ChatMessage(role, content))
        pruneIfNecessary()
    }

    @Synchronized
    fun getHistory(): List<ChatMessage> {
        return messages.toList()
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }

    private fun pruneIfNecessary() {
        if (messages.size > MAX_TURNS * 2) {
            val subList = messages.takeLast(MAX_TURNS * 2)
            messages.clear()
            messages.addAll(subList)
        }
    }
}
