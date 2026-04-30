package com.mg.wazealerts.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.mg.wazealerts.R
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.store.AlertStore
import java.util.Locale

class AlertsMediaBrowserService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var alertStore: AlertStore
    private lateinit var settings: AppSettings

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            notifyChildrenChanged(ROOT_ID)
        }
    }

    override fun onCreate() {
        super.onCreate()
        alertStore = AlertStore(this)
        settings = AppSettings(this)
        registerAlertsReceiver()
        mediaSession = MediaSessionCompat(this, "TrafficAlertsMedia").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                    openAlert(mediaId)
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(updateReceiver) }
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }

        val passed = alertStore.passedAlertIds()
        val alerts = alertStore.activeAlerts().filterNot { it.id in passed }
        val items = if (alerts.isEmpty()) {
            mutableListOf(emptyItem())
        } else {
            alerts.take(MAX_MEDIA_ALERTS).map { it.toMediaItem() }.toMutableList()
        }
        result.sendResult(items)
    }

    private fun RoadAlert.toMediaItem(): MediaBrowserCompat.MediaItem {
        val direction = directionDistanceLine(this)
        val description = MediaDescriptionCompat.Builder()
            .setMediaId("$ALERT_ID_PREFIX${Uri.encode(id)}")
            .setTitle("$direction · $title")
            .setSubtitle(addressLine())
            .setDescription(description)
            .setIconUri(resourceUri())
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun emptyItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(EMPTY_ID)
            .setTitle("No active road alerts")
            .setSubtitle("Open the phone app or start monitoring to refresh")
            .setIconUri(resourceUri())
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun openAlert(mediaId: String) {
        if (!mediaId.startsWith(ALERT_ID_PREFIX)) return
        val alertId = Uri.decode(mediaId.removePrefix(ALERT_ID_PREFIX))
        val alert = alertStore.activeAlerts().firstOrNull { it.id == alertId } ?: return
        val uri = Uri.parse(
            "https://waze.com/ul?ll=${alert.latitude},${alert.longitude}&navigate=yes&z=10&utm_source=$packageName"
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun RoadAlert.addressLine(): String =
        address?.takeIf { it.isNotBlank() } ?: "%.5f, %.5f".format(Locale.US, latitude, longitude)

    private fun directionDistanceLine(alert: RoadAlert): String {
        val current = currentLocation() ?: return "${formatDistance(alert.distanceMeters)} away"
        val target = Location("").apply {
            latitude = alert.latitude
            longitude = alert.longitude
        }
        val bearingToAlert = current.bearingTo(target)
        val heading = settings.lastBearingDegrees
        return if (heading >= 0f) {
            val relative = normalizeDegrees(bearingToAlert - heading)
            "${relativeArrow(relative)} ${relativeLabel(relative)} - ${formatDistance(current.distanceTo(target))} away"
        } else {
            "${compassArrow(bearingToAlert)} ${compassLabel(bearingToAlert)} - ${formatDistance(current.distanceTo(target))} away"
        }
    }

    private fun currentLocation(): Location? {
        val lat = settings.lastLatitude.toDouble()
        val lon = settings.lastLongitude.toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return Location("").apply {
            latitude = lat
            longitude = lon
        }
    }

    private fun normalizeDegrees(degrees: Float): Float = (degrees + 360f) % 360f

    private fun relativeArrow(degrees: Float): String = when {
        degrees < 22.5f || degrees >= 337.5f -> "↑"
        degrees < 67.5f -> "↗"
        degrees < 112.5f -> "→"
        degrees < 157.5f -> "↘"
        degrees < 202.5f -> "↓"
        degrees < 247.5f -> "↙"
        degrees < 292.5f -> "←"
        else -> "↖"
    }

    private fun relativeLabel(degrees: Float): String = when {
        degrees < 22.5f || degrees >= 337.5f -> "ahead"
        degrees < 67.5f -> "front right"
        degrees < 112.5f -> "right"
        degrees < 157.5f -> "back right"
        degrees < 202.5f -> "behind"
        degrees < 247.5f -> "back left"
        degrees < 292.5f -> "left"
        else -> "front left"
    }

    private fun compassArrow(degrees: Float): String = relativeArrow(normalizeDegrees(degrees))

    private fun compassLabel(degrees: Float): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((normalizeDegrees(degrees) + 22.5f) / 45f).toInt() % labels.size
        return labels[index]
    }

    private fun formatDistance(meters: Float): String =
        if (meters >= 1000f) "%.1f km".format(Locale.US, meters / 1000f) else "${meters.toInt()} m"

    private fun registerAlertsReceiver() {
        val filter = IntentFilter("com.mg.wazealerts.ALERTS_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    private fun resourceUri(): Uri =
        Uri.parse("android.resource://$packageName/${R.mipmap.ic_launcher}")

    companion object {
        private const val ROOT_ID = "road_alerts_root"
        private const val EMPTY_ID = "road_alerts_empty"
        private const val ALERT_ID_PREFIX = "road_alert:"
        private const val MAX_MEDIA_ALERTS = 12

    }
}
