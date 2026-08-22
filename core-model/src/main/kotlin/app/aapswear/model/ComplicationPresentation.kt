package app.aapswear.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Canonical complication presentation shared by Wear providers and phone previews.
 *
 * Short-text complications deliberately keep the main value, optional secondary value and trend
 * separate. Wear OS can then decide how to lay out text and icon without us concatenating a long
 * string that gets clipped in small slots.
 */
data class ComplicationPresentation(
    val text: String,
    val title: String? = null,
    val trend: Trend? = null,
    val contentDescription: String,
)

data class TrendVisualSpec(
    val rotationDegrees: Float,
    val arrowCount: Int = 1,
)

object TrendVisuals {
    fun spec(trend: Trend): TrendVisualSpec? = when (trend) {
        Trend.DOUBLE_UP -> TrendVisualSpec(-90f, 2)
        Trend.SINGLE_UP -> TrendVisualSpec(-90f)
        Trend.FORTY_FIVE_UP -> TrendVisualSpec(-45f)
        Trend.FLAT -> TrendVisualSpec(0f)
        Trend.FORTY_FIVE_DOWN -> TrendVisualSpec(45f)
        Trend.SINGLE_DOWN -> TrendVisualSpec(90f)
        Trend.DOUBLE_DOWN -> TrendVisualSpec(90f, 2)
        Trend.UNKNOWN -> null
    }
}

object SugarliciousComplicationIds {
    const val GLUCOSE = 1
    const val GLUCOSE_TREND = 2
    const val TIME_DELTA = 3
    const val GLUCOSE_TREND_DELTA = 4
    const val GLUCOSE_AGE = 5
    const val GRAPH = 9
    const val IOB = 11
    const val COB = 14
    const val BASAL = 16
    const val LOOP = 19
    const val RESERVOIR = 22
    const val GLUCOSE_PLUS_DELTA = 29
    const val SENSOR_AGE = 30
    const val TIR = 31
    const val GLUCOSE_TREND_DELTA_AGE = 32
    const val GLUCOSE_TREND_AGE = 33
    const val IOB_COB_BASAL = 34
    const val TREND_ONLY = 35
    const val DELTA_ONLY = 36
    const val DATE = 53

    const val GLUCOSE_LONG = 37
    const val GLUCOSE_RANGED = 38
    const val IOB_RANGED = 39
    const val COB_RANGED = 40
    const val GLUCOSE_TREND_LONG = 41
    const val GLUCOSE_TREND_RANGED = 42
    const val GLUCOSE_PLUS_DELTA_LONG = 43
    const val GLUCOSE_TREND_AGE_LONG = 44
    const val GLUCOSE_TREND_DELTA_AGE_LONG = 45
    const val IOB_COB_BASAL_LONG = 46
    const val LOOP_ICON = 47
    const val RESERVOIR_RANGED = 48
    const val SENSOR_AGE_RANGED = 49
    const val TIR_GOAL = 50
    const val TIR_WEIGHTED = 51
    const val GRAPH_LARGE = 52

    val ordered = listOf(
        GLUCOSE,
        GLUCOSE_TREND,
        GLUCOSE_PLUS_DELTA,
        GLUCOSE_TREND_AGE,
        GLUCOSE_TREND_DELTA,
        GLUCOSE_TREND_DELTA_AGE,
        GRAPH,
        TREND_ONLY,
        DELTA_ONLY,
        GLUCOSE_AGE,
        TIME_DELTA,
        SENSOR_AGE,
        BASAL,
        IOB,
        COB,
        IOB_COB_BASAL,
        LOOP,
        RESERVOIR,
        TIR,
        DATE,
    )

