package com.mg.wazealerts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mg.wazealerts.settings.AppSettings
import com.mg.wazealerts.ui.UiPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : Activity() {

    private lateinit var palette: UiPalette
    private lateinit var settings: AppSettings
    private lateinit var root: LinearLayout
    private lateinit var logContainer: LinearLayout
    private lateinit var countView: TextView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderEntries()
            refreshHandler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        palette = UiPalette.from(this, settings.themeMode)
        palette.applyWindow(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        renderEntries()
        refreshHandler.postDelayed(refreshRunnable, 3_000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
            addView(root)
        }
        setContentView(scroll)
        applyInsets(scroll)

        // Title row
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8.dp)
        }
        titleRow.addView(
            text("App Log", 20f, palette.title, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(btnSmall("Clear") {
            AppLogger.clear()
            renderEntries()
        })
        titleRow.addView(btnSmall("Copy") { copyToClipboard() }, marginStart(8.dp))
        titleRow.addView(btnSmall("Export") { exportFile() }, marginStart(8.dp))
        root.addView(titleRow)

        countView = text("", 12f, palette.secondary)
        root.addView(countView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 8.dp)
        })

        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(logContainer)
    }

    private fun renderEntries() {
        logContainer.removeAllViews()
        val all = AppLogger.all()
        val entries = all.reversed().take(300)
        countView.text = "${all.size} entries (showing ${entries.size}), newest first"

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        entries.forEach { entry ->
            val time = fmt.format(Date(entry.timeMs))
            val levelColor = when (entry.level) {
                'E' -> palette.danger
                'W' -> 0xFFFF8800.toInt()
                'I' -> palette.accent
                else -> palette.secondary
            }
            val tv = TextView(this).apply {
                text = "$time [${entry.level}] ${entry.tag}: ${entry.message}"
                setTextColor(levelColor)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(0, 2.dp, 0, 2.dp)
            }
            logContainer.addView(tv)
        }

        if (entries.isEmpty()) {
            logContainer.addView(text("No log entries yet.", 14f, palette.secondary).apply {
                gravity = Gravity.CENTER
                setPadding(0, 24.dp, 0, 24.dp)
            })
        }
    }

    private fun copyToClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Traffic Alerts Log", AppLogger.formatted()))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun exportFile() {
        val uri = AppLogger.exportToFile(this)
        if (uri == null) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export log"))
    }

    private fun btnSmall(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            palette.styleButton(this, compact = true)
            setOnClickListener { onClick() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            palette.styleText(this, color, size, bold)
        }

    private fun marginStart(px: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(px, 0, 0, 0)
        }

    private fun applyInsets(scroll: ScrollView) {
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(16.dp, bars.top + 16.dp, 16.dp, bars.bottom + 24.dp)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
