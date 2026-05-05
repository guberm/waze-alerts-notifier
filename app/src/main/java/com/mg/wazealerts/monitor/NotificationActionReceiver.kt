package com.mg.wazealerts.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op: satisfies Android Auto MessagingStyle reply-action requirement
    }
}
