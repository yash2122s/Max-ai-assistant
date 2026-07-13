package com.example.utils

object ContextManager {
    var lastContact: String? = null
    var lastIntent: String? = null
    var lastMessage: String? = null

    fun clear() {
        lastContact = null
        lastIntent = null
        lastMessage = null
    }
}
