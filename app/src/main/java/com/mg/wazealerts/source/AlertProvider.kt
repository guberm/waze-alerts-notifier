package com.mg.wazealerts.source

import android.location.Location
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings

interface AlertProvider {
    suspend fun alertsNear(location: Location, settings: AppSettings, radiusMeters: Int = settings.radiusMeters): List<RoadAlert>
}
