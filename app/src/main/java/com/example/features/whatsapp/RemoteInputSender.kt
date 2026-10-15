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
                // Hardening check: validate both remoteInputs and actionIntent before proceeding
                if (action.remoteInputs == null || action.actionIntent == null) {
                    continue
                }
                val remoteInputs = action.remoteInputs

                // Find a RemoteInput with a reply result key
                val replyInput = remoteInputs.firstOrNull { ri ->
                    ri.resultKey != null && (ri.resultKey.lowercase().contains("reply") || ri.resultKey.lowercase().contains("text"))
                } ?: remoteInputs.firstOrNull() ?: continue

                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(replyInput.resultKey, text)
                
                RemoteInput.addResultsToIntent(arrayOf(replyInput), intent, bundle)
                
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    action.actionIntent.send(context, sbn.id, intent, { _, _, _, _, _ ->
                        Log.d("RemoteInputSender", "Successfully sent action reply via RemoteInput!")
                    }, null)
                } catch (e: Exception) {
                    Log.e("RemoteInputSender", "Immediate RemoteInput send failed, trying delayed fallback retry", e)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            action.actionIntent.send(context, sbn.id, intent)
                            Log.d("RemoteInputSender", "Delayed fallback send completed successfully.")
                        } catch (ex: Exception) {
                            Log.e("RemoteInputSender", "Delayed fallback send failed too", ex)
                        }
                    }, 300)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e("RemoteInputSender", "Error sending reply via RemoteInput", e)
        }
        return false
    }
}
