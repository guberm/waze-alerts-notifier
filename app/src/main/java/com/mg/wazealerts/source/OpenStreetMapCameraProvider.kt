package com.mg.wazealerts.source

import android.content.Context
import android.location.Location
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos

class OpenStreetMapCameraProvider(private val context: Context) : AlertProvider {
    override suspend fun alertsNear(location: Location, settings: AppSettings): List<RoadAlert> {
        if (!settings.osmCamerasEnabled) return emptyList()

        val cellKey = cellKey(location.latitude, location.longitude)
        val cached = loadCache(cellKey)
        if (cached != null) {
            AppLogger.d("OSM", "Cache hit for cell $cellKey — ${cached.size} cameras")
            return filterByRadius(cached, location, settings.radiusMeters)
        }

        AppLogger.i("OSM", "Cache miss for cell $cellKey — fetching Overpass")
        return runCatching {
            // Always fetch at MAX_RADIUS so the cached result covers all future radius changes
            val bbox = boundingBox(location.latitude, location.longitude, MAX_RADIUS_METERS)
            val query = overpassQuery(bbox)
            val body = fetch(query)
            val allCameras = body.toAlerts(location, MAX_RADIUS_METERS)
            saveCache(cellKey, allCameras)
            AppLogger.i("OSM", "Cached ${allCameras.size} cameras for cell $cellKey")
            filterByRadius(allCameras, location, settings.radiusMeters)
        }.getOrElse {
            AppLogger.w("OSM", "Overpass fetch failed: ${it.message}")
            emptyList()
        }
    }

    // Cell ≈ 0.1° = ~11 km — if you stay in the cell, use cache; covers full 25 km fetch area
    private fun cellKey(lat: Double, lon: Double): String {
        val cellLat = kotlin.math.floor(lat * 10) / 10.0
        val cellLon = kotlin.math.floor(lon * 10) / 10.0
        return "%.1f_%.1f".format(Locale.US, cellLat, cellLon)
    }

    private fun filterByRadius(cameras: List<RoadAlert>, location: Location, radiusMeters: Int): List<RoadAlert> {
        val origin = Location("filter").apply { latitude = location.latitude; longitude = location.longitude }
        return cameras.mapNotNull { alert ->
            val alertLoc = Location("alert").apply { latitude = alert.latitude; longitude = alert.longitude }
            val dist = origin.distanceTo(alertLoc)
            if (dist <= radiusMeters) alert.copy(distanceMeters = dist) else null
        }
    }

    private fun loadCache(cellKey: String): List<RoadAlert>? {
        val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong("${cellKey}_t", 0L)
        if (System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS) return null
        val json = prefs.getString("${cellKey}_d", null) ?: return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toCachedAlert() }
        }.getOrNull()
    }

    private fun saveCache(cellKey: String, cameras: List<RoadAlert>) {
        val arr = JSONArray()
        cameras.forEach { arr.put(it.toCacheJson()) }
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit()
            .putLong("${cellKey}_t", System.currentTimeMillis())
            .putString("${cellKey}_d", arr.toString())
            .apply()
    }

    private fun RoadAlert.toCacheJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", description)
        .put("address", address ?: "")
        .put("lat", latitude)
        .put("lon", longitude)

    private fun JSONObject.toCachedAlert(): RoadAlert = RoadAlert(
        id = getString("id"),
        kind = AlertKind.CAMERA,
        title = getString("title"),
        description = getString("description"),
        address = optString("address").takeIf { it.isNotBlank() },
        latitude = getDouble("lat"),
        longitude = getDouble("lon"),
        distanceMeters = 0f,
        reportedAtMillis = System.currentTimeMillis()
    )

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
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L   // 24 hours
        private const val PREFS_CACHE = "osm_camera_cache"
    }
}
