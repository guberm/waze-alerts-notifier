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
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: AppSettings
    private lateinit var repository: AlertRepository
    private lateinit var alertStore: AlertStore
    private lateinit var root: LinearLayout
    private lateinit var palette: UiPalette
    private var activeAlerts: List<RoadAlert> = emptyList()
    private var statusText: String = "Refresh to load nearby alerts"

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
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && ::root.isInitialized) {
            render()
        }
    }

    override fun onDestroy() {
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
        row.addView(chip(formatRefresh(settings.pollIntervalMillis)))
        row.addView(chip("${activeAlerts.size} active"))
        return row
    }

    private fun controlsPanel() {
        root.addView(panel {
            addView(sectionHeader("Controls", "Tune the scan area and update cadence."))
            addView(sliderRow(
                label = "Radius",
                value = "${settings.radiusMeters} m",
                max = 49_750,
                progress = settings.radiusMeters - 250,
                onProgress = { progress -> settings.radiusMeters = progress + 250 },
                valueText = { "${settings.radiusMeters} m" },
                onStop = { refreshAlerts() }
            ))
            addView(sliderRow(
                label = "Refresh time",
                value = formatRefresh(settings.pollIntervalMillis),
                max = REFRESH_STEPS - 1,
                progress = millisToRefreshStep(settings.pollIntervalMillis),
                onProgress = { progress -> settings.pollIntervalMillis = refreshStepToMillis(progress) },
                valueText = { formatRefresh(settings.pollIntervalMillis) },
                onStop = {}
            ))
        })
    }

    private fun alertsPanel() {
        root.addView(sectionHeader("Active Alerts", statusText))

        root.addView(Button(this).apply {
            text = "Refresh alerts"
            palette.styleButton(this)
            setOnClickListener { refreshAlerts() }
        }, blockParams(top = 6.dp, bottom = 10.dp))

        if (activeAlerts.isEmpty()) {
            root.addView(emptyState())
            return
        }

        activeAlerts.forEach { alert ->
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

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
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
                    statusText = "Showing ${activeAlerts.size} alert(s) within ${settings.radiusMeters} m."
                    render()
                }
            }
            .addOnFailureListener {
                statusText = "Location unavailable."
                render()
            }
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
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            100
        )
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

    private fun refreshStepToMillis(step: Int): Long = (step + 1) * 15_000L

    private fun millisToRefreshStep(millis: Long): Int =
        ((millis / 15_000L).toInt() - 1).coerceIn(0, REFRESH_STEPS - 1)

    private fun coordinateLabel(alert: RoadAlert): String =
        "%.5f, %.5f".format(Locale.US, alert.latitude, alert.longitude)

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_STEPS = 60
    }
}
