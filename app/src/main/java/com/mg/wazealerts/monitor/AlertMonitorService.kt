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

class AlertMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var settings: AppSettings
    private lateinit var repository: AlertRepository
    private lateinit var alertStore: AlertStore
    private val notifiedIds = linkedSetOf<String>()
    private var isMapsNavigating = false

    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isMapsNavigating = intent?.getBooleanExtra("isNavigating", false) ?: false
            settings.mapsNavigationActive = isMapsNavigating
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { checkAlerts(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
            stopSelf()
            return START_NOT_STICKY
        }

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

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, settings.pollIntervalMillis)
            .setMinUpdateIntervalMillis(settings.pollIntervalMillis / 2)
            .setMinUpdateDistanceMeters(100f)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun checkAlerts(location: Location) {
        scope.launch {
            val alerts = repository.nearby(location)
            alertStore.saveActiveAlerts(alerts)
            sendBroadcast(Intent("com.mg.wazealerts.ALERTS_UPDATED").setPackage(packageName))
            if (!settings.notificationsEnabled) return@launch
            // Google Maps does not expose route geometry to third-party apps; during navigation,
            // monitor the live device position and surface native alerts around the current path.
            if (isMapsNavigating) {
                alerts.filterNot { it.id in notifiedIds || alertStore.isMuted(it.id) }.forEach { alert ->
                    notifiedIds += alert.id
                    showAlert(alert)
                }
            }
            while (notifiedIds.size > 100) {
                notifiedIds.remove(notifiedIds.first())
            }
        }
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

    private fun showAlert(alert: RoadAlert) {
        val carAppExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.addressLine())
            .setStyle(NotificationCompat.BigTextStyle().bigText("${alert.addressLine()}\n${alert.description}"))
            .setContentIntent(wazeIntent(alert))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .extend(carAppExtender)
            .build()
        getSystemService(NotificationManager::class.java).notify(alert.id.hashCode(), notification)
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
        address?.takeIf { it.isNotBlank() } ?: "%.5f, %.5f".format(java.util.Locale.US, latitude, longitude)

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 4100
        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_ALERTS = "road_alerts"
    }
}
