package com.mg.wazealerts.source

import android.location.Location
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings

class WazeOfficialAlertProvider : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings): List<RoadAlert> {
        // Waze's public docs expose deep links for launching Waze, not a public read API for
        // nearby user-reported alerts. Replace this when a Waze partner feed/SDK contract exists.
        return emptyList()
    }
}
