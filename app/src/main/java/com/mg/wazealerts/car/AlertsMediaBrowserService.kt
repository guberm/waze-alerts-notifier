package com.mg.wazealerts.car

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.mg.wazealerts.R
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.store.AlertStore
import java.util.Locale

class AlertsMediaBrowserService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var alertStore: AlertStore

    override fun onCreate() {
        super.onCreate()
        alertStore = AlertStore(this)
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

        val alerts = alertStore.activeAlerts()
        val items = if (alerts.isEmpty()) {
            mutableListOf(emptyItem())
        } else {
            alerts.take(MAX_MEDIA_ALERTS).map { it.toMediaItem() }.toMutableList()
        }
        result.sendResult(items)
    }

    private fun RoadAlert.toMediaItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId("$ALERT_ID_PREFIX${Uri.encode(id)}")
            .setTitle(title)
            .setSubtitle(addressLine())
            .setDescription("${distanceMeters.toInt()} m away")
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

    private fun resourceUri(): Uri =
        Uri.parse("android.resource://$packageName/${R.mipmap.ic_launcher}")

    companion object {
        private const val ROOT_ID = "road_alerts_root"
        private const val EMPTY_ID = "road_alerts_empty"
        private const val ALERT_ID_PREFIX = "road_alert:"
        private const val MAX_MEDIA_ALERTS = 12
    }
}
