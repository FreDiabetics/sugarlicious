package app.aapswear.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousTheme
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.CgmThresholds
import app.aapswear.model.TherapyDisplayState
import java.util.Locale

enum class DashboardScreen { OVERVIEW, WATCH, SETTINGS }
enum class DisplayUnitPreference { AAPS, MG_DL, MMOL_L }
enum class DashboardThemeMode { SYSTEM, LIGHT, DARK }
enum class GlucoseTileDetailMode { TIR, THERAPY }

internal fun thresholdForUi(valueMgDl: Double, unit: DisplayUnitPreference): Float =
    if (unit == DisplayUnitPreference.MMOL_L) (valueMgDl / 18.0).toFloat() else valueMgDl.toFloat()

internal fun thresholdFromUi(value: Float, unit: DisplayUnitPreference): Double =
    if (unit == DisplayUnitPreference.MMOL_L) value * 18.0 else value.toDouble()

internal fun formatThreshold(valueMgDl: Double, unit: DisplayUnitPreference): String =
    if (unit == DisplayUnitPreference.MMOL_L) {
        String.format(Locale.GERMANY, "%.1f mmol/L", valueMgDl / 18.0)
    } else {
        "${valueMgDl.toInt()} mg/dL"
    }

data class DashboardUiPreferences(
    val unit: DisplayUnitPreference = DisplayUnitPreference.AAPS,
    val showDetails: Boolean = true,
    val glucoseTileDetailMode: GlucoseTileDetailMode = GlucoseTileDetailMode.TIR,
    val iobProgressMaximumUnits: Float = 10f,
    val showCgmGraph: Boolean = true,
    val showCgmTargetValue: Boolean = true,
    val showCgmBasal: Boolean = false,
    val showCgmActivity: Boolean = false,
    val showCgmPredictionIob: Boolean = false,
    val showCgmPredictionCob: Boolean = false,
    val showCgmPredictionUam: Boolean = false,
    val showCgmPredictionZeroTemp: Boolean = false,
    val showMetabolicGraph: Boolean = false,
    val showMealBolusMarkers: Boolean = true,
    val showCorrectionMarkers: Boolean = true,
    val showSmbMarkers: Boolean = true,
    val showMealCarbMarkers: Boolean = true,
    val showECarbMarkers: Boolean = true,
    val cgmDotRadiusDp: Float = 2.4f,
    val cgmDotOutlineEnabled: Boolean = true,
    val cgmDotOutlineWidthDp: Float = 0.95f,
    val predictionDotRadiusDp: Float = 1.75f,
    val predictionDotOutlineWidthDp: Float = 0.70f,
    val compact: Boolean = true,
    val graphHours: Int = 3,
    val liveNotification: Boolean = false,
    val notificationGraphEnabled: Boolean = true,
    val notificationGraphHours: Int = 3,
    val watchFaceIndex: Int = 1,
    val dataSource: DataSourcePreference = DataSourcePreference.ANDROID_APS,
    val themeMode: DashboardThemeMode = DashboardThemeMode.SYSTEM,
    val cgmThresholds: CgmThresholds = CgmThresholds.DEFAULT,
    val glucoseScalePercent: Int = GlucoseTrendSizing.DEFAULT_SCALE_PERCENT,
    val trendScalePercent: Int = GlucoseTrendSizing.DEFAULT_SCALE_PERCENT,
) {
    val effectiveShowDetails: Boolean
        get() = glucoseTileDetailMode == GlucoseTileDetailMode.TIR && showDetails
    val anyCgmPredictionEnabled: Boolean
        get() =
            showCgmPredictionIob ||
                showCgmPredictionCob ||
                showCgmPredictionUam ||
                showCgmPredictionZeroTemp

    fun unitFor(state: TherapyDisplayState?): GlucoseUnit = when (unit) {
        DisplayUnitPreference.AAPS -> state?.glucose?.displayUnit ?: GlucoseUnit.MG_DL
        DisplayUnitPreference.MG_DL -> GlucoseUnit.MG_DL
        DisplayUnitPreference.MMOL_L -> GlucoseUnit.MMOL_L
    }

    companion object {
        fun read(preferences: SharedPreferences) =
            DashboardUiPreferences(
                unit = runCatching {
                    DisplayUnitPreference.valueOf(preferences.getString("unit", "AAPS")!!)
                }.getOrDefault(DisplayUnitPreference.AAPS),
                showDetails = preferences.getBoolean("showDetails", true),
                glucoseTileDetailMode = runCatching {
                    GlucoseTileDetailMode.valueOf(preferences.getString(GLUCOSE_TILE_DETAIL_MODE_KEY, GlucoseTileDetailMode.TIR.name)!!)
                }.getOrDefault(GlucoseTileDetailMode.TIR),
                iobProgressMaximumUnits = preferences.getFloat(IOB_PROGRESS_MAXIMUM_KEY, 10f).coerceIn(0f, 30f),
                showCgmGraph = preferences.getBoolean("showCgmGraph", true),
                showCgmTargetValue = preferences.getBoolean("cgm.targetValue", true),
                showCgmBasal = preferences.getBoolean("cgm.basal", false),
                showCgmActivity = preferences.getBoolean("cgm.activity", false),
                showCgmPredictionIob = preferences.getBoolean("cgm.prediction.iob", false),
                showCgmPredictionCob = preferences.getBoolean("cgm.prediction.cob", false),
                showCgmPredictionUam = preferences.getBoolean("cgm.prediction.uam", false),
                showCgmPredictionZeroTemp = preferences.getBoolean("cgm.prediction.zeroTemp", false),
                showMetabolicGraph = preferences.getBoolean("showMetabolicGraph", false),
                showMealBolusMarkers = preferences.getBoolean("treatment.mealBolus", true),
                showCorrectionMarkers = preferences.getBoolean("treatment.correction", true),
                showSmbMarkers = preferences.getBoolean("treatment.smb", true),
                showMealCarbMarkers = preferences.getBoolean("treatment.mealCarbs", true),
                showECarbMarkers = preferences.getBoolean("treatment.eCarbs", true),
                cgmDotRadiusDp = readMobileGraphStyle(preferences).cgmDotRadiusDp,
                cgmDotOutlineEnabled = readMobileGraphStyle(preferences).cgmDotOutlineEnabled,
                cgmDotOutlineWidthDp = readMobileGraphStyle(preferences).cgmDotOutlineWidthDp,
                predictionDotRadiusDp = readMobilePredictionDotRadius(preferences),
                predictionDotOutlineWidthDp = readMobilePredictionDotOutlineWidth(preferences),
                compact = preferences.getBoolean("compact", true),
                graphHours = preferences.getInt("graphHours", 3).takeIf { it in OVERVIEW_GRAPH_HOUR_OPTIONS } ?: 3,
                liveNotification = preferences.getBoolean(PersistentBridgeService.PREFERENCE_LIVE_NOTIFICATION, false),
                notificationGraphEnabled = preferences.getBoolean(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_ENABLED, true),
                notificationGraphHours = preferences.getInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 3).takeIf { it in 1..3 } ?: 3,
                watchFaceIndex = preferences.getInt("watchFaceIndex", 1).coerceIn(sugarliciousWatchFaceCards.indices),
                dataSource = DataSourcePreference.ANDROID_APS,
                themeMode = runCatching {
                    DashboardThemeMode.valueOf(preferences.getString("themeMode", "SYSTEM")!!)
                }.getOrDefault(DashboardThemeMode.SYSTEM),
                cgmThresholds = CgmThresholdPreferences.read(preferences),
                glucoseScalePercent = preferences.getInt(MOBILE_GLUCOSE_SCALE_KEY, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
                    .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT),
                trendScalePercent = preferences.getInt(MOBILE_TREND_SCALE_KEY, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
                    .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT),
            )

        const val MOBILE_GLUCOSE_SCALE_KEY = "visual.mobile.glucoseScalePercent"
        const val MOBILE_TREND_SCALE_KEY = "visual.mobile.trendScalePercent"
        const val GLUCOSE_TILE_DETAIL_MODE_KEY = "overview.glucoseTileDetailMode"
        const val IOB_PROGRESS_MAXIMUM_KEY = "overview.iobProgressMaximumUnits"
    }
}

