package com.example.automation.actions

import android.content.Context
import org.json.JSONObject

class DirectAutomationAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val type = payload.optString("type")
        val contact = payload.optString("contact")
        val message = payload.optString("message")
        val isScheduled = payload.optBoolean("is_scheduled", false)
        
        if (contact.isNotEmpty() && message.isNotEmpty()) {
            val controller = when (type.lowercase()) {
                "whatsapp" -> com.example.features.whatsapp.WhatsAppController(isScheduled = isScheduled)
                "telegram" -> com.example.features.telegram.TelegramController(isScheduled = isScheduled)
                "instagram" -> com.example.automation.InstagramController(isScheduled = isScheduled)
                else -> com.example.features.whatsapp.WhatsAppController(isScheduled = isScheduled)
            }
            controller.execute(context, contact, message)
        }
    }
}