    val variantsByBase = mapOf(
        GLUCOSE to listOf(GLUCOSE, GLUCOSE_LONG, GLUCOSE_RANGED),
        IOB to listOf(IOB, IOB_RANGED),
        COB to listOf(COB, COB_RANGED),
        GLUCOSE_TREND to listOf(GLUCOSE_TREND, GLUCOSE_TREND_LONG, GLUCOSE_TREND_RANGED),
        GLUCOSE_PLUS_DELTA to listOf(GLUCOSE_PLUS_DELTA, GLUCOSE_PLUS_DELTA_LONG),
        GLUCOSE_TREND_AGE to listOf(GLUCOSE_TREND_AGE, GLUCOSE_TREND_AGE_LONG),
        GLUCOSE_TREND_DELTA_AGE to listOf(GLUCOSE_TREND_DELTA_AGE, GLUCOSE_TREND_DELTA_AGE_LONG),
        IOB_COB_BASAL to listOf(IOB_COB_BASAL, IOB_COB_BASAL_LONG),
        LOOP to listOf(LOOP, LOOP_ICON),
        RESERVOIR to listOf(RESERVOIR, RESERVOIR_RANGED),
        SENSOR_AGE to listOf(SENSOR_AGE, SENSOR_AGE_RANGED),
        TIR to listOf(TIR, TIR_GOAL, TIR_WEIGHTED),
        GRAPH to listOf(GRAPH, GRAPH_LARGE),
    )

    val all: List<Int> =
        ordered.flatMap { base -> variantsByBase[base] ?: listOf(base) }

    fun baseId(id: Int): Int =
        variantsByBase.entries.firstOrNull { (_, variants) -> id in variants }?.key ?: id
}

