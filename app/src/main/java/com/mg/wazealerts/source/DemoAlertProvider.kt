package com.mg.wazealerts.source

import android.location.Location
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import kotlin.math.cos

class DemoAlertProvider : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings, radiusMeters: Int): List<RoadAlert> {
        if (!settings.demoAlertsEnabled) return emptyList()

        val enabled = settings.enabledKinds()
        val now = System.currentTimeMillis()
        return AlertKind.entries.mapIndexedNotNull { index, kind ->
            if (kind !in enabled) return@mapIndexedNotNull null

            val distance = ((index + 1) * radiusMeters / 8).coerceAtLeast(120)
            val bearingDegrees = 30.0 + index * 47.0
            val point = offset(location.latitude, location.longitude, distance.toDouble(), bearingDegrees)
            val check = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, point.first, point.second, check)
            if (check[0] > radiusMeters) return@mapIndexedNotNull null

            RoadAlert(
                id = "${kind.name}-${location.latitude.round4()}-${location.longitude.round4()}",
                kind = kind,
                title = kind.label,
                description = "${check[0].toInt()} m from your current location",
                address = null,
                latitude = point.first,
                longitude = point.second,
                distanceMeters = check[0],
                reportedAtMillis = now - index * 180_000L
            )
        }
    }

    private fun offset(lat: Double, lon: Double, meters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val earthRadius = 6_378_137.0
        val bearing = Math.toRadians(bearingDegrees)
        val dLat = meters * kotlin.math.cos(bearing) / earthRadius
        val dLon = meters * kotlin.math.sin(bearing) / (earthRadius * cos(Math.toRadians(lat)))
        return lat + Math.toDegrees(dLat) to lon + Math.toDegrees(dLon)
    }

    private fun Double.round4(): String = "%.4f".format(this)
}
