package com.example.features.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.preferences.SettingsManager

open class AutoReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.isMaxEnabled) {
            Log.d("AutoReplyActionReceiver", "Max global switch is OFF. Suppressing auto-reply action.")
            return
        }

        val confirmationId = intent.getStringExtra("confirmation_id") 
            ?: intent.getStringExtra("msg_id") 
            ?: return
        val action = intent.action

        when (action) {
            "com.example.ACTION_CONFIRM_REPLY", "com.example.ACTION_SEND_REPLY_NOW" -> {
                AutoReplyController.confirmAndSend(context, confirmationId)
            }
            "com.example.ACTION_CANCEL_REPLY" -> {
                AutoReplyController.cancelPendingReply(context, confirmationId)
            }
        }
    }
}
