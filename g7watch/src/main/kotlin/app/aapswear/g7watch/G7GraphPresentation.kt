package app.aapswear.g7watch

import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingOrigin
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmQuality
import app.aapswear.model.GlucoseSample
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette

/** Shared SugarWear adapter. Both the in-app Canvas and Graph Tile use this exact input. */
internal fun g7SharedGraphInput(
    readings: List<CgmReading>,
    palette: G7AppearancePalette,
    settings: G7DirectToWatchSettingsStore,
    graphHours: Int,
    nowEpochMs: Long,
    emptyLabel: String = "",
): SharedWearCgmGraphInput {
    val colors = settings.graphColors().copy(
        graphBackground = palette.argb(G7AppearanceRole.GRAPH_BACKGROUND),
        rangeLow = palette.argb(G7AppearanceRole.GRAPH_LOW_AREA),
        rangeInRange = palette.argb(G7AppearanceRole.GRAPH_TARGET_AREA),
        rangeHigh = palette.argb(G7AppearanceRole.GRAPH_HIGH_AREA),
        cgmLow = palette.argb(G7AppearanceRole.GRAPH_DOT_LOW),
        cgmInRange = palette.argb(G7AppearanceRole.GRAPH_DOT_IN_RANGE),
        cgmHigh = palette.argb(G7AppearanceRole.GRAPH_DOT_HIGH),
        cgmVeryLow = palette.argb(G7AppearanceRole.GLUCOSE_VERY_LOW),
        cgmVeryHigh = palette.argb(G7AppearanceRole.GLUCOSE_VERY_HIGH),
        divider = palette.argb(G7AppearanceRole.GRAPH_TILE_BORDER),
        highLine = palette.argb(G7AppearanceRole.GRAPH_HIGH_LINE),
        lowLine = palette.argb(G7AppearanceRole.GRAPH_LOW_LINE),
        axisLabel = palette.argb(G7AppearanceRole.GRAPH_AXIS_TEXT),
        axisTick = palette.argb(G7AppearanceRole.GRAPH_GRID),
        outline = palette.argb(G7AppearanceRole.GRAPH_DOT_OUTLINE),
        predictionIob = palette.argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionCob = palette.argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionUam = palette.argb(G7AppearanceRole.GRAPH_PREDICTION),
        predictionZeroTemp = palette.argb(G7AppearanceRole.GRAPH_PREDICTION),
    )
    val hours = graphHours.takeIf { it in G7DirectToWatchSettingsStore.HOUR_OPTIONS } ?: 3
    return SharedWearCgmGraphInput(
        history = normalizeG7LocalHistory(readings).map(CgmReading::toG7GraphSample),
        timeWindow = g7CollectorGraphWindow(nowEpochMs, hours),
        nowEpochMs = nowEpochMs,
        thresholds = settings.thresholds(),
        palette = SharedWearCgmGraphPalette(
            background = colors.graphBackground,
            targetArea = colors.rangeInRange,
            highArea = colors.rangeHigh,
            lowArea = colors.rangeLow,
            highLine = colors.highLine,
            lowLine = colors.lowLine,
            dotHigh = colors.cgmHigh,
            dotInRange = colors.cgmInRange,
            dotLow = colors.cgmLow,
            dotVeryHigh = colors.cgmVeryHigh,
            dotVeryLow = colors.cgmVeryLow,
            dotOutline = colors.outline,
            axisText = colors.axisLabel,
            axisTick = colors.axisTick,
            nowLine = colors.nowLine,
            border = colors.divider,
            predictionIob = colors.predictionIob,
            predictionCob = colors.predictionCob,
            predictionUam = colors.predictionUam,
            predictionZeroTemp = colors.predictionZeroTemp,
            targetText = colors.targetValue,
            emptyText = colors.signalLoss,
        ),
        style = settings.graphStyle(),
        emptyLabel = emptyLabel,
    )
}

internal fun normalizeG7LocalHistory(source: List<CgmReading>): List<CgmReading> {
    val valid = source.filter { it.status == CgmReadingStatus.VALID }
    val newest = valid.maxByOrNull(CgmReading::timestampEpochMs)
    val sameSession = newest?.let { latest ->
        valid.filter { it.sensorId == latest.sensorId && it.sessionId == latest.sessionId }
    }.orEmpty()

    return sameSession
        .groupBy { reading ->
            G7LocalReadingIdentity(
                reading.sensorId,
                reading.sessionId,
                reading.sequenceNumber,
                if (reading.sequenceNumber == null) reading.timestampEpochMs / G7_FALLBACK_BUCKET_MS else 0L,
            )
        }
        .values
        .map { duplicates ->
            duplicates.maxWithOrNull(
                compareBy<CgmReading> { if (it.origin == CgmReadingOrigin.LIVE) 1 else 0 }
                    .thenBy(CgmReading::receivedAtEpochMs),
            ) ?: duplicates.first()
        }
        .sortedBy(CgmReading::timestampEpochMs)
}

private fun CgmReading.toG7GraphSample() = GlucoseSample(
    valueMgDl = glucoseMgDl,
    measuredAtEpochMs = timestampEpochMs,
    source = source,
    sensorId = sensorId,
    sessionId = sessionId,
    sequenceNumber = sequenceNumber,
    receivedAtEpochMs = receivedAtEpochMs,
    quality = CgmQuality.VALID,
)

private data class G7LocalReadingIdentity(
    val sensorId: String,
    val sessionId: String,
    val sequenceNumber: Long?,
    val fallbackMinuteBucket: Long,
)

private const val G7_FALLBACK_BUCKET_MS = 60_000L
