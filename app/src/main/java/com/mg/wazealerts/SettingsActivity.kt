package com.mg.wazealerts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.monitor.AlertMonitorService
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.ui.ThemeMode
import com.mg.wazealerts.ui.UiPalette

class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var root: LinearLayout
    private lateinit var palette: UiPalette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        palette = UiPalette.from(this, settings.themeMode)
        palette.applyWindow(this)
        render()
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
            addView(root)
        }
        setContentView(scroll)
        applySystemBarPadding(scroll)

        header()
        appearancePanel()
        controlsPanel()
        sourcesPanel()
        behaviorPanel()
        cachePanel()
        alertTypesPanel()
        permissionPanel()
    }

    private fun header() {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 15.dp, 16.dp, 16.dp)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.accent, if (palette.dark) 0xFF234A84.toInt() else 0xFF4CA6D8.toInt())
            ).apply { cornerRadius = 8.dp.toFloat() }
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Settings", 24f, 0xFFFFFFFF.toInt(), bold = true))
                addView(text("Monitoring and alert preferences", 13f, 0xDDEFFFFF.toInt()), blockParams(top = 3.dp))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@SettingsActivity).apply {
                text = "Done"
                palette.styleButton(this, compact = true)
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(0x24FFFFFF, 0x40FFFFFF)
                setOnClickListener { finish() }
            })
        }, blockParams(bottom = 12.dp))
    }

    private fun appearancePanel() {
        root.addView(panel {
            addView(sectionHeader("Appearance", "Follow the phone theme or choose a fixed mode."))
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ThemeMode.entries.forEach { mode ->
                row.addView(Button(this@SettingsActivity).apply {
                    text = mode.label
                    palette.styleButton(this, selected = settings.themeMode == mode, compact = true)
                    setOnClickListener {
                        settings.themeMode = mode
                        render()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 8.dp, 0)
                })
            }
            addView(row, blockParams(top = 8.dp))
        })
    }

    private fun behaviorPanel() {
        root.addView(panel {
            addView(sectionHeader("Behavior", "Control background work and notification delivery."))
            addSwitch("Background monitoring", settings.monitoringEnabled) { enabled ->
                settings.monitoringEnabled = enabled
                if (enabled) startMonitoring() else stopMonitoring()
            }
            addSwitch("Notifications", settings.notificationsEnabled) {
                settings.notificationsEnabled = it
            }
        })
    }

    private fun cachePanel() {
        root.addView(panel {
            addView(sectionHeader("Movement Cache", "Fetch wider during drives, show only current alerts."))
            addView(sliderRow(
                label = "Cache time",
                value = "${settings.alertCacheTtlMinutes} min",
                max = CACHE_TTL_STEPS_MINUTES.size - 1,
                progress = stepIndex(CACHE_TTL_STEPS_MINUTES, settings.alertCacheTtlMinutes),
                onProgress = { settings.alertCacheTtlMinutes = CACHE_TTL_STEPS_MINUTES[it] },
                valueText = { "${settings.alertCacheTtlMinutes} min" }
            ))
            addView(sliderRow(
                label = "Minimum cache radius",
                value = formatRadius(settings.cacheMinRadiusMeters),
                max = CACHE_MIN_RADIUS_STEPS.size - 1,
                progress = stepIndex(CACHE_MIN_RADIUS_STEPS, settings.cacheMinRadiusMeters),
                onProgress = {
                    settings.cacheMinRadiusMeters = CACHE_MIN_RADIUS_STEPS[it]
                    if (settings.cacheMaxRadiusMeters < settings.cacheMinRadiusMeters) {
                        settings.cacheMaxRadiusMeters = settings.cacheMinRadiusMeters
                    }
                },
                valueText = { formatRadius(settings.cacheMinRadiusMeters) }
            ))
            addView(sliderRow(
                label = "Maximum cache radius",
                value = formatRadius(settings.cacheMaxRadiusMeters),
                max = CACHE_MAX_RADIUS_STEPS.size - 1,
                progress = stepIndex(CACHE_MAX_RADIUS_STEPS, settings.cacheMaxRadiusMeters),
                onProgress = {
                    settings.cacheMaxRadiusMeters = CACHE_MAX_RADIUS_STEPS[it].coerceAtLeast(settings.cacheMinRadiusMeters)
                },
                valueText = { formatRadius(settings.cacheMaxRadiusMeters) }
            ))
            addView(sliderRow(
                label = "Radius expansion",
                value = "${settings.cacheRadiusMultiplier}x",
                max = CACHE_MULTIPLIER_STEPS.size - 1,
                progress = stepIndex(CACHE_MULTIPLIER_STEPS, settings.cacheRadiusMultiplier),
                onProgress = { settings.cacheRadiusMultiplier = CACHE_MULTIPLIER_STEPS[it] },
                valueText = { "${settings.cacheRadiusMultiplier}x" }
            ))
            addView(sliderRow(
                label = "Visible alert limit",
                value = settings.maxVisibleAlerts.toString(),
                max = VISIBLE_ALERT_STEPS.size - 1,
                progress = stepIndex(VISIBLE_ALERT_STEPS, settings.maxVisibleAlerts),
                onProgress = { settings.maxVisibleAlerts = VISIBLE_ALERT_STEPS[it] },
                valueText = { settings.maxVisibleAlerts.toString() }
            ))
        })
    }

    private fun sourcesPanel() {
        root.addView(panel {
            addView(sectionHeader("Sources", "Choose live feeds and fallbacks for active alerts."))
            addSwitch("Waze Live Map", settings.wazeLiveMapEnabled) {
                settings.wazeLiveMapEnabled = it
            }
            addSwitch("OpenStreetMap cameras", settings.osmCamerasEnabled) {
                settings.osmCamerasEnabled = it
            }
            addSwitch("Demo alert source", settings.demoAlertsEnabled) {
                settings.demoAlertsEnabled = it
            }

            addView(text("TomTom API key", 14f, palette.body), blockParams(top = 10.dp))
            val tomTomKeyField = EditText(this@SettingsActivity).apply {
                setText(settings.tomTomApiKey)
                hint = "Optional global traffic source"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine(true)
                setTextColor(palette.title)
                setHintTextColor(palette.secondary)
                background = rounded(palette.surface, palette.border)
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) settings.tomTomApiKey = text.toString()
                }
            }
            addView(tomTomKeyField, blockParams(top = 6.dp))
            addView(Button(this@SettingsActivity).apply {
                text = "Save traffic key"
                palette.styleButton(this)
                setOnClickListener {
                    settings.tomTomApiKey = tomTomKeyField.text.toString()
                    currentFocus?.clearFocus()
                }
            }, blockParams(top = 8.dp))

            addView(text("FlareSolverr URL", 14f, palette.body), blockParams(top = 14.dp))
            addView(text("Proxy server to bypass Waze bot-detection (e.g. http://your-server:8191)", 12f, palette.secondary), blockParams(top = 2.dp))
            val flareSolverrField = EditText(this@SettingsActivity).apply {
                setText(settings.flareSolverrUrl)
                hint = "http://your-server:8191"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                setTextColor(palette.title)
                setHintTextColor(palette.secondary)
                background = rounded(palette.surface, palette.border)
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) settings.flareSolverrUrl = text.toString()
                }
            }
            addView(flareSolverrField, blockParams(top = 6.dp))
            addView(Button(this@SettingsActivity).apply {
                text = "Save proxy URL"
                palette.styleButton(this)
                setOnClickListener {
                    settings.flareSolverrUrl = flareSolverrField.text.toString()
                    currentFocus?.clearFocus()
                }
            }, blockParams(top = 8.dp))
        })
    }

    private fun alertTypesPanel() {
        root.addView(panel {
            addView(sectionHeader("Alert Types", "Choose which categories appear in notifications."))
            AlertKind.entries.forEach { kind ->
                addSwitch(kind.label, settings.isKindEnabled(kind)) {
                    settings.setKindEnabled(kind, it)
                }
            }
        })
    }

    private fun permissionPanel() {
        root.addView(panel {
            addView(sectionHeader("Permissions", "Location and notifications are required for monitoring."))
            addView(Button(this@SettingsActivity).apply {
                text = "Grant permissions"
                palette.styleButton(this)
                setOnClickListener { requestNeededPermissions() }
            }, blockParams(top = 8.dp))
            addView(Button(this@SettingsActivity).apply {
                text = "Open system settings"
                palette.styleButton(this)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                }
            }, blockParams(top = 8.dp))
            addView(Button(this@SettingsActivity).apply {
                text = "Notification Access (for Maps)"
                palette.styleButton(this)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }, blockParams(top = 8.dp))
        })
    }

    private fun LinearLayout.addSwitch(text: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 9.dp, 0, 9.dp)
            addView(text(text, 14f, palette.body), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean -> onChanged(enabled) }
            })
        })
    }

    private fun controlsPanel() {
        root.addView(panel {
            addView(sectionHeader("Controls", "Tune the scan area and update cadence."))
            val metrics = LinearLayout(this@SettingsActivity).apply {
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
                progress = stepIndex(RADIUS_STEPS, settings.radiusMeters),
                onProgress = { settings.radiusMeters = RADIUS_STEPS[it] },
                valueText = { formatRadius(settings.radiusMeters) }
            ))
            addView(sliderRow(
                label = "Refresh time",
                value = formatRefresh(settings.pollIntervalMillis),
                max = REFRESH_STEPS_MILLIS.size - 1,
                progress = stepIndexLong(REFRESH_STEPS_MILLIS, settings.pollIntervalMillis),
                onProgress = { settings.pollIntervalMillis = REFRESH_STEPS_MILLIS[it] },
                valueText = { formatRefresh(settings.pollIntervalMillis) }
            ))
        })
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

    private fun sliderRow(
        label: String,
        value: String,
        max: Int,
        progress: Int,
        onProgress: (Int) -> Unit,
        valueText: () -> String
    ): LinearLayout {
        val valueView = text(value, 14f, palette.title, bold = true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dp, 0, 0)
            val row = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(label, 14f, palette.body), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueView)
            addView(row)
            addView(SeekBar(this@SettingsActivity).apply {
                this.max = max
                this.progress = progress
                setPadding(0, 2.dp, 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onProgress(progress)
                        valueView.text = valueText()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 17f, palette.title, bold = true))
            addView(text(subtitle, 12.5f, palette.secondary), blockParams(top = 3.dp))
        }

    private fun panel(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15.dp, 13.dp, 15.dp, 14.dp)
            background = rounded(palette.panel, palette.border)
            content()
        }.also {
            it.layoutParams = blockParams(top = 12.dp)
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

    private fun requestNeededPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun startMonitoring() {
        requestNeededPermissions()
        ContextCompat.startForegroundService(this, Intent(this, AlertMonitorService::class.java))
    }

    private fun stopMonitoring() {
        stopService(Intent(this, AlertMonitorService::class.java))
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun formatRefresh(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds < 60) "${seconds}s" else "${seconds / 60} min"
    }

    private fun stepIndexLong(steps: LongArray, value: Long): Int =
        steps.indexOf(steps.minByOrNull { kotlin.math.abs(it - value) } ?: steps.first()).coerceAtLeast(0)

    private fun stepIndex(steps: IntArray, value: Int): Int =
        steps.indexOf(steps.minByOrNull { kotlin.math.abs(it - value) } ?: steps.first()).coerceAtLeast(0)

    private fun formatRadius(meters: Int): String =
        if (meters >= 1000) "${meters / 1000} km" else "$meters m"

    companion object {
        private val RADIUS_STEPS = intArrayOf(100, 200, 300, 500, 1000, 2000, 3000)
        private val REFRESH_STEPS_MILLIS = longArrayOf(30_000L, 60_000L, 120_000L, 180_000L, 300_000L)
        private val CACHE_TTL_STEPS_MINUTES = intArrayOf(5, 10, 20, 30, 60, 120)
        private val CACHE_MIN_RADIUS_STEPS = intArrayOf(3_000, 5_000, 10_000, 15_000, 25_000, 50_000)
        private val CACHE_MAX_RADIUS_STEPS = intArrayOf(5_000, 10_000, 15_000, 25_000, 50_000, 75_000, 100_000)
        private val CACHE_MULTIPLIER_STEPS = intArrayOf(1, 2, 3, 5, 8, 10)
        private val VISIBLE_ALERT_STEPS = intArrayOf(6, 12, 24, 36, 48, 60)
    }
}
