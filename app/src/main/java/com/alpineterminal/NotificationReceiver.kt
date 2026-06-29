package com.alpineterminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "TOGGLE_WAKELOCK" -> {
            }
            "EXIT_SESSION" -> {
            }
        }
    }
}
