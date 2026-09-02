package app.aapswear.wear

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.complications.R as ComplicationR
import app.aapswear.model.BasalState
import app.aapswear.model.DataSourceId
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TrendVisuals
import app.aapswear.model.WearGlucoseCardInput
import app.aapswear.model.WearGlucoseCardStyle
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.wearGlucoseCardPresentation
import app.aapswear.uishared.TrendDrawableResources
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class WearActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var refreshJob: Job? = null
    private var latest: TherapyDisplayState? = null
    private var connectedNodes = 0
    private var lastRenderedState: TherapyDisplayState? = null
    private var lastRenderedPreferences: WearDisplayPreferences? = null
    private var lastRenderedConnectedNodes: Int? = null
    private var hasRendered = false
    private var batteryRequestPending = false

    private lateinit var glucose: TextView
    private lateinit var trendContainer: LinearLayout
    private lateinit var trendArrow1: ImageView
    private lateinit var delta: TextView
    private lateinit var age: TextView
    private lateinit var source: TextView
    private lateinit var connection: TextView
    private lateinit var syncHint: TextView
    private lateinit var iob: TextView
    private lateinit var cob: TextView
    private lateinit var basal: TextView
    private lateinit var iobIcon: ImageView
    private lateinit var cobIcon: ImageView
    private lateinit var basalIcon: ImageView
    private lateinit var therapyRow: LinearLayout
    private lateinit var chart: WearGlucoseChart
    private lateinit var watchFacePushStatus: TextView
    private var currentBasalIconRes: Int = ComplicationR.drawable.ic_complication_basal

    private val displayPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runOnUiThread { render() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { StateDataLayerService.start(this) }
            .onFailure { error ->
                recordRuntimeDiagnostic(
                    "WATCH-FGS-500",
                    "Wear runtime foreground service could not be started from the app",
                    DiagnosticSeverity.ERROR,
                    error,
                )
            }

        setContentView(R.layout.activity_wear)
        bindViews()

        findViewById<View>(R.id.wear_connection_card).setOnClickListener { requestPhoneRefresh() }
        findViewById<View>(R.id.wear_settings_action).setOnClickListener {
            startActivity(Intent(this, WearSettingsActivity::class.java))
        }

        scope.launch {
            TherapyStateStore(this@WearActivity).state.collectLatest {
                latest = it
                render()
            }
        }
        findViewById<View>(R.id.wear_graph_period).setOnClickListener {
            val current = WearDisplayPreferences.read(this)
            val values = WearDisplayPreferences.allowedGraphHours
            val next = values[(values.indexOf(current.graphHours).coerceAtLeast(0) + 1) % values.size]
            WearDisplayPreferences.saveLocal(this, current.copy(graphHours = next))
            render(refreshClock = true)
        }
        scope.launch {
            WearCanonicalStateEvents.updates.collectLatest {
                // The event only invalidates the canonical resolver-backed UI. It does not copy
                // G7 database data into the phone-fed TherapyStateStore.
                render()
            }
        }

        val notificationRequestStarted = requestRuntimeNotificationPermission()
        if (!notificationRequestStarted) {
            val batteryRequestStarted = requestBatteryExemptionIfNeeded()
            if (!batteryRequestStarted) requestWatchFacePermissionOnFirstLaunch()
        }
        requestPhoneRefresh(initial = true)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!batteryRequestPending) return

        batteryRequestPending = false
        val unrestricted = WearBackgroundAccess.isBatteryUnrestricted(this)
        if (unrestricted) {
            Toast.makeText(this, "Dauerbetrieb ist uneingeschränkt", Toast.LENGTH_SHORT).show()
            recordRuntimeDiagnostic(
                "WATCH-BG-200",
                "Battery optimization exemption granted for Sugarlicious Wear",
                DiagnosticSeverity.INFO,
            )
        } else {
            Toast.makeText(
                this,
                "Dauerbetrieb nicht freigegeben – Akkuoptimierung ist weiterhin aktiv",
                Toast.LENGTH_LONG,
            ).show()
            recordRuntimeDiagnostic(
                "WATCH-BG-403",
                "Battery optimization exemption was not granted for Sugarlicious Wear",
                DiagnosticSeverity.WARNING,
            )
        }
        runCatching { StateDataLayerService.start(this) }
        requestWatchFacePermissionOnFirstLaunch()
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(WearDisplayPreferences.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(displayPreferencesListener)

        refreshJob = scope.launch {
            while (true) {
                render(refreshClock = true)
                delay(30_000L)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        getSharedPreferences(WearDisplayPreferences.PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(displayPreferencesListener)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        glucose = findViewById(R.id.wear_glucose)
        trendContainer = findViewById(R.id.wear_trend_container)
        trendArrow1 = findViewById(R.id.wear_trend_arrow_1)
        delta = findViewById(R.id.wear_delta)
        age = findViewById(R.id.wear_age)
        source = findViewById(R.id.wear_source)
        connection = findViewById(R.id.wear_connection)
        syncHint = findViewById(R.id.wear_sync_hint)
        iob = findViewById(R.id.wear_iob)
        cob = findViewById(R.id.wear_cob)
        basal = findViewById(R.id.wear_basal)
        iobIcon = findViewById(R.id.wear_iob_icon)
        cobIcon = findViewById(R.id.wear_cob_icon)
        basalIcon = findViewById(R.id.wear_basal_icon)
        therapyRow = findViewById(R.id.wear_therapy_row)
        chart = findViewById(R.id.wear_glucose_chart)
        watchFacePushStatus = findViewById(R.id.wear_watchface_push_status)
    }

    private fun requestPhoneRefresh(initial: Boolean = false) {
        if (!::connection.isInitialized) return
        if (!initial) syncHint.text = "Werte werden synchronisiert"

        scope.launch {
            connectedNodes = withContext(Dispatchers.IO) {
                runCatching { requestLatestState(applicationContext) }.getOrDefault(0)
            }
            syncHint.text = if (connectedNodes > 0) {
                "Tippen zum Aktualisieren"
            } else {
                "Telefon derzeit nicht erreichbar"
            }
            render()
        }
    }

    private fun render(refreshClock: Boolean = false) {
        if (!::glucose.isInitialized) return

        val now = System.currentTimeMillis()
        val preferences = WearDisplayPreferences.read(this)
        val state = G7LocalReadingResolver.resolve(this, latest, now, preferences.dataSource)
        val previousState = lastRenderedState
        val glucoseState = state?.glucose
        val previousPreferences = lastRenderedPreferences
        val firstRender = !hasRendered

        if (firstRender || previousPreferences?.uiColors != preferences.uiColors) {
            applyUiColors(preferences)
        }

        val thresholds = preferences.cgmThresholds
        val resolvedUnit = resolveUnit(glucoseState?.displayUnit, preferences.glucoseUnit)
        val glucosePresentation = wearGlucoseCardPresentation(
            WearGlucoseCardInput(
                valueMgDl = glucoseState?.valueMgDl,
                displayUnit = resolvedUnit,
                deltaMgDl = glucoseState?.deltaMgDl,
                trend = glucoseState?.trend ?: app.aapswear.model.Trend.UNKNOWN,
                measuredAtEpochMs = glucoseState?.measuredAtEpochMs,
                quality = glucoseState?.quality ?: app.aapswear.model.CgmQuality.INVALID,
                sourceLabel = TherapyDisplayFormatter.sourceName(state?.source),
            ),
            thresholds,
            now,
        )
        val canShowValue = glucosePresentation.displayable

        val glucoseSectionChanged =
            firstRender || refreshClock || previousState?.glucose != glucoseState ||
                previousState?.target != state?.target ||
                previousState?.source != state?.source ||
                previousPreferences?.glucoseUnit != preferences.glucoseUnit ||
                previousPreferences?.glucoseScalePercent != preferences.glucoseScalePercent ||
                previousPreferences?.trendScalePercent != preferences.trendScalePercent ||
                previousPreferences?.uiColors != preferences.uiColors

        if (glucoseSectionChanged) {
            val presentation = glucosePresentation
            glucose.text = presentation.value
            glucose.textSize = WearGlucoseCardStyle.VALUE_TEXT_SP * GlucoseTrendSizing.scaleFactor(preferences.glucoseScalePercent)
            val valueColor = when {
                !presentation.displayable -> preferences.uiColors.textPrimary
                presentation.rangeClass == app.aapswear.model.CgmRangeClass.VERY_LOW -> preferences.uiColors.glucoseVeryLow
                presentation.rangeClass == app.aapswear.model.CgmRangeClass.LOW -> preferences.uiColors.glucoseLow
                presentation.rangeClass == app.aapswear.model.CgmRangeClass.VERY_HIGH -> preferences.uiColors.glucoseVeryHigh
                presentation.rangeClass == app.aapswear.model.CgmRangeClass.HIGH -> preferences.uiColors.glucoseHigh
                else -> preferences.uiColors.glucoseInRange
            }
            glucose.setTextColor(valueColor)
            val glucoseFill = preferences.uiColors.tileBackground
            renderTrend(
                trend = presentation.trend,
                color = valueColor,
                background = glucoseFill,
                scale = GlucoseTrendSizing.scaleFactor(preferences.trendScalePercent),
                style = preferences.trendArrowStyle,
            )
            delta.text = presentation.primaryMeta
            age.text = presentation.secondaryMeta
            age.visibility = if (presentation.secondaryMeta.isBlank()) View.GONE else View.VISIBLE

            findViewById<View>(R.id.wear_glucose_card).background =
                roundedBackground(glucoseFill, preferences.uiColors.tileBorder, WearGlucoseCardStyle.CARD_RADIUS_DP)
        }

        chart.bind(
            newState = state,
            graphHours = preferences.graphHours,
            showPredictions = preferences.showPredictions,
            colors = preferences.graphColors,
            style = preferences.graphStyle,
            thresholds = preferences.cgmThresholds,
        )
        findViewById<TextView>(R.id.wear_graph_period).apply {
            text = "${preferences.graphHours}h"
            setTextColor(preferences.uiColors.textPrimary)
            background = null
        }
        if (refreshClock) chart.invalidate()

        if (
            firstRender || refreshClock ||
            previousPreferences?.showTherapyStats != preferences.showTherapyStats ||
            previousState?.insulin != state?.insulin ||
            previousState?.carbs != state?.carbs ||
            previousState?.basal != state?.basal
        ) {
            therapyRow.visibility = if (preferences.showTherapyStats) View.VISIBLE else View.GONE
            findViewById<View>(R.id.wear_basal_card).visibility =
                if (preferences.showTherapyStats) View.VISIBLE else View.GONE

            iob.text = if (canShowValue) formatNumber(state?.insulin?.totalIob, 2, " U") else "—"
            cob.text = if (canShowValue) formatNumber(state?.carbs?.cobGrams, 0, " g") else "—"
            basal.text = if (canShowValue) {
                formatNumber(basalDisplayUnitsPerHour(state?.basal), 2, " U/h")
            } else "—"
            currentBasalIconRes = basalIconResource(state?.basal)
            basalIcon.renderSugarliciousWearIcon(
                drawableRes = currentBasalIconRes,
                tintArgb = preferences.uiColors.basal,
                backgroundArgb = preferences.uiColors.tileBackground,
            )
        }

        if (firstRender || previousState?.source != state?.source || previousState?.sourceVersion != state?.sourceVersion) {
            source.text = when (state?.source) {
                DataSourceId.DEXCOM_G7_WATCH -> "Dexcom G7 Watch"
                DataSourceId.ANDROID_APS -> "AndroidAPS"
                DataSourceId.NIGHTSCOUT -> "Nightscout"
                DataSourceId.XDRIP_PLUS -> state.sourceVersion?.let { "xDrip+ $it" } ?: "xDrip+"
                DataSourceId.OTHER -> "Other"
                null -> "Datenquelle nicht verfügbar"
            }
        }

        if (
            firstRender || lastRenderedConnectedNodes != connectedNodes ||
            previousPreferences?.uiColors != preferences.uiColors
        ) {
            connection.text = if (connectedNodes > 0) "● Telefon verbunden" else "○ Telefon nicht erreichbar"
            connection.setTextColor(
                if (connectedNodes > 0) preferences.uiColors.accent else preferences.uiColors.textSecondary,
            )
        }

        renderWatchFacePushStatus()
        lastRenderedState = state
        lastRenderedPreferences = preferences
        lastRenderedConnectedNodes = connectedNodes
        hasRendered = true
    }

    private fun renderTrend(trend: app.aapswear.model.Trend?, color: Int, background: Int, scale: Float, style: app.aapswear.model.TrendArrowStyle) {
        val spec = trend?.let(TrendVisuals::spec)
        if (spec == null) {
            trendContainer.visibility = View.GONE
            return
        }
        trendContainer.visibility = View.VISIBLE
        trendArrow1.renderSugarliciousWearIcon(TrendDrawableResources.forAsset(spec.asset), color, background, trendStyle = style.copy(fillColor = color))
        trendArrow1.rotation = 0f
        val density = resources.displayMetrics.density
        trendArrow1.layoutParams = trendArrow1.layoutParams.apply {
            height = (WearGlucoseCardStyle.TREND_SIZE_DP * scale * density).roundToInt()
            width = (WearGlucoseCardStyle.TREND_SIZE_DP * scale * spec.aspectRatio * density).roundToInt()
        }
        trendArrow1.visibility = View.VISIBLE
    }

    private fun applyUiColors(preferences: WearDisplayPreferences) {
        val ui = preferences.uiColors
        findViewById<View>(R.id.wear_root).setBackgroundColor(ui.background)

        listOf(R.id.wear_basal_card, R.id.wear_iob_card, R.id.wear_cob_card).forEach { id ->
            findViewById<View>(id).background = roundedBackground(ui.tileBackground, ui.tileBorder, 20f)
        }
        listOf(R.id.wear_connection_card, R.id.wear_settings_action).forEach { id ->
            findViewById<View>(id).background = roundedBackground(ui.tileBackground, ui.tileBorder, 22f)
        }
        findViewById<View>(R.id.wear_graph_card).background = null

        listOf(
            R.id.wear_glucose,
            R.id.wear_basal,
            R.id.wear_iob,
            R.id.wear_cob,
            R.id.wear_source,
            R.id.wear_settings_label,
            R.id.wear_header_title,
        ).forEach { id -> findViewById<TextView>(id).setTextColor(ui.textPrimary) }

        listOf(
            R.id.wear_delta,
            R.id.wear_age,
            R.id.wear_sync_hint,
            R.id.wear_watchface_push_status,
            R.id.wear_footer_text,
        ).forEach { id -> findViewById<TextView>(id).setTextColor(ui.textSecondary) }

        findViewById<ImageView>(R.id.wear_header_logo).renderSugarliciousWearIcon(
            R.drawable.ic_foreground,
            tintArgb = null,
            backgroundArgb = ui.background,
        )
        iobIcon.renderSugarliciousWearIcon(ComplicationR.drawable.ic_complication_iob, ui.iob, ui.tileBackground)
        cobIcon.renderSugarliciousWearIcon(ComplicationR.drawable.ic_complication_carbs, ui.cob, ui.tileBackground)
        basalIcon.renderSugarliciousWearIcon(currentBasalIconRes, ui.basal, ui.tileBackground)
        findViewById<ImageView>(R.id.wear_settings_icon).renderSugarliciousWearIcon(
            R.drawable.ic_settings,
            ui.textPrimary,
            ui.tileBackground,
            colored = false,
        )
        findViewById<ImageView>(R.id.wear_footer_icon).renderSugarliciousWearIcon(
            R.drawable.ic_monochrome_outlined,
            ui.accent,
            ui.background,
        )
    }

    private fun roundedBackground(fill: Int, border: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            setStroke((1f * resources.displayMetrics.density).roundToInt().coerceAtLeast(1), border)
        }

    private fun requestRuntimeNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        return true
    }

    private fun requestBatteryExemptionIfNeeded(): Boolean {
        if (WearBackgroundAccess.isBatteryUnrestricted(this)) return false

        batteryRequestPending = true
        if (WearBackgroundAccess.openBatterySettings(this)) return true

        batteryRequestPending = false
        Toast.makeText(
            this,
            "Akku-Einstellungen konnten auf dieser Watch nicht geöffnet werden",
            Toast.LENGTH_LONG,
        ).show()
        recordRuntimeDiagnostic(
            "WATCH-BG-404",
            "Battery optimization settings could not be opened for Sugarlicious Wear",
            DiagnosticSeverity.ERROR,
        )
        return false
    }

    private fun requestWatchFacePermissionOnFirstLaunch() {
        val onboarding = getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        if (onboarding.getBoolean(KEY_WFP_PERMISSION_REQUESTED, false)) return

        onboarding.edit().putBoolean(KEY_WFP_PERMISSION_REQUESTED, true).apply()
        if (SugarliciousWatchFacePush.isSupported() && !SugarliciousWatchFacePush.hasActivationPermission(this)) {
            requestPermissions(arrayOf(SugarliciousWatchFacePush.ACTIVE_PERMISSION), WATCH_FACE_PERMISSION_REQUEST)
        }
    }

    private fun renderWatchFacePushStatus() {
        if (!::watchFacePushStatus.isInitialized) return
        watchFacePushStatus.text = when {
            !SugarliciousWatchFacePush.isSupported() -> "Watchface-Direktwechsel: Wear OS 6+ erforderlich"
            SugarliciousWatchFacePush.hasActivationPermission(this) -> "Watchface-Direktwechsel freigegeben"
            else -> "Watchface-Direktwechsel nicht freigegeben"
        }
    }

    private fun recordRuntimeDiagnostic(
        code: String,
        message: String,
        severity: DiagnosticSeverity,
        error: Throwable? = null,
    ) {
        scope.launch(Dispatchers.IO) {
            applicationContext.recordWatchDiagnostic(
                "RUNTIME",
                code,
                message,
                severity,
                error?.let { mapOf("error" to it.javaClass.simpleName) }.orEmpty(),
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Toast.makeText(
                        this,
                        "Benachrichtigungen nicht freigegeben – die Dauerbetrieb-Anzeige kann verborgen bleiben",
                        Toast.LENGTH_LONG,
                    ).show()
                    recordRuntimeDiagnostic(
                        "WATCH-NOTIFY-403",
                        "Notification permission was not granted for Sugarlicious Wear",
                        DiagnosticSeverity.WARNING,
                    )
                }
                val batteryRequestStarted = requestBatteryExemptionIfNeeded()
                if (!batteryRequestStarted) requestWatchFacePermissionOnFirstLaunch()
            }
            WATCH_FACE_PERMISSION_REQUEST -> renderWatchFacePushStatus()
        }
    }

    private fun resolveUnit(stateUnit: GlucoseUnit?, preference: WatchGlucoseUnit): GlucoseUnit = when (preference) {
        WatchGlucoseUnit.AAPS -> stateUnit ?: GlucoseUnit.MG_DL
        WatchGlucoseUnit.MG_DL -> GlucoseUnit.MG_DL
        WatchGlucoseUnit.MMOL_L -> GlucoseUnit.MMOL_L
    }

    private fun formatNumber(value: Double?, digits: Int, suffix: String): String =
        value?.let { String.format(Locale.US, "%.${digits}f%s", it, suffix) } ?: "—"

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 700
        private const val WATCH_FACE_PERMISSION_REQUEST = 701
        private const val ONBOARDING_PREFS = "wear_onboarding"
        private const val KEY_WFP_PERMISSION_REQUESTED = "watchface_permission_requested"
    }
}

internal fun basalDisplayUnitsPerHour(basal: BasalState?): Double? = basal?.currentUnitsPerHour

internal fun basalIconResource(basal: BasalState?): Int {
    val absolute = basal?.tempAbsoluteUnitsPerHour
    val base = basal?.currentUnitsPerHour
    val percent = basal?.tempPercent
    return when {
        absolute != null && base != null && absolute > base + BASAL_COMPARE_EPSILON ->
            ComplicationR.drawable.ic_complication_basal_more
        absolute != null && base != null && absolute < base - BASAL_COMPARE_EPSILON ->
            ComplicationR.drawable.ic_complication_basal_less
        percent != null && percent > 100 -> ComplicationR.drawable.ic_complication_basal_more
        percent != null && percent < 100 -> ComplicationR.drawable.ic_complication_basal_less
        else -> ComplicationR.drawable.ic_complication_basal
    }
}

private const val BASAL_COMPARE_EPSILON = 0.001
