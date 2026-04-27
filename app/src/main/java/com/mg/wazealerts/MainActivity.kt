package com.mg.wazealerts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import com.mg.wazealerts.model.RoadAlert
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.source.AlertRepository
import com.mg.wazealerts.store.AlertStore
import com.mg.wazealerts.ui.UiPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.CountDownTimer
import java.util.Locale
import android.app.AlertDialog

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AppSettings
    private lateinit var repository: AlertRepository
    private lateinit var alertStore: AlertStore
    private lateinit var root: LinearLayout
    private lateinit var palette: UiPalette
    private var activeAlerts: List<RoadAlert> = emptyList()
    private var statusText: String = "Refresh to load nearby alerts"
    private var countdownTimer: CountDownTimer? = null
    private var secondsToRefresh: Int = 0

    private val alertUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mg.wazealerts.ALERTS_UPDATED") {
                activeAlerts = alertStore.activeAlerts()
                statusText = "Showing ${activeAlerts.size} alert(s) within ${formatRadius(settings.radiusMeters)}."
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        palette = UiPalette.from(this, settings.themeMode)
        palette.applyWindow(this)
        repository = AlertRepository(this)
        alertStore = AlertStore(this)
        activeAlerts = alertStore.activeAlerts()
        render()
        refreshAlerts()
        showChangelogIfUpdated()
    }

    private fun showChangelogIfUpdated() {
        val currentVersion = BuildConfig.VERSION_CODE
        if (settings.lastVersionCode < currentVersion) {
            settings.lastVersionCode = currentVersion
            AlertDialog.Builder(this)
                .setTitle("What's new in v${BuildConfig.VERSION_NAME}")
                .setMessage("• Android Auto media integration\n• Google Maps navigation detection\n• Native notifications along the route\n• New radius slider steps\n• Live refresh timer\n• Background sync fixes")
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alertUpdateReceiver, IntentFilter("com.mg.wazealerts.ALERTS_UPDATED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alertUpdateReceiver, IntentFilter("com.mg.wazealerts.ALERTS_UPDATED"))
        }
        if (::settings.isInitialized && ::root.isInitialized) {
            render()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(alertUpdateReceiver)
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        palette = UiPalette.from(this, settings.themeMode)
        palette.applyWindow(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.background)
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        applySystemBarPadding(scroll)

        header()
        if (settings.mapsNavigationActive) navPanel()
        controlsPanel()
        alertsPanel()
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Waze Alerts", 24f, palette.title, bold = true))
            addView(text("Nearby road intelligence", 13f, palette.secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Log"
            palette.styleButton(this, compact = true)
            setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 8.dp, 0)
        })
        row.addView(Button(this).apply {
            text = "Settings"
            palette.styleButton(this, compact = true)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(row)
        root.addView(statusStrip())
    }

    private fun statusStrip(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14.dp, 0, 4.dp)
        }
        row.addView(chip("${settings.radiusMeters} m"))
        val refreshLabel = if (secondsToRefresh > 0) "${secondsToRefresh}s to next" else formatRefresh(settings.pollIntervalMillis)
        row.addView(chip(refreshLabel))
        row.addView(chip("${activeAlerts.size} active"))
        if (settings.mapsNavigationActive) {
            val nearest = displayAlerts().minByOrNull { it.distanceMeters }
            if (nearest != null) row.addView(chip("⚠ ${formatDistance(nearest.distanceMeters)}"))
        }
        return row
    }

    private fun controlsPanel() {
        root.addView(panel {
            addView(sectionHeader("Controls", "Tune the scan area and update cadence."))
            addView(sliderRow(
                label = "Radius",
                value = formatRadius(settings.radiusMeters),
                max = RADIUS_STEPS.size - 1,
                progress = RADIUS_STEPS.indexOf(RADIUS_STEPS.minByOrNull { kotlin.math.abs(it - settings.radiusMeters) } ?: RADIUS_STEPS.last()).coerceAtLeast(0),
                onProgress = { progress -> settings.radiusMeters = RADIUS_STEPS[progress] },
                valueText = { formatRadius(settings.radiusMeters) },
                onStart = { countdownTimer?.cancel() },
                onStop = { refreshAlerts() }
            ))
            addView(sliderRow(
                label = "Refresh time",
                value = formatRefresh(settings.pollIntervalMillis),
                max = REFRESH_STEPS_MILLIS.size - 1,
                progress = REFRESH_STEPS_MILLIS.indexOf(REFRESH_STEPS_MILLIS.minByOrNull { kotlin.math.abs(it - settings.pollIntervalMillis) } ?: REFRESH_STEPS_MILLIS.last()).coerceAtLeast(0),
                onProgress = { progress -> settings.pollIntervalMillis = REFRESH_STEPS_MILLIS[progress] },
                valueText = { formatRefresh(settings.pollIntervalMillis) },
                onStart = { countdownTimer?.cancel() },
                onStop = { startCountdown() }
            ))
        })
    }

    private fun alertsPanel() {
        val displayed = displayAlerts()
        val subtitle = buildString {
            append(statusText)
            if (settings.mapsNavigationActive) {
                val passedCount = alertStore.passedAlertIds().size
                if (passedCount > 0) append(" ($passedCount passed)")
                if (settings.routeFilterEnabled) append(" · ahead only")
            }
        }
        root.addView(sectionHeader("Active Alerts", subtitle))

        root.addView(Button(this).apply {
            text = "Refresh alerts"
            palette.styleButton(this)
            setOnClickListener { refreshAlerts() }
        }, blockParams(top = 6.dp, bottom = 10.dp))

        if (displayed.isEmpty()) {
            root.addView(emptyState())
            return
        }

        displayed.forEach { alert ->
            root.addView(alertCard(alert), blockParams(top = 6.dp, bottom = 8.dp))
        }
    }

    private fun alertCard(alert: RoadAlert): LinearLayout {
        val muted = alertStore.isMuted(alert.id)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = rounded(if (muted) palette.mutedSurface else palette.surface, palette.border)

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(text(alert.title, 18f, palette.title, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(chip("${alert.distanceMeters.toInt()} m"))
            addView(top)

            addView(text(alert.address ?: coordinateLabel(alert), 13f, palette.body), blockParams(top = 7.dp))
            addView(text(if (muted) "Notifications muted for this alert" else "Notifications enabled", 12f, if (muted) palette.danger else palette.secondary), blockParams(top = 5.dp))

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp, 0, 0)
            }
            actions.addView(Button(this@MainActivity).apply {
                text = "Navigate"
                palette.styleButton(this, compact = true)
                setOnClickListener { openNavigation(alert) }
            })
            actions.addView(Button(this@MainActivity).apply {
                text = if (muted) "Unmute" else "Mute"
                palette.styleButton(this, selected = muted, compact = true)
                setOnClickListener {
                    alertStore.setMuted(alert.id, !muted)
                    render()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8.dp, 0, 0, 0)
            })
            addView(actions)
        }
    }

    private fun sliderRow(
        label: String,
        value: String,
        max: Int,
        progress: Int,
        onProgress: (Int) -> Unit,
        valueText: () -> String,
        onStart: () -> Unit = {},
        onStop: () -> Unit
    ): LinearLayout {
        val valueView = text(value, 14f, palette.title, bold = true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp, 0, 0)
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(label, 14f, palette.body), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueView)
            addView(row)
            addView(SeekBar(this@MainActivity).apply {
                this.max = max
                this.progress = progress
                setPadding(0, 2.dp, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onProgress(progress)
                        valueView.text = valueText()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = onStart()
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = onStop()
                })
            })
        }
    }

    private fun refreshAlerts() {
        if (!hasLocationPermission()) {
            requestLocationPermissions()
            statusText = "Location permission is required."
            render()
            return
        }

        statusText = "Refreshing..."
        render()
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location == null) {
                    statusText = "Location unavailable. Try again after GPS gets a fix."
                    activeAlerts = alertStore.activeAlerts()
                    render()
                    return@addOnSuccessListener
                }
                scope.launch {
                    activeAlerts = withContext(Dispatchers.IO) { repository.nearby(location) }
                    alertStore.saveActiveAlerts(activeAlerts)
                    statusText = "Showing ${activeAlerts.size} alert(s) within ${formatRadius(settings.radiusMeters)}."
                    render()
                    startCountdown()
                }
            }
            .addOnFailureListener {
                statusText = "Location unavailable."
                render()
            }
    }

    private fun startCountdown() {
        countdownTimer?.cancel()
        secondsToRefresh = (settings.pollIntervalMillis / 1000).toInt()
        countdownTimer = object : CountDownTimer(settings.pollIntervalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsToRefresh = (millisUntilFinished / 1000).toInt()
                // Just update the chip without full render to avoid flickering
                // Or call render() if lightweight enough. Let's re-render
                render()
            }
            override fun onFinish() {
                secondsToRefresh = 0
                refreshAlerts()
            }
        }.start()
    }

    private fun openNavigation(alert: RoadAlert) {
        val uri = Uri.parse(
            "https://waze.com/ul?ll=${alert.latitude},${alert.longitude}&navigate=yes&z=10&utm_source=$packageName"
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun navPanel() {
        root.addView(panel {
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val navChip = chip("Navigating").apply {
                background = rounded(palette.accent, 0)
                setTextColor(0xFFFFFFFF.toInt())
            }
            titleRow.addView(navChip)
            val bearing = settings.lastBearingDegrees
            if (bearing >= 0f) {
                titleRow.addView(chip(bearingToDirection(bearing)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8.dp, 0, 0, 0)
                })
            }
            addView(titleRow)

            val address = settings.currentLocationAddress
            if (address.isNotBlank()) {
                addView(text("You: $address", 13f, palette.body), blockParams(top = 6.dp))
            }

            val step = settings.navStepText
            val sub = settings.navSubText
            if (step.isNotBlank()) {
                val line = if (sub.isNotBlank()) "$step  ·  $sub" else step
                addView(text(line, 13f, palette.body), blockParams(top = 4.dp))
            }

            val nearest = displayAlerts().minByOrNull { it.distanceMeters }
            val nearestText = if (nearest != null) "Nearest alert: ${formatDistance(nearest.distanceMeters)}" else "No alerts ahead"
            addView(text(nearestText, 14f, if (nearest != null) palette.danger else palette.secondary, bold = nearest != null), blockParams(top = 6.dp))

            val routeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp, 0, 0)
            }
            routeRow.addView(text("Show ahead only", 13f, palette.body), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            routeRow.addView(Button(this@MainActivity).apply {
                text = if (settings.routeFilterEnabled) "On" else "Off"
                palette.styleButton(this, selected = settings.routeFilterEnabled, compact = true)
                setOnClickListener {
                    settings.routeFilterEnabled = !settings.routeFilterEnabled
                    render()
                }
            })
            addView(routeRow)
        })
    }

    private fun displayAlerts(): List<RoadAlert> {
        val passed = alertStore.passedAlertIds()
        return if (settings.mapsNavigationActive) {
            activeAlerts
                .filterNot { it.id in passed }
                .let { list -> if (settings.routeFilterEnabled) list.filter { alertIsAhead(it) } else list }
        } else {
            activeAlerts
        }
    }

    private fun alertIsAhead(alert: RoadAlert): Boolean {
        val bearing = settings.lastBearingDegrees
        if (bearing < 0f) return true
        val lat = settings.lastLatitude.toDouble()
        val lon = settings.lastLongitude.toDouble()
        if (lat == 0.0 && lon == 0.0) return true
        val from = Location("device").apply { latitude = lat; longitude = lon }
        val to = Location("alert").apply { latitude = alert.latitude; longitude = alert.longitude }
        return bearingDiff(bearing, from.bearingTo(to)) <= 75f
    }

    private fun bearingDiff(a: Float, b: Float): Float {
        var d = ((b - a + 360f) % 360f)
        if (d > 180f) d = 360f - d
        return d
    }

    private fun formatDistance(meters: Float): String =
        if (meters >= 1000f) "${"%.1f".format(Locale.US, meters / 1000f)} km" else "${meters.toInt()} m"

    private fun bearingToDirection(degrees: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((degrees / 45 + 0.5f).toInt() % 8)]
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp, 0, 6.dp)
            addView(text(title, 18f, palette.title, bold = true))
            addView(text(subtitle, 13f, palette.secondary), blockParams(top = 3.dp))
        }

    private fun panel(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 14.dp)
            background = rounded(palette.panel, palette.border)
            content()
        }.also {
            it.layoutParams = blockParams(top = 12.dp, bottom = 10.dp)
        }

    private fun emptyState(): TextView =
        text("No active alerts loaded.", 14f, palette.secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, 22.dp, 0, 22.dp)
            background = rounded(palette.panel, palette.border)
        }

    private fun chip(label: String): TextView =
        text(label, 12f, palette.title, bold = true).apply {
            setPadding(9.dp, 5.dp, 9.dp, 5.dp)
            background = rounded(palette.accentSoft, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 8.dp, 0)
            }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            palette.styleText(this, color, size, bold)
        }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 8.dp.toFloat()
            if (stroke != 0) setStroke(1.dp, stroke)
        }

    private fun blockParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, top, 0, bottom)
        }

    private fun applySystemBarPadding(scroll: ScrollView) {
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(16.dp, bars.top + 16.dp, 16.dp, bars.bottom + 24.dp)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun formatRefresh(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds < 60) "${seconds}s" else "${seconds / 60} min"
    }

    private fun coordinateLabel(alert: RoadAlert): String =
        "%.5f, %.5f".format(Locale.US, alert.latitude, alert.longitude)

    private fun formatRadius(meters: Int): String =
        if (meters >= 1000) "${meters / 1000} km" else "$meters m"

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private val REFRESH_STEPS_MILLIS = longArrayOf(30_000L, 60_000L, 120_000L, 180_000L, 300_000L)
        private val RADIUS_STEPS = intArrayOf(100, 200, 300, 500, 1000, 2000, 3000)
    }
}