data class DiagnosticsSnapshot(
    val sourceVersion: String?,
    val sourceContract: String?,
    val sourcePackage: String?,
    val receivedAt: Long,
    val measuredAt: Long,
    val reachableWatches: Int,
    val lastSyncAt: Long,
    val syncStatus: String?,
    val syncError: String?,
) {
    companion object {
        fun read(preferences: SharedPreferences) =
            DiagnosticsSnapshot(
                sourceVersion = preferences.getString("sourceVersion", null),
                sourceContract = preferences.getString("contract", null),
                sourcePackage = preferences.getString("sourcePackage", null),
                receivedAt = preferences.getLong("received", 0L),
                measuredAt = preferences.getLong("measurement", 0L),
                reachableWatches = preferences.getInt("reachableWatches", 0),
                lastSyncAt = preferences.getLong("lastSyncAt", 0L),
                syncStatus = preferences.getString("lastSyncStatus", null),
                syncError = preferences.getString("lastSyncError", null),
            )
    }
}

data class DashboardCallbacks(
    val navigate: (DashboardScreen) -> Unit,
    val setUnit: (DisplayUnitPreference) -> Unit,
    val setDataSource: (DataSourcePreference) -> Unit,
    val openNightscoutTreatments: () -> Unit,
    val openDiagnostics: () -> Unit,
    val setThemeMode: (DashboardThemeMode) -> Unit,
    val setShowDetails: (Boolean) -> Unit,
    val setGlucoseTileDetailMode: (GlucoseTileDetailMode) -> Unit,
    val setShowCgmGraph: (Boolean) -> Unit,
    val setGraphHours: (Int) -> Unit,
    val setCgmStream: (String, Boolean) -> Unit = { _, _ -> },
    val setShowMetabolicGraph: (Boolean) -> Unit,
    val setCompact: (Boolean) -> Unit,
    val setLiveNotification: (Boolean) -> Unit,
    val setNotificationGraphEnabled: (Boolean) -> Unit,
    val setNotificationGraphHours: (Int) -> Unit,
    val setWatchFaceIndex: (Int) -> Unit,
    val syncNow: () -> Unit,
    val connectHealthConnect: () -> Unit,
    val syncHealthConnect: () -> Unit,
    val manageHealthConnect: () -> Unit,
    val requestNotificationAccess: () -> Unit,
    val requestUnrestrictedBattery: () -> Unit,
    val exportSettings: () -> Unit,
    val importSettings: () -> Unit,
    val openProjectGitHub: () -> Unit,
    val openContactEmail: () -> Unit,
)

