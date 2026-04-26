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

class OpenStreetMapCameraProvider : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings): List<RoadAlert> {
        if (!settings.osmCamerasEnabled) return emptyList()

        return runCatching {
            val bbox = boundingBox(location.latitude, location.longitude, settings.radiusMeters)
            val query = overpassQuery(bbox)
            val body = fetch(query)
            body.toAlerts(location, settings.radiusMeters)
        }.getOrElse {
            emptyList()
        }
    }

    private fun fetch(query: String): JSONObject {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "https://overpass.kumi.systems/api/interpreter?data=$encoded",
            "https://overpass-api.de/api/interpreter?data=$encoded"
        )
        var lastError: IOException? = null

        for (url in urls) {
            val result = runCatching { fetchUrl(URL(url)) }
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull() as? IOException
        }

        throw lastError ?: IOException("Overpass request failed")
    }

    private fun fetchUrl(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WazeAlertsNotifier/0.5 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Overpass returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toAlerts(origin: Location, radiusMeters: Int): List<RoadAlert> {
        val elements = optJSONArray("elements") ?: return emptyList()
        val result = linkedMapOf<String, RoadAlert>()

        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val alert = element.toRoadAlert(origin, radiusMeters) ?: continue
            result[alert.id] = alert
        }

        return result.values.toList()
    }

    private fun JSONObject.toRoadAlert(origin: Location, radiusMeters: Int): RoadAlert? {
        val type = optString("type", "")
        val osmId = optLong("id", -1)
        val tags = optJSONObject("tags") ?: JSONObject()
        val latitude = when (type) {
            "node" -> optDouble("lat", Double.NaN)
            else -> optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
        }
        val longitude = when (type) {
            "node" -> optDouble("lon", Double.NaN)
            else -> optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
        }
        if (latitude.isNaN() || longitude.isNaN()) return null

        val cameraLocation = Location("osm-overpass").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        val distance = origin.distanceTo(cameraLocation)
        if (distance > radiusMeters) return null

        val cameraType = cameraType(tags)
        val name = tags.optString("name", "").takeIf { it.isNotBlank() }
        val ref = tags.optString("ref", "").takeIf { it.isNotBlank() }
        val maxSpeed = tags.optString("maxspeed", "").takeIf { it.isNotBlank() }
        val direction = tags.optString("direction", "").takeIf { it.isNotBlank() }
        val details = buildList {
            if (maxSpeed != null) add("limit $maxSpeed")
            if (direction != null) add("direction $direction")
            add("OpenStreetMap fixed camera")
        }.joinToString(", ")

        return RoadAlert(
            id = "osm-camera:$type:$osmId",
            kind = AlertKind.CAMERA,
            title = cameraType,
            description = details,
            address = listOfNotNull(name, ref).joinToString(", ").ifBlank { null },
            latitude = latitude,
            longitude = longitude,
            distanceMeters = distance,
            reportedAtMillis = System.currentTimeMillis()
        )
    }

    private fun cameraType(tags: JSONObject): String {
        val enforcement = tags.optString("enforcement", "").lowercase(Locale.US)
        val speedCamera = tags.optString("speed_camera", "").lowercase(Locale.US)
        val note = tags.optString("note", "").lowercase(Locale.US)
        return when {
            enforcement.contains("traffic_signals") ||
                enforcement.contains("red_light") ||
                speedCamera.contains("traffic_signals") ||
                note.contains("red light") -> "Red light camera"
            enforcement.contains("average_speed") -> "Average speed camera"
            else -> "Speed camera"
        }
    }

    private fun overpassQuery(bbox: BoundingBox): String {
        val south = bbox.bottom.formatCoord()
        val west = bbox.left.formatCoord()
        val north = bbox.top.formatCoord()
        val east = bbox.right.formatCoord()
        return """
            [out:json][timeout:10];
            (
              node["highway"="speed_camera"]($south,$west,$north,$east);
              way["highway"="speed_camera"]($south,$west,$north,$east);
              relation["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($south,$west,$north,$east);
              node["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($south,$west,$north,$east);
              way["enforcement"~"maxspeed|traffic_signals|red_light_camera|average_speed"]($south,$west,$north,$east);
            );
            out center tags 100;
        """.trimIndent()
    }

    private fun boundingBox(latitude: Double, longitude: Double, radiusMeters: Int): BoundingBox {
        val cappedRadius = radiusMeters.coerceAtMost(MAX_RADIUS_METERS)
        val latDelta = cappedRadius / METERS_PER_DEGREE_LAT
        val lonDelta = cappedRadius / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return BoundingBox(
            top = latitude + latDelta,
            bottom = latitude - latDelta,
            left = longitude - lonDelta,
            right = longitude + lonDelta
        )
    }

    private fun Double.formatCoord(): String = "%.6f".format(Locale.US, this)

    private data class BoundingBox(
        val top: Double,
        val bottom: Double,
        val left: Double,
        val right: Double
    )

    companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val MAX_RADIUS_METERS = 25_000
    }
}
