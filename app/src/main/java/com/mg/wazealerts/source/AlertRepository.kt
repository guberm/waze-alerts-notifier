package com.mg.wazealerts.source

import android.content.Context
import android.location.Location
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings

class AlertRepository(context: Context) {
    private val appContext = context.applicationContext
    private val demoProvider = DemoAlertProvider()
    private val wazeProvider = WazeOfficialAlertProvider()

    suspend fun nearby(location: Location): List<RoadAlert> {
        val settings = AppSettings(appContext)
        return (demoProvider.alertsNear(location, settings) + wazeProvider.alertsNear(location, settings))
            .filter { it.kind in settings.enabledKinds() }
            .sortedBy { it.distanceMeters }
    }
}
