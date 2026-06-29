package com.alpineterminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {
    private val CHANNEL_ID = "axiom_terminal_service_channel"
    private val NOTIFICATION_ID = 1001
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Axiom Session Service"
            val descriptionText = "Keeps Axiom Terminal sessions active in the background"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't make sound
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showPersistentNotification(isWakeLockAcquired: Boolean) {
        val title = "Axiom Console Active"
        val message = if (isWakeLockAcquired) {
            "WakeLock ACQUIRED — CPU kept active"
        } else {
            "Running in background — click to manage"
        }

        // Action Intents for Buttons
        val wakeLockIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = "TOGGLE_WAKELOCK"
        }
        val wakeLockPendingIntent = PendingIntent.getBroadcast(
            context, 0, wakeLockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = "EXIT_SESSION"
        }
        val exitPendingIntent = PendingIntent.getBroadcast(
            context, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Makes it persistent (cannot be swiped away)
            .addAction(
                android.R.drawable.ic_lock_lock,
                if (isWakeLockAcquired) "Release WakeLock" else "Acquire WakeLock",
                wakeLockPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Exit Session",
                exitPendingIntent
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun showNotification(title: String, message: String) {
        // Fallback for short non-persistent alert notifications
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
