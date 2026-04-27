package com.mg.wazealerts.monitor

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.settings.AppSettings

class MapsNavigationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        publishMapsNavigationState()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        publishMapsNavigationState()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishMapsNavigationState()
    }

    private fun publishMapsNavigationState() {
        val mapsNotif = activeNotifications?.firstOrNull { it.isMapsNavigation() }
        val isNavigating = mapsNotif != null
        val settings = AppSettings(this)
        settings.mapsNavigationActive = isNavigating

        if (isNavigating && mapsNotif != null) {
            val extras = mapsNotif.notification.extras
            val stepText = extras?.getString(Notification.EXTRA_TITLE) ?: ""
            val subText = extras?.getString(Notification.EXTRA_TEXT) ?: ""
            settings.navStepText = stepText
            settings.navSubText = subText
            AppLogger.d("NavListener", "Step: $stepText | $subText")
        } else if (!isNavigating) {
            settings.navStepText = ""
            settings.navSubText = ""
        }

        val intent = Intent("com.mg.wazealerts.MAPS_NAVIGATION_STATE").setPackage(packageName)
        intent.putExtra("isNavigating", isNavigating)
        sendBroadcast(intent)

        if (isNavigating && settings.monitoringEnabled) {
            runCatching {
                val serviceIntent = Intent(this, AlertMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
    }

    private fun StatusBarNotification.isMapsNavigation(): Boolean =
        packageName == "com.google.android.apps.maps" &&
            isOngoing &&
            notification.category == Notification.CATEGORY_NAVIGATION
}
