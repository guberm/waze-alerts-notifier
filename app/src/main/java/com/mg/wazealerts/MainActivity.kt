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
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mg.wazealerts.model.AlertKind
import com.mg.wazealerts.monitor.AlertMonitorService
import com.mg.wazealerts.settings.AppSettings

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        render()
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 36)
        }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        title("Waze Alerts Notifier")
        body("Nearby road alerts with radius filters, background monitoring, notifications, and Android Auto template support.")
        body("Official Waze docs do not expose a public nearby-alert read API. Demo alerts are enabled so the app can be tested until a partner source is connected.")

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

        radiusControl()

        title("Alert types")
        AlertKind.entries.forEach { kind ->
            addSwitch(kind.label, settings.isKindEnabled(kind)) {
                settings.setKindEnabled(kind, it)
            }
        }

        val permissionButton = Button(this).apply {
            text = "Grant permissions"
            setOnClickListener { requestNeededPermissions() }
        }
        root.addView(permissionButton)

        val appSettingsButton = Button(this).apply {
            text = "Open system settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
        }
        root.addView(appSettingsButton)
    }

    private fun radiusControl() {
        title("Radius")
        val label = body("${settings.radiusMeters} m")
        val seek = SeekBar(this).apply {
            max = 49_750
            progress = settings.radiusMeters - 250
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val radius = progress + 250
                    settings.radiusMeters = radius
                    label.text = "$radius m"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(seek)
    }

    private fun addSwitch(text: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val view = Switch(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = checked
            setPadding(0, 10, 0, 10)
            setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean -> onChanged(enabled) }
        }
        root.addView(view)
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(0xFF12332F.toInt())
            setPadding(0, 22, 0, 8)
        })
    }

    private fun body(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF35433F.toInt())
            setPadding(0, 4, 0, 12)
            root.addView(this)
        }
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
}
