package app.aapswear.g7watch

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt
import app.aapswear.model.AppearanceMode
import app.aapswear.model.GlucoseTrendSizing

class G7AppearanceActivity : Activity() {
    private lateinit var store: G7AppearanceStore
    private var selectedMode: AppearanceMode = AppearanceMode.DARK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = G7AppearanceStore(this)
        selectedMode = store.activeMode()
        render()
    }

    private fun render() {
        val palette = store.load(selectedMode)
        window.statusBarColor = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.navigationBarColor = palette.argb(G7AppearanceRole.MENU_BACKGROUND)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 18.dp, 18.dp, 28.dp)
            setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
        }
        content.addView(topBar(palette))
        content.addView(themeSelector(palette), params(top = 8))
        content.addView(label("GRÖSSE", 10f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply {
            letterSpacing = 0.10f
            setPadding(4.dp, 14.dp, 4.dp, 5.dp)
        })
        content.addView(scaleRow("Glukosewert", store.glucoseScalePercent(), palette, store::setGlucoseScalePercent), params(top = 5))
        content.addView(scaleRow("Trendpfeil", store.trendScalePercent(), palette, store::setTrendScalePercent), params(top = 5))

        G7AppearanceSection.entries.forEach { section ->
            content.addView(label(section.label.uppercase(Locale.GERMANY), 10f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply {
                gravity = Gravity.START
                letterSpacing = 0.10f
                setPadding(4.dp, 14.dp, 4.dp, 5.dp)
            })
            G7AppearanceRole.entries.filter { it.section == section }.forEach { role ->
                content.addView(colorRow(role, palette), params(top = 5))
            }
        }

        content.addView(pill("ALLES RESET", palette.argb(G7AppearanceRole.MENU_PRIMARY), palette.argb(G7AppearanceRole.MENU_BACKGROUND)) {
            store.resetAll()
            render()
        }, params(top = 14))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
            addView(content)
        })
    }

    private fun topBar(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@G7AppearanceActivity).apply {
            text = "←"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(label("Farben & Darstellung", 18f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun colorRow(role: G7AppearanceRole, palette: G7AppearancePalette): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 8.dp, 8.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)

            val swatch = View(this@G7AppearanceActivity).apply {
                background = rounded(palette.argb(role), palette.argb(G7AppearanceRole.MENU_BORDER), 12f)
            }
            addView(swatch, LinearLayout.LayoutParams(28.dp, 28.dp).apply { marginEnd = 9.dp })

            addView(LinearLayout(this@G7AppearanceActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(role.label, 12f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply { gravity = Gravity.START })
                addView(label(toHex(palette.argb(role)), 9f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply { gravity = Gravity.START })
                setOnClickListener { openColorEditor(role) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(pill("RESET", palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_PRIMARY)) {
                store.reset(selectedMode, role)
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dp))
            setOnClickListener { openColorEditor(role) }
        }

    private fun scaleRow(title: String, initial: Int, palette: G7AppearancePalette, save: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            val valueLabel = label("$title · $initial %", 11f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true)
            addView(valueLabel)
            addView(SeekBar(this@G7AppearanceActivity).apply {
                max = GlucoseTrendSizing.MAX_SCALE_PERCENT - GlucoseTrendSizing.MIN_SCALE_PERCENT
                progress = initial - GlucoseTrendSizing.MIN_SCALE_PERCENT
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + GlucoseTrendSizing.MIN_SCALE_PERCENT
                        valueLabel.text = "$title · $value %"
                        if (fromUser) save(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }

    private fun openColorEditor(role: G7AppearanceRole) {
        val initial = store.load(selectedMode).argb(role)
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        var hue = hsv[0]
        var saturation = hsv[1]
        var brightness = hsv[2]
        var alpha = Color.alpha(initial) / 255f

        val palette = store.load(selectedMode)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        }
        val preview = View(this).apply { background = rounded(initial, palette.argb(G7AppearanceRole.MENU_BORDER), 14f) }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp))

        val hexInput = EditText(this).apply {
            setText(toHex(initial))
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(9))
            setSelectAllOnFocus(true)
        }
        root.addView(hexInput)

        fun currentColor(): Int = Color.HSVToColor(
            (alpha * 255f).roundToInt().coerceIn(0, 255),
            floatArrayOf(hue, saturation, brightness),
        )
        fun refreshFromSliders() {
            val color = currentColor()
            preview.background = rounded(color, palette.argb(G7AppearanceRole.MENU_BORDER), 14f)
            hexInput.setText(toHex(color))
            store.save(selectedMode, role, color)
        }
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                parseHex(text?.toString())?.let { color ->
                    preview.background = rounded(color, palette.argb(G7AppearanceRole.MENU_BORDER), 14f)
                    store.save(selectedMode, role, color)
                }
            }
        })
        fun slider(title: String, max: Int, initialProgress: Int, onChange: (Int) -> Unit) {
            root.addView(label(title, 10f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply { gravity = Gravity.START })
            root.addView(SeekBar(this).apply {
                this.max = max
                progress = initialProgress.coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            onChange(progress)
                            refreshFromSliders()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }

        slider("Farbton", 360, hue.roundToInt()) { hue = it.toFloat() }
        slider("Sättigung", 100, (saturation * 100f).roundToInt()) { saturation = it / 100f }
        slider("Helligkeit", 100, (brightness * 100f).roundToInt()) { brightness = it / 100f }
        slider("Deckkraft / Alpha", 100, (alpha * 100f).roundToInt()) { alpha = it / 100f }

        val scrollableEditor = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        AlertDialog.Builder(this)
            .setTitle(role.label)
            .setView(scrollableEditor)
            .create()
            .apply {
                setOnDismissListener { render() }
                show()
            }
    }

    private fun pill(textValue: String, fill: Int, textColor: Int, action: () -> Unit) = TextView(this).apply {
        text = textValue
        textSize = 10f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        setPadding(13.dp, 7.dp, 13.dp, 7.dp)
        background = rounded(fill, textColor, 999f)
        setOnClickListener { action() }
    }

    private fun themeSelector(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(AppearanceMode.LIGHT to "LIGHT", AppearanceMode.DARK to "DARK").forEach { (mode, title) ->
            addView(pill(
                title,
                if (selectedMode == mode) palette.argb(G7AppearanceRole.MENU_PRIMARY) else palette.argb(G7AppearanceRole.MENU_SURFACE),
                if (selectedMode == mode) palette.argb(G7AppearanceRole.MENU_BACKGROUND) else palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY),
            ) {
                selectedMode = mode
                store.setActiveMode(mode)
                render()
            }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginEnd = 6.dp })
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = top.dp
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(1.dp, stroke)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun toHex(argb: Int): String = String.format(
        Locale.US,
        "#%02X%02X%02X%02X",
        Color.alpha(argb), Color.red(argb), Color.green(argb), Color.blue(argb),
    )

    private fun parseHex(value: String?): Int? {
        val cleaned = value.orEmpty().trim().removePrefix("#")
        return when (cleaned.length) {
            6 -> cleaned.toLongOrNull(16)?.toInt()?.let { 0xFF000000.toInt() or it }
            8 -> cleaned.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
