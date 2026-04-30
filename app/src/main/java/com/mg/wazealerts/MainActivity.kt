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
            background = backgroundGradient()
        }
        val scroll = ScrollView(this).apply {
            background = backgroundGradient()
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
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 15.dp, 16.dp, 16.dp)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.accent, if (palette.dark) 0xFF234A84.toInt() else 0xFF4CA6D8.toInt())
            ).apply { cornerRadius = 8.dp.toFloat() }

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Traffic Alerts", 25f, 0xFFFFFFFF.toInt(), bold = true))
                addView(text("Live road intelligence", 13f, 0xDDEFFFFF.toInt()))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(Button(this@MainActivity).apply {
                text = "Log"
                palette.styleButton(this, compact = true)
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0x24FFFFFF, 0x40FFFFFF)
                setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 8.dp, 0)
            })
            top.addView(Button(this@MainActivity).apply {
                text = "Settings"
                palette.styleButton(this, compact = true)
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0x24FFFFFF, 0x40FFFFFF)
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
            addView(top)
            addView(statusStrip(), blockParams(top = 14.dp))
        }, blockParams(bottom = 12.dp))
    }

    private fun statusStrip(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        row.addView(chip("Radius ${formatRadius(settings.radiusMeters)}", 0x22FFFFFF, 0xFFFFFFFF.toInt()))
        val refreshLabel = if (secondsToRefresh > 0) "${secondsToRefresh}s to next" else formatRefresh(settings.pollIntervalMillis)
        row.addView(chip(refreshLabel, 0x22FFFFFF, 0xFFFFFFFF.toInt()))
        row.addView(chip("${activeAlerts.size} active", 0x22FFFFFF, 0xFFFFFFFF.toInt()))
        if (settings.mapsNavigationActive) {
            val nearest = displayAlerts().minByOrNull { it.distanceMeters }
            if (nearest != null) row.addView(chip("Nearest ${formatDistance(nearest.distanceMeters)}", 0x33FFCA5C, 0xFFFFFFFF.toInt()))
        }
        return row
    }

    private fun controlsPanel() {
        root.addView(panel {
            addView(sectionHeader("Controls", "Tune the scan area and update cadence."))
            val metrics = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            metrics.addView(metricTile("Scan", formatRadius(settings.radiusMeters), "radius"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8.dp, 0)
            })
            metrics.addView(metricTile("Refresh", formatRefresh(settings.pollIntervalMillis), "cadence"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(metrics, blockParams(top = 4.dp, bottom = 8.dp))
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
            text = "Refresh now"
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
            setPadding(15.dp, 13.dp, 15.dp, 13.dp)
            background = rounded(if (muted) palette.mutedSurface else palette.surface, palette.border)

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(kindBadge(alert.kind.label), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 10.dp, 0)
            })
            top.addView(text(alert.title, 17f, palette.title, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(chip(directionDistanceLine(alert), if (alert.distanceMeters <= 500f) 0x22D77474 else palette.accentSoft, if (alert.distanceMeters <= 500f) palette.danger else palette.title))
            addView(top)

            addView(text(alert.address ?: coordinateLabel(alert), 13f, palette.body), blockParams(top = 9.dp))
            addView(text(alert.description, 12f, palette.secondary), blockParams(top = 5.dp))
            addView(text(if (muted) "Notifications muted" else "Notifications on", 12f, if (muted) palette.danger else palette.success), blockParams(top = 7.dp))

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
                val nextSeconds = (millisUntilFinished / 1000).toInt()
                val shouldRender = secondsToRefresh == 0 ||
                    nextSeconds % 10 == 0 ||
                    nextSeconds <= 5
                secondsToRefresh = nextSeconds
                if (shouldRender) {
                    // Post to avoid calling setContentView() during an in-flight layout traversal
                    window.decorView.post { if (!isFinishing) render() }
                }
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
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(if (palette.dark) 0xFF1A312B.toInt() else 0xFFE9F8F2.toInt(), palette.panel)
            ).apply {
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, palette.border)
            }
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val navChip = chip("Navigating", palette.accent, 0xFFFFFFFF.toInt())
            titleRow.addView(navChip)
            val bearing = settings.lastBearingDegrees
            if (bearing >= 0f) {
                titleRow.addView(chip(bearingToDirection(bearing), palette.accentSoft, palette.title), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8.dp, 0, 0, 0)
                })
            }
            addView(titleRow)

            val address = settings.currentLocationAddress
            if (address.isNotBlank()) {
                addView(text("📍 $address", 13f, palette.body), blockParams(top = 6.dp))
            }

            val destination = settings.navDestination
            if (destination.isNotBlank()) {
                addView(text("🏁 $destination", 13f, palette.accent, bold = true), blockParams(top = 4.dp))
            }

            val step = settings.navStepText
            val sub = settings.navSubText
            if (step.isNotBlank()) {
                val line = if (sub.isNotBlank()) "$step  ·  $sub" else step
                addView(text("▸ $line", 13f, palette.body), blockParams(top = 4.dp))
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

    private fun directionDistanceLine(alert: RoadAlert): String {
        val current = currentLocation() ?: return formatDistance(alert.distanceMeters)
        val target = Location("alert").apply {
            latitude = alert.latitude
            longitude = alert.longitude
        }
        val bearingToAlert = current.bearingTo(target)
        val heading = settings.lastBearingDegrees
        val distance = current.distanceTo(target)
        return if (heading >= 0f) {
            val relative = normalizeDegrees(bearingToAlert - heading)
            "${relativeArrow(relative)} ${formatDistance(distance)}"
        } else {
            "${compassArrow(bearingToAlert)} ${formatDistance(distance)}"
        }
    }

    private fun currentLocation(): Location? {
        val lat = settings.lastLatitude.toDouble()
        val lon = settings.lastLongitude.toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return Location("device").apply {
            latitude = lat
            longitude = lon
        }
    }

    private fun normalizeDegrees(degrees: Float): Float = (degrees + 360f) % 360f

    private fun relativeArrow(degrees: Float): String = when {
        degrees < 22.5f || degrees >= 337.5f -> "↑"
        degrees < 67.5f -> "↗"
        degrees < 112.5f -> "→"
        degrees < 157.5f -> "↘"
        degrees < 202.5f -> "↓"
        degrees < 247.5f -> "↙"
        degrees < 292.5f -> "←"
        else -> "↖"
    }

    private fun compassArrow(degrees: Float): String = relativeArrow(normalizeDegrees(degrees))

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
            setPadding(2.dp, 14.dp, 2.dp, 7.dp)
            addView(text(title, 18f, palette.title, bold = true))
            addView(text(subtitle, 13f, palette.secondary), blockParams(top = 3.dp))
        }

    private fun panel(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15.dp, 12.dp, 15.dp, 15.dp)
            background = rounded(palette.panel, palette.border)
            content()
        }.also {
            it.layoutParams = blockParams(top = 12.dp, bottom = 10.dp)
        }

    private fun emptyState(): TextView =
        text("No active alerts loaded.", 14f, palette.secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, 26.dp, 0, 26.dp)
            background = rounded(palette.panel, palette.border)
        }

    private fun chip(label: String, fill: Int = palette.accentSoft, textColor: Int = palette.title): TextView =
        text(label, 12f, palette.title, bold = true).apply {
            setTextColor(textColor)
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            background = rounded(fill, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 8.dp, 0)
            }
        }

    private fun kindBadge(label: String): TextView =
        text(label.take(1).uppercase(Locale.US), 12f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = rounded(palette.accent, 0)
            layoutParams = LinearLayout.LayoutParams(30.dp, 30.dp)
        }

    private fun metricTile(label: String, value: String, caption: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 11.dp)
            background = rounded(if (palette.dark) 0xFF182824.toInt() else 0xFFF6FAF8.toInt(), palette.border)
            addView(text(label, 11f, palette.secondary, bold = true))
            addView(text(value, 18f, palette.title, bold = true), blockParams(top = 4.dp))
            addView(text(caption, 11f, palette.secondary), blockParams(top = 3.dp))
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

    private fun backgroundGradient(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.backgroundAlt, palette.background)
        )

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
