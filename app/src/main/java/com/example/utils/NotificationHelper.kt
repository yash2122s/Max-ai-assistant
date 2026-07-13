package com.example.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.receiver.AutoReplyActionReceiver

object NotificationHelper {
    private const val CHANNEL_ID = "jarvis_automation"
    private const val CHANNEL_NAME = "Jarvis Automation"

    fun showSendFallbackNotification(context: Context, phoneNumber: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val encodedMessage = Uri.encode(message)
        val url = "https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedMessage"
        
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("WhatsApp Automation Paused")
            .setContentText("Tap to review and send message to $phoneNumber")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(101, notification)
    }

    fun showPendingReplyNotification(context: Context, msgId: String, sender: String, replyText: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val cancelIntent = Intent(context, AutoReplyActionReceiver::class.java).apply {
            action = "com.example.ACTION_CANCEL_REPLY"
            putExtra("msg_id", msgId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 
            msgId.hashCode(), 
            cancelIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sendNowIntent = Intent(context, AutoReplyActionReceiver::class.java).apply {
            action = "com.example.ACTION_SEND_REPLY_NOW"
            putExtra("msg_id", msgId)
        }
        val sendNowPendingIntent = PendingIntent.getBroadcast(
            context, 
            msgId.hashCode() + 1, 
            sendNowIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val quickRepliesStr = com.example.data.preferences.SettingsManager(context).quickReplies
        val quickRepliesList = quickRepliesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        
        val remoteInput = androidx.core.app.RemoteInput.Builder("quick_reply_text")
            .setLabel("Quick Reply")
            .setChoices(quickRepliesList)
            .build()
            
        val quickReplyIntent = Intent(context, AutoReplyActionReceiver::class.java).apply {
            action = "com.example.ACTION_QUICK_REPLY"
            putExtra("msg_id", msgId)
        }
        
        val quickReplyPendingIntent = PendingIntent.getBroadcast(
            context,
            msgId.hashCode() + 2,
            quickReplyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        
        val quickReplyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Quick Reply",
            quickReplyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Auto-replying to $sender in 8s")
            .setContentText(replyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Send Now", sendNowPendingIntent)
            .addAction(quickReplyAction)
            .build()

        notificationManager.notify(msgId.hashCode(), notification)
    }

    fun cancelPendingReplyNotification(context: Context, msgId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(msgId.hashCode())
    }
}
