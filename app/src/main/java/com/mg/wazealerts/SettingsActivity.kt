package com.mg.wazealerts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CompoundButton
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

class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        render()
    }

    private fun render() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        applySystemBarPadding(scroll)

        title("Settings")
        body("Configure background behavior, notification delivery, demo data, and alert categories.")

        addSwitch("Background monitoring", settings.monitoringEnabled) { enabled ->
            settings.monitoringEnabled = enabled
            if (enabled) startMonitoring() else stopMonitoring()
        }

        addSwitch("Notifications", settings.notificationsEnabled) {
            settings.notificationsEnabled = it
        }

        addSwitch("Demo alert source", settings.demoAlertsEnabled) {
            settings.demoAlertsEnabled = it
        }

        title("Alert types")
        AlertKind.entries.forEach { kind ->
            addSwitch(kind.label, settings.isKindEnabled(kind)) {
                settings.setKindEnabled(kind, it)
            }
        }

        Button(this).apply {
            text = "Grant permissions"
            setOnClickListener { requestNeededPermissions() }
            root.addView(this)
        }

        Button(this).apply {
            text = "Open system settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            root.addView(this)
        }
    }

    private fun addSwitch(text: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        root.addView(Switch(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = checked
            setPadding(0, 6.dp, 0, 6.dp)
            setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean -> onChanged(enabled) }
        })
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(0xFF12332F.toInt())
            setPadding(0, 18.dp, 0, 8.dp)
        })
    }

    private fun body(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF35433F.toInt())
            setPadding(0, 4.dp, 0, 14.dp)
        })
    }

    private fun applySystemBarPadding(scroll: ScrollView) {
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(20.dp, bars.top + 16.dp, 20.dp, bars.bottom + 24.dp)
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
