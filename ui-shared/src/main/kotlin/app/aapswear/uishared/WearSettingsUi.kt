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
    /** Flat One UI Watch action surface, with no platform button inset or elevation shadow. */
    fun actionButton(view: android.widget.Button, cardSurface: Int, foreground: Int) {
        val density = view.resources.displayMetrics.density

        fun channel(shift: Int): Int = (((cardSurface ushr shift) and 255) * .90f + ((foreground ushr shift) and 255) * .10f).roundToInt()
        val fill = Color.rgb(channel(16), channel(8), channel(0))
        val shape =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = 26f * density
            }
        view.apply {
            backgroundTintList = null
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, null)
            layoutParams =
                (layoutParams as? ViewGroup.MarginLayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
                    topMargin = dp(context, 6)
                }
            stateListAnimator = null
            elevation = 0f
            translationZ = 0f
            minimumHeight = dp(context, 48)
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            setTextColor(foreground)
            SystemTypography.apply(this, true)
        }
    }

    fun accent(context: Context): Int =
        SugarliciousAccentPalette.primary(context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun textSize(legacySp: Float): Float =
        when {
            legacySp >= 16f -> 18f
            legacySp >= 10f -> 15f
            else -> 12f
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

    fun slider(view: SeekBar, accent: Int, muted: Int, label: String? = null) {
        val context = view.context
        val inactive = if (Color.alpha(muted) < 128 || Color.luminance(muted) < .025f) Color.rgb(70, 70, 73) else muted

        view.progressTintList = null
        view.progressBackgroundTintList = null
        view.secondaryProgressTintList = null

        fun track(color: Int) = background(context, color, 5f).apply { setSize(dp(context, 120), dp(context, 10)) }
        view.progressDrawable =
            LayerDrawable(arrayOf(track(inactive), ClipDrawable(track(accent), Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (index in 0..1) {
                    setLayerHeight(index, dp(context, 10))
                    setLayerGravity(index, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
        view.thumb =
            swatch(context, Color.WHITE).apply {
                setSize(dp(context, 20), dp(context, 20))
                setStroke(0, Color.TRANSPARENT)
            }
        view.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        view.minimumHeight = dp(context, 48)
        view.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
        if (label != null) view.contentDescription = label
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.splitTrack = false
    }

    @Suppress("DEPRECATION")
    fun toggle(view: Switch, accent: Int, textColor: Int) {
        val context = view.context
        view.showText = false
        view.splitTrack = false
        view.switchPadding = dp(context, 12)
        view.switchMinWidth = dp(context, 40)
        view.minimumHeight = dp(context, 48)
        view.textSize = 15f
        view.includeFontPadding = false
        view.setTextColor(textColor)
        // Switch lays its thumb out at track height. Keep the actual disc at 20dp
        // inside that 24dp allocation instead of stretching an oval to fill it.
        view.thumbDrawable =
            LayerDrawable(arrayOf(swatch(context, Color.WHITE).apply { setStroke(0, Color.TRANSPARENT) })).apply {
                setLayerSize(0, dp(context, 20), dp(context, 20))
                setLayerGravity(0, Gravity.CENTER)
            }
        view.minimumWidth = dp(context, 54)
        view.background =
            LayerDrawable(arrayOf(background(context, Color.rgb(83, 83, 88), 0f))).apply {
                setLayerSize(0, dp(context, 1), dp(context, 32))
                setLayerGravity(0, Gravity.END or Gravity.CENTER_VERTICAL)
                setLayerInsetEnd(0, dp(context, 53))
            }
        view.trackDrawable = background(context, Color.DKGRAY, 14f).apply { setSize(dp(context, 40), dp(context, 24)) }
        view.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.DKGRAY))
        view.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        SystemTypography.apply(view, false)
    }

    /** A single visual element owns all of its controls, in one neutral settings surface. */
    fun elementCard(context: Context, title: String, surface: Int, textColor: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = background(context, surface)
            setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTextColor(textColor)
                    setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8))
                    SystemTypography.apply(this, true)
                    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                },
            )
        }

    fun header(context: Context, title: String, textColor: Int, back: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56)
            addView(
                android.widget.ImageButton(context).apply {
                    setImageDrawable(WearBackIcon(context, textColor))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    contentDescription = "Zurück"
                    tooltipText = "Zurück"
                    background = android.graphics.drawable.InsetDrawable(background(context, Color.rgb(42, 42, 45), 24f), dp(context, 2))
                    setOnClickListener { back() }
                },
                LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)),
            )
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 18f
                    includeFontPadding = false
                    setTextColor(textColor)
                    SystemTypography.apply(this, true)
                    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                    setPadding(dp(context, 10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
}

/** Navigation is an icon, so increasing the system font never clips a text glyph. */
private class WearBackIcon(
    context: Context,
    color: Int,
) : android.graphics.drawable.Drawable() {
    private val size = WearSettingsUi.dp(context, 20)
    private val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = WearSettingsUi.dp(context, 2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }

    override fun draw(canvas: android.graphics.Canvas) {
        val path =
            android.graphics.Path().apply {
                moveTo(bounds.left + bounds.width() * .65f, bounds.top + bounds.height() * .18f)
                lineTo(bounds.left + bounds.width() * .32f, bounds.top + bounds.height() * .5f)
                lineTo(bounds.left + bounds.width() * .65f, bounds.top + bounds.height() * .82f)
            }
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth(): Int = size

    override fun getIntrinsicHeight(): Int = size

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
