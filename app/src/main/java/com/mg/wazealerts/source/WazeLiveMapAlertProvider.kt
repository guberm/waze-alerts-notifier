package com.mg.wazealerts.source

import android.content.Context
import android.location.Location
import com.mg.wazealerts.AppLogger
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos

class WazeLiveMapAlertProvider(context: Context) : AlertProvider {
    private val fetcher = WazeWebViewFetcher(context)
    private var sessionWarmedUp = false

    override suspend fun alertsNear(location: Location, settings: AppSettings, radiusMeters: Int): List<RoadAlert> {
        if (!settings.wazeLiveMapEnabled) return emptyList()

        val flareSolverrUrl = settings.flareSolverrUrl
        val mode = when {
            flareSolverrUrl.isNotBlank() -> "FlareSolverr"
            else -> "WebView"
        }
        AppLogger.d(TAG, "Fetching alerts via $mode")

        return runCatching {
            val bbox = boundingBox(location.latitude, location.longitude, radiusMeters)
            val urlStr = "https://www.waze.com/live-map/api/georss" +
                "?top=${bbox.top}" +
                "&bottom=${bbox.bottom}" +
                "&left=${bbox.left}" +
                "&right=${bbox.right}" +
                "&env=${environmentFor(location.latitude, location.longitude)}" +
                "&types=alerts"
            AppLogger.d(TAG, "Request URL: $urlStr")
            val json = when (mode) {
                "FlareSolverr" -> fetchViaFlareSolverr(URL(urlStr), flareSolverrUrl)
                else -> JSONObject(fetcher.fetch(urlStr))
            }
            val alerts = json.toAlerts(location, radiusMeters)
            AppLogger.i(TAG, "Got ${alerts.size} alerts via $mode (radius=${radiusMeters}m)")
            alerts
        }.getOrElse { e ->
            AppLogger.e(TAG, "Fetch failed [$mode]: ${e.javaClass.simpleName}: ${e.message}")
            when {
                mode == "FlareSolverr" && e is org.json.JSONException -> sessionWarmedUp = false
                mode == "WebView" -> fetcher.invalidate()
            }
            emptyList()
        }
    }

    fun destroy() = fetcher.destroy()

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
            AppLogger.d(TAG, "Direct HTTP response: $code")
            if (code !in 200..299) throw IOException("Waze Live Map returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun warmupFlareSolverrSession(baseUrl: String) {
        AppLogger.i(TAG, "Warming up FlareSolverr session via waze.com/live-map/")
        val payload = JSONObject().apply {
            put("cmd", "request.get")
            put("url", "https://www.waze.com/live-map/")
            put("session", SESSION_ID)
            put("maxTimeout", 60_000)
        }
        val connection = (URL("$baseUrl/v1").openConnection() as HttpURLConnection).apply {
            connectTimeout = 70_000
            readTimeout = 70_000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val code = connection.responseCode
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val status = runCatching { JSONObject(body).optString("status") }.getOrDefault("?")
            AppLogger.i(TAG, "Warmup response: HTTP $code, status=$status")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Warmup failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchViaFlareSolverr(targetUrl: URL, baseUrl: String): JSONObject {
        if (!sessionWarmedUp) {
            warmupFlareSolverrSession(baseUrl)
            sessionWarmedUp = true
        }
        val solverUrl = URL("$baseUrl/v1")
        AppLogger.d(TAG, "Posting to FlareSolverr: $solverUrl")
        val payload = JSONObject().apply {
            put("cmd", "request.get")
            put("url", targetUrl.toString())
            put("session", SESSION_ID)
            put("maxTimeout", 60_000)
        }
        val connection = (solverUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 70_000
            readTimeout = 70_000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            val code = connection.responseCode
            AppLogger.d(TAG, "FlareSolverr HTTP response: $code")
            if (code !in 200..299) throw IOException("FlareSolverr returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(body)
            val status = response.optString("status")
            AppLogger.d(TAG, "FlareSolverr status: $status, message: ${response.optString("message")}")
            if (status != "ok") throw IOException("FlareSolverr: ${response.optString("message")}")
            val solutionBody = response.getJSONObject("solution").getString("response")
            AppLogger.d(TAG, "Solution body preview: ${solutionBody.take(300)}")
            return JSONObject(solutionBody)
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
        private const val TAG = "WazeLiveMap"
        private const val SESSION_ID = "waze-session"
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
    }
}
