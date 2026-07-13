package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.automation.AutoReplyController

class AutoReplyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msgId = intent.getStringExtra("msg_id") ?: return
        val action = intent.action
        
        when (action) {
            "com.example.ACTION_CANCEL_REPLY" -> {
                AutoReplyController.cancelPendingReply(msgId)
            }
            "com.example.ACTION_SEND_REPLY_NOW" -> {
                AutoReplyController.sendReplyNow(msgId)
            }
            "com.example.ACTION_QUICK_REPLY" -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence("quick_reply_text")?.toString()
                if (replyText != null) {
                    AutoReplyController.sendQuickReply(msgId, replyText)
                }
            }
        }
    }
}
