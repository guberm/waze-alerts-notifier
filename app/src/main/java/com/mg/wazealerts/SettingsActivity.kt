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
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.background)
            addView(root)
        }
        setContentView(scroll)
        applySystemBarPadding(scroll)

        header()
        appearancePanel()
        sourcesPanel()
        behaviorPanel()
        alertTypesPanel()
        permissionPanel()
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Settings", 24f, palette.title, bold = true))
            addView(text("Preferences for monitoring and alerts", 13f, palette.secondary), blockParams(top = 3.dp))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Done"
            palette.styleButton(this, compact = true)
            setOnClickListener { finish() }
        })
        root.addView(row)
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

    private fun sectionHeader(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 17f, palette.title, bold = true))
            addView(text(subtitle, 12.5f, palette.secondary), blockParams(top = 3.dp))
        }

    private fun panel(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
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
}
