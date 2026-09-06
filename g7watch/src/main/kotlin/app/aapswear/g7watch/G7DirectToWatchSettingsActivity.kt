package app.aapswear.g7watch

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.text.InputType
import app.aapswear.model.AppearanceMode
import app.aapswear.model.ArgbColor
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.uishared.SharedColorEditor
import kotlin.math.roundToInt

class G7DirectToWatchSettingsActivity : Activity() {
    private lateinit var settings: G7DirectToWatchSettingsStore
    private lateinit var appearance: G7AppearanceStore
    private var mode = AppearanceMode.DARK
    private lateinit var scrollView: ScrollView
    private lateinit var pageRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = G7DirectToWatchSettingsStore(this)
        appearance = G7AppearanceStore(this)
        mode = settings.activeAppearanceMode(appearance.activeMode())
        settings.saveActiveAppearanceMode(mode)
        pageRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView = G7EdgeFadeScrollView(this).apply {
            isFillViewport = true
            addView(pageRoot)
        }.applyG7EdgeFade()
        setContentView(scrollView)
        render()
    }

    private fun render() {
        val restoreScrollY = scrollView.scrollY
        val palette = appearance.load(mode)
        val root = pageRoot.apply {
            removeAllViews()
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 30.dp)
            setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
        }
        root.addView(topBar(palette))
        root.addView(modeSelector(palette), params(8))

        section(root, "GLUKOSE · EINHEIT", palette)
        root.addView(glucoseUnitRow(settings.glucoseUnit(), palette), params(5))
        root.addView(toggle("Zuckerwert fett", settings.glucoseBold(), palette) {
            settings.saveGlucoseBold(it)
        }, params(5))
        section(root, "WATCHFACE · SKALA UND ALTER", palette)
        root.addView(slider("Größe", 75, 150, settings.statusSizePercent(), palette, { "Größe · $it %" }) {
            settings.saveStatusSizePercent(it)
        }, params(5))
        root.addView(colorRow("Farbe", settings.statusColor(), palette, settings::saveStatusColor), params(5))
        root.addView(toggle("Fett", settings.statusBold(), palette, settings::saveStatusBold), params(5))
        section(root, "WATCHFACE · UHRZEIT", palette)
        root.addView(slider("Größe", 75, 150, settings.clockSizePercent(), palette, { "Größe · $it %" }) {
            settings.saveClockSizePercent(it)
        }, params(5))
        root.addView(colorRow("Farbe", settings.clockColor(), palette, settings::saveClockColor), params(5))
        root.addView(toggle("Fett", settings.clockBold(), palette, settings::saveClockBold), params(5))
        val thresholds = settings.thresholds()
        section(root, "GLUKOSE · ZIELBEREICH", palette)
        root.addView(numericThresholdRow("Tief", thresholds.lowMgDl, isHigh = false, palette), params(5))
        root.addView(numericThresholdRow("Hoch", thresholds.highMgDl, isHigh = true, palette), params(5))

        section(root, "GRAPH · ZEITSKALA", palette)
        root.addView(choiceRow(settings.graphHours(), palette), params(5))
        val graphStyle = settings.graphStyle()
        section(root, "GRAPH · PUNKTE", palette)
        root.addView(slider("Punktgröße", 15, 60, (graphStyle.dotRadiusDp * 10).roundToInt(), palette, { "Punktgröße · ${it / 10f} dp" }) {
            settings.saveGraphStyle(settings.graphStyle().copy(dotRadiusDp = it / 10f))
        }, params(5))
        root.addView(toggle("Kontur · bisherige Punkte", graphStyle.historicalDotOutlineEnabled, palette) {
            settings.saveGraphStyle(settings.graphStyle().copy(historicalDotOutlineEnabled = it))
        }, params(5))
        root.addView(toggle("Kontur · aktueller Wert", graphStyle.currentDotOutlineEnabled, palette) {
            settings.saveGraphStyle(settings.graphStyle().copy(currentDotOutlineEnabled = it))
        }, params(5))
        root.addView(slider("Konturbreite", 25, 300, (graphStyle.dotOutlineWidthDp * 100).roundToInt(), palette, { "Konturbreite · ${it / 100f} dp" }) {
            settings.saveGraphStyle(settings.graphStyle().copy(dotOutlineWidthDp = it / 100f))
        }, params(5))
        root.addView(slider("Eckenrundung", 0, 400, (graphStyle.cornerRadiusDp * 10).roundToInt(), palette, { "Eckenrundung · ${it / 10f} dp" }) {
            settings.saveGraphStyle(settings.graphStyle().copy(cornerRadiusDp = it / 10f))
        }, params(5))
        root.addView(toggle("Graphkontur", graphStyle.borderEnabled, palette) {
            settings.saveGraphStyle(settings.graphStyle().copy(borderEnabled = it))
        }, params(5))
        root.addView(toggle("Zeitachsenskala", graphStyle.timeAxisEnabled, palette) {
            settings.saveGraphStyle(settings.graphStyle().copy(timeAxisEnabled = it))
        }, params(5))
        root.addView(slider("Skalenbereich", 0, 100, graphStyle.scaleLaneOpacityPercent, palette, { "Skalenbereich · $it %" }) {
            settings.saveGraphStyle(settings.graphStyle().copy(scaleLaneOpacityPercent = it))
        }, params(5))
        val colors = settings.graphColors()
        section(root, "GRAPH · FARBEN", palette)
        graphColorRows(root, colors, palette)
        root.addView(button("GRAPH ZURÜCKSETZEN", palette) { settings.resetGraph(); render() }, params(8))

        val trend = settings.trendStyle(mode)
        section(root, "TRENDPFEIL", palette)
        root.addView(slider("Größe", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, trend.sizePercent, palette, { "Größe · $it %" }) {
            settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(sizePercent = it))
        }, params(5))
        root.addView(colorRow("Füllfarbe", trend.fillColor, palette) { value -> settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(fillColor = value)) }, params(5))
        root.addView(toggle("Kontur", trend.outlineEnabled, palette) { settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(outlineEnabled = it)) }, params(5))
        root.addView(colorRow("Konturfarbe", trend.outlineColor, palette) { value -> settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(outlineColor = value)) }, params(5))
        root.addView(slider("Konturdicke", 25, 400, (trend.outlineThicknessDp * 100).roundToInt(), palette, { "Konturdicke · ${it / 100f} dp" }) {
            settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(outlineThicknessDp = it / 100f))
        }, params(5))
        root.addView(slider("Deckkraft", 0, 100, (trend.alpha * 100).roundToInt(), palette, { "Deckkraft · $it %" }) {
            settings.saveTrendStyle(mode, settings.trendStyle(mode).copy(alpha = it / 100f))
        }, params(5))
        root.addView(button("TREND-STIL ZURÜCKSETZEN", palette) { settings.resetTrendStyle(mode); render() }, params(8))

        restoreScrollPosition(restoreScrollY)
    }

    private fun graphColorRows(root: LinearLayout, c: WatchGraphColors, p: G7AppearancePalette) {
        fun add(title: String, value: Int, update: (WatchGraphColors, Int) -> WatchGraphColors) =
            root.addView(colorRow(title, value, p) { settings.saveGraphColors(update(settings.graphColors(), it)) }, params(5))
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

    private fun topBar(p: G7AppearancePalette) = g7SettingsHeader("Watchface", p)

    private fun restoreScrollPosition(scrollY: Int) {
        scrollView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scrollView.viewTreeObserver.removeOnPreDrawListener(this)
                val maxScroll = (pageRoot.measuredHeight - scrollView.height).coerceAtLeast(0)
                scrollView.scrollTo(0, scrollY.coerceAtMost(maxScroll))
                return true
            }
        })
    }

    private fun modeSelector(p: G7AppearancePalette) = LinearLayout(this).apply {
        listOf(AppearanceMode.LIGHT to "LIGHT", AppearanceMode.DARK to "DARK").forEach { (item, title) ->
            addView(button(title, p, item == mode) {
                mode = item
                settings.saveActiveAppearanceMode(item)
                render()
            }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginEnd = 5.dp })
        }
    }

    private fun choiceRow(current: Int, p: G7AppearancePalette) = LinearLayout(this).apply {
        G7DirectToWatchSettingsStore.HOUR_OPTIONS.forEach { hour ->
            addView(button("${hour}h", p, hour == current) {
                settings.saveGraphHours(hour)
                restyleChoices(this, hour, p)
            }.apply { tag = hour }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginEnd = 3.dp })
        }
    }

    private fun glucoseUnitRow(current: GlucoseUnit, p: G7AppearancePalette) = LinearLayout(this).apply {
        listOf(GlucoseUnit.MG_DL to "mg/dL", GlucoseUnit.MMOL_L to "mmol/L").forEach { (unit, title) ->
            addView(button(title, p, unit == current) {
                settings.saveGlucoseUnit(unit)
                restyleChoices(this, unit, p)
            }.apply { tag = unit }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { marginEnd = 3.dp })
        }
    }

    private fun restyleChoices(row: LinearLayout, selected: Any, p: G7AppearancePalette) {
        (0 until row.childCount).mapNotNull { row.getChildAt(it) as? TextView }.forEach { choice ->
            val active = choice.tag == selected
            choice.setTextColor(if (active) p.argb(G7AppearanceRole.MENU_BACKGROUND) else p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            choice.background = rounded(if (active) p.argb(G7AppearanceRole.MENU_PRIMARY) else p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_BORDER), 999f)
        }
    }

    private fun formatThreshold(valueMgDl: Int, unit: GlucoseUnit): String =
        if (unit == GlucoseUnit.MMOL_L) String.format(java.util.Locale.GERMANY, "%.1f mmol/L", valueMgDl / 18.0)
        else "$valueMgDl mg/dL"

    private fun numericThresholdRow(title: String, valueMgDl: Double, isHigh: Boolean, p: G7AppearancePalette) =
        button("$title · ${formatThreshold(valueMgDl.roundToInt(), settings.glucoseUnit())}", p) {
            showTargetThresholdEditor(title, valueMgDl, isHigh, p)
        }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(12.dp, 0, 12.dp, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp)
        }

    private fun showTargetThresholdEditor(title: String, currentMgDl: Double, isHigh: Boolean, p: G7AppearancePalette) {
        val restoreScrollY = scrollView.scrollY
        val unit = settings.glucoseUnit()
        val shown = if (unit == GlucoseUnit.MMOL_L) String.format(java.util.Locale.US, "%.1f", currentMgDl / 18.0) else currentMgDl.roundToInt().toString()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(shown)
            selectAll()
            setTextColor(p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Zielbereich · $title")
            .setView(input)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val mgDl = entered?.let { if (unit == GlucoseUnit.MMOL_L) it * 18.0 else it }
                val current = settings.thresholds()
                val updated = mgDl?.let { if (isHigh) current.copy(highMgDl = it) else current.copy(lowMgDl = it) }
                    ?.takeIf { it.isValid && it.lowMgDl >= 40.0 && it.veryHighMgDl <= 400.0 }
                if (updated == null) {
                    input.error = if (isHigh) "Muss über dem Tief-Wert liegen" else "Muss unter dem Hoch-Wert liegen"
                } else {
                    settings.saveThresholds(updated)
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.setOnDismissListener { restoreScrollPosition(restoreScrollY) }
        dialog.show()
        input.requestFocus()
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
        val valueLabel = label(ArgbColor.format(color), 8f, p.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
        val swatch = View(this@G7DirectToWatchSettingsActivity).apply { background = rounded(color, p.argb(G7AppearanceRole.MENU_BORDER), 10f) }
        addView(LinearLayout(this@G7DirectToWatchSettingsActivity).apply { orientation = LinearLayout.VERTICAL; addView(label(title, 11f, p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true)); addView(valueLabel) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(swatch, LinearLayout.LayoutParams(32.dp, 32.dp))
        setOnClickListener {
            SharedColorEditor.show(this@G7DirectToWatchSettingsActivity, title, color, p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), p.argb(G7AppearanceRole.MENU_BORDER), color, onChange = { changed ->
                save(changed)
                valueLabel.text = ArgbColor.format(changed)
                swatch.background = rounded(changed, p.argb(G7AppearanceRole.MENU_BORDER), 10f)
            }, onReset = {})
        }
    }

    private fun section(root: LinearLayout, title: String, p: G7AppearancePalette) = root.addView(label(title, 9f, p.argb(G7AppearanceRole.MENU_PRIMARY), true).apply { letterSpacing = .08f; setPadding(4.dp, 15.dp, 4.dp, 4.dp) })
    private fun button(title: String, p: G7AppearancePalette, selected: Boolean = false, action: () -> Unit) = label(title, 9f, if (selected) p.argb(G7AppearanceRole.MENU_BACKGROUND) else p.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply { gravity = Gravity.CENTER; background = rounded(if (selected) p.argb(G7AppearanceRole.MENU_PRIMARY) else p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_BORDER), 999f); setOnClickListener { action() } }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun card(p: G7AppearancePalette) = rounded(p.argb(G7AppearanceRole.MENU_SURFACE), p.argb(G7AppearanceRole.MENU_BORDER), 18f)
    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply { setColor(fill); setStroke(1.dp, stroke); cornerRadius = radius * resources.displayMetrics.density }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top.dp }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
