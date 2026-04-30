package com.mg.wazealerts.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.Uri
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.store.AlertStore
import java.util.Locale

class AlertsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        AppLogger.init(this)
        AppLogger.i("CarService", "onCreateSession display=${sessionInfo.displayType}")
        return object : Session() {
            private val mediaSession = MediaSessionCompat(
                this@AlertsCarAppService,
                "TrafficAlertsTemplateMedia"
            ).apply {
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                        .build()
                )
                isActive = true
            }

            init {
                lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_CREATE -> registerMediaPlaybackToken(mediaSession.sessionToken)
                            Lifecycle.Event.ON_DESTROY -> mediaSession.release()
                            else -> Unit
                        }
                    }
                )
            }

            override fun onCreateScreen(intent: Intent): Screen {
                AppLogger.i("CarService", "onCreateScreen apiLevel=${carContext.carAppApiLevel}")
                return AlertsCarScreen(carContext)
            }

            private fun registerMediaPlaybackToken(token: MediaSessionCompat.Token) {
                runCatching {
                    (carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager)
                        .registerMediaPlaybackToken(token)
                }.onFailure {
                    AppLogger.e("CarService", "registerMediaPlaybackToken failed: ${it.message}")
                }
            }
        }
    }
}

/**
 * Reads alerts from AlertStore (already populated by AlertMonitorService).
 * No network calls, no Play Services, no coroutines — all synchronous.
 * CarContext must NOT be used with LocationServices/FusedLocationProvider — it crashes.
 */
class AlertsCarScreen(carContext: CarContext) : Screen(carContext) {

    private val settings = AppSettings(carContext)
    private val alertStore = AlertStore(carContext)
    private var alerts: List<RoadAlert> = emptyList()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLogger.d("CarScreen", "ALERTS_UPDATED — reloading")
            loadAlerts()
            safeInvalidate()
        }
    }

    init {
        AppLogger.i("CarScreen", "init — loading from AlertStore")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                carContext.registerReceiver(
                    updateReceiver,
                    IntentFilter("com.mg.wazealerts.ALERTS_UPDATED"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                carContext.registerReceiver(
                    updateReceiver,
                    IntentFilter("com.mg.wazealerts.ALERTS_UPDATED")
                )
            }
        }.onFailure { AppLogger.e("CarScreen", "registerReceiver failed: ${it.message}") }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { carContext.unregisterReceiver(updateReceiver) }
                AppLogger.i("CarScreen", "onDestroy")
            }
        })

        loadAlerts()
        AppLogger.i("CarScreen", "init done — ${alerts.size} alerts")
    }

    override fun onGetTemplate(): Template {
        AppLogger.d("CarScreen", "onGetTemplate — ${alerts.size} alerts")
        return runCatching { buildTemplate() }
            .getOrElse { e ->
                AppLogger.e("CarScreen", "buildTemplate failed: ${e.message}")
                fallbackTemplate(e.message)
            }
    }

    private fun loadAlerts() {
        val passed = alertStore.passedAlertIds()
        alerts = alertStore.activeAlerts()
            .filterNot { it.id in passed }
            .map { it.withLiveDistance() }
            .sortedBy { it.distanceMeters }
    }

    private fun safeInvalidate() {
        runCatching { invalidate() }.onFailure {
            AppLogger.e("CarScreen", "invalidate failed: ${it.message}")
        }
    }

    private fun buildTemplate(): Template {
        val sectionBuilder = RowSection.Builder().setTitle("Nearby")
        when {
            !settings.monitoringEnabled ->
                sectionBuilder.addItem(
                    Row.Builder().setTitle("Monitoring is off")
                        .addText("Enable in phone app settings").build()
                )
            alerts.isEmpty() ->
                sectionBuilder.addItem(
                    Row.Builder().setTitle("No alerts nearby")
                        .addText("Within ${settings.radiusMeters} m").build()
                )
            else ->
                alerts.take(6).forEach { alert ->
                    sectionBuilder.addItem(
                        Row.Builder()
                            .setTitle(alert.title)
                            .addText(directionDistanceLine(alert))
                            .addText(alert.address ?: "%.5f, %.5f".format(Locale.US, alert.latitude, alert.longitude))
                            .setOnClickListener { openNavigation(alert) }
                            .build()
                    )
                }
        }
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Road alerts").setStartHeaderAction(Action.APP_ICON).build())
            .addSection(sectionBuilder.build())
            .setLoading(false)
            .build()
    }

    private fun fallbackTemplate(msg: String?): Template =
        SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Road alerts").setStartHeaderAction(Action.APP_ICON).build())
            .addSection(
                RowSection.Builder()
                    .setTitle("Status")
                        .addItem(Row.Builder().setTitle("Error").addText(msg ?: "Unknown error").build())
                    .build()
            )
            .setLoading(false)
            .build()

    private fun openNavigation(alert: RoadAlert) {
        runCatching {
            val uri = Uri.parse(
                "https://waze.com/ul?ll=${alert.latitude},${alert.longitude}&navigate=yes&z=10&utm_source=${carContext.packageName}"
            )
            carContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLogger.e("CarScreen", "openNavigation failed: ${it.message}") }
    }

    private fun RoadAlert.withLiveDistance(): RoadAlert {
        val current = currentLocation() ?: return this
        val target = alertLocation(this)
        return copy(distanceMeters = current.distanceTo(target))
    }

    private fun directionDistanceLine(alert: RoadAlert): String {
        val current = currentLocation() ?: return "${formatDistance(alert.distanceMeters)} away"
        val target = alertLocation(alert)
        val bearingToAlert = current.bearingTo(target)
        val heading = settings.lastBearingDegrees
        val distance = current.distanceTo(target)
        return if (heading >= 0f) {
            val relative = normalizeDegrees(bearingToAlert - heading)
            "${relativeArrow(relative)} ${relativeLabel(relative)} - ${formatDistance(distance)} away"
        } else {
            "${compassArrow(bearingToAlert)} ${compassLabel(bearingToAlert)} - ${formatDistance(distance)} away"
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

    private fun alertLocation(alert: RoadAlert): Location =
        Location("").apply {
            latitude = alert.latitude
            longitude = alert.longitude
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
}
