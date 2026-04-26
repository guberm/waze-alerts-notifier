package com.mg.wazealerts.source

import android.location.Location
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos

class WazeLiveMapAlertProvider : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings): List<RoadAlert> {
        if (!settings.wazeLiveMapEnabled) return emptyList()

        return runCatching {
            val bbox = boundingBox(location.latitude, location.longitude, settings.radiusMeters)
            val url = URL(
                "https://www.waze.com/live-map/api/georss" +
                    "?top=${bbox.top}" +
                    "&bottom=${bbox.bottom}" +
                    "&left=${bbox.left}" +
                    "&right=${bbox.right}" +
                    "&env=${environmentFor(location.latitude, location.longitude)}" +
                    "&types=alerts"
            )
            fetch(url).toAlerts(location, settings.radiusMeters)
        }.getOrElse {
            emptyList()
        }
    }

    private fun fetch(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Referer", "https://www.waze.com/live-map/directions")
            setRequestProperty("Origin", "https://www.waze.com")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Waze Live Map returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toAlerts(origin: Location, radiusMeters: Int): List<RoadAlert> {
        val alerts = optJSONArray("alerts") ?: return emptyList()
        val result = mutableListOf<RoadAlert>()
        for (index in 0 until alerts.length()) {
            val item = alerts.optJSONObject(index) ?: continue
            val alert = item.toRoadAlert(origin, radiusMeters) ?: continue
            result += alert
        }
        return result
    }

    private fun JSONObject.toRoadAlert(origin: Location, radiusMeters: Int): RoadAlert? {
        val type = optString("type", "UNKNOWN")
        val subtype = optString("subtype", "")
        val kind = kindFor(type, subtype) ?: return null
        val locationJson = optJSONObject("location") ?: return null
        val latitude = locationJson.optDouble("y", Double.NaN)
        val longitude = locationJson.optDouble("x", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        val alertLocation = Location("waze-live-map").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        val distance = origin.distanceTo(alertLocation)
        if (distance > radiusMeters) return null

        val street = optString("street", "").takeIf { it.isNotBlank() }
        val city = optString("city", "").takeIf { it.isNotBlank() }
        val address = listOfNotNull(street, city).joinToString(", ").ifBlank { null }
        val id = optString("uuid", "")
            .ifBlank { optString("id", "") }
            .ifBlank { "waze:${type}:${subtype}:${latitude.formatCoord()}:${longitude.formatCoord()}" }

        return RoadAlert(
            id = "waze:$id",
            kind = kind,
            title = titleFor(kind, subtype),
            description = descriptionFor(type, subtype, optInt("reliability", -1), optInt("confidence", -1)),
            address = address,
            latitude = latitude,
            longitude = longitude,
            distanceMeters = distance,
            reportedAtMillis = optLong("pubMillis", System.currentTimeMillis())
        )
    }

    private fun kindFor(type: String, subtype: String): AlertKind? =
        when (type.uppercase(Locale.US)) {
            "POLICE" -> AlertKind.POLICE
            "CAMERA" -> AlertKind.CAMERA
            "ACCIDENT" -> AlertKind.ACCIDENT
            "JAM" -> AlertKind.TRAFFIC
            "ROAD_CLOSED" -> AlertKind.ROADWORK
            "HAZARD" -> if (subtype.uppercase(Locale.US).contains("CONSTRUCTION")) AlertKind.ROADWORK else AlertKind.HAZARD
            else -> null
        }

    private fun titleFor(kind: AlertKind, subtype: String): String {
        val subtypeLabel = subtype
            .removePrefix("${kind.name}_")
            .replace('_', ' ')
            .lowercase(Locale.US)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        return if (subtypeLabel.isBlank()) "Waze ${kind.label}" else "Waze ${kind.label}: $subtypeLabel"
    }

    private fun descriptionFor(type: String, subtype: String, reliability: Int, confidence: Int): String =
        buildString {
            append("Waze Live Map report")
            append(" ($type")
            if (subtype.isNotBlank()) append(" / $subtype")
            append(")")
            if (reliability >= 0) append(", reliability $reliability")
            if (confidence >= 0) append(", confidence $confidence")
        }

    private fun boundingBox(latitude: Double, longitude: Double, radiusMeters: Int): BoundingBox {
        val latDelta = radiusMeters / METERS_PER_DEGREE_LAT
        val lonDelta = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return BoundingBox(
            top = latitude + latDelta,
            bottom = latitude - latDelta,
            left = longitude - lonDelta,
            right = longitude + lonDelta
        )
    }

    private fun environmentFor(latitude: Double, longitude: Double): String =
        when {
            latitude in 29.0..34.0 && longitude in 34.0..36.5 -> "il"
            longitude in -170.0..-30.0 && latitude in -60.0..85.0 -> "na"
            else -> "row"
        }

    private fun Double.formatCoord(): String = "%.5f".format(Locale.US, this)

    private data class BoundingBox(
        val top: Double,
        val bottom: Double,
        val left: Double,
        val right: Double
    )

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
    }
}
