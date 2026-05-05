package com.mg.wazealerts.source

import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import java.io.IOException
import java.util.Locale

private const val DEDUP_RADIUS_METERS = 200f

class AlertRepository(context: Context) {
    private val appContext = context.applicationContext
    private val demoProvider = DemoAlertProvider()
    private val wazeProvider = WazeLiveMapAlertProvider(context.applicationContext)
    private val tomTomProvider = TomTomTrafficAlertProvider()
    private val osmCameraProvider = OpenStreetMapCameraProvider(context.applicationContext)

    suspend fun nearby(location: Location, radiusMeters: Int? = null): List<RoadAlert> {
        val settings = AppSettings(appContext)
        val requestedRadius = radiusMeters ?: settings.radiusMeters
        return (
            wazeProvider.alertsNear(location, settings, requestedRadius) +
                osmCameraProvider.alertsNear(location, settings, requestedRadius) +
                tomTomProvider.alertsNear(location, settings, requestedRadius) +
                demoProvider.alertsNear(location, settings, requestedRadius)
            )
            .filter { it.kind in settings.enabledKinds() }
            .sortedBy { it.distanceMeters }
            .deduplicateNearby()
            .map { it.withResolvedAddress() }
    }

    private fun RoadAlert.withResolvedAddress(): RoadAlert {
        if (!address.isNullOrBlank()) return this

        val resolved = reverseGeocode(latitude, longitude)
        return copy(address = resolved ?: coordinateLabel(latitude, longitude))
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null

        return try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            when {
                address == null -> null
                !address.getAddressLine(0).isNullOrBlank() -> address.getAddressLine(0)
                !address.thoroughfare.isNullOrBlank() -> address.thoroughfare
                !address.locality.isNullOrBlank() -> address.locality
                else -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun List<RoadAlert>.deduplicateNearby(): List<RoadAlert> {
        val kept = mutableListOf<RoadAlert>()
        for (alert in this) {
            val isDupe = kept.any { other ->
                other.kind == alert.kind && distanceBetween(alert, other) < DEDUP_RADIUS_METERS
            }
            if (!isDupe) kept.add(alert)
        }
        return kept
    }

    private fun distanceBetween(a: RoadAlert, b: RoadAlert): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    fun destroy() = wazeProvider.destroy()

    private fun coordinateLabel(latitude: Double, longitude: Double): String =
        "%.5f, %.5f".format(Locale.US, latitude, longitude)
}
