package app.aapswear.wear

import app.aapswear.model.AppearanceTerminology
import app.aapswear.model.AppearanceMode
import app.aapswear.model.GlucoseTrendSizing

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
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
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchUiColors
import app.aapswear.uishared.SharedColorEditor
import kotlin.math.roundToInt

class WearSettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scrollView: ScrollView
    private var current = WearDisplayPreferences()
    private var selectedAppearanceMode = AppearanceMode.DARK
    private var selectedSettingsCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAppearanceMode = WearDisplayPreferences.activeAppearanceMode(this)
        current = WearDisplayPreferences.read(this, selectedAppearanceMode)
        buildUi()
    }

    private fun buildUi() {
        val restoreScrollY = if (::scrollView.isInitialized) scrollView.scrollY else 0
        current = WearDisplayPreferences.read(this, selectedAppearanceMode)
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
                    setOnClickListener {
                        if (selectedSettingsCategory != null) {
                            selectedSettingsCategory = null
                            buildUi()
                        } else {
                            finish()
                        }
                    }
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
        root.addView(
            choiceRow(
                listOf("Light" to AppearanceMode.LIGHT, "Dark" to AppearanceMode.DARK),
                selectedAppearanceMode,
            ) { mode ->
                selectedAppearanceMode = mode
                buildUi()
            },
            cardParams(),
        )

        settingsCategory("display", "Anzeige", "App-Flächen, Texte und Therapiefarben") {
            section("APP")
            colorRow(AppearanceTerminology.APP_BACKGROUND, current.uiColors.background) { updateUiColors { c -> c.copy(background = it) } }
            colorRow(AppearanceTerminology.SURFACE_BACKGROUND, current.uiColors.tileBackground) { updateUiColors { c -> c.copy(tileBackground = it) } }
            colorRow(AppearanceTerminology.SURFACE_BORDER, current.uiColors.tileBorder) { updateUiColors { c -> c.copy(tileBorder = it) } }
            colorRow(AppearanceTerminology.PRIMARY_TEXT, current.uiColors.textPrimary) { updateUiColors { c -> c.copy(textPrimary = it) } }
            colorRow(AppearanceTerminology.SECONDARY_TEXT, current.uiColors.textSecondary) { updateUiColors { c -> c.copy(textSecondary = it) } }
            colorRow(AppearanceTerminology.ACCENT, current.uiColors.accent) { updateUiColors { c -> c.copy(accent = it) } }
            section("THERAPIE")
            colorRow("IOB", current.uiColors.iob) { updateUiColors { c -> c.copy(iob = it) } }
            colorRow("COB", current.uiColors.cob) { updateUiColors { c -> c.copy(cob = it) } }
            colorRow("Basal", current.uiColors.basal) { updateUiColors { c -> c.copy(basal = it) } }
        }

        settingsCategory("glucose", "Glukose", "Einheit und Bereichsfarben") {
            section("EINHEIT")
            root.addView(
                choiceRow(
                    listOf("Auto" to WatchGlucoseUnit.AAPS, "mg/dL" to WatchGlucoseUnit.MG_DL, "mmol/L" to WatchGlucoseUnit.MMOL_L),
                    current.glucoseUnit,
                ) { save(current.copy(glucoseUnit = it)) },
                cardParams(),
            )
            section("FARBEN")
            colorRow(AppearanceTerminology.GLUCOSE_LOW, current.uiColors.glucoseLow) { updateUiColors { c -> c.copy(glucoseLow = it) } }
            colorRow(AppearanceTerminology.GLUCOSE_IN_RANGE, current.uiColors.glucoseInRange) { updateUiColors { c -> c.copy(glucoseInRange = it) } }
            colorRow(AppearanceTerminology.GLUCOSE_HIGH, current.uiColors.glucoseHigh) { updateUiColors { c -> c.copy(glucoseHigh = it) } }
            section("GRÖSSE")
            root.addView(
                sliderCard("Glukosewert", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, current.glucoseScalePercent, { "$it %" }) {
                    save(current.copy(glucoseScalePercent = it), rebuild = false)
                },
                cardParams(),
            )
            root.addView(
                sliderCard("Trendpfeil", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, current.trendScalePercent, { "$it %" }) {
                    save(current.copy(trendScalePercent = it, trendArrowStyle = current.trendArrowStyle.copy(sizePercent = it)), rebuild = false)
                },
                cardParams(),
            )
            colorRow("Trendpfeil · Füllfarbe", current.trendArrowStyle.fillColor) {
                save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(fillColor = it)))
            }
            root.addView(switchRow("Trendpfeil-Kontur", current.trendArrowStyle.outlineEnabled) {
                save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineEnabled = it)))
            }, cardParams())
            if (current.trendArrowStyle.outlineEnabled) {
                colorRow("Trendpfeil · Konturfarbe", current.trendArrowStyle.outlineColor) {
                    save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineColor = it)))
                }
                root.addView(sliderCard("Konturdicke", 25, 400, (current.trendArrowStyle.outlineThicknessDp * 100).roundToInt(), { "${it / 100f} dp" }) {
                    save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineThicknessDp = it / 100f)), rebuild = false)
                }, cardParams())
            }
            root.addView(sliderCard("Trend-Deckkraft", 0, 100, (current.trendArrowStyle.alpha * 100).roundToInt(), { "$it %" }) {
                save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(alpha = it / 100f)), rebuild = false)
            }, cardParams())
        }

        settingsCategory("graph", "Graph", "Zeitraum, Punkte, Linien und Prognosen") {
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

            section("CGM-PUNKTE")
            root.addView(
                sliderCard(
                    title = "Punktgröße",
                    min = 15,
                    max = 60,
                    progress = (current.graphStyle.cgmDotRadiusDp * 10f).roundToInt(),
                    value = { String.format("%.1f dp", it / 10f) },
                ) { progress -> save(current.copy(graphStyle = current.graphStyle.copy(cgmDotRadiusDp = progress / 10f)), rebuild = false) },
                cardParams(),
            )
            root.addView(switchRow("Kontur", current.graphStyle.cgmDotOutlineEnabled) { save(current.copy(graphStyle = current.graphStyle.copy(cgmDotOutlineEnabled = it))) }, cardParams())
            root.addView(
                sliderCard(
                    title = "Konturbreite",
                    min = 25,
                    max = 300,
                    progress = (current.graphStyle.cgmDotOutlineWidthDp * 100f).roundToInt(),
                    value = { String.format("%.2f dp", it / 100f) },
                ) { progress -> save(current.copy(graphStyle = current.graphStyle.copy(cgmDotOutlineWidthDp = progress / 100f)), rebuild = false) },
                cardParams(),
            )

            section("FARBEN")
            colorRow(AppearanceTerminology.GRAPH_BACKGROUND, current.graphColors.graphBackground) { updateGraphColors { c -> c.copy(graphBackground = it) } }
            colorRow(AppearanceTerminology.GRAPH_LOW_AREA, current.graphColors.rangeLow) { updateGraphColors { c -> c.copy(rangeLow = it) } }
            colorRow(AppearanceTerminology.GRAPH_TARGET_AREA, current.graphColors.rangeInRange) { updateGraphColors { c -> c.copy(rangeInRange = it) } }
            colorRow(AppearanceTerminology.GRAPH_HIGH_AREA, current.graphColors.rangeHigh) { updateGraphColors { c -> c.copy(rangeHigh = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_LOW, current.graphColors.cgmLow) { updateGraphColors { c -> c.copy(cgmLow = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_IN_RANGE, current.graphColors.cgmInRange) { updateGraphColors { c -> c.copy(cgmInRange = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_HIGH, current.graphColors.cgmHigh) { updateGraphColors { c -> c.copy(cgmHigh = it) } }
            colorRow(AppearanceTerminology.GRAPH_HIGH_LINE, current.graphColors.highLine) { updateGraphColors { c -> c.copy(highLine = it) } }
            colorRow(AppearanceTerminology.GRAPH_LOW_LINE, current.graphColors.lowLine) { updateGraphColors { c -> c.copy(lowLine = it) } }
            colorRow(AppearanceTerminology.GRAPH_AXIS_TEXT, current.graphColors.axisLabel) { updateGraphColors { c -> c.copy(axisLabel = it) } }
            colorRow(AppearanceTerminology.GRAPH_AXIS_TICK, current.graphColors.axisTick) { updateGraphColors { c -> c.copy(axisTick = it) } }
            colorRow(AppearanceTerminology.GRAPH_NOW_LINE, current.graphColors.nowLine) { updateGraphColors { c -> c.copy(nowLine = it) } }
            colorRow(AppearanceTerminology.GRAPH_DIVIDER, current.graphColors.divider) { updateGraphColors { c -> c.copy(divider = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_OUTLINE, current.graphColors.outline) { updateGraphColors { c -> c.copy(outline = it) } }
            section("PROGNOSEN")
            colorRow(AppearanceTerminology.PREDICTION_IOB, current.graphColors.predictionIob) { updateGraphColors { c -> c.copy(predictionIob = it) } }
            colorRow(AppearanceTerminology.PREDICTION_COB, current.graphColors.predictionCob) { updateGraphColors { c -> c.copy(predictionCob = it) } }
            colorRow(AppearanceTerminology.PREDICTION_UAM, current.graphColors.predictionUam) { updateGraphColors { c -> c.copy(predictionUam = it) } }
            colorRow(AppearanceTerminology.PREDICTION_ZERO_TEMP, current.graphColors.predictionZeroTemp) { updateGraphColors { c -> c.copy(predictionZeroTemp = it) } }
        }

        settingsCategory("tiles", "Tiles und Complications", "Darstellung bleibt je Tile lokal getrennt") {
            val glucoseTileColors = WearTileAppearanceStore.read(this, WearTileKind.GLUCOSE, selectedAppearanceMode)
            section("TILE 1 · INHALT")
            tileContentRows(WearTileKind.GLUCOSE)
            section("TILE 1 · FARBEN")
            tileBaseColorRows(WearTileKind.GLUCOSE, glucoseTileColors)
            colorRow(AppearanceTerminology.GLUCOSE_LOW, glucoseTileColors.glucoseLow) { updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseLow = it) } }
            colorRow(AppearanceTerminology.GLUCOSE_IN_RANGE, glucoseTileColors.glucoseInRange) { updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseInRange = it) } }
            colorRow(AppearanceTerminology.GLUCOSE_HIGH, glucoseTileColors.glucoseHigh) { updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseHigh = it) } }

            val therapyTileColors = WearTileAppearanceStore.read(this, WearTileKind.THERAPY, selectedAppearanceMode)
            section("TILE 2 · INHALT")
            tileContentRows(WearTileKind.THERAPY)
            section("TILE 2 · FARBEN")
            tileBaseColorRows(WearTileKind.THERAPY, therapyTileColors)
            colorRow("IOB", therapyTileColors.iob) { updateTileColors(WearTileKind.THERAPY) { colors -> colors.copy(iob = it) } }
            colorRow("COB", therapyTileColors.cob) { updateTileColors(WearTileKind.THERAPY) { colors -> colors.copy(cob = it) } }
            colorRow("Basal", therapyTileColors.basal) { updateTileColors(WearTileKind.THERAPY) { colors -> colors.copy(basal = it) } }
        }

        settingsCategory("watchfaces", "Watchfaces", "Auswahl und weitere Gestaltung in der Mobile-App") {
            root.addView(infoCard("Watchface-Auswahl", "Sugarlicious Mobile öffnen und den Bereich Watchfaces wählen."), cardParams())
        }
        settingsCategory("connection", "Verbindung", "Synchronisierung und Hintergrundzugriff") {
            root.addView(actionCard("Akku-Optimierung", if (WearBackgroundAccess.isBatteryUnrestricted(this)) "Freigegeben" else "Prüfen") {
                WearBackgroundAccess.openBatterySettings(this)
            }, cardParams())
        }
        settingsCategory("diagnostics", "Diagnose", "Status, Freshness und Datenquelle") {
            root.addView(infoCard("Live-Status", "Zurück in der Übersicht werden Quelle, Alter und Verbindungsstatus angezeigt."), cardParams())
        }
        settingsCategory("about", "Über", "Sugarlicious Wear") {
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            root.addView(infoCard("Sugarlicious Wear", "Version $version · Einstellungen werden lokal gespeichert."), cardParams())
        }
        scroll.post { scroll.scrollTo(0, restoreScrollY) }
    }

    private fun updateGraphColors(transform: (WatchGraphColors) -> WatchGraphColors) {
        save(current.copy(graphColors = transform(current.graphColors)))
    }

    private fun updateUiColors(transform: (WatchUiColors) -> WatchUiColors) {
        save(current.copy(uiColors = transform(current.uiColors)))
    }

    private fun tileBaseColorRows(kind: WearTileKind, colors: WatchUiColors) {
        colorRow(AppearanceTerminology.APP_BACKGROUND, colors.background) {
            updateTileColors(kind) { value -> value.copy(background = it) }
        }
        colorRow(AppearanceTerminology.SURFACE_BACKGROUND, colors.tileBackground) {
            updateTileColors(kind) { value -> value.copy(tileBackground = it) }
        }
        colorRow(AppearanceTerminology.SURFACE_BORDER, colors.tileBorder) {
            updateTileColors(kind) { value -> value.copy(tileBorder = it) }
        }
        colorRow(AppearanceTerminology.PRIMARY_TEXT, colors.textPrimary) {
            updateTileColors(kind) { value -> value.copy(textPrimary = it) }
        }
        colorRow(AppearanceTerminology.SECONDARY_TEXT, colors.textSecondary) {
            updateTileColors(kind) { value -> value.copy(textSecondary = it) }
        }
        colorRow(AppearanceTerminology.ACCENT, colors.accent) {
            updateTileColors(kind) { value -> value.copy(accent = it) }
        }
    }

    private fun updateTileColors(kind: WearTileKind, transform: (WatchUiColors) -> WatchUiColors) {
        WearTileAppearanceStore.write(this, kind, selectedAppearanceMode, transform(WearTileAppearanceStore.read(this, kind, selectedAppearanceMode)))
        requestSugarliciousTileUpdates(this)
        buildUi()
    }

    private fun tileContentRows(kind: WearTileKind) {
        val selected = WearTileContentStore.read(this, kind)
        WearTileContent.entries.chunked(3).forEach { rowItems ->
            root.addView(
                choiceRow(rowItems.map { it.label to it }, selected) { content ->
                    WearTileContentStore.write(this, kind, content)
                    requestSugarliciousTileUpdates(this)
                    buildUi()
                },
                cardParams(),
            )
        }
    }

    private fun settingsCategory(
        key: String,
        title: String,
        summary: String,
        buildContent: () -> Unit,
    ) {
        if (selectedSettingsCategory != null) {
            if (selectedSettingsCategory == key) buildContent()
            return
        }
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(current.uiColors.textSecondary)
        }
        val header = LinearLayout(this).apply {
            tag = "settings-category-$key"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 58.dp
            setPadding(13.dp, 8.dp, 8.dp, 8.dp)
            background = cardBackground()
            isClickable = true
            isFocusable = true
            addView(LinearLayout(this@WearSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@WearSettingsActivity).apply {
                    text = title
                    textSize = 11f
                    setTextColor(current.uiColors.textPrimary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@WearSettingsActivity).apply {
                    text = summary
                    textSize = 8f
                    maxLines = 2
                    setTextColor(current.uiColors.textSecondary)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(28.dp, 38.dp))
            setOnClickListener {
                selectedSettingsCategory = key
                buildUi()
            }
        }
        root.addView(header, cardParams())
    }

    private fun infoCard(title: String, detail: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = cardBackground()
            addView(TextView(this@WearSettingsActivity).apply {
                text = title
                textSize = 10f
                setTextColor(current.uiColors.textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@WearSettingsActivity).apply {
                text = detail
                textSize = 8.5f
                setTextColor(current.uiColors.textSecondary)
            })
        }

    private fun actionCard(title: String, value: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = cardBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@WearSettingsActivity).apply {
                text = title
                textSize = 10f
                setTextColor(current.uiColors.textPrimary)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@WearSettingsActivity).apply {
                text = value
                textSize = 9f
                setTextColor(current.uiColors.accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
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

    private fun <T> choiceRow(
        choices: List<Pair<String, T>>,
        selected: T,
        changed: (T) -> Unit,
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
        SharedColorEditor.show(
            this, title, selected,
            current.uiColors.tileBackground, current.uiColors.textPrimary, current.uiColors.tileBorder,
            selected,
            onChange = changed,
            onReset = { changed(selected) },
        )
    }

    private fun save(value: WearDisplayPreferences, rebuild: Boolean = true) {
        val trendChanged = current.trendScalePercent != value.trendScalePercent
        current = value
        WearDisplayPreferences.saveLocal(this, selectedAppearanceMode, current)
        requestSugarliciousTileUpdates(this)
        if (trendChanged) {
            ComplicationUpdatePlanner.allManagedProviders.forEach { provider ->
                ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, provider)).requestUpdateAll()
            }
        }
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

}
