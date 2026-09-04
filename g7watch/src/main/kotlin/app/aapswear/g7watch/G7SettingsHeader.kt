package app.aapswear.g7watch

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

internal fun Activity.g7SettingsHeader(title: String, palette: G7AppearancePalette): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * resources.displayMetrics.density).toInt()
        addView(TextView(this@g7SettingsHeader).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            contentDescription = "Zurück"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt()))
        addView(TextView(this@g7SettingsHeader).apply {
            text = title
            textSize = 17f
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
