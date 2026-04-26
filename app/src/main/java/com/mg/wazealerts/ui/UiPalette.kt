package com.mg.wazealerts.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.TextView
import androidx.core.view.WindowCompat

data class UiPalette(
    val dark: Boolean,
    val background: Int,
    val panel: Int,
    val surface: Int,
    val mutedSurface: Int,
    val border: Int,
    val title: Int,
    val body: Int,
    val secondary: Int,
    val buttonBackground: Int,
    val buttonText: Int,
    val accent: Int,
    val accentSoft: Int,
    val danger: Int
) {
    fun applyWindow(activity: Activity) {
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    fun styleButton(button: Button, selected: Boolean = false, compact: Boolean = false) {
        val radius = (6 * button.resources.displayMetrics.density).toInt().toFloat()
        val horizontal = ((if (compact) 12 else 16) * button.resources.displayMetrics.density).toInt()
        val vertical = ((if (compact) 7 else 10) * button.resources.displayMetrics.density).toInt()

        button.setAllCaps(false)
        button.textSize = if (compact) 13f else 14f
        button.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        button.minHeight = 0
        button.minWidth = 0
        button.minimumHeight = 0
        button.minimumWidth = 0
        button.setPadding(horizontal, vertical, horizontal, vertical)
        button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else buttonText)
        button.backgroundTintList = null
        button.background = GradientDrawable().apply {
            setColor(if (selected) accent else buttonBackground)
            cornerRadius = radius
        }
    }

    fun styleText(view: TextView, color: Int, size: Float, bold: Boolean = false) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = false
        view.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        fun from(context: Context, mode: ThemeMode): UiPalette {
            val dark = when (mode) {
                ThemeMode.SYSTEM -> {
                    val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    mask == Configuration.UI_MODE_NIGHT_YES
                }
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            return if (dark) {
                UiPalette(
                    dark = true,
                    background = 0xFF101816.toInt(),
                    panel = 0xFF121D1A.toInt(),
                    surface = 0xFF17231F.toInt(),
                    mutedSurface = 0xFF25302D.toInt(),
                    border = 0xFF34504A.toInt(),
                    title = 0xFFE7F4EF.toInt(),
                    body = 0xFFC4D4CE.toInt(),
                    secondary = 0xFF9CB1AA.toInt(),
                    buttonBackground = 0xFF2A3834.toInt(),
                    buttonText = 0xFFE7F4EF.toInt(),
                    accent = 0xFF147A68.toInt(),
                    accentSoft = 0xFF193D36.toInt(),
                    danger = 0xFFD77474.toInt()
                )
            } else {
                UiPalette(
                    dark = false,
                    background = 0xFFFAFBFA.toInt(),
                    panel = 0xFFF2F5F3.toInt(),
                    surface = 0xFFFFFFFF.toInt(),
                    mutedSurface = 0xFFE8E8E8.toInt(),
                    border = 0xFFD6DEDB.toInt(),
                    title = 0xFF12332F.toInt(),
                    body = 0xFF35433F.toInt(),
                    secondary = 0xFF50615D.toInt(),
                    buttonBackground = 0xFFD7DAD8.toInt(),
                    buttonText = 0xFF1F2322.toInt(),
                    accent = 0xFF147A68.toInt(),
                    accentSoft = 0xFFE1F0EC.toInt(),
                    danger = 0xFF9D3838.toInt()
                )
            }
        }
    }
}
