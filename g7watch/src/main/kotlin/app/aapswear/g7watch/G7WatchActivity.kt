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
import java.util.concurrent.Executors

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

internal enum class G7PairingScreenStep { NO_SENSOR, ENTER_CODE, CONNECTING, CONNECTED }

internal fun initialG7PairingScreenStep(state: G7PersistedState, restored: G7PairingScreenStep? = null): G7PairingScreenStep? {
    if (!requiresPairingGate(state)) return null
    return if (restored == G7PairingScreenStep.CONNECTING && isG7PairingAttemptActive(state, System.currentTimeMillis())) {
        G7PairingScreenStep.CONNECTING
    } else {
        G7PairingScreenStep.NO_SENSOR
    }
}

internal fun advanceG7PairingScreen(
    current: G7PairingScreenStep,
    state: G7PersistedState,
): G7PairingScreenStep = if (
    current == G7PairingScreenStep.CONNECTING &&
    hasUsableCollectorSession(state.lastReading, state.sensor?.sensorId)
) G7PairingScreenStep.CONNECTED else current

internal fun canStartG7Pairing(step: G7PairingScreenStep?, inFlight: Boolean, code: String): Boolean =
    step == G7PairingScreenStep.ENTER_CODE && !inFlight && code.length == 4 && code.all(Char::isDigit)

