package com.mg.wazealerts.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.mg.wazealerts.R
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.source.AlertRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mg.wazealerts.AppLogger

class AlertsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return object : Session() {
            override fun onCreateScreen(intent: android.content.Intent): Screen =
                AlertsCarScreen(carContext)
        }
    }
}

class AlertsCarScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = AppSettings(carContext)
    private val repository = AlertRepository(carContext)
    private var alerts: List<RoadAlert> = emptyList()
    private var message: String = "Waiting for location"

    init {
        refresh()
    }

    override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (e: Exception) {
            AppLogger.e("CarScreen", "Template error: ${e.message}")
            ListTemplate.Builder()
                .setTitle("Road alerts")
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(
                    ItemList.Builder()
                        .addItem(Row.Builder().setTitle("Error").addText(e.message ?: "Unknown").build())
                        .build()
                )
                .setLoading(false)
                .build()
        }
    }

    private fun buildTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (!settings.monitoringEnabled) {
            listBuilder.addItem(Row.Builder().setTitle("Monitoring is off").addText("Enable it in phone settings").build())
        } else if (alerts.isEmpty()) {
            listBuilder.addItem(Row.Builder().setTitle("No nearby alerts").addText(message).build())
        } else {
            alerts.take(6).forEach { alert ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(alert.title)
                        .addText(alert.address ?: "${alert.latitude}, ${alert.longitude}")
                        .addText("${alert.distanceMeters.toInt()} m away")
                        .setOnClickListener { openNavigation(alert) }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Road alerts")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .setLoading(false)
            .build()
    }

    private fun refresh() {
        if (!hasLocationPermission()) {
            message = "Location permission is required"
            return
        }

        LocationServices.getFusedLocationProviderClient(carContext)
            .lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location == null) {
                    message = "Open the phone app once to get a location fix"
                    invalidate()
                    return@addOnSuccessListener
                }
                scope.launch {
                    try {
                        alerts = withContext(Dispatchers.IO) { repository.nearby(location) }
                        message = "Within ${settings.radiusMeters} m"
                        AppLogger.d("CarScreen", "Loaded ${alerts.size} alerts")
                    } catch (e: Exception) {
                        AppLogger.e("CarScreen", "Fetch failed: ${e.message}")
                    }
                    invalidate()
                }
            }
            .addOnFailureListener {
                AppLogger.e("CarScreen", "Location failed: ${it.message}")
                message = "Location unavailable"
                invalidate()
            }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun openNavigation(alert: RoadAlert) {
        val uri = Uri.parse(
            "https://waze.com/ul?ll=${alert.latitude},${alert.longitude}&navigate=yes&z=10&utm_source=${carContext.packageName}"
        )
        carContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
