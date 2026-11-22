package com.example.features.whatsapp

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

object RemoteInputSender {

    /**
     * Checks if the given StatusBarNotification contains a valid direct reply action with RemoteInput.
     */
    fun hasRemoteInput(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val actions = notification.actions ?: return false
        return actions.any { action ->
            action.remoteInputs != null && action.actionIntent != null && action.remoteInputs.isNotEmpty()
        }
    }

    fun sendReply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        try {
            val notification = sbn.notification ?: return false
            val actions = notification.actions ?: return false

            for (action in actions) {
                if (action.remoteInputs == null || action.actionIntent == null) continue

                val replyInput = action.remoteInputs.firstOrNull { ri ->
                    ri.resultKey != null &&
                    (ri.resultKey.lowercase().contains("reply") ||
                     ri.resultKey.lowercase().contains("text"))
                } ?: continue

                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(replyInput.resultKey, text)
                RemoteInput.addResultsToIntent(arrayOf(replyInput), intent, bundle)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                return try {
                    action.actionIntent.send(context, sbn.id, intent)
                    Log.d("RemoteInputSender", "Reply sent via RemoteInput")
                    true
                } catch (e: Exception) {
                    Log.e("RemoteInputSender", "RemoteInput send failed", e)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteInputSender", "Error sending reply", e)
        }
        return false
    }
}