object ComplicationPresentationFormatter {
    fun format(
        id: Int,
        state: TherapyDisplayState?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ComplicationPresentation {
        val glucose = state?.glucose
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        val displayable = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
        val liveGlucose = glucose.takeIf { displayable }
        val glucoseText = liveGlucose?.let(TherapyDisplayFormatter::glucose) ?: DASH
        val delta = liveGlucose?.let { TherapyDisplayFormatter.signedDelta(it.deltaMgDl, it.displayUnit) }.orEmpty()
        val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, nowEpochMs)
        val trend = liveGlucose?.trend?.takeUnless { it == Trend.UNKNOWN }

        return when (SugarliciousComplicationIds.baseId(id)) {
            SugarliciousComplicationIds.GLUCOSE ->
                p(glucoseText, desc = "Glukose $glucoseText")

            SugarliciousComplicationIds.TREND_ONLY ->
                p(
                    text = trend?.let(TherapyDisplayFormatter::trendArrow).orEmpty().ifBlank { DASH },
                    desc = trend?.let { "Glukosetrend ${TherapyDisplayFormatter.trendArrow(it)}" } ?: "Kein Glukosetrend",
                )

            SugarliciousComplicationIds.DELTA_ONLY ->
                p(delta.ifBlank { DASH }, desc = "Delta ${delta.ifBlank { DASH }}")

            SugarliciousComplicationIds.GLUCOSE_AGE ->
                p(age, desc = "Glukosewert vor $age")

            SugarliciousComplicationIds.BASAL -> {
                val basal = TherapyDisplayFormatter.units(state?.basal?.currentUnitsPerHour, "U/h", 2)
                p(basal, desc = "Basal $basal")
            }

            SugarliciousComplicationIds.IOB -> {
                val iob = TherapyDisplayFormatter.units(state?.insulin?.totalIob, "U", 2)
                p(iob, desc = "IOB $iob")
            }

            SugarliciousComplicationIds.COB -> {
                val cob = TherapyDisplayFormatter.units(state?.carbs?.cobGrams, "g", 0)
                p(cob, desc = "COB $cob")
            }

            SugarliciousComplicationIds.GLUCOSE_TREND ->
                p(glucoseText, trend = trend, desc = "Glukose $glucoseText mit Trend")

            SugarliciousComplicationIds.GLUCOSE_PLUS_DELTA ->
                p(glucoseText, delta.ifBlank { DASH }, desc = "Glukose $glucoseText, Delta ${delta.ifBlank { DASH }}")

            SugarliciousComplicationIds.TIME_DELTA ->
                p(delta.ifBlank { DASH }, age, desc = "Delta ${delta.ifBlank { DASH }}, Wert vor $age")

            SugarliciousComplicationIds.GLUCOSE_TREND_AGE ->
                p(glucoseText, age, trend, "Glukose $glucoseText mit Trend, Wert vor $age")

            SugarliciousComplicationIds.GLUCOSE_TREND_DELTA ->
                p(glucoseText, delta.ifBlank { DASH }, trend, "Glukose $glucoseText mit Trend, Delta ${delta.ifBlank { DASH }}")

            SugarliciousComplicationIds.GLUCOSE_TREND_DELTA_AGE -> {
                val secondary = listOf(delta.ifBlank { DASH }, age).joinToString(" · ")
                p(glucoseText, secondary, trend, "Glukose $glucoseText mit Trend, Delta ${delta.ifBlank { DASH }}, Wert vor $age")
            }

            SugarliciousComplicationIds.IOB_COB_BASAL -> {
                val basal = TherapyDisplayFormatter.units(state?.basal?.currentUnitsPerHour, "", 2)
                val iob = TherapyDisplayFormatter.units(state?.insulin?.totalIob, "", 1)
                val cob = TherapyDisplayFormatter.units(state?.carbs?.cobGrams, "", 0)
                p("$basal/$iob/$cob", "B/I/C", desc = "Basal $basal U/h, IOB $iob U, COB $cob g")
            }

            SugarliciousComplicationIds.LOOP -> {
                val loop = when (state?.loop?.status?.lowercase()) {
                    "enacted", "closed", "loop", "on", "enabled", "suggested" -> "●"
                    null -> "○"
                    else -> "○"
                }
                p(loop, desc = "AndroidAPS Loop $loop")
            }

            SugarliciousComplicationIds.RESERVOIR -> {
                val reservoir = TherapyDisplayFormatter.units(state?.pump?.reservoirUnits, "U", 0)
                p(reservoir, state?.pump?.status?.takeIf { it.isNotBlank() }, desc = "Reservoir $reservoir")
            }

            SugarliciousComplicationIds.SENSOR_AGE ->
                p(DASH, desc = "Sensoralter nicht verfügbar")

            SugarliciousComplicationIds.TIR -> {
                val tir = tirPercent(state, nowEpochMs)
                val value = tir?.let { "$it%" } ?: DASH
                p(value, "70–180", desc = "TIR $value")
            }

            SugarliciousComplicationIds.DATE -> {
                val localDate = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.systemDefault())
                val weekday = germanWeekday(localDate.dayOfWeek)
                p(localDate.dayOfMonth.toString(), weekday, desc = "$weekday ${localDate.dayOfMonth}")
            }

            else -> p(DASH, desc = "Keine Daten")
        }
    }

    fun tirPercent(state: TherapyDisplayState?, nowEpochMs: Long): Int? {
        val cutoff = nowEpochMs - 24L * 60L * 60_000L
        val samples = state?.glucoseHistory.orEmpty().filter {
            it.measuredAtEpochMs in cutoff..(nowEpochMs + FreshnessPolicy.FUTURE_TOLERANCE_MS) &&
                it.valueMgDl in 20.0..1000.0
        }
        if (samples.isEmpty()) return null
        return (samples.count { it.valueMgDl in 70.0..180.0 } * 100.0 / samples.size).roundToInt()
    }

    private fun p(
        text: String,
        title: String? = null,
        trend: Trend? = null,
        desc: String,
    ) = ComplicationPresentation(text = text, title = title, trend = trend, contentDescription = desc)

    internal fun germanWeekday(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "MON"
        DayOfWeek.TUESDAY -> "DIE"
        DayOfWeek.WEDNESDAY -> "MIT"
        DayOfWeek.THURSDAY -> "DON"
        DayOfWeek.FRIDAY -> "FRE"
        DayOfWeek.SATURDAY -> "SAM"
        DayOfWeek.SUNDAY -> "SON"
    }

    private const val DASH = "—"
}
