package com.mg.wazealerts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    private var activeAlerts: List<RoadAlert> = emptyList()
    private var statusText: String = "Refresh to load nearby alerts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        repository = AlertRepository(this)
        alertStore = AlertStore(this)
        activeAlerts = alertStore.activeAlerts()
        render()
        refreshAlerts()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        applySystemBarPadding(scroll)

        header()
        radiusControl()
        refreshTimeControl()
        activeAlertsSection()
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = "Waze Alerts"
            textSize = 28f
            setTextColor(0xFF12332F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(row)

        body("Set the search radius and refresh cadence, then review active alerts below.")
    }

    private fun radiusControl() {
        sectionTitle("Radius")
        val label = body("${settings.radiusMeters} m")
        root.addView(SeekBar(this).apply {
            max = 49_750
            progress = settings.radiusMeters - 250
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val radius = progress + 250
                    settings.radiusMeters = radius
                    label.text = "$radius m"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    refreshAlerts()
                }
            })
        })
    }

    private fun refreshTimeControl() {
        sectionTitle("Refresh Time")
        val label = body(formatRefresh(settings.pollIntervalMillis))
        root.addView(SeekBar(this).apply {
            max = REFRESH_STEPS - 1
            progress = millisToRefreshStep(settings.pollIntervalMillis)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val millis = refreshStepToMillis(progress)
                    settings.pollIntervalMillis = millis
                    label.text = formatRefresh(millis)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }

    private fun activeAlertsSection() {
        sectionTitle("Active Alerts")
        body(statusText)

        Button(this).apply {
            text = "Refresh alerts"
            setOnClickListener { refreshAlerts() }
            root.addView(this)
        }

        if (activeAlerts.isEmpty()) {
            body("No active alerts loaded.")
            return
        }

        activeAlerts.forEach { alert ->
            alertRow(alert)
        }
    }

    private fun alertRow(alert: RoadAlert) {
        val muted = alertStore.isMuted(alert.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (muted) 0xFFE8E8E8.toInt() else 0xFFFFFFFF.toInt())
                setStroke(1.dp, 0xFFD6DEDB.toInt())
                cornerRadius = 6.dp.toFloat()
            }
        }

        card.addView(TextView(this).apply {
            text = alert.title
            textSize = 18f
            setTextColor(0xFF12332F.toInt())
        })
        card.addView(TextView(this).apply {
            text = alert.address ?: coordinateLabel(alert)
            textSize = 14f
            setTextColor(0xFF35433F.toInt())
        })
        card.addView(TextView(this).apply {
            text = "${alert.distanceMeters.toInt()} m away"
            textSize = 13f
            setTextColor(0xFF50615D.toInt())
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 0)
        }
        actions.addView(Button(this).apply {
            text = "Navigate"
            setOnClickListener { openNavigation(alert) }
        })
        actions.addView(Button(this).apply {
            text = if (muted) "Unmute" else "Mute"
            setOnClickListener {
                alertStore.setMuted(alert.id, !muted)
                render()
            }
        })
        card.addView(actions)

        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8.dp, 0, 8.dp) })
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
                    statusText = "Location unavailable. Try again after the device gets a GPS fix."
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

    private fun sectionTitle(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(0xFF12332F.toInt())
            setPadding(0, 20.dp, 0, 6.dp)
        })
    }

    private fun body(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF35433F.toInt())
            setPadding(0, 4.dp, 0, 10.dp)
            root.addView(this)
        }
    }

    private fun applySystemBarPadding(scroll: ScrollView) {
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(20.dp, bars.top + 16.dp, 20.dp, bars.bottom + 24.dp)
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
