package com.example.automation.actions

import android.content.Context
import org.json.JSONObject
import com.example.automation.WhatsAppController

class SendMessageAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val contact = payload.optString("contact")
            .ifEmpty { payload.optString("contact_name") }
            .ifEmpty { payload.optString("phone") }
            .ifEmpty { payload.optString("phoneNumber") }

        val message = payload.optString("message")
            .ifEmpty { payload.optString("text") }
            .ifEmpty { payload.optString("message_text") }

        if (contact.isNotEmpty() && message.isNotEmpty()) {
            val controller = WhatsAppController(isScheduled = false)
            controller.execute(context, contact, message)
        }
    }
}
