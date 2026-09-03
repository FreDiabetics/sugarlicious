package app.aapswear.g7watch

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.aapswear.model.AppearanceMode
import app.aapswear.model.ArgbColor
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.uishared.SharedColorEditor
import kotlin.math.roundToInt

class G7DirectToWatchSettingsActivity : Activity() {
    private lateinit var settings: G7DirectToWatchSettingsStore
    private lateinit var appearance: G7AppearanceStore
    private var mode = AppearanceMode.DARK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = G7DirectToWatchSettingsStore(this)
        appearance = G7AppearanceStore(this)
        mode = appearance.activeMode()
        render()
    }

    private fun render() {
        val palette = appearance.load(mode)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 30.dp)
            setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
        }
        root.addView(topBar(palette))
        root.addView(modeSelector(palette), params(8))

        section(root, "GRAPH · ZEITSKALA", palette)
        root.addView(choiceRow(settings.graphHours(), palette), params(5))
        val graphStyle = settings.graphStyle()
        section(root, "GRAPH · PUNKTE", palette)
        root.addView(slider("Punktgröße", 15, 60, (graphStyle.dotRadiusDp * 10).roundToInt(), palette, { "Punktgröße · ${it / 10f} dp" }) {
            settings.saveGraphStyle(graphStyle.copy(dotRadiusDp = it / 10f))
        }, params(5))
        root.addView(toggle("Punktkontur", graphStyle.dotOutlineEnabled, palette) {
            settings.saveGraphStyle(graphStyle.copy(dotOutlineEnabled = it)); render()
        }, params(5))
        if (graphStyle.dotOutlineEnabled) root.addView(slider("Konturbreite", 25, 300, (graphStyle.dotOutlineWidthDp * 100).roundToInt(), palette, { "Konturbreite · ${it / 100f} dp" }) {
            settings.saveGraphStyle(graphStyle.copy(dotOutlineWidthDp = it / 100f))
        }, params(5))
        root.addView(slider("Eckenrundung", 0, 400, (graphStyle.cornerRadiusDp * 10).roundToInt(), palette, { "Eckenrundung · ${it / 10f} dp" }) {
            settings.saveGraphStyle(graphStyle.copy(cornerRadiusDp = it / 10f))
        }, params(5))
        root.addView(toggle("Graphkontur", graphStyle.borderEnabled, palette) {
            settings.saveGraphStyle(graphStyle.copy(borderEnabled = it)); render()
        }, params(5))
        root.addView(toggle("Zeitachsenskala", graphStyle.timeAxisEnabled, palette) {
            settings.saveGraphStyle(graphStyle.copy(timeAxisEnabled = it)); render()
        }, params(5))
        root.addView(toggle("Horizontale Zielwert-Striche", graphStyle.targetTicksEnabled, palette) {
            settings.saveGraphStyle(graphStyle.copy(targetTicksEnabled = it)); render()
        }, params(5))

        val colors = settings.graphColors()
        section(root, "GRAPH · FARBEN", palette)
        graphColorRows(root, colors, palette)
        root.addView(button("GRAPH ZURÜCKSETZEN", palette) { settings.resetGraph(); render() }, params(8))

        val trend = settings.trendStyle(mode)
        section(root, "TRENDPFEIL", palette)
        root.addView(slider("Größe", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, trend.sizePercent, palette, { "Größe · $it %" }) {
            settings.saveTrendStyle(mode, trend.copy(sizePercent = it))
        }, params(5))
        root.addView(colorRow("Füllfarbe", trend.fillColor, palette) { value -> settings.saveTrendStyle(mode, trend.copy(fillColor = value)) }, params(5))
        root.addView(toggle("Kontur", trend.outlineEnabled, palette) { settings.saveTrendStyle(mode, trend.copy(outlineEnabled = it)); render() }, params(5))
        if (trend.outlineEnabled) {
            root.addView(colorRow("Konturfarbe", trend.outlineColor, palette) { value -> settings.saveTrendStyle(mode, trend.copy(outlineColor = value)) }, params(5))
            root.addView(slider("Konturdicke", 25, 400, (trend.outlineThicknessDp * 100).roundToInt(), palette, { "Konturdicke · ${it / 100f} dp" }) {
                settings.saveTrendStyle(mode, trend.copy(outlineThicknessDp = it / 100f))
            }, params(5))
        }
        root.addView(slider("Deckkraft", 0, 100, (trend.alpha * 100).roundToInt(), palette, { "Deckkraft · $it %" }) {
            settings.saveTrendStyle(mode, trend.copy(alpha = it / 100f))
        }, params(5))
        root.addView(button("TREND-STIL ZURÜCKSETZEN", palette) { settings.resetTrendStyle(mode); render() }, params(8))

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun graphColorRows(root: LinearLayout, c: WatchGraphColors, p: G7AppearancePalette) {
        fun add(title: String, value: Int, update: (WatchGraphColors, Int) -> WatchGraphColors) =
            root.addView(colorRow(title, value, p) { settings.saveGraphColors(update(c, it)) }, params(5))
        add("Hintergrund", c.graphBackground) { x, v -> x.copy(graphBackground = v) }
        add("LOW-Bereich", c.rangeLow) { x, v -> x.copy(rangeLow = v) }
        add("Zielbereich", c.rangeInRange) { x, v -> x.copy(rangeInRange = v) }
        add("HIGH-Bereich", c.rangeHigh) { x, v -> x.copy(rangeHigh = v) }
        add("LOW-Punkte", c.cgmLow) { x, v -> x.copy(cgmLow = v) }
        add("IN-RANGE-Punkte", c.cgmInRange) { x, v -> x.copy(cgmInRange = v) }
        add("HIGH-Punkte", c.cgmHigh) { x, v -> x.copy(cgmHigh = v) }
        add("VERY-LOW-Punkte", c.cgmVeryLow) { x, v -> x.copy(cgmVeryLow = v) }
        add("VERY-HIGH-Punkte", c.cgmVeryHigh) { x, v -> x.copy(cgmVeryHigh = v) }
        add("Punktkontur", c.outline) { x, v -> x.copy(outline = v) }
        add("HIGH-Linie", c.highLine) { x, v -> x.copy(highLine = v) }
        add("LOW-Linie", c.lowLine) { x, v -> x.copy(lowLine = v) }
        add("Achsentext", c.axisLabel) { x, v -> x.copy(axisLabel = v) }
        add("Achsenmarken", c.axisTick) { x, v -> x.copy(axisTick = v) }
        add("Jetzt-Linie", c.nowLine) { x, v -> x.copy(nowLine = v) }
        add("Rahmen", c.divider) { x, v -> x.copy(divider = v) }
        add("Zielwert", c.targetValue) { x, v -> x.copy(targetValue = v) }
        add("Signalverlust", c.signalLoss) { x, v -> x.copy(signalLoss = v) }
        add("Prognose · IOB", c.predictionIob) { x, v -> x.copy(predictionIob = v) }
        add("Prognose · COB", c.predictionCob) { x, v -> x.copy(predictionCob = v) }
        add("Prognose · UAM", c.predictionUam) { x, v -> x.copy(predictionUam = v) }
        add("Prognose · Zero Temp", c.predictionZeroTemp) { x, v -> x.copy(predictionZeroTemp = v) }
    }

    private fun topBar(p: G7AppearancePalette) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("‹", 26f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(LinearLayout(this@G7DirectToWatchSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("DIRECT TO WATCH", 8f, p.argb(G7AppearanceRole.MENU_PRIMARY), true))
            addView(label("Watchface", 17f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun modeSelector(p: G7AppearancePalette) = LinearLayout(this).apply {
        listOf(AppearanceMode.LIGHT to "LIGHT", AppearanceMode.DARK to "DARK").forEach { (item, title) ->
            addView(button(title, p, item == mode) { mode = item; render() }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginEnd = 5.dp })
        }
    }

    private fun choiceRow(current: Int, p: G7AppearancePalette) = LinearLayout(this).apply {
        G7DirectToWatchSettingsStore.HOUR_OPTIONS.forEach { hour ->
            addView(button("${hour}h", p, hour == current) { settings.saveGraphHours(hour); render() }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginEnd = 3.dp })
        }
    }

    private fun slider(title: String, min: Int, max: Int, initial: Int, p: G7AppearancePalette, format: (Int) -> String, save: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(10.dp, 8.dp, 10.dp, 8.dp); background = card(p)
        val value = label(format(initial), 11f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true); addView(value)
        addView(SeekBar(this@G7DirectToWatchSettingsActivity).apply {
            this.max = max - min; progress = initial.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { val selected = progress + min; value.text = format(selected); if (fromUser) save(selected) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
    }

    private fun toggle(title: String, checked: Boolean, p: G7AppearancePalette, save: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(10.dp, 8.dp, 10.dp, 8.dp); background = card(p)
        addView(label(title, 11f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Switch(this@G7DirectToWatchSettingsActivity).apply { isChecked = checked; setOnCheckedChangeListener { _, value -> save(value) } })
    }

    private fun colorRow(title: String, color: Int, p: G7AppearancePalette, save: (Int) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(10.dp, 8.dp, 10.dp, 8.dp); background = card(p)
        addView(LinearLayout(this@G7DirectToWatchSettingsActivity).apply { orientation = LinearLayout.VERTICAL; addView(label(title, 11f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true)); addView(label(ArgbColor.format(color), 8f, p.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(View(this@G7DirectToWatchSettingsActivity).apply { background = rounded(color, p.argb(G7AppearanceRole.MENU_BORDER), 10f) }, LinearLayout.LayoutParams(32.dp, 32.dp))
        setOnClickListener { SharedColorEditor.show(this@G7DirectToWatchSettingsActivity, title, color, p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), p.argb(G7AppearanceRole.MENU_BORDER), color, onChange = save, onReset = {}) }
    }

    private fun section(root: LinearLayout, title: String, p: G7AppearancePalette) = root.addView(label(title, 9f, p.argb(G7AppearanceRole.MENU_PRIMARY), true).apply { letterSpacing = .08f; setPadding(4.dp, 15.dp, 4.dp, 4.dp) })
    private fun button(title: String, p: G7AppearancePalette, selected: Boolean = false, action: () -> Unit) = label(title, 9f, if (selected) p.argb(G7AppearanceRole.MENU_BACKGROUND) else p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply { gravity = Gravity.CENTER; background = rounded(if (selected) p.argb(G7AppearanceRole.MENU_PRIMARY) else p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_BORDER), 999f); setOnClickListener { action() } }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun card(p: G7AppearancePalette) = rounded(p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_BORDER), 18f)
    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply { setColor(fill); setStroke(1.dp, stroke); cornerRadius = radius * resources.displayMetrics.density }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top.dp }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
