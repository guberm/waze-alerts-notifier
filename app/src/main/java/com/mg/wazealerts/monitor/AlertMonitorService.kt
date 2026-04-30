package com.mg.wazealerts.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mg.wazealerts.MainActivity
import com.mg.wazealerts.R
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.source.AlertRepository
import com.mg.wazealerts.store.AlertStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import com.mg.wazealerts.AppLogger
import java.util.Locale

class AlertMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var settings: AppSettings
    private lateinit var repository: AlertRepository
    private lateinit var alertStore: AlertStore
    private val notifiedIds = linkedSetOf<String>()
    private var isMapsNavigating = false
    private val prevDistances = HashMap<String, Float>()
    private var lastGeocodedLocation: Location? = null
    private var lastAlertRefreshAtMillis = 0L
    private var lastUiBroadcastAtMillis = 0L
    private var lastVisibleIdsFingerprint = ""
    private var lastVisibleDistanceFingerprint = ""

    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val was = isMapsNavigating
            isMapsNavigating = intent?.getBooleanExtra("isNavigating", false) ?: false
            settings.mapsNavigationActive = isMapsNavigating
            when {
                !was && isMapsNavigating -> {
                    prevDistances.clear()
                    AppLogger.i(TAG, "Navigation started")
                }
                was && !isMapsNavigating -> {
                    alertStore.clearPassed()
                    settings.currentLocationAddress = ""
                    settings.navDestination = ""
                    prevDistances.clear()
                    cancelAlertNotifications()
                    AppLogger.i(TAG, "Navigation ended — cleared passed alerts")
                }
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { checkAlerts(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        settings = AppSettings(this)
        repository = AlertRepository(this)
        alertStore = AlertStore(this)
        isMapsNavigating = settings.mapsNavigationActive
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        createChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navReceiver, IntentFilter("com.mg.wazealerts.MAPS_NAVIGATION_STATE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(navReceiver, IntentFilter("com.mg.wazealerts.MAPS_NAVIGATION_STATE"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.monitoringEnabled) {
            AppLogger.w(TAG, "Monitoring disabled — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        AppLogger.i(TAG, "Service started (radius=${settings.radiusMeters}m, interval=${settings.pollIntervalMillis}ms)")

        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        requestLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        fusedLocation.removeLocationUpdates(locationCallback)
        unregisterReceiver(navReceiver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LIVE_DISTANCE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(LIVE_DISTANCE_MIN_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(LIVE_DISTANCE_MIN_MOVE_METERS)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun checkAlerts(location: Location) {
        if (location.hasBearing()) settings.lastBearingDegrees = location.bearing
        settings.lastLatitude = location.latitude.toFloat()
        settings.lastLongitude = location.longitude.toFloat()

        scope.launch {
            AppLogger.d(TAG, "Location: %.4f,%.4f bearing=%s".format(
                location.latitude, location.longitude,
                if (location.hasBearing()) "${location.bearing.toInt()}°" else "n/a"
            ))

            val liveAlerts = updateStoredAlertDistances(location)
            if (!shouldRefreshAlerts()) {
                syncAlertNotifications(liveAlerts, location)
                geocodeCurrentPosition(location)
                return@launch
            }

            val cacheRadius = cacheRadiusMeters()
            val fetched = repository.nearby(location, cacheRadius)
                .map { it.withDistanceFrom(location) }
                .sortedBy { it.distanceMeters }
            AppLogger.i(TAG, "Fetched ${fetched.size} cache alerts (radius=${cacheRadius}m, visibleRadius=${settings.radiusMeters}m)")

            lastAlertRefreshAtMillis = System.currentTimeMillis()
            val cached = mergeCachedAlerts(fetched, alertStore.cachedAlerts(alertCacheTtlMillis()), location)
            val visible = visibleAlerts(cached, location)
            updatePassedAlerts(visible, location)

            if (cached.isNotEmpty()) {
                alertStore.saveCachedAlerts(cached, updateFetchedAt = fetched.isNotEmpty())
            }
            alertStore.saveActiveAlerts(visible)
            broadcastVisibleAlerts(visible, force = true)
            geocodeCurrentPosition(location)

            syncAlertNotifications(visible, location)
        }
    }

    private fun shouldRefreshAlerts(): Boolean {
        val elapsed = System.currentTimeMillis() - lastAlertRefreshAtMillis
        return lastAlertRefreshAtMillis == 0L || elapsed >= settings.pollIntervalMillis
    }

    private fun updateStoredAlertDistances(location: Location): List<RoadAlert> {
        val cached = alertStore.cachedAlerts(alertCacheTtlMillis())
        val current = if (cached.isNotEmpty()) cached else alertStore.activeAlerts()
        if (current.isEmpty()) {
            alertStore.saveActiveAlerts(emptyList())
            broadcastVisibleAlerts(emptyList())
            return emptyList()
        }

        val updatedCache = current.map { it.withDistanceFrom(location) }.sortedBy { it.distanceMeters }
        val visible = visibleAlerts(updatedCache, location)
        updatePassedAlerts(visible, location)
        if (cached.isNotEmpty()) {
            alertStore.saveCachedAlerts(updatedCache.take(MAX_CACHED_ALERTS), updateFetchedAt = false)
        }
        alertStore.saveActiveAlerts(visible)
        broadcastVisibleAlerts(visible)
        return visible
    }

    private fun broadcastVisibleAlerts(alerts: List<RoadAlert>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val idsFingerprint = alerts.joinToString("|") { it.id }
        val distanceFingerprint = alerts.joinToString("|") {
            "${it.id}:${(it.distanceMeters / DISTANCE_UI_BUCKET_METERS).toInt()}"
        }
        val idsChanged = idsFingerprint != lastVisibleIdsFingerprint
        val distancesChanged = distanceFingerprint != lastVisibleDistanceFingerprint
        val minElapsed = now - lastUiBroadcastAtMillis >= MIN_UI_BROADCAST_INTERVAL_MILLIS
        val maxElapsed = now - lastUiBroadcastAtMillis >= MAX_UI_BROADCAST_INTERVAL_MILLIS

        if (!force && !idsChanged && (!distancesChanged || !minElapsed) && !maxElapsed) return

        lastUiBroadcastAtMillis = now
        lastVisibleIdsFingerprint = idsFingerprint
        lastVisibleDistanceFingerprint = distanceFingerprint
        sendBroadcast(Intent("com.mg.wazealerts.ALERTS_UPDATED").setPackage(packageName))
    }

    private fun mergeCachedAlerts(
        fetched: List<RoadAlert>,
        existing: List<RoadAlert>,
        location: Location
    ): List<RoadAlert> {
        val byId = linkedMapOf<String, RoadAlert>()
        existing.forEach { alert ->
            val updated = alert.withDistanceFrom(location)
            if (updated.distanceMeters <= cacheRadiusMeters()) {
                byId[updated.id] = updated
            }
        }
        fetched.forEach { byId[it.id] = it.withDistanceFrom(location) }
        return byId.values
            .sortedBy { it.distanceMeters }
            .take(MAX_CACHED_ALERTS)
    }

    private fun visibleAlerts(alerts: List<RoadAlert>, location: Location): List<RoadAlert> {
        val passed = alertStore.passedAlertIds()
        return alerts
            .map { it.withDistanceFrom(location) }
            .filter { it.distanceMeters <= settings.radiusMeters }
            .filterNot { it.id in passed }
            .sortedBy { it.distanceMeters }
            .take(settings.maxVisibleAlerts)
    }

    private fun cacheRadiusMeters(): Int =
        maxOf(settings.radiusMeters * settings.cacheRadiusMultiplier, settings.cacheMinRadiusMeters)
            .coerceAtMost(settings.cacheMaxRadiusMeters)

    private fun alertCacheTtlMillis(): Long =
        settings.alertCacheTtlMinutes * 60_000L

    private fun updatePassedAlerts(alerts: List<RoadAlert>, location: Location) {
        if (!isMapsNavigating || settings.lastBearingDegrees < 0f) {
            prevDistances.keys.retainAll(alerts.map { it.id }.toSet())
            alerts.forEach { prevDistances[it.id] = it.distanceMeters }
            return
        }

        val bearing = settings.lastBearingDegrees
        for (alert in alerts) {
            val prev = prevDistances[alert.id]
            if (prev != null && alert.distanceMeters > prev + PASSED_DISTANCE_DELTA_METERS) {
                val alertLoc = Location("").apply {
                    latitude = alert.latitude
                    longitude = alert.longitude
                }
                val diff = bearingDiff(bearing, location.bearingTo(alertLoc))
                if (diff > PASSED_BEARING_DIFF_DEGREES) {
                    alertStore.markPassed(alert.id)
                    AppLogger.i(TAG, "Passed: ${alert.title} (${prev.toInt()}m -> ${alert.distanceMeters.toInt()}m, diff=${diff.toInt()}deg)")
                }
            }
            prevDistances[alert.id] = alert.distanceMeters
        }
        prevDistances.keys.retainAll(alerts.map { it.id }.toSet())
    }

    private fun syncAlertNotifications(alerts: List<RoadAlert>, location: Location) {
        if (!settings.notificationsEnabled || !isMapsNavigating) {
            cancelAlertNotifications()
            return
        }

        val passed = alertStore.passedAlertIds()
        val current = alerts
            .filterNot { alertStore.isMuted(it.id) || it.id in passed }
            .take(MAX_VISIBLE_CAR_NOTIFICATIONS)
        val currentIds = current.map { it.id }.toSet()
        notifiedIds.filterNot { it in currentIds }.toList().forEach { id ->
            cancelAlertNotification(id)
            notifiedIds.remove(id)
        }

        current.forEach { alert ->
            val isNew = notifiedIds.add(alert.id)
            showAlert(alert, location)
            if (isNew) {
                AppLogger.i(TAG, "Notifying: ${alert.title} at ${alert.distanceMeters.toInt()}m")
            }
        }
        while (notifiedIds.size > MAX_NOTIFIED_IDS) {
            val id = notifiedIds.first()
            cancelAlertNotification(id)
            notifiedIds.remove(id)
        }
    }

    private fun RoadAlert.withDistanceFrom(location: Location): RoadAlert {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, latitude, longitude, result)
        return copy(distanceMeters = result[0])
    }

    private fun geocodeCurrentPosition(location: Location) {
        val last = lastGeocodedLocation
        if (last != null && last.distanceTo(location) < 150f) return
        lastGeocodedLocation = location
        scope.launch {
            runCatching {
                @Suppress("DEPRECATION")
                val geocoder = android.location.Geocoder(this@AlertMonitorService, java.util.Locale.getDefault())
                val addrs = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val line = addrs?.firstOrNull()?.getAddressLine(0)
                if (!line.isNullOrBlank() && line != settings.currentLocationAddress) {
                    settings.currentLocationAddress = line
                    broadcastVisibleAlerts(alertStore.activeAlerts())
                    AppLogger.d(TAG, "Current address: $line")
                }
            }.onFailure { AppLogger.w(TAG, "Geocode current pos failed: ${it.message}") }
        }
    }

    private fun bearingDiff(a: Float, b: Float): Float {
        var d = ((b - a + 360f) % 360f)
        if (d > 180f) d = 360f - d
        return d
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Alert monitoring active")
            .setContentText("Watching for selected road alerts within ${settings.radiusMeters} m")
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showAlert(alert: RoadAlert, location: Location) {
        val directionLine = directionDistanceLine(alert, location)
        val title = "$directionLine - ${alert.title}"
        val text = alert.addressLine()
        val carAppExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(wazeIntent(alert))
            .build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$directionLine\n$text\n${alert.description}"))
            .setContentIntent(wazeIntent(alert))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .extend(carAppExtender)
        CarNotificationManager.from(this).notify(alert.id.hashCode(), builder)
    }

    private fun cancelAlertNotifications() {
        notifiedIds.toList().forEach { cancelAlertNotification(it) }
        notifiedIds.clear()
    }

    private fun cancelAlertNotification(alertId: String) {
        val notificationId = alertId.hashCode()
        runCatching { CarNotificationManager.from(this).cancel(notificationId) }
        getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITORING, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Road alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun wazeIntent(alert: RoadAlert): PendingIntent {
        val uri = Uri.parse(
            "https://waze.com/ul?ll=${alert.latitude},${alert.longitude}&navigate=yes&z=10&utm_source=$packageName"
        )
        return PendingIntent.getActivity(
            this,
            alert.id.hashCode(),
            Intent(Intent.ACTION_VIEW, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun RoadAlert.addressLine(): String =
        address?.takeIf { it.isNotBlank() } ?: "%.5f, %.5f".format(Locale.US, latitude, longitude)

    private fun directionDistanceLine(alert: RoadAlert, location: Location): String {
        val target = Location("").apply {
            latitude = alert.latitude
            longitude = alert.longitude
        }
        val distance = location.distanceTo(target)
        val bearingToAlert = location.bearingTo(target)
        val heading = if (location.hasBearing()) location.bearing else settings.lastBearingDegrees
        return if (heading >= 0f) {
            val relative = normalizeDegrees(bearingToAlert - heading)
            "${relativeArrow(relative)} ${relativeLabel(relative)} ${formatDistance(distance)}"
        } else {
            "${compassArrow(bearingToAlert)} ${compassLabel(bearingToAlert)} ${formatDistance(distance)}"
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

    companion object {
        private const val TAG = "AlertMonitor"
        private const val ONGOING_NOTIFICATION_ID = 4100
        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_ALERTS = "road_alerts"
        private const val LIVE_DISTANCE_INTERVAL_MILLIS = 2_000L
        private const val LIVE_DISTANCE_MIN_INTERVAL_MILLIS = 1_000L
        private const val LIVE_DISTANCE_MIN_MOVE_METERS = 5f
        private const val PASSED_DISTANCE_DELTA_METERS = 250f
        private const val PASSED_BEARING_DIFF_DEGREES = 100f
        private const val MAX_NOTIFIED_IDS = 100
        private const val MAX_VISIBLE_CAR_NOTIFICATIONS = 3
        private const val MAX_CACHED_ALERTS = 200
        private const val DISTANCE_UI_BUCKET_METERS = 100f
        private const val MIN_UI_BROADCAST_INTERVAL_MILLIS = 12_000L
        private const val MAX_UI_BROADCAST_INTERVAL_MILLIS = 30_000L
    }
}
