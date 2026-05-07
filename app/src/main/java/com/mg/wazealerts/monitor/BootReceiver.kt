package com.mg.wazealerts.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.settings.AppSettings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppLogger.init(context)
        val settings = AppSettings(context)
        if (!settings.monitoringEnabled) {
            AppLogger.d(TAG, "Boot event ${intent?.action} ignored — monitoring disabled")
            return
        }
        AppLogger.i(TAG, "Boot event ${intent?.action} — restarting monitoring")
        ServiceWatchdog.startMonitoring(context)
        ServiceWatchdog.scheduleWatchdog(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
