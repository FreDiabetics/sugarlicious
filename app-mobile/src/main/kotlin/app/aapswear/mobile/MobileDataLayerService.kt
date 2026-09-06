package app.aapswear.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WearProtocol
import app.aapswear.protocol.G7ReadingAck
import app.aapswear.protocol.G7ReadingBatch
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchAppearanceProfile
import app.aapswear.model.AppearanceMode
import app.aapswear.protocol.WatchGraphStyle
import app.aapswear.protocol.WatchUiColors
import app.aapswear.protocol.WatchDataSource
import app.aapswear.model.DataSourceId
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.storage.TherapyStateStore
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.DiagnosticEventStore
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MobileDataLayerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
    }

    override fun onMessageReceived(event: MessageEvent) {
        recordWatchContact(applicationContext)
        when (event.path) {
            WearProtocol.REQUEST_PATH -> {
                scope.launch {
                    applicationContext.recordMobileDiagnostic("SYNC", "SYNC-WATCH-100", "Watch requested current state")
                    val phoneState = PhoneTherapyStateStore(this@MobileDataLayerService)
                        .state
                        .first()
                        ?: TherapyStateStore(this@MobileDataLayerService)
                            .state
                            .first()
                            ?.withoutDirectWatchCgm()
                    runCatching {
                        phoneState?.let { publishState(this@MobileDataLayerService, it) }
                        publishWatchConfig(this@MobileDataLayerService)
                    }.onFailure { error ->
                        applicationContext.recordMobileDiagnostic(
                            "SYNC",
                            "SYNC-REQUEST-503",
                            "Watch request could not be fulfilled",
                            DiagnosticSeverity.WARNING,
                            metadata = mapOf("error" to error.javaClass.simpleName),
                        )
                    }
                }
            }

            WearProtocol.WATCH_CONFIG_REQUEST_PATH -> {
                scope.launch {
                    applicationContext.recordMobileDiagnostic("SYNC", "SYNC-CONFIG-101", "Watch requested display configuration")
                    runCatching { publishWatchConfig(this@MobileDataLayerService) }
                        .onFailure { error ->
                            applicationContext.recordMobileDiagnostic(
                                "SYNC",
                                "SYNC-CONFIG-503",
                                "Watch configuration request failed",
                                DiagnosticSeverity.WARNING,
                                metadata = mapOf("error" to error.javaClass.simpleName),
                            )
                        }
                }
            }
            WearProtocol.WATCH_FACE_STATUS_PATH -> {
                val message = event.data.decodeToString()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            WearProtocol.WATCH_RUNTIME_STATUS_PATH -> {
                runCatching { WearProtocol.decodeRuntimeStatus(event.data) }
                    .onSuccess {
                        WatchRuntimeStatusStore.save(applicationContext, it)
                        scope.launch {
                            applicationContext.recordMobileDiagnostic(
                                "WATCH",
                                "WATCH-STATUS-200",
                                "Watch runtime status received",
                                metadata = mapOf("complications" to it.activeComplicationIds.size, "watchface" to it.activeSugarliciousFaceIndex),
                            )
                        }
                    }
                    .onFailure {
                        scope.launch {
                            applicationContext.recordMobileDiagnostic("WATCH", "WATCH-STATUS-401", "Invalid Watch runtime status", DiagnosticSeverity.WARNING)
                        }
                    }
            }

            WearProtocol.G7_READING_BATCH_PATH -> {
                scope.launch {
                    val batch = runCatching { WearProtocol.decodeG7ReadingBatch(event.data) }.getOrElse {
                        applicationContext.recordMobileDiagnostic("G7", "G7-SYNC-401", "Invalid G7 Watch history batch rejected", DiagnosticSeverity.WARNING)
                        return@launch
                    }
                    acceptG7Batch(batch, event.sourceNodeId)
                }
            }

            WearProtocol.DIAGNOSTICS_BATCH_PATH -> {
                scope.launch {
                    runCatching { WearProtocol.decodeDiagnostics(event.data) }
                        .onSuccess { batch ->
                            DiagnosticEventStore(applicationContext).append(batch.events)
                            applicationContext.recordMobileDiagnostic(
                                "DIAGNOSTICS",
                                "DIAG-SYNC-200",
                                "Watch diagnostics received",
                                metadata = mapOf("eventCount" to batch.events.size),
                            )
                        }
                        .onFailure {
                            applicationContext.recordMobileDiagnostic(
                                "DIAGNOSTICS",
                                "DIAG-SYNC-401",
                                "Watch diagnostics could not be decoded",
                                DiagnosticSeverity.WARNING,
                            )
                        }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun acceptG7Batch(batch: G7ReadingBatch, sourceNodeId: String) {
        MobileG7BackfillStore(this).clear()
        val ignoredIds = batch.readings.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet()
        val ack = G7ReadingAck(
            batchId = batch.batchId,
            acknowledgedIds = ignoredIds,
            acknowledgedAtEpochMs = System.currentTimeMillis(),
        )
        Wearable.getMessageClient(this)
            .sendMessage(sourceNodeId, WearProtocol.G7_READING_ACK_PATH, WearProtocol.encodeG7ReadingAck(ack))
            .await()
        applicationContext.recordMobileDiagnostic(
            "G7", "G7-SYNC-204", "Direct-to-Watch history ignored by AndroidAPS-only Mobile policy",
            metadata = mapOf("batchId" to batch.batchId, "received" to batch.readings.size, "acknowledgedAsIgnored" to ignoredIds.size),
        )
    }
}

internal fun readWatchConfig(context: Context): WatchConfig {
    val preferences =
        context.getSharedPreferences(
            "dashboard_ui",
            Context.MODE_PRIVATE,
        )

    val unit =
        runCatching {
            WatchGlucoseUnit.valueOf(
                preferences.getString("unit", WatchGlucoseUnit.AAPS.name)
                    ?: WatchGlucoseUnit.AAPS.name,
            )
        }.getOrDefault(WatchGlucoseUnit.AAPS)

    val palette = SugarliciousColorStore.load(preferences)
    return WatchConfig(
        graphHours =
            preferences
                .getInt("graphHours", 3)
                .takeIf { it in listOf(1, 2, 3, 6, 12, 24) }
                ?: 3,
        showPredictions =
            listOf(
                "cgm.prediction.iob",
                "cgm.prediction.cob",
                "cgm.prediction.uam",
                "cgm.prediction.zeroTemp",
            ).any { preferences.getBoolean(it, false) },
        glucoseUnit = unit,
        dataSource = when (
            runCatching {
                DataSourcePreference.valueOf(
                    preferences.getString("dataSource", DataSourcePreference.AUTOMATIC.name)!!,
                )
            }.getOrDefault(DataSourcePreference.AUTOMATIC)
        ) {
            DataSourcePreference.AUTOMATIC -> WatchDataSource.AUTOMATIC
            DataSourcePreference.DEXCOM_G7_WATCH -> WatchDataSource.DEXCOM_G7_WATCH
            DataSourcePreference.ANDROID_APS,
            DataSourcePreference.XDRIP_PLUS,
            -> WatchDataSource.PHONE
        },
        showTherapyStats = preferences.getBoolean("showDetails", true),
        graphColors = WatchGraphColors(
            graphBackground = palette.argb(SugarliciousColorRole.GRAPH_BACKGROUND),
            rangeLow = palette.argb(SugarliciousColorRole.RANGE_LOW),
            rangeInRange = palette.argb(SugarliciousColorRole.RANGE_IN_RANGE),
            rangeHigh = palette.argb(SugarliciousColorRole.RANGE_HIGH),
            cgmLow = palette.argb(SugarliciousColorRole.CGM_DOT_LOW),
            cgmInRange = palette.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE),
            cgmHigh = palette.argb(SugarliciousColorRole.CGM_DOT_HIGH),
            cgmVeryLow = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_LOW),
            cgmVeryHigh = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_HIGH),
            divider = palette.argb(SugarliciousColorRole.GRAPH_DIVIDER),
            highLine = palette.argb(SugarliciousColorRole.GRAPH_HIGH_LINE),
            lowLine = palette.argb(SugarliciousColorRole.GRAPH_LOW_LINE),
            axisLabel = palette.argb(SugarliciousColorRole.GRAPH_LABEL),
            axisTick = palette.argb(SugarliciousColorRole.GRAPH_AXIS_TICK),
            nowLine = palette.argb(SugarliciousColorRole.GRAPH_NOW_LINE),
            outline = palette.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE),
            predictionIob = palette.argb(SugarliciousColorRole.PREDICTION_IOB),
            predictionCob = palette.argb(SugarliciousColorRole.PREDICTION_COB),
            predictionUam = palette.argb(SugarliciousColorRole.PREDICTION_UAM),
            predictionZeroTemp = palette.argb(SugarliciousColorRole.PREDICTION_ZERO_TEMP),
            targetValue = palette.argb(SugarliciousColorRole.TARGET_VALUE),
            signalLoss = palette.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS),
        ),
        graphStyle = readMobileGraphStyle(preferences),
        uiColors = WatchUiColors(
            background = palette.argb(SugarliciousColorRole.BACKGROUND),
            tileBackground = palette.argb(SugarliciousColorRole.SURFACE),
            tileBorder = palette.argb(SugarliciousColorRole.BORDER),
            textPrimary = palette.argb(SugarliciousColorRole.TEXT_PRIMARY),
            textSecondary = palette.argb(SugarliciousColorRole.TEXT_SECONDARY),
            accent = palette.argb(SugarliciousColorRole.PRIMARY),
            glucoseLow = palette.argb(SugarliciousColorRole.GLUCOSE_LOW),
            glucoseInRange = palette.argb(SugarliciousColorRole.GLUCOSE_IN_RANGE),
            glucoseHigh = palette.argb(SugarliciousColorRole.GLUCOSE_HIGH),
            glucoseVeryLow = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_LOW),
            glucoseVeryHigh = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_HIGH),
            iob = palette.argb(SugarliciousColorRole.BLUE),
            cob = palette.argb(SugarliciousColorRole.ORANGE),
            basal = palette.argb(SugarliciousColorRole.SECONDARY),
        ),
        cgmThresholds = CgmThresholdPreferences.read(preferences),
        sentAtEpochMs = System.currentTimeMillis(),
    )
}

