package app.aapswear.wear

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.model.AppearanceControlContract
import app.aapswear.model.AppearanceMode
import app.aapswear.model.AppearanceTerminology
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.TherapyMetricAppearance
import app.aapswear.model.TherapyProgressAppearance
import app.aapswear.model.TherapyProgressMetric
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchUiColors
import app.aapswear.uishared.SharedColorEditor
import app.aapswear.uishared.SharedNumberEditor
import kotlin.math.roundToInt

class WearSettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scrollView: ScrollView
    private var current = WearDisplayPreferences()
    private var selectedAppearanceMode = AppearanceMode.DARK
    private var selectedSettingsCategory: String? = null
    private var categoryListScrollY = 0
    private var colorEditorOpen = false

    // Settings stay legible while the user edits the display palette.
    private val menuColors =
        WatchUiColors(
            background = Color.BLACK,
            tileBackground = Color.rgb(35, 35, 38),
            tileBorder = Color.rgb(68, 68, 72),
            textPrimary = Color.WHITE,
            textSecondary = Color.rgb(177, 177, 185),
            accent = Color.rgb(137, 181, 255),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAppearanceMode = WearDisplayPreferences.activeAppearanceMode(this)
        current = WearDisplayPreferences.read(this, selectedAppearanceMode)
        buildUi()
    }

    private fun buildUi(scrollPosition: Int? = null) {
        val restoreScrollY = scrollPosition ?: if (::scrollView.isInitialized) scrollView.scrollY else 0
        current = WearDisplayPreferences.read(this, selectedAppearanceMode)
        val ui = menuColors
        val scroll =
            app.aapswear.uishared.WearSettingsScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(ui.background)
                isVerticalScrollBarEnabled = false
            }
        scrollView = scroll
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(18.dp, 32.dp, 18.dp, 56.dp)
            }
        scroll.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val pageTitle =
            when (selectedSettingsCategory) {
                "display" -> "App-Oberfläche"
                "glucose" -> "Glukose"
                "trend" -> "Trendpfeil"
                "delta" -> "Delta und Einheit"
                "therapy" -> "Therapie"
                "graph" -> "Graph"
                "tiles" -> "Tiles und Complications"
                "watchfaces" -> "Watchfaces"
                "connection" -> "Verbindung"
                "diagnostics" -> "Diagnose"
                "about" -> "Über"
                else -> getString(R.string.watch_settings_title)
            }
        root.addView(
            app.aapswear.uishared.WearSettingsUi.header(this, pageTitle, menuColors.textPrimary) {
                if (selectedSettingsCategory != null) {
                    selectedSettingsCategory = null
                    buildUi(scrollPosition = categoryListScrollY)
                } else {
                    finish()
                }
            },
            fullWidth(),
        )
        root.addView(
            choiceRow(
                listOf("Hell" to AppearanceMode.LIGHT, "Dunkel" to AppearanceMode.DARK),
                selectedAppearanceMode,
            ) { mode ->
                selectedAppearanceMode = mode
                WearDisplayPreferences.setActiveAppearanceMode(this, mode)
                buildUi()
            },
            cardParams(),
        )

        settingsCategory("display", "App-Oberfläche", "Hintergrund, Flächen und allgemeine Texte") {
            section("APP")
            colorRow(AppearanceTerminology.APP_BACKGROUND, current.uiColors.background, WatchUiColors().background) { updateUiColors { c -> c.copy(background = it) } }
            colorRow(AppearanceTerminology.SURFACE_BACKGROUND, current.uiColors.tileBackground, WatchUiColors().tileBackground) {
                updateUiColors { c ->
                    c.copy(tileBackground = it)
                }
            }
            colorRow(AppearanceTerminology.SURFACE_BORDER, current.uiColors.tileBorder, WatchUiColors().tileBorder) { updateUiColors { c -> c.copy(tileBorder = it) } }
            colorRow(AppearanceTerminology.PRIMARY_TEXT, current.uiColors.textPrimary, WatchUiColors().textPrimary) { updateUiColors { c -> c.copy(textPrimary = it) } }
            colorRow(AppearanceTerminology.SECONDARY_TEXT, current.uiColors.textSecondary, WatchUiColors().textSecondary) {
                updateUiColors { c ->
                    c.copy(textSecondary = it)
                }
            }
            colorRow(AppearanceTerminology.ACCENT, current.uiColors.accent, WatchUiColors().accent) { updateUiColors { c -> c.copy(accent = it) } }
        }

        settingsCategory("glucose", "Glukose", "Einheit, Zielbereich, Farben und Größe") {
            section("EINHEIT")
            root.addView(
                choiceRow(
                    listOf("Auto" to WatchGlucoseUnit.AAPS, "mg/dL" to WatchGlucoseUnit.MG_DL, "mmol/L" to WatchGlucoseUnit.MMOL_L),
                    current.glucoseUnit,
                ) { save(current.copy(glucoseUnit = it)) },
                cardParams(),
            )
            section("ZIELBEREICH")
            root.addView(
                sliderCard(
                    "Tief",
                    (current.cgmThresholds.veryLowMgDl.toInt() + 1).coerceAtLeast(40),
                    (current.cgmThresholds.highMgDl.toInt() - 1).coerceAtLeast(40),
                    current.cgmThresholds.lowMgDl.toInt(),
                    { formatGlucoseThreshold(it, current.glucoseUnit) },
                ) { value ->
                    save(current.copy(cgmThresholds = current.cgmThresholds.copy(lowMgDl = value.toDouble())))
                },
                cardParams(),
            )
            root.addView(
                sliderCard(
                    "Hoch",
                    (current.cgmThresholds.lowMgDl.toInt() + 1).coerceAtMost(300),
                    (current.cgmThresholds.veryHighMgDl.toInt() - 1).coerceAtLeast(current.cgmThresholds.lowMgDl.toInt() + 1),
                    current.cgmThresholds.highMgDl.toInt(),
                    { formatGlucoseThreshold(it, current.glucoseUnit) },
                ) { value ->
                    save(current.copy(cgmThresholds = current.cgmThresholds.copy(highMgDl = value.toDouble())))
                },
                cardParams(),
            )
            section("FARBEN")
            colorRow(AppearanceTerminology.GLUCOSE_LOW, current.uiColors.glucoseLow, WatchUiColors().glucoseLow) { updateUiColors { c -> c.copy(glucoseLow = it) } }
            colorRow(AppearanceTerminology.GLUCOSE_IN_RANGE, current.uiColors.glucoseInRange, WatchUiColors().glucoseInRange) {
                updateUiColors { c ->
                    c.copy(glucoseInRange = it)
                }
            }
            colorRow(AppearanceTerminology.GLUCOSE_HIGH, current.uiColors.glucoseHigh, WatchUiColors().glucoseHigh) { updateUiColors { c -> c.copy(glucoseHigh = it) } }
            section("GRÖSSE")
            root.addView(
                sliderCard("Glukosewert", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, current.glucoseScalePercent, {
                    "$it %"
                }) {
                    save(current.copy(glucoseScalePercent = it), rebuild = false)
                },
                cardParams(),
            )
        }

        settingsCategory("trend", "Trendpfeil", "Größe, Füllung, Kontur und Deckkraft") {
            root.addView(
                sliderCard("Trendpfeil", GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT, current.trendScalePercent, {
                    "$it %"
                }) {
                    save(
                        current.copy(trendScalePercent = it, trendArrowStyle = current.trendArrowStyle.copy(sizePercent = it)),
                        rebuild = false,
                    )
                },
                cardParams(),
            )
            colorRow(
                "Trendpfeil · Füllfarbe",
                current.trendArrowStyle.fillColor,
                app.aapswear.model.TrendArrowStyle
                    .defaults(selectedAppearanceMode, 0xFFFFFFFF.toInt())
                    .fillColor,
            ) {
                save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(fillColor = it)))
            }
            root.addView(
                switchRow("Trendpfeil-Kontur", current.trendArrowStyle.outlineEnabled) {
                    save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineEnabled = it)))
                },
                cardParams(),
            )
            if (current.trendArrowStyle.outlineEnabled) {
                colorRow(
                    "Trendpfeil · Konturfarbe",
                    current.trendArrowStyle.outlineColor,
                    app.aapswear.model.TrendArrowStyle
                        .defaults(selectedAppearanceMode, 0xFFFFFFFF.toInt())
                        .outlineColor,
                ) {
                    save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineColor = it)))
                }
                root.addView(
                    sliderCard(
                        "Konturdicke",
                        25,
                        400,
                        (current.trendArrowStyle.outlineThicknessDp * 100).roundToInt(),
                        { "${it / 100f} dp" },
                    ) {
                        save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(outlineThicknessDp = it / 100f)), rebuild = false)
                    },
                    cardParams(),
                )
            }
            root.addView(
                sliderCard("Trend-Deckkraft", 0, 100, (current.trendArrowStyle.alpha * 100).roundToInt(), { "$it %" }) {
                    save(current.copy(trendArrowStyle = current.trendArrowStyle.copy(alpha = it / 100f)), rebuild = false)
                },
                cardParams(),
            )
            root.addView(
                actionCard("Trend-Stil zurücksetzen", "Light/Dark getrennt") {
                    WearDisplayPreferences.resetTrendArrowStyle(this, selectedAppearanceMode)
                    buildUi()
                },
                cardParams(),
            )
        }

        settingsCategory("delta", "Delta und Einheit", "Farbe der Änderung und Einheit") {
            colorRow(AppearanceTerminology.DELTA_UNIT, current.uiColors.deltaUnit, WatchUiColors().deltaUnit) { updateUiColors { c -> c.copy(deltaUnit = it) } }
        }
        settingsCategory("therapy", "Therapie", "IOB, COB und Basal") {
            section("THERAPIE")
            colorRow("IOB", current.uiColors.iob, WatchUiColors().iob) { updateUiColors { c -> c.copy(iob = it) } }
            colorRow("COB", current.uiColors.cob, WatchUiColors().cob) { updateUiColors { c -> c.copy(cob = it) } }
            colorRow("Basal", current.uiColors.basal, WatchUiColors().basal) { updateUiColors { c -> c.copy(basal = it) } }
        }

        settingsCategory("graph", "Graph", "Zeitraum, Punkte, Linien und Prognosen") {
            section("ZEITSKALA")
            root.addView(
                choiceRow(WearDisplayPreferences.allowedGraphHours.map { getString(R.string.graph_hours, it) to it }, current.graphHours) {
                    save(current.copy(graphHours = it))
                },
                cardParams(),
            )
            root.addView(
                switchRow("IOB / COB / Basal anzeigen", current.showTherapyStats) { save(current.copy(showTherapyStats = it)) },
                cardParams(),
            )
            root.addView(
                switchRow("Kachelkontur", current.graphStyle.borderEnabled) {
                    save(current.copy(graphStyle = current.graphStyle.copy(borderEnabled = it)))
                },
                cardParams(),
            )

            section("CGM-PUNKTE")
            colorRow(AppearanceTerminology.GRAPH_DOT_LOW, current.graphColors.cgmLow, WatchGraphColors().cgmLow) { updateGraphColors { c -> c.copy(cgmLow = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_IN_RANGE, current.graphColors.cgmInRange, WatchGraphColors().cgmInRange) {
                updateGraphColors { c ->
                    c.copy(cgmInRange = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_DOT_HIGH, current.graphColors.cgmHigh, WatchGraphColors().cgmHigh) { updateGraphColors { c -> c.copy(cgmHigh = it) } }
            colorRow(AppearanceTerminology.GRAPH_DOT_OUTLINE, current.graphColors.outline, WatchGraphColors().outline) {
                updateGraphColors { c ->
                    c.copy(outline = it)
                }
            }

            root.addView(
                sliderCard(
                    title = "Punktgröße",
                    min = 15,
                    max = 60,
                    progress = (current.graphStyle.cgmDotRadiusDp * 10f).roundToInt(),
                    value = { String.format(java.util.Locale.GERMANY, "%.1f dp", it / 10f) },
                ) { progress ->
                    save(
                        current.copy(graphStyle = current.graphStyle.copy(cgmDotRadiusDp = progress / 10f)),
                        rebuild = false,
                    )
                },
                cardParams(),
            )
            root.addView(
                switchRow(AppearanceTerminology.GRAPH_HISTORICAL_DOT_OUTLINE, current.graphStyle.cgmHistoricalDotOutlineEnabled) {
                    save(
                        current.copy(
                            graphStyle = current.graphStyle.copy(cgmDotOutlineEnabled = true, cgmHistoricalDotOutlineEnabled = it),
                        ),
                    )
                },
                cardParams(),
            )
            root.addView(
                sliderCard(
                    title = AppearanceTerminology.GRAPH_HISTORICAL_DOT_OUTLINE_WIDTH,
                    min = 25,
                    max = 300,
                    progress = (current.graphStyle.cgmHistoricalDotOutlineWidthDp * 100f).roundToInt(),
                    value = { String.format(java.util.Locale.GERMANY, "%.2f dp", it / 100f) },
                ) { progress ->
                    save(
                        current.copy(
                            graphStyle =
                                current.graphStyle.copy(
                                    cgmHistoricalDotOutlineWidthDp = AppearanceControlContract.quantizeDotOutlineWidth(progress / 100f),
                                ),
                        ),
                        rebuild = false,
                    )
                },
                cardParams(),
            )
            root.addView(
                sliderCard(
                    title = AppearanceTerminology.GRAPH_CURRENT_DOT_OUTLINE_WIDTH,
                    min = 25,
                    max = 300,
                    progress = (current.graphStyle.cgmCurrentDotOutlineWidthDp * 100f).roundToInt(),
                    value = { String.format(java.util.Locale.GERMANY, "%.2f dp", it / 100f) },
                ) { progress ->
                    save(
                        current.copy(
                            graphStyle =
                                current.graphStyle.copy(
                                    cgmCurrentDotOutlineWidthDp = AppearanceControlContract.quantizeDotOutlineWidth(progress / 100f),
                                ),
                        ),
                        rebuild = false,
                    )
                },
                cardParams(),
            )
            section("SKALA")
            root.addView(
                sliderCard(
                    title = "Skalenbereich",
                    min = 0,
                    max = 100,
                    progress = current.graphStyle.scaleLaneOpacityPercent,
                    value = { "$it %" },
                ) { progress ->
                    save(
                        current.copy(graphStyle = current.graphStyle.copy(scaleLaneOpacityPercent = progress)),
                        rebuild = false,
                    )
                },
                cardParams(),
            )

            section("GRAPHFLÄCHE UND ZIELBEREICH")
            colorRow(AppearanceTerminology.GRAPH_BACKGROUND, current.graphColors.graphBackground, WatchGraphColors().graphBackground) {
                updateGraphColors { c ->
                    c.copy(graphBackground = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_LOW_AREA, current.graphColors.rangeLow, WatchGraphColors().rangeLow) {
                updateGraphColors { c ->
                    c.copy(rangeLow = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_TARGET_AREA, current.graphColors.rangeInRange, WatchGraphColors().rangeInRange) {
                updateGraphColors { c ->
                    c.copy(rangeInRange = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_HIGH_AREA, current.graphColors.rangeHigh, WatchGraphColors().rangeHigh) {
                updateGraphColors { c ->
                    c.copy(rangeHigh = it)
                }
            }
            section("LINIEN UND BESCHRIFTUNG")
            colorRow(AppearanceTerminology.GRAPH_HIGH_LINE, current.graphColors.highLine, WatchGraphColors().highLine) {
                updateGraphColors { c ->
                    c.copy(highLine = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_LOW_LINE, current.graphColors.lowLine, WatchGraphColors().lowLine) { updateGraphColors { c -> c.copy(lowLine = it) } }
            colorRow(AppearanceTerminology.GRAPH_AXIS_TEXT, current.graphColors.axisLabel, WatchGraphColors().axisLabel) {
                updateGraphColors { c ->
                    c.copy(axisLabel = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_AXIS_TICK, current.graphColors.axisTick, WatchGraphColors().axisTick) {
                updateGraphColors { c ->
                    c.copy(axisTick = it)
                }
            }
            colorRow(AppearanceTerminology.GRAPH_NOW_LINE, current.graphColors.nowLine, WatchGraphColors().nowLine) { updateGraphColors { c -> c.copy(nowLine = it) } }
            colorRow(AppearanceTerminology.GRAPH_DIVIDER, current.graphColors.divider, WatchGraphColors().divider) { updateGraphColors { c -> c.copy(divider = it) } }
            section("PROGNOSEN")
            root.addView(
                switchRow("Prognosen anzeigen", current.showPredictions) { save(current.copy(showPredictions = it)) },
                cardParams(),
            )

            colorRow(AppearanceTerminology.PREDICTION_IOB, current.graphColors.predictionIob, WatchGraphColors().predictionIob) {
                updateGraphColors { c ->
                    c.copy(predictionIob = it)
                }
            }
            colorRow(AppearanceTerminology.PREDICTION_COB, current.graphColors.predictionCob, WatchGraphColors().predictionCob) {
                updateGraphColors { c ->
                    c.copy(predictionCob = it)
                }
            }
            colorRow(AppearanceTerminology.PREDICTION_UAM, current.graphColors.predictionUam, WatchGraphColors().predictionUam) {
                updateGraphColors { c ->
                    c.copy(predictionUam = it)
                }
            }
            colorRow(AppearanceTerminology.PREDICTION_ZERO_TEMP, current.graphColors.predictionZeroTemp, WatchGraphColors().predictionZeroTemp) {
                updateGraphColors { c ->
                    c.copy(predictionZeroTemp = it)
                }
            }
        }

        settingsCategory("tiles", "Tiles und Complications", "Darstellung bleibt je Tile lokal getrennt") {
            val glucoseTileColors = WearTileAppearanceStore.read(this, WearTileKind.GLUCOSE, selectedAppearanceMode)
            section("TILE 1 · INHALT")
            tileContentRows(WearTileKind.GLUCOSE)
            section("TILE 1 · FARBEN")
            tileBaseColorRows(WearTileKind.GLUCOSE, glucoseTileColors)
            colorRow(AppearanceTerminology.GLUCOSE_LOW, glucoseTileColors.glucoseLow, WatchUiColors().glucoseLow) {
                updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseLow = it) }
            }
            colorRow(AppearanceTerminology.GLUCOSE_IN_RANGE, glucoseTileColors.glucoseInRange, WatchUiColors().glucoseInRange) {
                updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseInRange = it) }
            }
            colorRow(AppearanceTerminology.GLUCOSE_HIGH, glucoseTileColors.glucoseHigh, WatchUiColors().glucoseHigh) {
                updateTileColors(WearTileKind.GLUCOSE) { colors -> colors.copy(glucoseHigh = it) }
            }

            val therapyTileColors = WearTileAppearanceStore.read(this, WearTileKind.THERAPY, selectedAppearanceMode)
            section("TILE 2 · THERAPIE")
            therapyGeometryRows()
            section("TILE 2 · FARBEN")
            tileBaseColorRows(WearTileKind.THERAPY, therapyTileColors)
        }

        settingsCategory("watchfaces", "Watchfaces", "Auswahl und weitere Gestaltung in der Mobile-App") {
            root.addView(infoCard("Watchface-Auswahl", "Sugarlicious Mobile öffnen und den Bereich Watchfaces wählen."), cardParams())
        }
        settingsCategory("connection", "Verbindung", "Synchronisierung und Hintergrundzugriff") {
            root.addView(
                actionCard("Akku-Optimierung", if (WearBackgroundAccess.isBatteryUnrestricted(this)) "Freigegeben" else "Prüfen") {
                    WearBackgroundAccess.openBatterySettings(this)
                },
                cardParams(),
            )
        }
        settingsCategory("diagnostics", "Diagnose", "Status, Freshness und Datenquelle") {
            root.addView(
                infoCard("Live-Status", "Zurück in der Übersicht werden Quelle, Alter und Verbindungsstatus angezeigt."),
                cardParams(),
            )
        }
        settingsCategory("about", "Über", "Sugarlicious Wear") {
            root.addView(
                infoCard(
                    "Sugarlicious Wear",
                    app.aapswear.uishared.AppReleaseInfo
                        .summary(this, app.aapswear.uishared.AppReleaseInfo.Product.WEAR),
                ),
                cardParams(),
            )
        }
        scroll.post { scroll.scrollTo(0, restoreScrollY) }
    }

    private fun updateGraphColors(transform: (WatchGraphColors) -> WatchGraphColors) {
        save(current.copy(graphColors = transform(current.graphColors)))
    }

    private fun updateUiColors(transform: (WatchUiColors) -> WatchUiColors) {
        save(current.copy(uiColors = transform(current.uiColors)))
    }

    private fun tileBaseColorRows(
        kind: WearTileKind,
        colors: WatchUiColors,
    ) {
        colorRow(AppearanceTerminology.APP_BACKGROUND, colors.background, WatchUiColors().background) {
            updateTileColors(kind) { value -> value.copy(background = it) }
        }
        colorRow(AppearanceTerminology.SURFACE_BACKGROUND, colors.tileBackground, WatchUiColors().tileBackground) {
            updateTileColors(kind) { value -> value.copy(tileBackground = it) }
        }
        colorRow(AppearanceTerminology.SURFACE_BORDER, colors.tileBorder, WatchUiColors().tileBorder) {
            updateTileColors(kind) { value -> value.copy(tileBorder = it) }
        }
        colorRow(AppearanceTerminology.PRIMARY_TEXT, colors.textPrimary, WatchUiColors().textPrimary) {
            updateTileColors(kind) { value -> value.copy(textPrimary = it) }
        }
        colorRow(AppearanceTerminology.SECONDARY_TEXT, colors.textSecondary, WatchUiColors().textSecondary) {
            updateTileColors(kind) { value -> value.copy(textSecondary = it) }
        }
        colorRow(AppearanceTerminology.DELTA_UNIT, colors.deltaUnit, WatchUiColors().deltaUnit) {
            updateTileColors(kind) { value -> value.copy(deltaUnit = it) }
        }
        colorRow(AppearanceTerminology.ACCENT, colors.accent, WatchUiColors().accent) {
            updateTileColors(kind) { value -> value.copy(accent = it) }
        }
    }

    private fun updateTileColors(
        kind: WearTileKind,
        transform: (WatchUiColors) -> WatchUiColors,
    ) {
        WearTileAppearanceStore.write(
            this,
            kind,
            selectedAppearanceMode,
            transform(WearTileAppearanceStore.read(this, kind, selectedAppearanceMode)),
        )
        requestSugarliciousTileUpdates(this)
        if (!colorEditorOpen) buildUi()
    }

    private fun tileContentRows(kind: WearTileKind) {
        val selected = WearTileContentStore.read(this, kind)
        listOf(WearTileContent.GLUCOSE, WearTileContent.GRAPH).chunked(3).forEach { rowItems ->
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

    private fun therapyGeometryRows() {
        val appearance = TherapyTileGeometryStore.read(this)

        fun save(value: TherapyProgressAppearance) {
            TherapyTileGeometryStore.write(this, value.validated())
            requestSugarliciousTileUpdates(this, setOf(TherapyTileService::class.java))
            buildUi()
        }

        fun addSlider(
            title: String,
            range: IntRange,
            value: Int,
            suffix: String = "%",
            changed: (Int) -> Unit,
        ) {
            root.addView(sliderCard(title, range.first, range.last, value, { "$it$suffix" }, changed), cardParams())
        }
        addSlider("Gesamtgröße", 50..150, appearance.groupScalePercent) { save(appearance.copy(groupScalePercent = it)) }
        addSlider("Abstand", 0..24, appearance.gapDp, " dp") { save(appearance.copy(gapDp = it)) }
        addSlider("Alle · Ringdicke", 50..200, appearance.global.ringThicknessPercent) {
            save(appearance.copy(global = appearance.global.copy(ringThicknessPercent = it)))
        }
        addSlider("Alle · obere Zeile", 60..180, appearance.global.primaryTextPercent) {
            save(appearance.copy(global = appearance.global.copy(primaryTextPercent = it)))
        }
        addSlider("Alle · untere Zeile", 60..180, appearance.global.secondaryTextPercent) {
            save(appearance.copy(global = appearance.global.copy(secondaryTextPercent = it)))
        }
        addSlider("Alle · Icon", 50..180, appearance.global.iconPercent) {
            save(appearance.copy(global = appearance.global.copy(iconPercent = it)))
        }
        TherapyProgressMetric.entries.forEach { metric ->
            val label = metric.toTileMetric().label
            section(label)
            val local = appearance.metricOverrides[metric] ?: TherapyMetricAppearance()
            root.addView(
                switchRow("Anzeigen", metric in appearance.enabledMetrics) { enabled ->
                    val selected = if (enabled) appearance.enabledMetrics + metric else appearance.enabledMetrics - metric
                    if (selected.isNotEmpty()) save(appearance.copy(enabledMetrics = selected)) else buildUi()
                },
                cardParams(),
            )
            val colors = WearTileAppearanceStore.read(this, WearTileKind.THERAPY, selectedAppearanceMode)
            when (metric) {
                TherapyProgressMetric.IOB -> colorRow("Farbe", colors.iob, WatchUiColors().iob) { updateTileColors(WearTileKind.THERAPY) { c -> c.copy(iob = it) } }
                TherapyProgressMetric.COB -> colorRow("Farbe", colors.cob, WatchUiColors().cob) { updateTileColors(WearTileKind.THERAPY) { c -> c.copy(cob = it) } }
                TherapyProgressMetric.BASAL -> colorRow("Farbe", colors.basal, WatchUiColors().basal) { updateTileColors(WearTileKind.THERAPY) { c -> c.copy(basal = it) } }
            }

            fun update(transform: (TherapyMetricAppearance) -> TherapyMetricAppearance) =
                save(appearance.copy(metricOverrides = appearance.metricOverrides + (metric to transform(local))))
            addSlider("$label · Gesamtgröße", 60..140, local.overallScalePercent) { update { value -> value.copy(overallScalePercent = it) } }
            addSlider("$label · Ringdicke", 50..200, local.ringThicknessPercent) { update { value -> value.copy(ringThicknessPercent = it) } }
            addSlider("$label · obere Zeile", 60..180, local.primaryTextPercent) { update { value -> value.copy(primaryTextPercent = it) } }
            addSlider("$label · untere Zeile", 60..180, local.secondaryTextPercent) { update { value -> value.copy(secondaryTextPercent = it) } }
            addSlider("$label · Icon", 50..180, local.iconPercent) { update { value -> value.copy(iconPercent = it) } }
        }
        root.addView(
            actionCard("Progressanzeigen zurücksetzen", "Standard") { save(TherapyProgressAppearance()) },
            cardParams(),
        )
    }

    private fun settingsCategory(
        key: String,
        title: String,
        summary: String,
        buildContent: () -> Unit,
    ) {
        if (selectedSettingsCategory != null) {
            if (selectedSettingsCategory == key) {
                buildContent()
            }
            return
        }
        val chevron =
            TextView(this).apply {
                text = "›"
                textSize =
                    app.aapswear.uishared.WearSettingsUi
                        .textSize(19f)
                gravity = Gravity.CENTER
                setTextColor(menuColors.textSecondary)
            }
        val header =
            LinearLayout(this).apply {
                tag = "settings-category-$key"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 58.dp
                setPadding(13.dp, 8.dp, 8.dp, 8.dp)
                background = cardBackground()
                isClickable = true
                isFocusable = true
                addView(
                    LinearLayout(this@WearSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(this@WearSettingsActivity).apply {
                                text = title
                                textSize =
                                    app.aapswear.uishared.WearSettingsUi
                                        .textSize(11f)
                                setTextColor(menuColors.textPrimary)
                                app.aapswear.uishared.SystemTypography
                                    .apply(this, true)
                            },
                        )
                        addView(
                            TextView(this@WearSettingsActivity).apply {
                                text = summary
                                app.aapswear.uishared.SystemTypography
                                    .apply(this, false)
                                textSize =
                                    app.aapswear.uishared.WearSettingsUi
                                        .textSize(8f)
                                setTextColor(menuColors.textSecondary)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(chevron, LinearLayout.LayoutParams(28.dp, 38.dp))
                setOnClickListener {
                    categoryListScrollY = scrollView.scrollY
                    selectedSettingsCategory = key
                    buildUi(scrollPosition = 0)
                }
            }
        root.addView(header, cardParams())
    }

    private fun infoCard(
        title: String,
        detail: String,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = cardBackground()
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = title
                    textSize =
                        app.aapswear.uishared.WearSettingsUi
                            .textSize(10f)
                    setTextColor(menuColors.textPrimary)
                    app.aapswear.uishared.SystemTypography
                        .apply(this, true)
                },
            )
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = detail
                    app.aapswear.uishared.SystemTypography
                        .apply(this, false)
                    textSize =
                        app.aapswear.uishared.WearSettingsUi
                            .textSize(8.5f)
                    setTextColor(menuColors.textSecondary)
                },
            )
        }

    private fun actionCard(
        title: String,
        value: String,
        action: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13.dp, 10.dp, 13.dp, 10.dp)
            background = cardBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = title
                    textSize =
                        app.aapswear.uishared.WearSettingsUi
                            .textSize(10f)
                    setTextColor(menuColors.textPrimary)
                    app.aapswear.uishared.SystemTypography
                        .apply(this, true)
                },
                fullWidth(),
            )
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = value
                    textSize = 15f
                    setTextColor(menuColors.accent)
                    app.aapswear.uishared.SystemTypography
                        .apply(this, true)
                },
            )
        }

    private fun section(text: String) {
        root.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(menuColors.accent)
                app.aapswear.uishared.SystemTypography
                    .apply(this, true)
                letterSpacing = 0.08f
                setPadding(5.dp, 15.dp, 5.dp, 6.dp)
            },
            fullWidth(),
        )
    }

    private fun switchRow(
        title: String,
        checked: Boolean,
        changed: (Boolean) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(13.dp, 8.dp, 10.dp, 8.dp)
            background = cardBackground()
            addView(
                TextView(this@WearSettingsActivity).apply {
                    text = title
                    textSize =
                        app.aapswear.uishared.WearSettingsUi
                            .textSize(10f)
                    setTextColor(menuColors.textPrimary)
                    app.aapswear.uishared.SystemTypography
                        .apply(this, true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                app.aapswear.uishared.ScrollSafeSwitch(this@WearSettingsActivity).apply {
                    isChecked = checked
                    thumbTintList =
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(Color.WHITE, menuColors.textSecondary),
                        )
                    trackTintList =
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(menuColors.accent, menuColors.tileBorder),
                        )
                    app.aapswear.uishared.WearSettingsUi
                        .toggle(this, menuColors.accent, menuColors.textPrimary)
                    setOnCheckedChangeListener { _, value -> changed(value) }
                },
            )
        }

    private fun <T> choiceRow(
        choices: List<Pair<String, T>>,
        selected: T,
        changed: (T) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(5.dp, 5.dp, 5.dp, 5.dp)
            choices.forEach { (label, value) ->
                val active = selected == value
                addView(
                    Button(this@WearSettingsActivity).apply {
                        text = if (active) "✓  $label" else label
                        app.aapswear.uishared.SystemTypography
                            .apply(this, active)
                        textSize = 15f
                        isAllCaps = false
                        minimumHeight = 44.dp
                        setPadding(10.dp, 8.dp, 10.dp, 8.dp)
                        minWidth = 0
                        minimumWidth = 0
                        setTextColor(menuColors.textPrimary)
                        background = pillBackground(active)
                        setOnClickListener { changed(value) }
                    },
                    fullWidth().apply {
                        marginStart = 1.dp
                        marginEnd = 1.dp
                    },
                )
            }
        }

    // This listener only arbitrates parent scrolling and returns false, so the
    // native SeekBar retains its own click and accessibility semantics.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun sliderCard(
        title: String,
        min: Int,
        max: Int,
        progress: Int,
        value: (Int) -> String,
        changed: (Int) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp, 9.dp, 13.dp, 8.dp)
            background = cardBackground()
            var selectedValue = progress.coerceIn(min, max)
            lateinit var slider: app.aapswear.uishared.ScrollSafeSeekBar
            val valueText =
                TextView(this@WearSettingsActivity).apply {
                    text = value(progress)
                    textSize = 15f
                    gravity = Gravity.END
                    setTextColor(menuColors.accent)
                    app.aapswear.uishared.SystemTypography
                        .apply(this, true)
                    setPadding(10.dp, 5.dp, 10.dp, 5.dp)
                    setOnClickListener {
                        SharedNumberEditor.show(this@WearSettingsActivity, title, selectedValue, min, max) { selected ->
                            selectedValue = selected
                            text = value(selected)
                            slider.progress = selected - min
                            changed(selected)
                        }
                    }
                }
            addView(
                LinearLayout(this@WearSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@WearSettingsActivity).apply {
                            text = title
                            textSize =
                                app.aapswear.uishared.WearSettingsUi
                                    .textSize(10f)
                            setTextColor(menuColors.textPrimary)
                            app.aapswear.uishared.SystemTypography
                                .apply(this, true)
                        },
                        fullWidth(),
                    )
                    addView(valueText, fullWidth())
                },
                fullWidth(),
            )
            slider =
                app.aapswear.uishared.ScrollSafeSeekBar(this@WearSettingsActivity).apply {
                    this.max = max - min
                    this.progress = progress.coerceIn(min, max) - min
                    progressTintList = ColorStateList.valueOf(menuColors.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(menuColors.tileBorder)
                    thumbTintList = ColorStateList.valueOf(menuColors.accent)
                    app.aapswear.uishared.WearSettingsUi
                        .slider(this, menuColors.accent, menuColors.tileBorder, title)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar?,
                                raw: Int,
                                fromUser: Boolean,
                            ) {
                                if (!fromUser) return
                                selectedValue = raw + min
                                valueText.text = value(selectedValue)
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                            override fun onStopTrackingTouch(seekBar: SeekBar?) = changed(selectedValue)
                        },
                    )
                }
            addView(slider, fullWidth())
        }

    private fun colorRow(
        title: String,
        color: Int,
        defaultColor: Int,
        changed: (Int) -> Unit,
    ) {
        root.addView(
            LinearLayout(this).apply {
                minimumHeight = 48.dp
                setOnClickListener { showColorPicker(title, color, defaultColor, changed) }
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(13.dp, 8.dp, 11.dp, 8.dp)
                background = cardBackground()
                addView(
                    TextView(this@WearSettingsActivity).apply {
                        text = title
                        textSize =
                            app.aapswear.uishared.WearSettingsUi
                                .textSize(10f)
                        setTextColor(menuColors.textPrimary)
                        app.aapswear.uishared.SystemTypography
                            .apply(this, true)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    View(this@WearSettingsActivity).apply {
                        background = colorCircle(color)
                        contentDescription = title
                    },
                    LinearLayout.LayoutParams(32.dp, 32.dp),
                )
            },
            cardParams(),
        )
    }

    private fun showColorPicker(
        title: String,
        selected: Int,
        defaultColor: Int,
        changed: (Int) -> Unit,
    ) {
        colorEditorOpen = true
        SharedColorEditor.show(
            this,
            title,
            selected,
            menuColors.tileBackground,
            menuColors.textPrimary,
            menuColors.tileBorder,
            defaultColor,
            onChange = changed,
            onReset = { changed(defaultColor) },
            onDismiss = {
                colorEditorOpen = false
                buildUi()
            },
        )
    }

    private fun formatGlucoseThreshold(
        valueMgDl: Int,
        unit: WatchGlucoseUnit,
    ): String =
        if (unit == WatchGlucoseUnit.MMOL_L) {
            String.format(java.util.Locale.GERMANY, "%.1f mmol/L", valueMgDl / 18.0)
        } else {
            "$valueMgDl mg/dL"
        }

    private fun save(
        value: WearDisplayPreferences,
        rebuild: Boolean = true,
    ) {
        val trendChanged = current.trendScalePercent != value.trendScalePercent
        current = value
        WearDisplayPreferences.saveLocal(this, selectedAppearanceMode, current)
        requestSugarliciousTileUpdates(this)
        if (trendChanged) {
            ComplicationUpdatePlanner.allManagedProviders.forEach { provider ->
                app.aapswear.complications.ActiveProviderUpdates
                    .request(this, provider)
            }
        }
        if (rebuild && !colorEditorOpen) buildUi()
    }

    private fun cardBackground(radiusDp: Float = 20f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = maxOf(radiusDp, 26f) * resources.displayMetrics.density
            setColor(this@WearSettingsActivity.menuColors.tileBackground)
            setStroke(0, this@WearSettingsActivity.menuColors.tileBorder)
        }

    private fun pillBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 17.dp.toFloat()
            setColor(
                if (selected) {
                    Color.rgb(47, 64, 88)
                } else {
                    this@WearSettingsActivity
                        .menuColors.tileBackground
                },
            )
            setStroke(
                1.dp,
                if (selected) this@WearSettingsActivity.menuColors.accent else this@WearSettingsActivity.menuColors.tileBorder,
            )
        }

    private fun colorCircle(
        color: Int,
        selected: Boolean = false,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                if (selected) 3.dp else 1.dp,
                if (selected) this@WearSettingsActivity.menuColors.accent else this@WearSettingsActivity.menuColors.tileBorder,
            )
        }

    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun cardParams() =
        fullWidth().apply {
            topMargin = 3.dp
            bottomMargin = 3.dp
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()
}
