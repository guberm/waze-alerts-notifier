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
        get() = prefs.getBoolean(KEY_DEMO_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_ALERTS, value).apply()

    var radiusMeters: Int
        get() = prefs.getInt(KEY_RADIUS_METERS, 3000)
        set(value) = prefs.edit().putInt(KEY_RADIUS_METERS, value.coerceIn(250, 50000)).apply()

    var pollIntervalMillis: Long
        get() = prefs.getLong(KEY_POLL_INTERVAL, 60_000L)
        set(value) = prefs.edit().putLong(KEY_POLL_INTERVAL, value.coerceIn(15_000L, 15 * 60_000L)).apply()

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
        private const val KEY_RADIUS_METERS = "radius_meters"
        private const val KEY_POLL_INTERVAL = "poll_interval_millis"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