class DashboardViewFactory(
    private val context: Context,
    private val callbacks: DashboardCallbacks,
) {
    private data class ComposeRenderState(
        val state: TherapyDisplayState?,
        val diagnostics: DiagnosticsSnapshot,
        val preferences: DashboardUiPreferences,
        val now: Long,
    )

    private val overviewRenderState = androidx.compose.runtime.mutableStateOf<ComposeRenderState?>(null)
    private val watchRenderState = androidx.compose.runtime.mutableStateOf<ComposeRenderState?>(null)
    private var activeComposeScreen: DashboardScreen? = null
    private var activeComposeView: androidx.compose.ui.platform.ComposeView? = null
    private var colorSettingsExpanded = false
    private var notificationGraphSettingsExpanded = false
    private val expandedSettingsCategories = linkedSetOf<String>()

    private val density = context.resources.displayMetrics.density
    private val text: Int get() = SugarliciousColors.argb(SugarliciousColorRole.TEXT_PRIMARY)
    private val secondary: Int get() = SugarliciousColors.argb(SugarliciousColorRole.TEXT_SECONDARY)
    private val accent: Int get() = SugarliciousColors.argb(SugarliciousColorRole.PRIMARY)
    private val dashboardPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
    }

    /** Advances only the visible Overview clock; no data reload or background renderer wakes. */
    fun tickOverviewClock(nowEpochMs: Long) {
        if (activeComposeScreen != DashboardScreen.OVERVIEW) return
        overviewRenderState.value = overviewRenderState.value?.copy(now = nowEpochMs)
    }

    fun render(
        parent: LinearLayout,
        screen: DashboardScreen,
        state: TherapyDisplayState?,
        diagnostics: DiagnosticsSnapshot,
        preferences: DashboardUiPreferences,
        now: Long,
    ) {
        when (screen) {
            DashboardScreen.OVERVIEW -> renderOverview(parent, state, diagnostics, preferences, now)
            DashboardScreen.WATCH -> renderWatch(parent, state, diagnostics, preferences, now)
            DashboardScreen.SETTINGS -> renderSettings(parent, state, preferences)
        }
    }

    private fun renderOverview(
        parent: LinearLayout,
        state: TherapyDisplayState?,
        diagnostics: DiagnosticsSnapshot,
        preferences: DashboardUiPreferences,
        now: Long,
    ) {
        collapseSettingsSections()
        overviewRenderState.value = ComposeRenderState(state, diagnostics, preferences, now)
        if (activeComposeScreen == DashboardScreen.OVERVIEW && activeComposeView?.parent === parent) return
        parent.removeAllViews()
        watchRenderState.value = null
        val composeView = androidx.compose.ui.platform.ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SugarliciousTheme {
                    overviewRenderState.value?.let { rendered ->
                        SugarliciousOverviewScreen(
                            state = rendered.state,
                            diagnostics = rendered.diagnostics,
                            preferences = rendered.preferences,
                            now = rendered.now,
                            callbacks = callbacks,
                        )
                    }
                }
            }
        }
        parent.addView(composeView, fullWidth())
        activeComposeScreen = DashboardScreen.OVERVIEW
        activeComposeView = composeView
    }

    private fun renderWatch(
        parent: LinearLayout,
        state: TherapyDisplayState?,
        diagnostics: DiagnosticsSnapshot,
        preferences: DashboardUiPreferences,
        now: Long,
    ) {
        collapseSettingsSections()
        watchRenderState.value = ComposeRenderState(state, diagnostics, preferences, now)
        if (activeComposeScreen == DashboardScreen.WATCH && activeComposeView?.parent === parent) return
        parent.removeAllViews()
        overviewRenderState.value = null
        val composeView = androidx.compose.ui.platform.ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SugarliciousTheme {
                    watchRenderState.value?.let { rendered ->
                        SugarliciousWatchScreen(
                            state = rendered.state,
                            preferences = rendered.preferences,
                            onSelectedFace = callbacks.setWatchFaceIndex,
                        )
                    }
                }
            }
        }
        parent.addView(composeView, fullWidth())
        activeComposeScreen = DashboardScreen.WATCH
        activeComposeView = composeView
    }

    private fun renderSettings(parent: LinearLayout, state: TherapyDisplayState?, preferences: DashboardUiPreferences) {
        parent.removeAllViews()
        overviewRenderState.value = null
        watchRenderState.value = null
        activeComposeScreen = null
        activeComposeView = null
        parent.addView(screenTitle())

        val colorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (colorSettingsExpanded) View.VISIBLE else View.GONE
            val colorSettings = androidx.compose.ui.platform.ComposeView(context).apply {
                setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    SugarliciousTheme {
                        SugarliciousColorSettingsPanel(
                            showCgmGraph = preferences.showCgmGraph,
                            showMetabolicGraph = preferences.showMetabolicGraph,
                        )
                    }
                }
            }
            addView(colorSettings, fullWidth())
        }
        val notificationGraphCustomization = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (notificationGraphSettingsExpanded) View.VISIBLE else View.GONE
            addView(
                tile(null).apply {
                    addView(
                        choiceRow(
                            "Graph-Skalierung",
                            listOf(
                                Triple("1 h", preferences.notificationGraphHours == 1) { callbacks.setNotificationGraphHours(1) },
                                Triple("2 h", preferences.notificationGraphHours == 2) { callbacks.setNotificationGraphHours(2) },
                                Triple("3 h", preferences.notificationGraphHours == 3) { callbacks.setNotificationGraphHours(3) },
                            ),
                        ),
                    )
                },
                fullWidth(),
            )
            addView(
                androidx.compose.ui.platform.ComposeView(context).apply {
                    setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent { SugarliciousTheme { NotificationGraphSettingsPanel() } }
                },
                fullWidth(),
            )
        }

        addSettingsCategory(parent, "general", "Allgemein", R.drawable.ic_settings) {
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("DARSTELLUNG"))
                    addView(
                        choiceRow(
                            "Darstellung",
                            listOf(
                                Triple("System", preferences.themeMode == DashboardThemeMode.SYSTEM) { callbacks.setThemeMode(DashboardThemeMode.SYSTEM) },
                                Triple("Hell", preferences.themeMode == DashboardThemeMode.LIGHT) { callbacks.setThemeMode(DashboardThemeMode.LIGHT) },
                                Triple("Dunkel", preferences.themeMode == DashboardThemeMode.DARK) { callbacks.setThemeMode(DashboardThemeMode.DARK) },
                            ),
                        ),
                    )
                    addView(divider())
                    addView(settingsGroupLabel("AKTIVE DATENQUELLE"))
                    addView(
                        sourceChoiceRow(
                            listOf(
                                SourceChoice("AndroidAPS", R.drawable.ic_source_androidaps, preferences.dataSource == DataSourcePreference.ANDROID_APS) { callbacks.setDataSource(DataSourcePreference.ANDROID_APS) },
                            ),
                        ),
                    )
                    addView(divider())
                    addView(settingsGroupLabel("WATCH-VERBINDUNG"))
                    addView(actionRow("Jetzt synchronisieren", "Jetzt") { callbacks.syncNow() })
                },
                cardParams(top = 4),
            )
        }

        addSettingsCategory(parent, "glucose_ranges", "Glukose und Zielbereiche", R.drawable.ic_health_glucose) {
            val selectedThresholdUnit = when (preferences.unitFor(state)) {
                GlucoseUnit.MMOL_L -> DisplayUnitPreference.MMOL_L
                GlucoseUnit.MG_DL -> DisplayUnitPreference.MG_DL
            }
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("GLUKOSE-EINHEIT"))
                    addView(
                        choiceRow(
                            "Glukose-Einheit",
                            listOf(
                                Triple("Wie Datenquelle", preferences.unit == DisplayUnitPreference.AAPS) { callbacks.setUnit(DisplayUnitPreference.AAPS) },
                                Triple("mg/dL", preferences.unit == DisplayUnitPreference.MG_DL) { callbacks.setUnit(DisplayUnitPreference.MG_DL) },
                                Triple("mmol/L", preferences.unit == DisplayUnitPreference.MMOL_L) { callbacks.setUnit(DisplayUnitPreference.MMOL_L) },
                            ),
                        ),
                    )
                    addView(divider())
                    addView(settingsGroupLabel("GLUKOSEBEREICHE"))
                    fun thresholdRow(title: String, value: Double, minimum: Float, maximum: Float, update: (Double) -> CgmThresholds) =
                        sugarliciousSliderRow(
                            title = title,
                            value = thresholdForUi(value, selectedThresholdUnit),
                            minimum = thresholdForUi(minimum.toDouble(), selectedThresholdUnit),
                            maximum = thresholdForUi(maximum.toDouble(), selectedThresholdUnit),
                            valueFormatter = { formatThreshold(thresholdFromUi(it, selectedThresholdUnit), selectedThresholdUnit) },
                        ) { entered ->
                            val mgDl = thresholdFromUi(entered, selectedThresholdUnit)
                            CgmThresholdPreferences.save(dashboardPreferences, update(mgDl))
                        }
                    addView(thresholdRow("Sehr hoch", preferences.cgmThresholds.veryHighMgDl, 100f, 400f) { preferences.cgmThresholds.copy(veryHighMgDl = it) })
                    addView(divider())
                    addView(thresholdRow("Hoch", preferences.cgmThresholds.highMgDl, 80f, 300f) { preferences.cgmThresholds.copy(highMgDl = it) })
                    addView(divider())
                    addView(thresholdRow("Tief", preferences.cgmThresholds.lowMgDl, 40f, 180f) { preferences.cgmThresholds.copy(lowMgDl = it) })
                    addView(divider())
                    addView(thresholdRow("Sehr tief", preferences.cgmThresholds.veryLowMgDl, 20f, 120f) { preferences.cgmThresholds.copy(veryLowMgDl = it) })
                    addView(divider())
                    addView(settingsGroupLabel("GRÖSSE"))
                    addView(
                        sugarliciousSliderRow(
                            title = "Glukosewert",
                            value = preferences.glucoseScalePercent.toFloat(),
                            minimum = GlucoseTrendSizing.MIN_SCALE_PERCENT.toFloat(),
                            maximum = GlucoseTrendSizing.MAX_SCALE_PERCENT.toFloat(),
                            valueFormatter = { "${it.toInt()} %" },
                        ) { dashboardPreferences.edit().putInt(DashboardUiPreferences.MOBILE_GLUCOSE_SCALE_KEY, it.toInt()).apply() },
                    )
                    addView(divider())
                    addView(
                        sugarliciousSliderRow(
                            title = "Trendpfeil",
                            value = preferences.trendScalePercent.toFloat(),
                            minimum = GlucoseTrendSizing.MIN_SCALE_PERCENT.toFloat(),
                            maximum = GlucoseTrendSizing.MAX_SCALE_PERCENT.toFloat(),
                            valueFormatter = { "${it.toInt()} %" },
                        ) { dashboardPreferences.edit().putInt(DashboardUiPreferences.MOBILE_TREND_SCALE_KEY, it.toInt()).apply() },
                    )
                },
                cardParams(top = 4),
            )
        }

        addSettingsCategory(parent, "overview_graphs", "Übersicht und Graphen", R.drawable.ic_foreground) {
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("ÜBERSICHT"))
                    addView(
                        choiceRow(
                            "Glukose-Kachel",
                            listOf(
                                Triple("TIR", preferences.glucoseTileDetailMode == GlucoseTileDetailMode.TIR) { callbacks.setGlucoseTileDetailMode(GlucoseTileDetailMode.TIR) },
                                Triple("IOB · COB · Basal", preferences.glucoseTileDetailMode == GlucoseTileDetailMode.THERAPY) { callbacks.setGlucoseTileDetailMode(GlucoseTileDetailMode.THERAPY) },
                            ),
                        ),
                    )
                    if (preferences.glucoseTileDetailMode == GlucoseTileDetailMode.TIR) {
                        addView(divider())
                        addView(switchRowCompact("Therapiedetails", preferences.showDetails, R.id.dashboard_details_switch, callbacks.setShowDetails))
                    } else {
                        addView(divider())
                        addView(
                            sugarliciousSliderRow(
                                title = "IOB-Skala",
                                value = preferences.iobProgressMaximumUnits,
                                minimum = 0f,
                                maximum = 30f,
                                valueFormatter = { String.format(Locale.GERMANY, "%.1f U", it) },
                            ) { dashboardPreferences.edit().putFloat(DashboardUiPreferences.IOB_PROGRESS_MAXIMUM_KEY, it).apply() },
                        )
                    }
                    addView(divider())
                    addView(switchRowCompact("Kompakte Übersicht", preferences.compact, R.id.dashboard_compact_switch, callbacks.setCompact))
                    addView(divider())
                    addView(settingsGroupLabel("CGM-GRAPH"))
                    addView(switchRowCompact("Graph anzeigen", preferences.showCgmGraph, View.generateViewId(), callbacks.setShowCgmGraph))
                    if (preferences.showCgmGraph) {
                        addView(divider())
                        addView(settingsGroupLabel("DATENSTRÖME"))
                        addView(switchRowCompact("Aktueller Zielwert", preferences.showCgmTargetValue, View.generateViewId()) { callbacks.setCgmStream("cgm.targetValue", it) })
                        addView(divider())
                        addView(switchRowCompact("Basal", preferences.showCgmBasal, View.generateViewId()) { callbacks.setCgmStream("cgm.basal", it) })
                        addView(divider())
                        addView(switchRowCompact("Insulinaktivität", preferences.showCgmActivity, View.generateViewId()) { callbacks.setCgmStream("cgm.activity", it) })
                        addView(divider())
                        addView(switchRowCompact("IOB/COB-Graph", preferences.showMetabolicGraph, View.generateViewId(), callbacks.setShowMetabolicGraph))
                        addView(divider())
                        addView(switchRowCompact("IOB-Prognose", preferences.showCgmPredictionIob, View.generateViewId()) { callbacks.setCgmStream("cgm.prediction.iob", it) })
                        addView(divider())
                        addView(switchRowCompact("COB-Prognose", preferences.showCgmPredictionCob, View.generateViewId()) { callbacks.setCgmStream("cgm.prediction.cob", it) })
                        addView(divider())
                        addView(switchRowCompact("UAM-Prognose", preferences.showCgmPredictionUam, View.generateViewId()) { callbacks.setCgmStream("cgm.prediction.uam", it) })
                        addView(divider())
                        addView(switchRowCompact("ZeroTemp-Prognose", preferences.showCgmPredictionZeroTemp, View.generateViewId()) { callbacks.setCgmStream("cgm.prediction.zeroTemp", it) })
                        addView(divider())
                        addView(switchRowCompact("Mahlzeitenboli", preferences.showMealBolusMarkers, View.generateViewId()) { callbacks.setCgmStream("treatment.mealBolus", it) })
                        addView(divider())
                        addView(switchRowCompact("Korrekturboli", preferences.showCorrectionMarkers, View.generateViewId()) { callbacks.setCgmStream("treatment.correction", it) })
                        addView(divider())
                        addView(switchRowCompact("SMB", preferences.showSmbMarkers, View.generateViewId()) { callbacks.setCgmStream("treatment.smb", it) })
                        addView(divider())
                        addView(switchRowCompact("Mahlzeiten-Carbs", preferences.showMealCarbMarkers, View.generateViewId()) { callbacks.setCgmStream("treatment.mealCarbs", it) })
                        addView(divider())
                        addView(switchRowCompact("eCarbs", preferences.showECarbMarkers, View.generateViewId()) { callbacks.setCgmStream("treatment.eCarbs", it) })
                        addView(divider())
                        val nightscout = NightscoutConfigurationStore.read(context)
                        addView(actionRow("Nightscout Treatment-Anreicherung", if (nightscout.enabled) "Aktiv" else "Aus") { callbacks.openNightscoutTreatments() })
                    }
                    addView(divider())
                    addView(settingsGroupLabel("FARBEN & DARSTELLUNG"))
                    addView(actionRow("Farben & Darstellung", "Anpassen") {
                        colorSettingsExpanded = !colorSettingsExpanded
                        colorContainer.visibility = if (colorSettingsExpanded) View.VISIBLE else View.GONE
                    })
                },
                cardParams(top = 4),
            )
            addView(colorContainer, cardParams(top = 4))
        }

        addSettingsCategory(parent, "notifications", "Benachrichtigungen und Alarme", R.drawable.ic_notification_outlined) {
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("LIVE-ANZEIGE"))
                    addView(switchRowCompact("Live-Benachrichtigung", preferences.liveNotification, R.id.dashboard_live_notification_switch, callbacks.setLiveNotification))
                    addView(divider())
                    addView(settingsGroupLabel("GRAPH"))
                    addView(switchRowCompact("CGM-Graph anzeigen", preferences.notificationGraphEnabled, View.generateViewId(), callbacks.setNotificationGraphEnabled))
                    if (preferences.notificationGraphEnabled) {
                        addView(divider())
                        addView(actionRow("CGM-Graph", "Anpassen") {
                            notificationGraphSettingsExpanded = !notificationGraphSettingsExpanded
                            notificationGraphCustomization.visibility = if (notificationGraphSettingsExpanded) View.VISIBLE else View.GONE
                        })
                    }
                    addView(divider())
                    addView(settingsGroupLabel("SYSTEMZUGRIFF"))
                    addView(actionRow("Benachrichtigungen", AppRuntimeAccess.notificationLabel(context), callbacks.requestNotificationAccess))
                },
                cardParams(top = 4),
            )
            addView(notificationGraphCustomization, cardParams(top = 4))
        }

        addSettingsCategory(parent, "data", "Datenverwaltung", R.drawable.ic_health_activity) {
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("HINTERGRUNDBETRIEB"))
                    addView(actionRow("Dauerbetrieb", AppRuntimeAccess.batteryLabel(context), callbacks.requestUnrestrictedBattery))
                    addView(helper("Der sichtbare Sugarlicious-Dienst startet mit der App und nach einem Geräteneustart. Für zuverlässige Watch-Synchronisierung kann die Akku-Optimierung freigegeben werden.", 3))
                    addView(divider())
                    addView(settingsGroupLabel("SICHERUNG"))
                    addView(actionRow("Einstellungen sichern", "Exportieren", callbacks.exportSettings))
                    addView(divider())
                    addView(actionRow("Einstellungen wiederherstellen", "Importieren", callbacks.importSettings))
                    addView(helper("Die Sicherung enthält Darstellung, Verhalten und Watchface-/Complication-Auswahl; Sensorzugänge bleiben ausgeschlossen.", 3))
                    addView(divider())
                    addView(settingsGroupLabel("HEALTH CONNECT"))
                    addView(actionRow("Google Health Connect", HealthConnectIntegration.statusLabel(context)) { callbacks.connectHealthConnect() })
                    addView(divider())
                    addView(actionRow("Gesundheitsdaten aktualisieren", "Synchronisieren") { callbacks.syncHealthConnect() })
                    addView(divider())
                    addView(actionRow("Zugriff verwalten", "Öffnen") { callbacks.manageHealthConnect() })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, 8.dp, 0, 4.dp)
                        addView(chip("Alle Health-Daten", true) { HealthConnectDataDialog.show(context) })
                    })
                    addView(helper(HealthConnectIntegration.detailLabel(context), 3))
                },
                cardParams(top = 4),
            )
        }

        addSettingsCategory(parent, "diagnostics", "Diagnose", R.drawable.ic_watch_status) {
            addView(
                tile(null).apply {
                    addView(settingsGroupLabel("LOKALE DIAGNOSE"))
                    val provenance = state?.therapyEvents.orEmpty().groupingBy { it.source }.eachCount()
                    addView(actionRow("Treatment-Quellen", "AAPS ${provenance[app.aapswear.model.TherapyEventSource.AAPS_ONLY] ?: 0} · NS ${provenance[app.aapswear.model.TherapyEventSource.NIGHTSCOUT_ONLY] ?: 0} · ergänzt ${provenance[app.aapswear.model.TherapyEventSource.AAPS_ENRICHED_BY_NIGHTSCOUT] ?: 0}") { callbacks.openNightscoutTreatments() })
                    addView(divider())
                    addView(actionRow("Ereignisse & Fehlercodes", "Öffnen") { callbacks.openDiagnostics() })
                    addView(helper("Lokale, begrenzte Ablaufdiagnose von Smartphone und Watch. Sensor- und Authentifizierungs-Rohdaten werden nicht exportiert.", 3))
                },
                cardParams(top = 4),
            )
        }

        addSettingsCategory(parent, "about", "Über Sugarlicious", R.drawable.ic_foreground) {
            addView(aboutCard(), cardParams(top = 4, bottom = 10))
        }
    }

    private fun collapseSettingsSections() {
        colorSettingsExpanded = false
        notificationGraphSettingsExpanded = false
        expandedSettingsCategories.clear()
    }

    private fun aboutCard(): View =
        tile(null).apply {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 8.dp, 0, 8.dp)
            }
            header.addView(
                sugarliciousIconView(
                    context = context,
                    drawableRes = R.drawable.ic_foreground,
                    contentDescription = context.getString(R.string.brand_logo),
                ),
                LinearLayout.LayoutParams(56.dp, 56.dp),
            )
            header.addView(value("Sugarlicious", text, 20f, 1).apply { gravity = Gravity.CENTER })
            header.addView(helper("by FreDiabetics", 1).apply { gravity = Gravity.CENTER })
            addView(header)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 8.dp, 0, 1.dp)
                    addView(
                        aboutActionPill(R.drawable.ic_github, "GitHub", callbacks.openProjectGitHub).also { it.id = R.id.dashboard_github },
                        LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginEnd = 5.dp },
                    )
                    addView(
                        aboutActionPill(R.drawable.ic_mail, "E-Mail", callbacks.openContactEmail).also { it.id = R.id.dashboard_contact_email },
                        LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginStart = 5.dp },
                    )
                },
            )
        }

    private fun aboutActionPill(icon: Int, label: String, action: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = roundedBackground(SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH), SugarliciousColors.argb(SugarliciousColorRole.BORDER), 999)
            setOnClickListener { action() }
            addView(
                sugarliciousIconView(context, icon, label, tintArgb = accent),
                LinearLayout.LayoutParams(19.dp, 19.dp).apply { marginEnd = 7.dp },
            )
            addView(value(label, text, 13f, 1))
        }

    private fun addSettingsCategory(
        parent: LinearLayout,
        key: String,
        title: String,
        icon: Int,
        buildContent: LinearLayout.() -> Unit,
    ) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (key in expandedSettingsCategories) View.VISIBLE else View.GONE
            buildContent()
        }
        val chevron = value(if (key in expandedSettingsCategories) "⌄" else "›", secondary, 23f, 1)
        val header = tile(null).apply {
            tag = "settings-category-$key"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 60.dp
            isClickable = true
            isFocusable = true
            addView(
                sugarliciousIconView(context, icon, title, tintArgb = accent),
                LinearLayout.LayoutParams(27.dp, 27.dp).apply { marginEnd = 10.dp },
            )
            addView(value(title, text, 17f, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(30.dp, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                val expanded = key in expandedSettingsCategories
                if (expanded) expandedSettingsCategories.remove(key) else expandedSettingsCategories.add(key)
                content.visibility = if (expanded) View.GONE else View.VISIBLE
                chevron.text = if (expanded) "›" else "⌄"
            }
        }
        parent.addView(header, cardParams(top = 7))
        parent.addView(content, fullWidth())
    }

    private data class SourceChoice(
        val label: String,
        val icon: Int,
        val selected: Boolean,
        val action: () -> Unit,
    )

    private fun sourceChoiceRow(items: List<SourceChoice>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 5.dp, 0, 5.dp)
        items.forEach { item ->
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 43.dp
                    setPadding(12.dp, 5.dp, 12.dp, 5.dp)
                    background = roundedBackground(
                        SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH),
                        SugarliciousColors.argb(SugarliciousColorRole.BORDER),
                        999,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { item.action() }
                    addView(
                        sugarliciousIconView(
                            context = context,
                            drawableRes = item.icon,
                            contentDescription = item.label,
                            tintArgb =
                                if (item.icon == R.drawable.ic_sensor) {
                                    null
                                } else {
                                    secondary
                                },
                        ),
                        LinearLayout.LayoutParams(27.dp, 27.dp).apply { marginEnd = 9.dp },
                    )
                    addView(value(item.label, if (item.selected) accent else secondary, 13f, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp },
            )
        }
    }

    private fun settingsGroupLabel(label: String) = TextView(context).apply {
        text = label
        textSize = 11f
        setTextColor(accent)
        typeface = Typeface.create("sans", Typeface.BOLD)
        letterSpacing = 0.04f
        setPadding(4.dp, 12.dp, 4.dp, 3.dp)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(SugarliciousColors.argb(SugarliciousColorRole.BORDER))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp)
    }

    private fun choiceRow(title: String, items: List<Triple<String, Boolean, () -> Unit>>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 5.dp, 0, 5.dp)
        addView(value(title, text, 14f, 1))
        addView(chipRow(items))
    }

    private fun actionRow(title: String, valueText: String?, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 52.dp
        setPadding(0, 5.dp, 0, 5.dp)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(value(title, text, 15f, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        valueText?.let {
            addView(TextView(context).apply {
                text = it
                textSize = 13f
                setTextColor(secondary)
                gravity = Gravity.END
                setPadding(8.dp, 0, 8.dp, 0)
            })
        }
        addView(TextView(context).apply {
            text = "\u203a"
            textSize = 21f
            setTextColor(secondary)
        })
    }

    private fun switchRowCompact(title: String, checked: Boolean, id: Int, callback: (Boolean) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 52.dp
        setPadding(0, 5.dp, 0, 5.dp)
        addView(value(title, text, 15f, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            Switch(context).apply {
                this.id = id
                isChecked = checked
                minWidth = 50.dp
                minimumHeight = 30.dp
                splitTrack = false
                scaleX = 0.92f
                scaleY = 0.92f
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(android.graphics.Color.WHITE, SugarliciousColors.argb(SugarliciousColorRole.TEXT_SECONDARY)),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH)),
                )
                setOnCheckedChangeListener { _, newValue -> callback(newValue) }
            },
        )
    }

    private fun sugarliciousSliderRow(
        title: String,
        description: String? = null,
        value: Float,
        minimum: Float,
        maximum: Float,
        valueFormatter: (Float) -> String,
        callback: (Float) -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = 72.dp
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background =
            roundedBackground(
                SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH),
                SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH),
                16,
            )
        val valueLabel = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.END
            setTextColor(accent)
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(this@DashboardViewFactory.value(title, text, 13f, 1).apply {
                        typeface = Typeface.create(typeface, Typeface.NORMAL)
                    })
                    if (!description.isNullOrBlank()) {
                        addView(helper(description, 1).apply { textSize = 10f })
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(valueLabel, LinearLayout.LayoutParams(72.dp, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(titleRow)
        val steps = 1000
        fun progressToValue(progress: Int): Float = minimum + (maximum - minimum) * progress.toFloat() / steps.toFloat()
        fun valueToProgress(current: Float): Int = (((current.coerceIn(minimum, maximum) - minimum) / (maximum - minimum)) * steps).toInt().coerceIn(0, steps)
        valueLabel.text = valueFormatter(value)
        addView(
            SeekBar(context).apply {
                max = steps
                progress = valueToProgress(value)
                progressTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList =
                    ColorStateList.valueOf(SugarliciousColors.argb(SugarliciousColorRole.SURFACE_RAISED))
                thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        private var currentValue = value.coerceIn(minimum, maximum)
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            currentValue = progressToValue(progress)
                            valueLabel.text = valueFormatter(currentValue)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = callback(currentValue)
                    },
                )
            },
            fullWidth(),
        )
    }

    private fun chipRow(items: List<Triple<String, Boolean, () -> Unit>>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, 7.dp, 0, 4.dp)
        items.forEach { (label, selected, click) ->
            addView(chip(label, selected, click), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 7.dp })
        }
    }

    private fun chip(label: String, selected: Boolean, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 11f
        minHeight = 36.dp
        setTextColor(if (selected) accent else secondary)
        background = roundedBackground(
            if (selected) SugarliciousColors.argb(SugarliciousColorRole.SURFACE_SELECTED) else SugarliciousColors.argb(SugarliciousColorRole.SURFACE_HIGH),
            if (selected) accent else SugarliciousColors.argb(SugarliciousColorRole.BORDER),
            999,
        )
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(12.dp, 0, 12.dp, 0)
        setOnClickListener { click() }
    }

    private fun tile(title: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12.dp, 11.dp, 12.dp, 11.dp)
        background = roundedBackground(SugarliciousColors.argb(SugarliciousColorRole.SURFACE), SugarliciousColors.argb(SugarliciousColorRole.BORDER), 22)
        clipToOutline = true
        title?.let { addView(sectionLabel(it)) }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.dp.toFloat()
        setColor(fillColor)
        setStroke(1.dp, strokeColor)
    }

    private fun screenTitle() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(4.dp, 4.dp, 4.dp, 10.dp)
        addView(value("Einstellungen", text, 26f, 1))
    }

    private fun sectionLabel(label: String) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(this@DashboardViewFactory.text)
        typeface = Typeface.create("sans", Typeface.NORMAL)
        letterSpacing = 0.03f
    }

    private fun value(value: String, color: Int, size: Float, maxLines: Int = 2) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", Typeface.NORMAL)
        this.maxLines = maxLines
        if (maxLines == 1) ellipsize = TextUtils.TruncateAt.END
    }

    private fun helper(value: String, maxLines: Int = 2, color: Int = secondary) = TextView(context).apply {
        text = value
        textSize = 11f
        setTextColor(color)
        this.maxLines = maxLines
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun cardParams(top: Int = 6, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = top.dp
        bottomMargin = bottom.dp
    }

    private val Int.dp: Int get() = (this * density).toInt()
}
