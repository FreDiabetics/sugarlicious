package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SessionState
import app.aapswear.g7.G7SensorState
import app.aapswear.g7.G7SetupPayload
import app.aapswear.model.Trend
import app.aapswear.model.TrendVisuals
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.cgmBoundaryDisplay
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.WearGlucoseCardInput
import app.aapswear.model.WearGlucoseCardStyle
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.wearGlucoseCardPresentation
import app.aapswear.uishared.TrendDrawableResources
import java.util.Locale

internal fun hasUsableCollectorSession(reading: CgmReading?, sensorId: String?): Boolean =
    reading != null && reading.status == CgmReadingStatus.VALID && reading.sensorId == sensorId

internal fun requiresPairingGate(state: G7PersistedState): Boolean =
    state.sensor == null ||
        state.sensor?.state == G7SensorState.ENDED ||
        !hasUsableCollectorSession(state.lastReading, state.sensor?.sensorId)

internal fun isG7PairingAttemptActive(state: G7PersistedState, nowEpochMs: Long): Boolean {
    val timedOut = (state.pairingDeadlineEpochMs ?: state.scanTimeoutAtEpochMs)?.let { nowEpochMs >= it } == true
    return state.collectorEnabled && !timedOut && state.lastError?.code != "G7-AUTH-204"
}

internal fun pairingSuccessRemainingMs(deadlineEpochMs: Long, nowEpochMs: Long): Long =
    (deadlineEpochMs - nowEpochMs).coerceAtLeast(0L)

internal fun nextDirectGraphHours(current: Int): Int {
    val options = G7DirectToWatchSettingsStore.HOUR_OPTIONS
    val index = options.indexOf(current)
    return options[if (index < 0) 0 else (index + 1) % options.size]
}

