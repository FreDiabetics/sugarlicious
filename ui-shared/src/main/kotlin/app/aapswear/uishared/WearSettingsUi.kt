package app.aapswear.uishared

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared round-screen settings vocabulary. Uses public Android widgets, not private Samsung APIs. */
object WearSettingsUi {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun textSize(legacySp: Float): Float =
        when {
            legacySp >= 16f -> 20f
            legacySp >= 12f -> 17f
            legacySp >= 10f -> 16f
            else -> 13f
        }

    fun background(context: Context, color: Int, radiusDp: Float = 26f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }

    fun swatch(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(context, 1), Color.GRAY)
        }

    fun slider(view: SeekBar, accent: Int, muted: Int) {
        val context = view.context

        fun track(color: Int) = background(context, color, 5f).apply { setSize(dp(context, 120), dp(context, 10)) }
        view.progressDrawable =
            LayerDrawable(arrayOf(track(muted), ClipDrawable(track(accent), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        view.thumb =
            swatch(context, Color.WHITE).apply {
                setSize(dp(context, 20), dp(context, 20))
                setStroke(0, Color.TRANSPARENT)
            }
        view.progressTintList = ColorStateList.valueOf(accent)
        view.progressBackgroundTintList = ColorStateList.valueOf(muted)
        view.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        view.minimumHeight = dp(context, 48)
        view.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        view.isFocusable = false
        view.isFocusableInTouchMode = false
    }

    @Suppress("DEPRECATION")
    fun toggle(view: Switch, accent: Int, textColor: Int) {
        val context = view.context
        view.showText = false
        view.switchMinWidth = dp(context, 44)
        view.minimumHeight = dp(context, 48)
        view.textSize = 16f
        view.setTextColor(textColor)
        view.thumbDrawable =
            swatch(context, Color.WHITE).apply {
                setSize(dp(context, 22), dp(context, 22))
                setStroke(0, Color.TRANSPARENT)
            }
        view.trackDrawable = background(context, Color.DKGRAY, 14f).apply { setSize(dp(context, 44), dp(context, 28)) }
        view.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.DKGRAY))
        view.thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    fun header(context: Context, title: String, textColor: Int, back: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56)
            addView(
                TextView(context).apply {
                    text = "‹"
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(textColor)
                    contentDescription = "Zurück"
                    background = background(context, Color.rgb(42, 42, 45), 24f)
                    setOnClickListener { back() }
                },
                LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)),
            )
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(textColor)
                    SystemTypography.apply(this, true)
                    setPadding(dp(context, 10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
}
