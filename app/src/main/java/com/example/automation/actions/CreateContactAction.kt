package com.example.automation.actions

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import org.json.JSONObject

class CreateContactAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val name = payload.optString("name", "")
        val phone = payload.optString("phone", "").ifEmpty { payload.optString("phone_number", "") }

        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, name)
                putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            log("Opened contact insertion screen for Name: '$name', Phone: '$phone'")
        } catch (e: Exception) {
            logError("Failed to open contact insertion screen", e)
        }
    }
}
