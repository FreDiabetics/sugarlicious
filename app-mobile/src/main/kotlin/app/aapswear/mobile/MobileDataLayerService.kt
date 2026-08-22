package app.aapswear.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WearProtocol
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchColorSync
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
        scope.launch {
            MobileWatchCgmMigration.runOnce(applicationContext)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        recordWatchContact(applicationContext)
        when (event.path) {
            WearProtocol.REQUEST_PATH -> {
                scope.launch {
                    applicationContext.recordMobileDiagnostic("SYNC", "SYNC-WATCH-100", "Watch requested current state")
                    MobileWatchCgmMigration.runOnce(applicationContext)
                    val phoneState = PhoneTherapyStateStore(this@MobileDataLayerService)
                        .state
                        .first()
                        ?: TherapyStateStore(this@MobileDataLayerService)
                            .state
                            .first()
                            ?.withoutDirectWatchCgm()
                    phoneState?.let { publishState(this@MobileDataLayerService, it) }
                    publishWatchConfig(this@MobileDataLayerService)
                }
            }

            WearProtocol.WATCH_CONFIG_REQUEST_PATH -> {
                scope.launch {
                    applicationContext.recordMobileDiagnostic("SYNC", "SYNC-CONFIG-101", "Watch requested display configuration")
                    publishWatchConfig(this@MobileDataLayerService)
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

            // Product rule: direct G7 Watch readings are local-Watch CGM data only.
            // Keep the legacy paths for compatibility, but reject their payloads explicitly so an
            // older Wear client cannot repopulate Mobile history or Health Connect after migration.
            WearProtocol.G7_READING_PATH,
            WearProtocol.G7_READING_BATCH_PATH,
            -> {
                scope.launch {
                    MobileWatchCgmMigration.runOnce(applicationContext)
                    applicationContext.recordMobileDiagnostic(
                        "G7",
                        "G7-SYNC-410",
                        "Direct G7 Watch CGM payload rejected by Mobile-local data policy",
                        DiagnosticSeverity.INFO,
                        metadata = mapOf("path" to event.path, "sourceNodeId" to event.sourceNodeId),
                    )
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
            divider = palette.argb(SugarliciousColorRole.GRAPH_DIVIDER),
            outline = palette.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE),
            predictionIob = palette.argb(SugarliciousColorRole.PREDICTION_IOB),
            predictionCob = palette.argb(SugarliciousColorRole.PREDICTION_COB),
            predictionUam = palette.argb(SugarliciousColorRole.PREDICTION_UAM),
            predictionZeroTemp = palette.argb(SugarliciousColorRole.PREDICTION_ZERO_TEMP),
            targetValue = palette.argb(SugarliciousColorRole.TARGET_VALUE),
            signalLoss = palette.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS),
        ),
        graphStyle = WatchGraphStyle(
            cgmDotRadiusDp =
                preferences
                    .getFloat("cgm.dotRadiusDp", 2.4f)
                    .coerceIn(1.5f, 6.0f),
            cgmDotOutlineEnabled =
                preferences.getBoolean(
                    "cgm.dotOutlineEnabled",
                    true,
                ),
            cgmDotOutlineWidthDp =
                preferences
                    .getFloat("cgm.dotOutlineWidthDp", 0.95f)
                    .coerceIn(0.25f, 3.0f),
        ),
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
            iob = palette.argb(SugarliciousColorRole.BLUE),
            cob = palette.argb(SugarliciousColorRole.ORANGE),
            basal = palette.argb(SugarliciousColorRole.SECONDARY),
        ),
        sentAtEpochMs = System.currentTimeMillis(),
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
    val colors = readWatchConfig(context).graphColors
    val request =
        PutDataRequest
            .create(WearProtocol.WATCH_COLOR_SYNC_PATH)
            .setData(
                WearProtocol.encodeWatchColorSync(
                    WatchColorSync(
                        graphColors = colors,
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
