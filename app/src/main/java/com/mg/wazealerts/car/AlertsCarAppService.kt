package com.mg.wazealerts.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.store.AlertStore

class AlertsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        AppLogger.i("CarService", "onCreateSession display=${sessionInfo.displayType}")
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                AppLogger.i("CarService", "onCreateScreen")
                return AlertsCarScreen(carContext)
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
        alerts = alertStore.activeAlerts().filterNot { it.id in passed }
    }

    private fun safeInvalidate() {
        runCatching { invalidate() }.onFailure {
            AppLogger.e("CarScreen", "invalidate failed: ${it.message}")
        }
    }

    private fun buildTemplate(): Template {
        val listBuilder = ItemList.Builder()
        when {
            !settings.monitoringEnabled ->
                listBuilder.addItem(
                    Row.Builder().setTitle("Monitoring is off")
                        .addText("Enable in phone app settings").build()
                )
            alerts.isEmpty() ->
                listBuilder.addItem(
                    Row.Builder().setTitle("No alerts nearby")
                        .addText("Within ${settings.radiusMeters} m").build()
                )
            else ->
                alerts.take(6).forEach { alert ->
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(alert.title)
                            .addText(alert.address ?: "%.5f, %.5f".format(alert.latitude, alert.longitude))
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

    private fun fallbackTemplate(msg: String?): Template =
        ListTemplate.Builder()
            .setTitle("Road alerts")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder()
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
}
