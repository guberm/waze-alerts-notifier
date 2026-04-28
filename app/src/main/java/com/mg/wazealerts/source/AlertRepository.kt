package com.mg.wazealerts.source

import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import java.io.IOException
import java.util.Locale

class AlertRepository(context: Context) {
    private val appContext = context.applicationContext
    private val demoProvider = DemoAlertProvider()
    private val wazeProvider = WazeLiveMapAlertProvider()
    private val tomTomProvider = TomTomTrafficAlertProvider()
    private val osmCameraProvider = OpenStreetMapCameraProvider(context.applicationContext)

    suspend fun nearby(location: Location): List<RoadAlert> {
        val settings = AppSettings(appContext)
        return (
            wazeProvider.alertsNear(location, settings) +
                osmCameraProvider.alertsNear(location, settings) +
                tomTomProvider.alertsNear(location, settings) +
                demoProvider.alertsNear(location, settings)
            )
            .filter { it.kind in settings.enabledKinds() }
            .sortedBy { it.distanceMeters }
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

    private fun coordinateLabel(latitude: Double, longitude: Double): String =
        "%.5f, %.5f".format(Locale.US, latitude, longitude)
}