internal fun readWatchAppearanceProfile(context: Context, mode: AppearanceMode): WatchAppearanceProfile {
    val preferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
    val palette = SugarliciousColorStore.load(preferences, mode)
    return WatchAppearanceProfile(
        graphColors = WatchGraphColors(
            graphBackground = palette.argb(SugarliciousColorRole.GRAPH_BACKGROUND),
            rangeLow = palette.argb(SugarliciousColorRole.RANGE_LOW),
            rangeInRange = palette.argb(SugarliciousColorRole.RANGE_IN_RANGE),
            rangeHigh = palette.argb(SugarliciousColorRole.RANGE_HIGH),
            cgmLow = palette.argb(SugarliciousColorRole.CGM_DOT_LOW),
            cgmInRange = palette.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE),
            cgmHigh = palette.argb(SugarliciousColorRole.CGM_DOT_HIGH),
            cgmVeryLow = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_LOW),
            cgmVeryHigh = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_HIGH),
            divider = palette.argb(SugarliciousColorRole.GRAPH_DIVIDER),
            highLine = palette.argb(SugarliciousColorRole.GRAPH_HIGH_LINE),
            lowLine = palette.argb(SugarliciousColorRole.GRAPH_LOW_LINE),
            axisLabel = palette.argb(SugarliciousColorRole.GRAPH_LABEL),
            axisTick = palette.argb(SugarliciousColorRole.GRAPH_AXIS_TICK),
            nowLine = palette.argb(SugarliciousColorRole.GRAPH_NOW_LINE),
            outline = palette.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE),
            predictionIob = palette.argb(SugarliciousColorRole.PREDICTION_IOB),
            predictionCob = palette.argb(SugarliciousColorRole.PREDICTION_COB),
            predictionUam = palette.argb(SugarliciousColorRole.PREDICTION_UAM),
            predictionZeroTemp = palette.argb(SugarliciousColorRole.PREDICTION_ZERO_TEMP),
            targetValue = palette.argb(SugarliciousColorRole.TARGET_VALUE),
            signalLoss = palette.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS),
        ),
        graphStyle = readMobileGraphStyle(preferences, mode),
        uiColors = WatchUiColors(
            background = palette.argb(SugarliciousColorRole.BACKGROUND),
            tileBackground = palette.argb(SugarliciousColorRole.SURFACE),
            tileBorder = palette.argb(SugarliciousColorRole.BORDER),
            textPrimary = palette.argb(SugarliciousColorRole.TEXT_PRIMARY),
            textSecondary = palette.argb(SugarliciousColorRole.TEXT_SECONDARY),
            accent = palette.argb(SugarliciousColorRole.PRIMARY),
            glucoseLow = palette.argb(SugarliciousColorRole.GLUCOSE_LOW),
            glucoseInRange = palette.argb(SugarliciousColorRole.GLUCOSE_IN_RANGE),
            glucoseHigh = palette.argb(SugarliciousColorRole.GLUCOSE_HIGH),
            glucoseVeryLow = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_LOW),
            glucoseVeryHigh = palette.argb(SugarliciousColorRole.GLUCOSE_VERY_HIGH),
            iob = palette.argb(SugarliciousColorRole.BLUE),
            cob = palette.argb(SugarliciousColorRole.ORANGE),
            basal = palette.argb(SugarliciousColorRole.SECONDARY),
        ),
    )
}

