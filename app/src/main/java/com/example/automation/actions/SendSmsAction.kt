package com.example.automation.actions

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject

class SendSmsAction : BaseAction<JSONObject>() {
    companion object {
        private const val TAG = "SendSmsAction"
    }

    override fun execute(context: Context, payload: JSONObject) {
        val number = payload.optString("number").ifEmpty { payload.optString("phone") }
        val message = payload.optString("message").ifEmpty { payload.optString("text") }

        if (number.isBlank() || message.isBlank()) {
            throw IllegalArgumentException("SMS requires both number and message")
        }

        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.d(TAG, "[SMS_RESULT] SMS sent successfully to $number: '$message'")
        } catch (e: Exception) {
            Log.e(TAG, "[SMS_RESULT] Failed to send SMS to $number: ${e.message}", e)
            throw e
        }
    }
}
