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
    val backgroundAlt: Int,
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
    val success: Int,
    val warning: Int,
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
        val radius = (8 * button.resources.displayMetrics.density).toInt().toFloat()
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
            if (!selected) setStroke((1 * button.resources.displayMetrics.density).toInt(), border)
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
                    backgroundAlt = 0xFF162321.toInt(),
                    panel = 0xFF14201D.toInt(),
                    surface = 0xFF1A2925.toInt(),
                    mutedSurface = 0xFF26322F.toInt(),
                    border = 0xFF395650.toInt(),
                    title = 0xFFF0FAF7.toInt(),
                    body = 0xFFC7D8D2.toInt(),
                    secondary = 0xFFA0B6AF.toInt(),
                    buttonBackground = 0xFF22322E.toInt(),
                    buttonText = 0xFFE7F4EF.toInt(),
                    accent = 0xFF18A383.toInt(),
                    accentSoft = 0xFF183F38.toInt(),
                    success = 0xFF59C88B.toInt(),
                    warning = 0xFFF1B84A.toInt(),
                    danger = 0xFFD77474.toInt()
                )
            } else {
                UiPalette(
                    dark = false,
                    background = 0xFFF8FAF9.toInt(),
                    backgroundAlt = 0xFFEAF3F0.toInt(),
                    panel = 0xFFFFFFFF.toInt(),
                    surface = 0xFFFFFFFF.toInt(),
                    mutedSurface = 0xFFF0F2F1.toInt(),
                    border = 0xFFD5E1DD.toInt(),
                    title = 0xFF102D2A.toInt(),
                    body = 0xFF334641.toInt(),
                    secondary = 0xFF647873.toInt(),
                    buttonBackground = 0xFFF3F7F5.toInt(),
                    buttonText = 0xFF172B28.toInt(),
                    accent = 0xFF0F8F75.toInt(),
                    accentSoft = 0xFFE0F4EF.toInt(),
                    success = 0xFF207A4F.toInt(),
                    warning = 0xFF9B6A10.toInt(),
                    danger = 0xFF9D3838.toInt()
                )
            }
        }
    }
}
