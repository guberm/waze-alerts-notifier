package com.mg.wazealerts.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MapsNavigationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        checkMapsNavigation(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        checkMapsNavigation(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach { checkMapsNavigation(it) }
    }

    private fun checkMapsNavigation(sbn: StatusBarNotification?) {
        if (sbn?.packageName == "com.google.android.apps.maps") {
            val isOngoing = sbn.isOngoing
            val notification = sbn.notification
            val hasNavigationCategory = notification.category == Notification.CATEGORY_NAVIGATION
            val isNavigating = isOngoing && hasNavigationCategory
            
            // Broadcast the navigation state to the app
            val intent = android.content.Intent("com.mg.wazealerts.MAPS_NAVIGATION_STATE")
            intent.putExtra("isNavigating", isNavigating)
            sendBroadcast(intent)
        }
    }
}