class G7WatchActivity : Activity() {
    private val appearanceStore by lazy { G7AppearanceStore(this) }
    private val directSettings by lazy { G7DirectToWatchSettingsStore(this) }
    private var readingObserverRegistered = false
    private var activePalette: G7AppearancePalette? = null
    private var screenBuilt = false
    private var pairingGateVisible = false
    private var pairingSuccessDialog: android.app.Dialog? = null
    private val pairingSuccessFinish = Runnable {
        pairingSuccessDialog?.dismiss()
        pairingSuccessDialog = null
        getSharedPreferences(PAIRING_UI_PREFERENCES, MODE_PRIVATE)
            .edit().remove(KEY_PAIRING_SUCCESS_DEADLINE).apply()
        if (!isFinishing && !isDestroyed) refreshScreen()
    }
    private val pairingRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && pairingGateVisible) {
                refreshScreen()
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val graphClockRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && screenBuilt) updateGraphOnly()
            val now = System.currentTimeMillis()
            mainHandler.postDelayed(this, 60_000L - now % 60_000L)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollView: ScrollView
    private lateinit var statusHost: LinearLayout
    private lateinit var glucoseHost: LinearLayout
    private lateinit var graphView: G7CollectorGraphView
    private lateinit var graphPeriodPill: TextView
    private val readingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!isFinishing && !isDestroyed) refreshScreen()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        G7RuntimeReconciler.reconcile(this, G7RuntimeEntryPoint.WATCH_APP)
        requestMissingPermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshLiveContent()
    }

    override fun onResume() {
        super.onResume()
        registerReadingObserver()
        refreshScreen()
        mainHandler.removeCallbacks(graphClockRefresh)
        val now = System.currentTimeMillis()
        mainHandler.postDelayed(graphClockRefresh, 60_000L - now % 60_000L)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(pairingRefresh)
        mainHandler.removeCallbacks(graphClockRefresh)
        unregisterReadingObserver()
        super.onPause()
    }

    private fun registerReadingObserver() {
        if (readingObserverRegistered) return
        contentResolver.registerContentObserver(
            G7ReadingProvider.CONTENT_URI,
            true,
            readingObserver,
        )
        readingObserverRegistered = true
    }

    private fun unregisterReadingObserver() {
        if (!readingObserverRegistered) return
        contentResolver.unregisterContentObserver(readingObserver)
        readingObserverRegistered = false
    }

    private fun refreshScreen() {
        val palette = appearanceStore.load()
        val state = G7SensorStateStore(this).read()
        if (requiresPairingGate(state)) {
            buildPairingGate(palette, state)
        } else if (!screenBuilt || pairingGateVisible || palette != activePalette) {
            pairingGateVisible = false
            mainHandler.removeCallbacks(pairingRefresh)
            buildScreen(palette)
            showPairingSuccessIfNeeded(state)
        } else refreshLiveContent()
    }

    private fun buildPairingGate(palette: G7AppearancePalette, state: G7PersistedState) {
        pairingGateVisible = true
        screenBuilt = false
        activePalette = palette
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 20.dp, 24.dp, 24.dp)
            setBackgroundColor(background)
        }
        val timedOut = (state.pairingDeadlineEpochMs ?: state.scanTimeoutAtEpochMs)
            ?.let { System.currentTimeMillis() >= it } == true
        val authenticationRejected = state.lastError?.code == "G7-AUTH-204"
        val pairingStarted = isG7PairingAttemptActive(state, System.currentTimeMillis())
        content.addView(label("Suche Sensor", 20f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true))
        if (pairingStarted) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_sensor_outline)
                    setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                    contentDescription = "Sensor"
                }, LinearLayout.LayoutParams(48.dp, 48.dp))
                addView(G7ConnectionDotsView(this@G7WatchActivity).apply {
                    contentDescription = "7 Verbindungsdots"
                    color = palette.argb(G7AppearanceRole.MENU_PRIMARY)
                }, LinearLayout.LayoutParams(0, 24.dp, 1f).apply { setMargins(10.dp, 0, 10.dp, 0) })
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_watch_device)
                    setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                    contentDescription = "Smartwatch"
                }, LinearLayout.LayoutParams(48.dp, 48.dp))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 30.dp; gravity = Gravity.CENTER_HORIZONTAL
            })
            content.addView(label("Dies kann bis zu", 14f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply {
                gravity = Gravity.CENTER; setPadding(4.dp, 34.dp, 4.dp, 0)
            })
            content.addView(label("30 Minuten dauern", 17f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
                gravity = Gravity.CENTER; setPadding(4.dp, 6.dp, 4.dp, 0)
            })
        } else {
            content.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_sensor_outline)
                setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                contentDescription = "Sensor koppeln"
            }, LinearLayout.LayoutParams(56.dp, 56.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
            content.addView(label(
                when {
                    authenticationRejected -> "Sensor ist noch mit einer anderen Uhr verbunden. Dort zuerst „Für andere Uhr freigeben“ wählen."
                    timedOut -> "Verbindung zum Sensor fehlgeschlagen"
                    else -> "Vierstelligen Kopplungscode vom Sensor eingeben"
                },
                12f,
                if (timedOut) palette.argb(G7AppearanceRole.GLUCOSE_ERROR) else palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
                timedOut,
            ).apply { setPadding(4.dp, 8.dp, 4.dp, 10.dp) })
        }
        if (!pairingStarted) {
            val code = EditText(this).apply {
                hint = "0000"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
                setHintTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
                contentDescription = "Vierstelliger G7 Kopplungscode"
            }
            content.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58.dp))
            content.addView(pill(if (timedOut || authenticationRejected) "Sensor auf diese Uhr umziehen" else "Koppeln", PillStyle.PRIMARY, palette) {
                val entered = code.text?.toString().orEmpty()
                if (entered.length != 4 || entered.any { !it.isDigit() }) {
                    code.error = "Bitte genau vier Ziffern eingeben"
                    return@pill
                }
                val sensor = moveG7SensorToThisWatch(this, entered)
                getSharedPreferences(PAIRING_UI_PREFERENCES, MODE_PRIVATE).edit()
                    .putString(KEY_PAIRING_SENSOR_ID, sensor.sensorId)
                    .apply()
                refreshScreen()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10.dp; gravity = Gravity.CENTER_HORIZONTAL
        })
    }

        setContentView(
            if (pairingStarted) FrameLayout(this).apply {
                setBackgroundColor(background)
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else G7EdgeFadeScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(background)
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }.applyG7EdgeFade(),
        )
        mainHandler.removeCallbacks(pairingRefresh)
        // Do not rebuild an idle code form every second: that used to clear the EditText while
        // the user was entering the four digits. Poll only after pairing has actually started.
        if (pairingStarted) mainHandler.postDelayed(pairingRefresh, 1_000L)
    }

    private fun showPairingSuccessIfNeeded(state: G7PersistedState) {
        val sensorId = state.sensor?.sensorId ?: return
        if (!hasUsableCollectorSession(state.lastReading, sensorId)) return
        val preferences = getSharedPreferences(PAIRING_UI_PREFERENCES, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (preferences.getString(KEY_PAIRING_SENSOR_ID, null) == sensorId) {
            preferences.edit()
                .remove(KEY_PAIRING_SENSOR_ID)
                .putLong(KEY_PAIRING_SUCCESS_DEADLINE, now + PAIRING_SUCCESS_DURATION_MS)
                .apply()
        }
        val deadline = preferences.getLong(KEY_PAIRING_SUCCESS_DEADLINE, 0L)
        if (deadline <= now || pairingSuccessDialog?.isShowing == true) return
        val palette = appearanceStore.load()
        pairingSuccessDialog = android.app.Dialog(this).apply {
            setContentView(LinearLayout(this@G7WatchActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(28.dp, 30.dp, 28.dp, 30.dp)
                setBackgroundColor(palette.argb(G7AppearanceRole.MENU_BACKGROUND))
                addView(label("Sensor erfolgreich\nverbunden", 18f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_success_check)
                    contentDescription = "Sensor erfolgreich verbunden"
                }, LinearLayout.LayoutParams(72.dp, 72.dp).apply { topMargin = 28.dp; gravity = Gravity.CENTER_HORIZONTAL })
            })
            setCancelable(false)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }
        mainHandler.removeCallbacks(pairingSuccessFinish)
        mainHandler.postDelayed(pairingSuccessFinish, pairingSuccessRemainingMs(deadline, now))
    }

    private fun buildScreen(palette: G7AppearancePalette) {
        val previousScrollY = if (screenBuilt) scrollView.scrollY else 0
        activePalette = palette
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background

        val state = G7SensorStateStore(this).read()
        val credentials = G7CredentialStore(this).read()
        val userStatus = deriveG7UserStatus(state, credentials != null)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 14.dp, 18.dp, 30.dp)
            setBackgroundColor(background)
        }

        glucoseHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(glucoseHost, cardParams(top = 4))
        content.addView(graphTile(G7ReadingDatabase(this).query(limit = 300), palette), cardParams(top = 7))
        content.addView(header(palette, userStatus))

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), PorterDuff.Mode.SRC_IN)
            setOnClickListener { startActivity(Intent(this@G7WatchActivity, G7SettingsActivity::class.java)) }
            contentDescription = "Einstellungen"
        }, LinearLayout.LayoutParams(48.dp, 48.dp).apply {
            topMargin = 3.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })

        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_g7_sensor)
            contentDescription = "SugarWear"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(54.dp, 54.dp).apply {
            topMargin = 9.dp
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(label("SugarWear", 15f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            setPadding(3.dp, 2.dp, 3.dp, 0)
        })
        content.addView(label("by Sugarlicious", 9f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
            letterSpacing = 0.08f
            setPadding(3.dp, 1.dp, 3.dp, 0)
        })

        scrollView = G7EdgeFadeScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        }.applyG7EdgeFade()
        setContentView(scrollView)
        screenBuilt = true
        refreshLiveContent(preserveScroll = false)
        if (previousScrollY > 0) scrollView.post { scrollView.scrollTo(0, previousScrollY) }
    }

    private fun header(palette: G7AppearancePalette, status: G7UserStatus) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        statusHost = LinearLayout(this@G7WatchActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusPill(status, palette))
        }
        addView(statusHost)
    }

    private fun glucoseTile(
        reading: CgmReading?,
        palette: G7AppearancePalette,
    ): LinearLayout {
        val now = System.currentTimeMillis()
        val basePresentation = wearGlucoseCardPresentation(
            WearGlucoseCardInput(
                valueMgDl = reading?.glucoseMgDl,
                displayUnit = GlucoseUnit.MG_DL,
                deltaMgDl = reading?.deltaMgDl,
                trend = reading?.trend ?: Trend.UNKNOWN,
                measuredAtEpochMs = reading?.timestampEpochMs,
                quality = when (reading?.status) {
                    CgmReadingStatus.VALID -> CgmQuality.VALID
                    CgmReadingStatus.SENSOR_ERROR -> CgmQuality.SENSOR_ERROR
                    CgmReadingStatus.INVALID, null -> CgmQuality.INVALID
                },
                sourceLabel = "",
            ),
            G7GraphColorStore(this).readThresholds(),
            now,
        )
        val boundary = reading?.takeIf { it.status == CgmReadingStatus.VALID }?.glucoseMgDl.let(::cgmBoundaryDisplay)
        val presentation = if (boundary == null) basePresentation else basePresentation.copy(
            value = boundary.label,
            trend = null,
            primaryMeta = "",
        )
        val valueColor = when {
            !presentation.displayable -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
            presentation.rangeClass == CgmRangeClass.VERY_LOW -> palette.argb(G7AppearanceRole.GLUCOSE_VERY_LOW)
            presentation.rangeClass == CgmRangeClass.LOW -> palette.argb(G7AppearanceRole.GLUCOSE_LOW)
            presentation.rangeClass == CgmRangeClass.VERY_HIGH -> palette.argb(G7AppearanceRole.GLUCOSE_VERY_HIGH)
            presentation.rangeClass == CgmRangeClass.HIGH -> palette.argb(G7AppearanceRole.GLUCOSE_HIGH)
            else -> palette.argb(G7AppearanceRole.GLUCOSE_IN_RANGE)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = WearGlucoseCardStyle.CARD_HEIGHT_DP.dp
            setPadding(
                WearGlucoseCardStyle.HORIZONTAL_PADDING_DP.dp,
                WearGlucoseCardStyle.VERTICAL_PADDING_DP.dp,
                WearGlucoseCardStyle.HORIZONTAL_PADDING_DP.dp,
                WearGlucoseCardStyle.VERTICAL_PADDING_DP.dp,
            )
            background = rounded(
                palette.argb(G7AppearanceRole.MENU_SURFACE),
                palette.argb(G7AppearanceRole.MENU_BORDER),
                WearGlucoseCardStyle.CARD_RADIUS_DP,
            )
        }
        tile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(label(presentation.value, WearGlucoseCardStyle.VALUE_TEXT_SP * GlucoseTrendSizing.scaleFactor(appearanceStore.glucoseScalePercent()), valueColor, true))
            presentation.trend?.let { addView(trendIndicator(it, valueColor)) }
        })
        tile.addView(label(presentation.primaryMeta, WearGlucoseCardStyle.META_TEXT_SP, palette.argb(G7AppearanceRole.GLUCOSE_DELTA), true))
        if (presentation.secondaryMeta.isNotBlank()) {
            tile.addView(label(presentation.secondaryMeta, WearGlucoseCardStyle.META_TEXT_SP, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true))
        }
        return tile
    }

    private fun refreshLiveContent(preserveScroll: Boolean = true) {
        if (pairingGateVisible) {
            refreshScreen()
            return
        }
        if (!screenBuilt) {
            refreshScreen()
            return
        }
        val palette = appearanceStore.load()
        if (palette != activePalette) {
            buildScreen(palette)
            return
        }
        preserveScrollPosition(preserveScroll) {
            val state = G7SensorStateStore(this).read()
            val credentials = G7CredentialStore(this).read()
            val userStatus = deriveG7UserStatus(state, credentials != null)

            statusHost.removeAllViews()
            statusHost.addView(statusPill(userStatus, palette))
            glucoseHost.removeAllViews()
            glucoseHost.addView(label("Gewebeglukose", 11f, 0xFFFFFFFF.toInt(), false).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(WearGlucoseCardStyle.CARD_RADIUS_DP.toInt().dp, 4.dp, 0, 5.dp)
            })
            glucoseHost.addView(glucoseTile(state.lastReading, palette))
            updateGraphOnly(preserveScroll = false)
        }
    }

    private fun updateGraphOnly(preserveScroll: Boolean = true) {
        if (!screenBuilt) return
        val palette = activePalette ?: return
        preserveScrollPosition(preserveScroll) {
            val hours = directSettings.graphHours()
            val latestTimestamp = G7SensorStateStore(this).read().lastReading?.timestampEpochMs
            val ageMinutes = latestTimestamp?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 60_000L) }
            graphPeriodPill.text = buildString {
                append(hours).append('h')
                if (ageMinutes != null) append(" • ").append(ageMinutes).append('m')
            }
            graphView.bind(
                readings = G7ReadingDatabase(this).query(limit = 300),
                palette = palette,
                graphHours = hours,
            )
        }
    }

    private inline fun preserveScrollPosition(enabled: Boolean, update: () -> Unit) {
        val previousScrollY = if (enabled && screenBuilt) scrollView.scrollY else 0
        update()
        if (enabled && screenBuilt) scrollView.post { scrollView.scrollTo(0, previousScrollY) }
    }

    private fun graphTile(readings: List<CgmReading>, palette: G7AppearancePalette): FrameLayout {
        val hours = directSettings.graphHours()
        return FrameLayout(this).apply {
            setOnClickListener {
                advanceGraphScale(directSettings.graphHours())
                updateGraphOnly()
            }
            graphView = G7CollectorGraphView(this@G7WatchActivity).apply {
                bind(
                    readings = readings,
                    palette = palette,
                    graphHours = hours,
                )
            }
            addView(graphView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150.dp))

            graphPeriodPill = label("${hours}h", 11f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), true).apply {
                tag = "graph-scale-control"
                contentDescription = "Graphskalierung"
                gravity = Gravity.CENTER
                minWidth = 72.dp
                minHeight = 44.dp
                setOnClickListener {
                    advanceGraphScale(directSettings.graphHours())
                    updateGraphOnly()
                }
            }
            addView(
                graphPeriodPill,
                FrameLayout.LayoutParams(72.dp, 44.dp, Gravity.TOP or Gravity.START).apply {
                    topMargin = 2.dp
                    marginStart = 2.dp
                },
            )
        }
    }

    private fun advanceGraphScale(current: Int) {
        val next = nextDirectGraphHours(current)
        directSettings.saveGraphHours(next)
    }

    private fun statusPill(status: G7UserStatus, palette: G7AppearancePalette): TextView {
        val color = statusColor(status, palette)
        val marker = if (status.level == G7UserStatusLevel.OFF) "○" else "●"
        return label("$marker  ${status.title.uppercase(Locale.GERMANY)}", 10f, color, true).apply {
            background = rounded(withAlpha(color, 36), color, 999f)
            maxWidth = 300.dp
            setPadding(10.dp, 5.dp, 10.dp, 5.dp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 7.dp
                bottomMargin = 3.dp
            }
        }
    }

    private fun statusColor(status: G7UserStatus, palette: G7AppearancePalette): Int = when (status.level) {
        G7UserStatusLevel.OK, G7UserStatusLevel.WORKING -> palette.argb(G7AppearanceRole.MENU_PRIMARY)
        G7UserStatusLevel.ATTENTION -> palette.argb(G7AppearanceRole.GLUCOSE_STALE)
        G7UserStatusLevel.ERROR -> palette.argb(G7AppearanceRole.GLUCOSE_ERROR)
        G7UserStatusLevel.OFF -> palette.argb(G7AppearanceRole.GLUCOSE_NO_SOURCE)
    }

    private enum class PillStyle { PRIMARY, SECONDARY, DANGER }

    private fun pill(
        textValue: String,
        style: PillStyle,
        palette: G7AppearancePalette,
        action: () -> Unit,
    ) = TextView(this).apply {
        text = textValue
        textSize = 11f
        gravity = Gravity.CENTER
        minHeight = 40.dp
        setPadding(13.dp, 8.dp, 13.dp, 8.dp)
        setTypeface(typeface, Typeface.BOLD)
        val (fill, textColor, stroke) = when (style) {
            PillStyle.PRIMARY -> Triple(palette.argb(G7AppearanceRole.MENU_PRIMARY), palette.argb(G7AppearanceRole.MENU_BACKGROUND), palette.argb(G7AppearanceRole.MENU_PRIMARY))
            PillStyle.SECONDARY -> Triple(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), palette.argb(G7AppearanceRole.MENU_BORDER))
            PillStyle.DANGER -> Triple(withAlpha(palette.argb(G7AppearanceRole.GLUCOSE_ERROR), 36), palette.argb(G7AppearanceRole.GLUCOSE_ERROR), palette.argb(G7AppearanceRole.GLUCOSE_ERROR))
        }
        setTextColor(textColor)
        background = rounded(fill, stroke, 999f)
        setOnClickListener { action() }
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(1.dp, stroke)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun cardParams(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, top.dp, 0, 0)
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) refreshLiveContent()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pairingSuccessFinish)
        pairingSuccessDialog?.dismiss()
        pairingSuccessDialog = null
        unregisterReadingObserver()
        super.onDestroy()
    }

    private fun trendIndicator(trend: Trend, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(WearGlucoseCardStyle.TREND_GAP_DP.dp, 0, 0, 0)
        TrendVisuals.spec(trend)?.let { spec ->
            val style = appearanceStore.trendArrowStyle().renderSpec()
            val height = (WearGlucoseCardStyle.TREND_SIZE_DP * style.scale).toInt().dp
            val width = (WearGlucoseCardStyle.TREND_SIZE_DP * style.scale * spec.aspectRatio).toInt().dp
            addView(android.widget.FrameLayout(this@G7WatchActivity).apply {
                fun arrow(tint: Int, x: Float = 0f, y: Float = 0f) = ImageView(this@G7WatchActivity).apply {
                    setImageResource(TrendDrawableResources.forAsset(spec.asset))
                    setColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    translationX = x
                    translationY = y
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (style.outlineThicknessDp > 0f) {
                    val offset = style.outlineThicknessDp * resources.displayMetrics.density
                    listOf(-offset to 0f, offset to 0f, 0f to -offset, 0f to offset).forEach { (x, y) ->
                        addView(arrow(style.outlineColor, x, y), android.widget.FrameLayout.LayoutParams(width, height))
                    }
                }
                addView(arrow(style.fillColor).apply { contentDescription = "Trend ${trend.name}" }, android.widget.FrameLayout.LayoutParams(width, height))
                contentDescription = "Trend ${trend.name}"
            }, LinearLayout.LayoutParams(width, height))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION_REQUEST = 7
        const val PAIRING_UI_PREFERENCES = "sugarwear_pairing_ui"
        const val KEY_PAIRING_SENSOR_ID = "pairing_sensor_id"
        const val KEY_PAIRING_SUCCESS_DEADLINE = "pairing_success_deadline"
        const val PAIRING_SUCCESS_DURATION_MS = 5_000L
    }
}