internal suspend fun publishWatchConfig(context: Context) {
    val request =
        PutDataRequest
            .create(WearProtocol.WATCH_CONFIG_PATH)
            .setData(
                WearProtocol.encodeConfig(
                    readWatchConfig(context),
                ),
            )
            .setUrgent()

    Wearable
        .getDataClient(context)
        .putDataItem(request)
        .await()
}

internal suspend fun publishWatchColors(context: Context) {
    val active = readWatchConfig(context)
    val light = readWatchAppearanceProfile(context, AppearanceMode.LIGHT)
    val dark = readWatchAppearanceProfile(context, AppearanceMode.DARK)
    val request =
        PutDataRequest
            .create(WearProtocol.WATCH_COLOR_SYNC_PATH)
            .setData(
                WearProtocol.encodeWatchColorSync(
                    WatchColorSync(
                        graphColors = active.graphColors,
                        lightProfile = light,
                        darkProfile = dark,
                        cgmThresholds = CgmThresholdPreferences.read(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)),
                        sentAtEpochMs = System.currentTimeMillis(),
                    ),
                ),
            )
            .setUrgent()
    Wearable.getDataClient(context).putDataItem(request).await()
}

internal suspend fun requestWatchRuntimeStatus(context: Context) {
    refreshReachableWatchNodeIds(context).forEach { nodeId ->
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, WearProtocol.WATCH_RUNTIME_REQUEST_PATH, byteArrayOf())
            .await()
    }
}
