package com.example.features.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

open class AutoReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
