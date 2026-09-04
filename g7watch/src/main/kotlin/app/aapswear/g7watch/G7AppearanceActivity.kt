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
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt
import app.aapswear.model.AppearanceMode
import app.aapswear.model.ArgbColor
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.TrendArrowStyle
import app.aapswear.uishared.SharedColorEditor

class G7AppearanceActivity : Activity() {
    private lateinit var store: G7AppearanceStore
    private var selectedMode: AppearanceMode = AppearanceMode.DARK
    private lateinit var scrollView: ScrollView
    private lateinit var pageRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = G7AppearanceStore(this)
        selectedMode = store.activeMode()
        pageRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = ScrollView(this).apply { isFillViewport = true; addView(pageRoot) }.applyG7EdgeFade()
        setContentView(scrollView)
        render()
    }

    private fun render() {
        val palette = store.load(selectedMode)
        window.statusBarColor = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.navigationBarColor = palette.argb(G7AppearanceRole.MENU_BACKGROUND)

        val restoreScrollY = scrollView.scrollY
        val content = pageRoot.apply {
            removeAllViews()
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
        val trendStyle = store.trendArrowStyle(selectedMode)
        content.addView(simpleColorRow("Trendpfeil · Füllfarbe", trendStyle.fillColor, palette) {
            SharedColorEditor.show(
                this, "Trendpfeil · Füllfarbe", trendStyle.fillColor,
                palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), palette.argb(G7AppearanceRole.MENU_BORDER),
                TrendArrowStyle.defaults(selectedMode, palette.argb(G7AppearanceRole.GLUCOSE_TREND)).fillColor,
                onChange = { store.saveTrendArrowStyle(selectedMode, trendStyle.copy(fillColor = it)) },
                onReset = { store.resetTrendArrowStyle(selectedMode); render() },
            )
        }, params(top = 5))
        content.addView(toggleRow("Trendpfeil-Kontur", trendStyle.outlineEnabled, palette) {
            store.saveTrendArrowStyle(selectedMode, trendStyle.copy(outlineEnabled = it))
            render()
        }, params(top = 5))
        if (trendStyle.outlineEnabled) {
            content.addView(simpleColorRow("Trendpfeil · Konturfarbe", trendStyle.outlineColor, palette) {
                SharedColorEditor.show(
                    this, "Trendpfeil · Konturfarbe", trendStyle.outlineColor,
                    palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), palette.argb(G7AppearanceRole.MENU_BORDER),
                    TrendArrowStyle.defaults(selectedMode, trendStyle.fillColor).outlineColor,
                    onChange = { store.saveTrendArrowStyle(selectedMode, trendStyle.copy(outlineColor = it)) },
                    onReset = { store.saveTrendArrowStyle(selectedMode, trendStyle.copy(outlineColor = TrendArrowStyle.defaults(selectedMode, trendStyle.fillColor).outlineColor)); render() },
                )
            }, params(top = 5))
            content.addView(scaleRow("Konturdicke", (trendStyle.outlineThicknessDp * 100).roundToInt(), palette, {
                store.saveTrendArrowStyle(selectedMode, trendStyle.copy(outlineThicknessDp = it / 100f))
            }, min = 25, max = 400, format = { "Konturdicke · ${it / 100f} dp" }), params(top = 5))
        }
        content.addView(scaleRow("Deckkraft", (trendStyle.alpha * 100).roundToInt(), palette, {
            store.saveTrendArrowStyle(selectedMode, trendStyle.copy(alpha = it / 100f))
        }, min = 0, max = 100, format = { "Deckkraft · $it %" }), params(top = 5))
        content.addView(pill("TREND-STIL RESET", palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_PRIMARY)) {
            store.resetTrendArrowStyle(selectedMode)
            render()
        }, params(top = 5))

        content.addView(label("GRAPH · PUNKTKONTUREN", 10f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply {
            letterSpacing = 0.10f
            setPadding(4.dp, 14.dp, 4.dp, 5.dp)
        })
        content.addView(toggleRow("Kontur · bisherige Punkte", store.historicalDotOutlineEnabled(), palette) {
            store.setHistoricalDotOutlineEnabled(it)
        }, params(top = 5))
        content.addView(toggleRow("Kontur · aktueller Wert", store.currentDotOutlineEnabled(), palette) {
            store.setCurrentDotOutlineEnabled(it)
        }, params(top = 5))

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

        scrollView.setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
        scrollView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scrollView.viewTreeObserver.removeOnPreDrawListener(this)
                val maxScroll = (pageRoot.measuredHeight - scrollView.height).coerceAtLeast(0)
                scrollView.scrollTo(0, restoreScrollY.coerceAtMost(maxScroll))
                return true
            }
        })
    }

    private fun topBar(palette: G7AppearancePalette) = g7SettingsHeader("Farben & Darstellung", palette)

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

    private fun scaleRow(
        title: String,
        initial: Int,
        palette: G7AppearancePalette,
        save: (Int) -> Unit,
        min: Int = GlucoseTrendSizing.MIN_SCALE_PERCENT,
        max: Int = GlucoseTrendSizing.MAX_SCALE_PERCENT,
        format: (Int) -> String = { "$title · $it %" },
    ) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            val valueLabel = label(format(initial), 11f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true)
            addView(valueLabel)
            addView(SeekBar(this@G7AppearanceActivity).apply {
                this.max = max - min
                progress = initial.coerceIn(min, max) - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueLabel.text = format(value)
                        if (fromUser) save(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }

    private fun toggleRow(title: String, checked: Boolean, palette: G7AppearancePalette, save: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            addView(label(title, 11f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Switch(this@G7AppearanceActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> save(value) }
            })
        }

    private fun simpleColorRow(title: String, color: Int, palette: G7AppearancePalette, edit: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 18f)
            addView(label(title, 11f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@G7AppearanceActivity).apply { background = rounded(color, palette.argb(G7AppearanceRole.MENU_BORDER), 12f); setOnClickListener { edit() } }, LinearLayout.LayoutParams(32.dp, 32.dp))
            setOnClickListener { edit() }
        }

    private fun openColorEditor(role: G7AppearanceRole) {
        val initial = store.load(selectedMode).argb(role)
        val palette = store.load(selectedMode)
        SharedColorEditor.show(
            this, role.label, initial,
            palette.argb(G7AppearanceRole.MENU_SURFACE),
            palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY),
            palette.argb(G7AppearanceRole.MENU_BORDER),
            if (selectedMode == AppearanceMode.LIGHT) role.lightArgb else role.defaultArgb,
            onChange = { store.save(selectedMode, role, it) },
            onReset = { store.reset(selectedMode, role); render() },
        )
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

    private fun toHex(argb: Int): String = ArgbColor.format(argb)

    private fun parseHex(value: String?): Int? = ArgbColor.parse(value)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
