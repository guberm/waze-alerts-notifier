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
            val stepText = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val subText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val infoText = extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val subTextNot = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            // Text lines (InboxStyle / MessagingStyle): pick last non-empty line
            val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            settings.navStepText = stepText
            settings.navSubText = subText

            // Destination: try known extras, then text lines, then subText
            val destination = extractDestination(infoText, subTextNot, subText, textLines)
            if (destination.isNotBlank()) settings.navDestination = destination

            AppLogger.d("NavListener", "Step=\"$stepText\" sub=\"$subText\" info=\"$infoText\" subNot=\"$subTextNot\" lines=$textLines dest=\"${settings.navDestination}\"")
        } else if (!isNavigating) {
            settings.navStepText = ""
            settings.navSubText = ""
            settings.navDestination = ""
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

    /**
     * Tries to extract the navigation destination from various Maps notification extras.
     * Google Maps doesn't expose a dedicated "destination" extra; we probe several fields
     * and match known text patterns like "15 min · Destination Name" or "Arriving at X".
     */
    private fun extractDestination(
        infoText: String,
        subTextNotif: String,
        textLine: String,
        allLines: List<String>
    ): String {
        // 1. EXTRA_INFO_TEXT often contains "Destination Name · ETA" or just destination
        if (infoText.isNotBlank()) {
            val stripped = infoText
                .replace(Regex("\\d+:\\d+\\s*(AM|PM)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\d+\\s*(min|h|hr|km|m)\\b.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[·•|–\\-].*"), "")
                .trim().trimEnd(',', '.', '·', '•')
            if (stripped.length > 2) return stripped
        }
        // 2. EXTRA_SUB_TEXT (different from EXTRA_TEXT)
        if (subTextNotif.isNotBlank() && !subTextNotif.matches(Regex("\\d+.*"))) {
            return subTextNotif.trim()
        }
        // 3. "Arriving at X" pattern in any text field
        val arrivingPattern = Regex("(?:arriving|arrive|destination)\\s*(?:at)?\\s*(.+)", RegexOption.IGNORE_CASE)
        for (t in listOf(textLine, subTextNotif, infoText) + allLines) {
            val m = arrivingPattern.find(t) ?: continue
            val dest = m.groupValues[1].replace(Regex("[·•|–].*"), "").trim()
            if (dest.length > 2) return dest
        }
        // 4. Last text line that doesn't look like distance/time
        for (line in allLines.reversed()) {
            if (!line.matches(Regex(".*\\d+\\s*(min|h|km|m|mi).*", RegexOption.IGNORE_CASE))) {
                return line.trim()
            }
        }
        return ""
    }
}
