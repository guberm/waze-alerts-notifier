package com.mg.wazealerts.store

import android.content.Context
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.model.RoadAlert
import org.json.JSONArray
import org.json.JSONObject

class AlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("alert_store", Context.MODE_PRIVATE)

    fun activeAlerts(): List<RoadAlert> {
        val raw = prefs.getString(KEY_ACTIVE_ALERTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toRoadAlert())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveActiveAlerts(alerts: List<RoadAlert>) {
        val array = JSONArray()
        alerts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ACTIVE_ALERTS, array.toString()).apply()
    }

    fun isMuted(alertId: String): Boolean = mutedIds().contains(alertId)

    fun setMuted(alertId: String, muted: Boolean) {
        val ids = mutedIds().toMutableSet()
        if (muted) ids += alertId else ids -= alertId
        prefs.edit().putStringSet(KEY_MUTED_ALERTS, ids).apply()
    }

    fun mutedIds(): Set<String> = prefs.getStringSet(KEY_MUTED_ALERTS, emptySet()).orEmpty()

    private fun RoadAlert.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.name)
            .put("title", title)
            .put("description", description)
            .put("address", address)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("distanceMeters", distanceMeters.toDouble())
            .put("reportedAtMillis", reportedAtMillis)

    private fun JSONObject.toRoadAlert(): RoadAlert =
        RoadAlert(
            id = getString("id"),
            kind = AlertKind.valueOf(getString("kind")),
            title = getString("title"),
            description = getString("description"),
            address = optString("address").takeIf { it.isNotBlank() && it != "null" },
            latitude = getDouble("latitude"),
            longitude = getDouble("longitude"),
            distanceMeters = getDouble("distanceMeters").toFloat(),
            reportedAtMillis = getLong("reportedAtMillis")
        )

    companion object {
        private const val KEY_ACTIVE_ALERTS = "active_alerts"
        private const val KEY_MUTED_ALERTS = "muted_alerts"
    }
}
