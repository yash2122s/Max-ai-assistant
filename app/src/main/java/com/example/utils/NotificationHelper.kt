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
    private const val CHANNEL_NAME = "MAX Automation"

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

    fun showPendingReplyNotification(
        context: Context,
        confirmationId: String,
        sender: String,
        incomingMessage: String,
        proposedReply: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val confirmIntent = Intent(context, AutoReplyActionReceiver::class.java).apply {
            action = "com.example.ACTION_CONFIRM_REPLY"
            putExtra("confirmation_id", confirmationId)
        }
        val confirmPendingIntent = PendingIntent.getBroadcast(
            context,
            confirmationId.hashCode(),
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, AutoReplyActionReceiver::class.java).apply {
            action = "com.example.ACTION_CANCEL_REPLY"
            putExtra("confirmation_id", confirmationId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            confirmationId.hashCode() + 1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_max_notification)
            .setContentTitle("Auto-Reply Confirmation: $sender")
            .setContentText("Incoming: \"$incomingMessage\" | Proposed: \"$proposedReply\"")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Sender: $sender\nIncoming: \"$incomingMessage\"\nProposed Reply: \"$proposedReply\""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(android.R.drawable.ic_menu_send, "CONFIRM", confirmPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL", cancelPendingIntent)
            .build()

        notificationManager.notify(confirmationId.hashCode(), notification)
    }

    fun cancelPendingReplyNotification(context: Context, confirmationId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(confirmationId.hashCode())
    }
}
