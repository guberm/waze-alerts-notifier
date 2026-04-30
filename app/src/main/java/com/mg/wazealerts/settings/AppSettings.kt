package com.mg.wazealerts.settings

import android.content.Context
import android.content.SharedPreferences
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.ui.ThemeMode

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alert_settings", Context.MODE_PRIVATE)

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING, false)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var demoAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEMO_ALERTS, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_ALERTS, value).apply()

    var wazeLiveMapEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAZE_LIVE_MAP, true)
        set(value) = prefs.edit().putBoolean(KEY_WAZE_LIVE_MAP, value).apply()

    var osmCamerasEnabled: Boolean
        get() = prefs.getBoolean(KEY_OSM_CAMERAS, true)
        set(value) = prefs.edit().putBoolean(KEY_OSM_CAMERAS, value).apply()

    var tomTomApiKey: String
        get() = prefs.getString(KEY_TOMTOM_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOMTOM_API_KEY, value.trim()).apply()

    var radiusMeters: Int
        get() = prefs.getInt(KEY_RADIUS_METERS, 3000)
        set(value) = prefs.edit().putInt(KEY_RADIUS_METERS, value.coerceIn(100, 50000)).apply()

    var pollIntervalMillis: Long
        get() = prefs.getLong(KEY_POLL_INTERVAL, 60_000L)
        set(value) = prefs.edit().putLong(KEY_POLL_INTERVAL, value.coerceIn(30_000L, 300_000L)).apply()

    var alertCacheTtlMinutes: Int
        get() = prefs.getInt(KEY_ALERT_CACHE_TTL_MINUTES, 20)
        set(value) = prefs.edit().putInt(KEY_ALERT_CACHE_TTL_MINUTES, value.coerceIn(5, 120)).apply()

    var cacheMinRadiusMeters: Int
        get() = prefs.getInt(KEY_CACHE_MIN_RADIUS_METERS, 15_000)
        set(value) = prefs.edit().putInt(KEY_CACHE_MIN_RADIUS_METERS, value.coerceIn(3_000, 50_000)).apply()

    var cacheMaxRadiusMeters: Int
        get() = prefs.getInt(KEY_CACHE_MAX_RADIUS_METERS, 50_000)
        set(value) = prefs.edit().putInt(KEY_CACHE_MAX_RADIUS_METERS, value.coerceIn(5_000, 100_000)).apply()

    var cacheRadiusMultiplier: Int
        get() = prefs.getInt(KEY_CACHE_RADIUS_MULTIPLIER, 5)
        set(value) = prefs.edit().putInt(KEY_CACHE_RADIUS_MULTIPLIER, value.coerceIn(1, 10)).apply()

    var maxVisibleAlerts: Int
        get() = prefs.getInt(KEY_MAX_VISIBLE_ALERTS, 24)
        set(value) = prefs.edit().putInt(KEY_MAX_VISIBLE_ALERTS, value.coerceIn(6, 60)).apply()

    var lastVersionCode: Int
        get() = prefs.getInt(KEY_LAST_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_VERSION_CODE, value).apply()

    var mapsNavigationActive: Boolean
        get() = prefs.getBoolean(KEY_MAPS_NAVIGATION_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_MAPS_NAVIGATION_ACTIVE, value).apply()

    var currentLocationAddress: String
        get() = prefs.getString(KEY_CURRENT_LOC_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_LOC_ADDRESS, value).apply()

    var navStepText: String
        get() = prefs.getString(KEY_NAV_STEP_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAV_STEP_TEXT, value).apply()

    var navSubText: String
        get() = prefs.getString(KEY_NAV_SUB_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAV_SUB_TEXT, value).apply()

    var navDestination: String
        get() = prefs.getString(KEY_NAV_DESTINATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAV_DESTINATION, value).apply()

    var lastBearingDegrees: Float
        get() = prefs.getFloat(KEY_LAST_BEARING, -1f)
        set(value) = prefs.edit().putFloat(KEY_LAST_BEARING, value).apply()

    var lastLatitude: Float
        get() = prefs.getFloat(KEY_LAST_LAT, 0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_LAT, value).apply()

    var lastLongitude: Float
        get() = prefs.getFloat(KEY_LAST_LON, 0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_LON, value).apply()

    var routeFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROUTE_FILTER, false)
        set(value) = prefs.edit().putBoolean(KEY_ROUTE_FILTER, value).apply()

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    fun isKindEnabled(kind: AlertKind): Boolean =
        prefs.getBoolean(kindKey(kind), true)

    fun setKindEnabled(kind: AlertKind, enabled: Boolean) {
        prefs.edit().putBoolean(kindKey(kind), enabled).apply()
    }

    fun enabledKinds(): Set<AlertKind> = AlertKind.entries.filterTo(mutableSetOf()) { isKindEnabled(it) }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun kindKey(kind: AlertKind) = "kind_${kind.name.lowercase()}"

    companion object {
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_DEMO_ALERTS = "demo_alerts_enabled"
        private const val KEY_WAZE_LIVE_MAP = "waze_live_map_enabled"
        private const val KEY_OSM_CAMERAS = "osm_cameras_enabled"
        private const val KEY_TOMTOM_API_KEY = "tomtom_api_key"
        private const val KEY_RADIUS_METERS = "radius_meters"
        private const val KEY_POLL_INTERVAL = "poll_interval_millis"
        private const val KEY_ALERT_CACHE_TTL_MINUTES = "alert_cache_ttl_minutes"
        private const val KEY_CACHE_MIN_RADIUS_METERS = "cache_min_radius_meters"
        private const val KEY_CACHE_MAX_RADIUS_METERS = "cache_max_radius_meters"
        private const val KEY_CACHE_RADIUS_MULTIPLIER = "cache_radius_multiplier"
        private const val KEY_MAX_VISIBLE_ALERTS = "max_visible_alerts"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"
        private const val KEY_MAPS_NAVIGATION_ACTIVE = "maps_navigation_active"
        private const val KEY_CURRENT_LOC_ADDRESS = "current_loc_address"
        private const val KEY_NAV_STEP_TEXT = "nav_step_text"
        private const val KEY_NAV_SUB_TEXT = "nav_sub_text"
        private const val KEY_NAV_DESTINATION = "nav_destination"
        private const val KEY_LAST_BEARING = "last_bearing"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_ROUTE_FILTER = "route_filter"
    }
}
