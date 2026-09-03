package app.aapswear.complications

import app.aapswear.model.TherapyDisplayState

/**
 * Selects only the providers whose rendered payload can change for a new state.
 * This keeps a new glucose sample from needlessly rebuilding therapy complications
 * and keeps an IOB/COB update from invalidating every graph and glucose field.
 */
object ComplicationUpdatePlanner {
    val allManagedProviders: List<Class<*>>
        get() = (AllProviders.classes + directToWatchProviders).distinct()

    fun affectedProviders(
        old: TherapyDisplayState?,
        new: TherapyDisplayState,
    ): List<Class<*>> {
        if (old == null) return allManagedProviders
        if (old == new) return emptyList()

        val affected = linkedSetOf<Class<*>>()
        val glucoseChanged = old.glucose != new.glucose
        val glucoseHistoryChanged = old.glucoseHistory != new.glucoseHistory

        if (glucoseChanged) {
            affected += glucoseProviders
            affected += directToWatchStatusProviders
        }
        if (old.source != new.source) affected += directToWatchStatusProviders

        if (
            glucoseChanged ||
            glucoseHistoryChanged ||
            old.glucosePredictions != new.glucosePredictions ||
            old.target != new.target
        ) {
            affected += graphProviders
        }

        if (glucoseChanged || glucoseHistoryChanged) affected += tirProviders

        if (old.insulin != new.insulin) {
            affected += iobProviders
            affected += combinedTherapyProviders
            affected += compactStatusProviders
        }
        if (old.carbs != new.carbs) {
            affected += cobProviders
            affected += combinedTherapyProviders
            affected += compactStatusProviders
        }
        if (old.basal != new.basal) {
            affected += basalProviders
            affected += combinedTherapyProviders
        }
        if (old.loop != new.loop) affected += loopProviders
        if (old.pump != new.pump) affected += pumpProviders
        if (old.device != new.device) affected += phoneProviders

        return affected.toList()
    }

    private val directToWatchProviders =
        listOf(
            DirectToWatchHeaderComplication::class.java,
            DirectToWatchGraphComplication::class.java,
            DirectToWatchStatusComplication::class.java,
        )
    private val directToWatchStatusProviders = listOf(DirectToWatchStatusComplication::class.java)

    private val glucoseProviders =
        listOf(
            GlucoseComplication::class.java,
            GlucoseLongTextComplication::class.java,
            GlucoseRangedValueComplication::class.java,
            GlucoseTrendComplication::class.java,
            GlucoseTrendLongTextComplication::class.java,
            GlucoseTrendRangedValueComplication::class.java,
            GlucosePlusDeltaComplication::class.java,
            GlucosePlusDeltaLongTextComplication::class.java,
            GlucoseTrendAgeComplication::class.java,
            GlucoseTrendAgeLongTextComplication::class.java,
            GlucoseTrendDeltaComplication::class.java,
            GlucoseTrendDeltaAgeComplication::class.java,
            GlucoseTrendDeltaAgeLongTextComplication::class.java,
            TrendOnlyComplication::class.java,
            DeltaOnlyComplication::class.java,
            GlucoseAgeComplication::class.java,
            GlucoseDeltaComplication::class.java,
            DirectToWatchHeaderComplication::class.java,
            AapsStatusComplication::class.java,
        )

    private val graphProviders =
        listOf(
            GlucoseGraphComplication::class.java,
            GlucoseGraphLargeComplication::class.java,
            DirectToWatchGraphComplication::class.java,
        )

    private val tirProviders =
        listOf(
            TirComplication::class.java,
            TirGoalProgressComplication::class.java,
            TirWeightedElementsComplication::class.java,
        )

    private val basalProviders = listOf(BasalComplication::class.java)
    private val iobProviders =
        listOf(
            IobComplication::class.java,
            IobRangedValueComplication::class.java,
        )
    private val cobProviders =
        listOf(
            CobComplication::class.java,
            CobRangedValueComplication::class.java,
        )
    private val combinedTherapyProviders =
        listOf(
            IobCobComplication::class.java,
            IobCobLongTextComplication::class.java,
            IobCobBasalComplication::class.java,
            IobCobBasalLongTextComplication::class.java,
        )
    private val loopProviders =
        listOf(
            LoopComplication::class.java,
            LoopIconComplication::class.java,
        )
    private val pumpProviders =
        listOf(
            ReservoirComplication::class.java,
            ReservoirRangedValueComplication::class.java,
            PumpBatteryComplication::class.java,
        )
    private val phoneProviders = listOf(PhoneBatteryComplication::class.java)
    private val compactStatusProviders = listOf(AapsStatusComplication::class.java)
}
