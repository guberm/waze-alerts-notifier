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

class TomTomTrafficAlertProvider : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings, radiusMeters: Int): List<RoadAlert> {
        val key = settings.tomTomApiKey
        if (key.isBlank()) return emptyList()

        return runCatching {
            val bbox = boundingBox(location.latitude, location.longitude, radiusMeters)
            val fields = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,events{description,code,iconCategory},startTime,endTime,from,to,roadNumbers,lastReportTime}}}"
            val url = URL(
                "https://api.tomtom.com/traffic/services/5/incidentDetails" +
                    "?key=${encode(key)}" +
                    "&bbox=${bbox.left},${bbox.bottom},${bbox.right},${bbox.top}" +
                    "&fields=${encode(fields)}" +
                    "&language=en-US" +
                    "&timeValidityFilter=present"
            )
            fetch(url).toAlerts(location, radiusMeters)
        }.getOrElse {
            emptyList()
        }
    }

    private fun fetch(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("TomTom returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toAlerts(origin: Location, radiusMeters: Int): List<RoadAlert> {
        val incidents = optJSONArray("incidents") ?: return emptyList()
        val result = mutableListOf<RoadAlert>()
        for (index in 0 until incidents.length()) {
            val item = incidents.optJSONObject(index) ?: continue
            val alert = item.toRoadAlert(origin, radiusMeters) ?: continue
            result += alert
        }
        return result
    }

    private fun JSONObject.toRoadAlert(origin: Location, radiusMeters: Int): RoadAlert? {
        val properties = optJSONObject("properties") ?: return null
        val geometry = optJSONObject("geometry") ?: return null
        val firstPoint = geometry.optJSONArray("coordinates") ?: return null
        val point = when {
            firstPoint.length() >= 2 && firstPoint.opt(0) is Number -> firstPoint
            firstPoint.length() > 0 -> firstPoint.optJSONArray(0)
            else -> null
        } ?: return null
        val longitude = point.optDouble(0, Double.NaN)
        val latitude = point.optDouble(1, Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        val alertLocation = Location("tomtom").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        val distance = origin.distanceTo(alertLocation)
        if (distance > radiusMeters) return null

        val iconCategory = properties.optInt("iconCategory", 0)
        val kind = kindFor(iconCategory)
        val eventDescription = properties.optJSONArray("events")
            ?.optJSONObject(0)
            ?.optString("description", "")
            ?.takeIf { it.isNotBlank() }
        val from = properties.optString("from", "").takeIf { it.isNotBlank() }
        val to = properties.optString("to", "").takeIf { it.isNotBlank() }
        val road = properties.optJSONArray("roadNumbers")?.optString(0, "")?.takeIf { it.isNotBlank() }
        val address = listOfNotNull(road, from, to).joinToString(" -> ").ifBlank { null }
        val id = properties.optString("id", "").ifBlank {
            "tomtom:${iconCategory}:${latitude.formatCoord()}:${longitude.formatCoord()}"
        }

        return RoadAlert(
            id = "tomtom:$id",
            kind = kind,
            title = "Traffic ${kind.label}",
            description = eventDescription ?: "TomTom traffic incident category $iconCategory",
            address = address,
            latitude = latitude,
            longitude = longitude,
            distanceMeters = distance,
            reportedAtMillis = parseTime(properties.optString("lastReportTime", "")) ?: System.currentTimeMillis()
        )
    }

    private fun kindFor(iconCategory: Int): AlertKind =
        when (iconCategory) {
            1 -> AlertKind.ACCIDENT
            7, 8, 9 -> AlertKind.ROADWORK
            6 -> AlertKind.TRAFFIC
            2, 3, 4, 5, 10, 11, 14 -> AlertKind.HAZARD
            else -> AlertKind.TRAFFIC
        }

    private fun parseTime(value: String): Long? =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

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

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun Double.formatCoord(): String = "%.5f".format(Locale.US, this)

    private data class BoundingBox(
        val top: Double,
        val bottom: Double,
        val left: Double,
        val right: Double
    )

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