internal fun shouldScheduleG7PairingCompletion(step: G7PairingScreenStep?, alreadyScheduled: Boolean): Boolean =
    step == G7PairingScreenStep.CONNECTED && !alreadyScheduled

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
    private var pairingStep: G7PairingScreenStep? = null
    private var pairingCodeDraft = ""
    private var pairingStartInFlight = false
    private var pairingCompletionScheduled = false
    private var pairingErrorCode: String? = null
    private var pairingStartedAtEpochMs = Long.MAX_VALUE
    private var pairingFailureMessage: String? = null
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val pairingSuccessFinish = Runnable {
        if (!pairingCompletionScheduled) return@Runnable
        pairingCompletionScheduled = false
        pairingStep = null
        pairingGateVisible = false
        if (!isFinishing && !isDestroyed) buildScreen(appearanceStore.load())
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
        pairingCodeDraft = savedInstanceState?.getString(KEY_PAIRING_CODE_DRAFT).orEmpty()
        val restoredStep = savedInstanceState?.getString(KEY_PAIRING_STEP)?.let {
            runCatching { G7PairingScreenStep.valueOf(it) }.getOrNull()
        }
        pairingStep = initialG7PairingScreenStep(G7SensorStateStore(this).read(), restoredStep)
        if (pairingStep == G7PairingScreenStep.CONNECTING) {
            pairingStartedAtEpochMs = G7SensorStateStore(this).read().pairingStartedAtEpochMs ?: System.currentTimeMillis()
        }
        requestMissingPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PAIRING_STEP, pairingStep?.name)
        outState.putString(KEY_PAIRING_CODE_DRAFT, pairingCodeDraft)
        super.onSaveInstanceState(outState)
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
        val currentStep = pairingStep
        if (currentStep != null) {
            val nextStep = advanceG7PairingScreen(currentStep, state)
            val nextError = state.lastError?.takeIf { it.occurredAtEpochMs >= pairingStartedAtEpochMs }?.code
                .takeIf { nextStep == G7PairingScreenStep.CONNECTING }
            if (nextStep != currentStep) {
                pairingStep = nextStep
                pairingStartInFlight = false
                buildPairingGate(palette, state)
                schedulePairingCompletion()
            } else if (!pairingGateVisible || palette != activePalette || nextError != pairingErrorCode) {
                pairingErrorCode = nextError
                buildPairingGate(palette, state)
            }
        } else if (requiresPairingGate(state)) {
            pairingStep = G7PairingScreenStep.NO_SENSOR
            buildPairingGate(palette, state)
        } else if (!screenBuilt || pairingGateVisible || palette != activePalette) {
            pairingGateVisible = false
            mainHandler.removeCallbacks(pairingRefresh)
            buildScreen(palette)
        } else refreshLiveContent()
    }

    private fun buildPairingGate(
        palette: G7AppearancePalette,
        state: G7PersistedState,
    ) {
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
        val step = pairingStep ?: G7PairingScreenStep.NO_SENSOR
        val timedOut = state.pairingStartedAtEpochMs?.let { it >= pairingStartedAtEpochMs } == true &&
            (state.pairingDeadlineEpochMs ?: state.scanTimeoutAtEpochMs)?.let { System.currentTimeMillis() >= it } == true
        val currentPairingError = state.lastError?.takeIf { it.occurredAtEpochMs >= pairingStartedAtEpochMs }
        val authenticationRejected = currentPairingError?.code == "G7-AUTH-204"
        content.addView(label(when (step) {
            G7PairingScreenStep.NO_SENSOR -> "Kein Sensor verbunden"
            G7PairingScreenStep.ENTER_CODE -> "Sensor verbinden"
            G7PairingScreenStep.CONNECTING -> "Sensor verbinden"
            G7PairingScreenStep.CONNECTED -> "Sensor verbunden"
        }, 20f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply { gravity = Gravity.CENTER })
        if (step == G7PairingScreenStep.NO_SENSOR) {
            content.addView(label("Verbinden Sie Ihren Sensor direkt mit Ihrer WearOS Smartwatch.", 13f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply {
                gravity = Gravity.CENTER; setPadding(8.dp, 14.dp, 8.dp, 12.dp)
            })
            content.addView(pill("Sensor verbinden", PillStyle.PRIMARY, palette) {
                pairingStep = G7PairingScreenStep.ENTER_CODE
                buildPairingGate(palette, state)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else if (step == G7PairingScreenStep.CONNECTING || step == G7PairingScreenStep.CONNECTED) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_sensor_outline)
                    setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                    contentDescription = "Sensor"
                }, LinearLayout.LayoutParams(40.dp, 40.dp))
                addView(G7ConnectionDotsView(this@G7WatchActivity).apply {
                    contentDescription = "7 Verbindungsdots"
                    color = palette.argb(G7AppearanceRole.MENU_PRIMARY)
                }, LinearLayout.LayoutParams(0, 24.dp, 1f).apply { setMargins(10.dp, 0, 10.dp, 0) })
                addView(ImageView(this@G7WatchActivity).apply {
                    setImageResource(R.drawable.ic_watch_device)
                    setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                    contentDescription = "Smartwatch"
                }, LinearLayout.LayoutParams(40.dp, 40.dp))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16.dp; gravity = Gravity.CENTER_HORIZONTAL
            })
            content.addView(label(if (step == G7PairingScreenStep.CONNECTED) "Sensor erfolgreich verbunden" else "Dies kann bis zu\n30 Minuten dauern", 16f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
                gravity = Gravity.CENTER; setPadding(4.dp, 14.dp, 4.dp, 8.dp)
            })
            if (step == G7PairingScreenStep.CONNECTED) content.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_success_check)
                contentDescription = "Sensor erfolgreich verbunden"
            }, LinearLayout.LayoutParams(52.dp, 52.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 6.dp })
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = step == G7PairingScreenStep.CONNECTING
                progress = if (step == G7PairingScreenStep.CONNECTED) 100 else 0
                contentDescription = if (step == G7PairingScreenStep.CONNECTED) "Verbindung abgeschlossen" else "Sensorverbindung läuft"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dp).apply { setMargins(20.dp, 0, 20.dp, 0) })
            if (step == G7PairingScreenStep.CONNECTING && (timedOut || authenticationRejected || currentPairingError != null)) {
                content.addView(label(if (authenticationRejected) "Sensor ist noch mit einer anderen Uhr verbunden." else "Verbindung zum Sensor fehlgeschlagen. Erneuter Versuch möglich.", 12f, palette.argb(G7AppearanceRole.GLUCOSE_ERROR), true).apply {
                    gravity = Gravity.CENTER; setPadding(4.dp, 10.dp, 4.dp, 0)
                })
                content.addView(pill("Erneut versuchen", PillStyle.PRIMARY, palette) {
                    pairingStartInFlight = false
                    pairingStep = G7PairingScreenStep.ENTER_CODE
                    buildPairingGate(palette, state)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 5.dp; gravity = Gravity.CENTER_HORIZONTAL
                })
            }
        } else {
            content.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_sensor_outline)
                setColorFilter(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), PorterDuff.Mode.SRC_IN)
                contentDescription = "Sensor koppeln"
            }, LinearLayout.LayoutParams(42.dp, 42.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 6.dp })
            content.addView(label(
                "Vierstelligen Sensorcode eingeben", 13f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)
            ).apply { gravity = Gravity.CENTER; setPadding(4.dp, 5.dp, 4.dp, 5.dp) })
            pairingFailureMessage?.let { message ->
                content.addView(label(message, 11f, palette.argb(G7AppearanceRole.GLUCOSE_ERROR), true).apply { gravity = Gravity.CENTER })
            }
            val code = EditText(this).apply {
                hint = "0000"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
                setHintTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
                this.background = rounded(Color.TRANSPARENT, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY), 12f)
                setText(pairingCodeDraft)
                setSelection(text.length)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pairingCodeDraft = s?.toString().orEmpty() }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
                contentDescription = "Vierstelliger G7 Kopplungscode"
            }
            content.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp))
            content.addView(pill("Verbinden", PillStyle.PRIMARY, palette) {
                val entered = code.text?.toString().orEmpty()
                if (!canStartG7Pairing(pairingStep, pairingStartInFlight, entered)) {
                    if (pairingStartInFlight) return@pill
                    code.error = "Bitte genau vier Ziffern eingeben"
                    return@pill
                }
                pairingStartInFlight = true
                pairingFailureMessage = null
                pairingStartedAtEpochMs = System.currentTimeMillis()
                code.isEnabled = false
                pairingStep = G7PairingScreenStep.CONNECTING
                buildPairingGate(palette, G7SensorStateStore(this).read())
                pairingExecutor.execute {
                    runCatching { moveG7SensorToThisWatch(applicationContext, entered) }
                        .onSuccess {
                            // The collector store is the canonical session owner. UI progression
                            // remains local and waits for a validated reading from this sensor.
                        }
                        .onFailure {
                            mainHandler.post {
                                pairingStartInFlight = false
                                pairingStep = G7PairingScreenStep.ENTER_CODE
                                pairingFailureMessage = "Verbindung konnte nicht gestartet werden. Bitte erneut versuchen."
                                if (!isFinishing && !isDestroyed) buildPairingGate(appearanceStore.load(), G7SensorStateStore(this).read())
                            }
                        }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 7.dp; gravity = Gravity.CENTER_HORIZONTAL
        })
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(background)
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
        mainHandler.removeCallbacks(pairingRefresh)
        // Poll state while pairing, but rebuild only when the semantic pairing presentation
        // changes. The animated dots own their own animation and must not recreate the page.
        if (step == G7PairingScreenStep.CONNECTING) mainHandler.postDelayed(pairingRefresh, 1_000L)
    }

    private fun schedulePairingCompletion() {
        if (!shouldScheduleG7PairingCompletion(pairingStep, pairingCompletionScheduled)) return
        pairingCompletionScheduled = true
        mainHandler.removeCallbacks(pairingSuccessFinish)
        mainHandler.postDelayed(pairingSuccessFinish, PAIRING_SUCCESS_DURATION_MS)
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
        pairingExecutor.shutdown()
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
        const val KEY_PAIRING_STEP = "pairing_step"
        const val KEY_PAIRING_CODE_DRAFT = "pairing_code_draft"
        const val PAIRING_SUCCESS_DURATION_MS = 3_000L
    }
}
