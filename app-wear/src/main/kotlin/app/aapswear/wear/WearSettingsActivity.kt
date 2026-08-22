package app.aapswear.wear

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchUiColors
import kotlin.math.roundToInt

class WearSettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scrollView: ScrollView
    private var current = WearDisplayPreferences()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WearDisplayPreferences.read(this)
        buildUi()
    }

    private fun buildUi() {
        val restoreScrollY = if (::scrollView.isInitialized) scrollView.scrollY else 0
        current = WearDisplayPreferences.read(this)
        val ui = current.uiColors
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.background)
            isVerticalScrollBarEnabled = false
        }
        scrollView = scroll
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 14.dp, 24.dp, 32.dp)
        }
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 7.dp)
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = "‹"
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(ui.textPrimary)
                    background = compactActionBackground()
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(40.dp, 40.dp),
            )
            addView(
                LinearLayout(this@WearSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(9.dp, 0, 0, 0)
                    addView(TextView(this@WearSettingsActivity).apply {
                        text = "Sugarlicious"
                        textSize = 8f
                        setTextColor(ui.accent)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        letterSpacing = 0.08f
                    })
                    addView(TextView(this@WearSettingsActivity).apply {
                        text = "Watch Einstellungen"
                        textSize = 14f
                        setTextColor(ui.textPrimary)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        root.addView(header, fullWidth())

        section("ZEITSKALA")
        val hoursRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(5.dp, 5.dp, 5.dp, 5.dp)
            background = cardBackground()
        }
        WearDisplayPreferences.allowedGraphHours.forEach { hours ->
            val selected = current.graphHours == hours
            hoursRow.addView(
                Button(this).apply {
                    text = "${hours}h"
                    textSize = 9f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(1.dp, 0, 1.dp, 0)
                    isAllCaps = false
                    setTextColor(if (selected) ui.background else ui.textPrimary)
                    background = pillBackground(selected)
                    setOnClickListener { save(current.copy(graphHours = hours)) }
                },
                LinearLayout.LayoutParams(0, 34.dp, 1f).apply {
                    marginStart = 1.dp
                    marginEnd = 1.dp
                },
            )
        }
        root.addView(hoursRow, cardParams())

        root.addView(switchRow("Prognosen anzeigen", current.showPredictions) { save(current.copy(showPredictions = it)) }, cardParams())
        root.addView(switchRow("IOB / COB / Basal anzeigen", current.showTherapyStats) { save(current.copy(showTherapyStats = it)) }, cardParams())

        section("GLUKOSE")
        root.addView(
            choiceRow(
                listOf("Auto" to WatchGlucoseUnit.AAPS, "mg/dL" to WatchGlucoseUnit.MG_DL, "mmol/L" to WatchGlucoseUnit.MMOL_L),
                current.glucoseUnit,
            ) { save(current.copy(glucoseUnit = it)) },
            cardParams(),
        )

        section("CGM DOTS")
        root.addView(
            sliderCard(
                title = "Punktgröße",
                min = 15,
                max = 60,
                progress = (current.graphStyle.cgmDotRadiusDp * 10f).roundToInt(),
                value = { String.format("%.1f dp", it / 10f) },
            ) { progress ->
                save(current.copy(graphStyle = current.graphStyle.copy(cgmDotRadiusDp = progress / 10f)), rebuild = false)
            },
            cardParams(),
        )
        root.addView(
            switchRow("Kontur", current.graphStyle.cgmDotOutlineEnabled) {
                save(current.copy(graphStyle = current.graphStyle.copy(cgmDotOutlineEnabled = it)))
            },
            cardParams(),
        )
        root.addView(
            sliderCard(
                title = "Konturbreite",
                min = 25,
                max = 300,
                progress = (current.graphStyle.cgmDotOutlineWidthDp * 100f).roundToInt(),
                value = { String.format("%.2f dp", it / 100f) },
            ) { progress ->
                save(current.copy(graphStyle = current.graphStyle.copy(cgmDotOutlineWidthDp = progress / 100f)), rebuild = false)
            },
            cardParams(),
        )

        section("APP & TILES")
        colorRow("App Hintergrund", current.uiColors.background) { updateUiColors { c -> c.copy(background = it) } }
        colorRow("Tile Hintergrund", current.uiColors.tileBackground) { updateUiColors { c -> c.copy(tileBackground = it) } }
        colorRow("Tile Kontur", current.uiColors.tileBorder) { updateUiColors { c -> c.copy(tileBorder = it) } }
        colorRow("Haupttext", current.uiColors.textPrimary) { updateUiColors { c -> c.copy(textPrimary = it) } }
        colorRow("Sekundärtext", current.uiColors.textSecondary) { updateUiColors { c -> c.copy(textSecondary = it) } }
        colorRow("Akzent", current.uiColors.accent) { updateUiColors { c -> c.copy(accent = it) } }

        section("GLUKOSE FARBEN")
        colorRow("Zuckerwert niedrig", current.uiColors.glucoseLow) { updateUiColors { c -> c.copy(glucoseLow = it) } }
        colorRow("Zuckerwert im Ziel", current.uiColors.glucoseInRange) { updateUiColors { c -> c.copy(glucoseInRange = it) } }
        colorRow("Zuckerwert hoch", current.uiColors.glucoseHigh) { updateUiColors { c -> c.copy(glucoseHigh = it) } }

        section("THERAPIE FARBEN")
        colorRow("IOB", current.uiColors.iob) { updateUiColors { c -> c.copy(iob = it) } }
        colorRow("COB", current.uiColors.cob) { updateUiColors { c -> c.copy(cob = it) } }
        colorRow("Basal", current.uiColors.basal) { updateUiColors { c -> c.copy(basal = it) } }

        section("GRAPH FARBEN")
        colorRow("Graph Hintergrund", current.graphColors.graphBackground) { updateGraphColors { c -> c.copy(graphBackground = it) } }
        colorRow("Bereich niedrig", current.graphColors.rangeLow) { updateGraphColors { c -> c.copy(rangeLow = it) } }
        colorRow("Bereich im Ziel", current.graphColors.rangeInRange) { updateGraphColors { c -> c.copy(rangeInRange = it) } }
        colorRow("Bereich hoch", current.graphColors.rangeHigh) { updateGraphColors { c -> c.copy(rangeHigh = it) } }
        colorRow("CGM niedrig", current.graphColors.cgmLow) { updateGraphColors { c -> c.copy(cgmLow = it) } }
        colorRow("CGM im Bereich", current.graphColors.cgmInRange) { updateGraphColors { c -> c.copy(cgmInRange = it) } }
        colorRow("CGM hoch", current.graphColors.cgmHigh) { updateGraphColors { c -> c.copy(cgmHigh = it) } }
        colorRow("Linien / Achsen", current.graphColors.divider) { updateGraphColors { c -> c.copy(divider = it) } }
        colorRow("Dot Kontur", current.graphColors.outline) { updateGraphColors { c -> c.copy(outline = it) } }

        section("PROGNOSE FARBEN")
        colorRow("IOB Prognose", current.graphColors.predictionIob) { updateGraphColors { c -> c.copy(predictionIob = it) } }
        colorRow("COB Prognose", current.graphColors.predictionCob) { updateGraphColors { c -> c.copy(predictionCob = it) } }
        colorRow("UAM Prognose", current.graphColors.predictionUam) { updateGraphColors { c -> c.copy(predictionUam = it) } }
        colorRow("ZeroTemp Prognose", current.graphColors.predictionZeroTemp) { updateGraphColors { c -> c.copy(predictionZeroTemp = it) } }

        TextView(this).apply {
            text = "Sugarlicious Watch · Einstellungen werden lokal gespeichert"
            textSize = 8f
            setTextColor(ui.textSecondary)
            gravity = Gravity.CENTER
            setPadding(6.dp, 16.dp, 6.dp, 0)
            root.addView(this, fullWidth())
        }
        scroll.post { scroll.scrollTo(0, restoreScrollY) }
    }

    private fun updateGraphColors(transform: (WatchGraphColors) -> WatchGraphColors) {
        save(current.copy(graphColors = transform(current.graphColors)))
    }

    private fun updateUiColors(transform: (WatchUiColors) -> WatchUiColors) {
        save(current.copy(uiColors = transform(current.uiColors)))
    }

    private fun section(text: String) {
        root.addView(
            TextView(this).apply {
                this.text = text
                textSize = 8.5f
                setTextColor(current.uiColors.accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(5.dp, 15.dp, 5.dp, 6.dp)
            },
            fullWidth(),
        )
    }

    private fun switchRow(title: String, checked: Boolean, changed: (Boolean) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13.dp, 8.dp, 10.dp, 8.dp)
            background = cardBackground()
            addView(TextView(this@WearSettingsActivity).apply {
                text = title
                textSize = 10f
                setTextColor(current.uiColors.textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@WearSettingsActivity).apply {
                isChecked = checked
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, current.uiColors.textSecondary),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(current.uiColors.accent, current.uiColors.tileBorder),
                )
                setOnCheckedChangeListener { _, value -> changed(value) }
            })
        }

    private fun choiceRow(
        choices: List<Pair<String, WatchGlucoseUnit>>,
        selected: WatchGlucoseUnit,
        changed: (WatchGlucoseUnit) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(5.dp, 5.dp, 5.dp, 5.dp)
        background = cardBackground(16f)
        choices.forEach { (label, value) ->
            val active = selected == value
            addView(Button(this@WearSettingsActivity).apply {
                text = label
                textSize = 9f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setTextColor(if (active) current.uiColors.background else current.uiColors.textPrimary)
                background = pillBackground(active)
                setOnClickListener { changed(value) }
            }, LinearLayout.LayoutParams(0, 34.dp, 1f).apply {
                marginStart = 1.dp
                marginEnd = 1.dp
            })
        }
    }

    private fun sliderCard(
        title: String,
        min: Int,
        max: Int,
        progress: Int,
        value: (Int) -> String,
        changed: (Int) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(13.dp, 9.dp, 13.dp, 8.dp)
        background = cardBackground()
        val valueText = TextView(this@WearSettingsActivity).apply {
            text = value(progress)
            textSize = 9f
            gravity = Gravity.END
            setTextColor(current.uiColors.accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(LinearLayout(this@WearSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@WearSettingsActivity).apply {
                text = title
                textSize = 10f
                setTextColor(current.uiColors.textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
        }, fullWidth())
        addView(SeekBar(this@WearSettingsActivity).apply {
            this.max = max - min
            this.progress = progress.coerceIn(min, max) - min
            progressTintList = ColorStateList.valueOf(current.uiColors.accent)
            progressBackgroundTintList = ColorStateList.valueOf(current.uiColors.tileBorder)
            thumbTintList = ColorStateList.valueOf(current.uiColors.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var pendingValue = progress.coerceIn(min, max)

                override fun onProgressChanged(seekBar: SeekBar?, raw: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    pendingValue = raw + min
                    valueText.text = value(pendingValue)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = changed(pendingValue)
            })
        }, fullWidth())
    }

    private fun colorRow(title: String, color: Int, changed: (Int) -> Unit) {
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(13.dp, 8.dp, 11.dp, 8.dp)
                background = cardBackground()
                addView(TextView(this@WearSettingsActivity).apply {
                    text = title
                    textSize = 10f
                    setTextColor(current.uiColors.textPrimary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(this@WearSettingsActivity).apply {
                    background = colorCircle(color)
                    setOnClickListener { showColorPicker(title, color, changed) }
                }, LinearLayout.LayoutParams(32.dp, 32.dp))
            },
            cardParams(),
        )
    }

    private fun showColorPicker(title: String, selected: Int, changed: (Int) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setBackgroundColor(current.uiColors.tileBackground)
        }
        COLOR_CHOICES.forEach { color ->
            grid.addView(View(this).apply { background = colorCircle(color, color == selected) }, GridLayout.LayoutParams().apply {
                width = 42.dp
                height = 42.dp
                setMargins(4.dp, 4.dp, 4.dp, 4.dp)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton("Abbrechen", null)
            .create()
        grid.children().forEach { child ->
            child.setOnClickListener {
                val color = COLOR_CHOICES[grid.indexOfChild(child)]
                changed(color)
                dialog.dismiss()
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(cardBackground(26f))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(current.uiColors.accent)
        }
        dialog.show()
    }

    private fun GridLayout.children(): List<View> = (0 until childCount).map(::getChildAt)

    private fun save(value: WearDisplayPreferences, rebuild: Boolean = true) {
        current = value
        WearDisplayPreferences.saveLocal(this, current)
        if (rebuild) buildUi()
    }

    private fun compactActionBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 20.dp.toFloat()
        setColor(this@WearSettingsActivity.current.uiColors.tileBackground)
        setStroke(1.dp, this@WearSettingsActivity.current.uiColors.tileBorder)
    }

    private fun cardBackground(radiusDp: Float = 20f): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(this@WearSettingsActivity.current.uiColors.tileBackground)
        setStroke(1.dp, this@WearSettingsActivity.current.uiColors.tileBorder)
    }

    private fun pillBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 17.dp.toFloat()
        setColor(if (selected) this@WearSettingsActivity.current.uiColors.accent else this@WearSettingsActivity.current.uiColors.tileBackground)
        setStroke(1.dp, if (selected) this@WearSettingsActivity.current.uiColors.accent else this@WearSettingsActivity.current.uiColors.tileBorder)
    }

    private fun colorCircle(color: Int, selected: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) this@WearSettingsActivity.current.uiColors.accent else this@WearSettingsActivity.current.uiColors.tileBorder,
        )
    }

    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun cardParams() = fullWidth().apply {
        topMargin = 3.dp
        bottomMargin = 3.dp
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private val COLOR_CHOICES = intArrayOf(
            0xFF181818.toInt(), 0xFF202020.toInt(), 0xFF242424.toInt(), 0xFF404040.toInt(),
            0xFFF5F5F5.toInt(), 0xFFB5B5B5.toInt(), 0xFF6DE892.toInt(), 0xFF54DF30.toInt(),
            0xFF19D7E8.toInt(), 0xFF52C1FF.toInt(), 0xFF64BFFF.toInt(), 0xFF9575CD.toInt(),
            0xFFD69AFF.toInt(), 0xFFFF5C69.toInt(), 0xFFFF9D18.toInt(), 0xFFFFAE1F.toInt(),
            0xFFFFD040.toInt(), 0xFFF4DE00.toInt(), 0xFF30DBDE.toInt(), 0xFF969696.toInt(),
            0xFF000000.toInt(),
        )
    }
}
