package app.aapswear.model

enum class PresentationSurface {
    MOBILE,
    WIDGET,
    NOTIFICATION,
    WEAR,
    COLLECTOR,
    TILE,
    COMPLICATION,
    WATCHFACE,
}

/** Semantic glucose geometry shared by renderers; renderers still own their platform primitives. */
data class GlucoseVisualSpec(
    val surface: PresentationSurface,
    val glucoseTextSize: Float,
    val trendHeight: Float,
    val unitScale: Float = 1f,
    val secondaryTextScale: Float = 1f,
    val spacing: Float,
    val baselineAligned: Boolean = true,
) {
    fun scaled(glucosePercent: Int, trendPercent: Int): GlucoseVisualSpec =
        copy(
            glucoseTextSize = glucoseTextSize * GlucoseTrendSizing.scaleFactor(glucosePercent),
            trendHeight = trendHeight * GlucoseTrendSizing.scaleFactor(trendPercent),
        )

    companion object {
        /** The accepted 2x2 widget appearance is the cross-surface ratio reference. */
        fun twoByTwoWidgetReference(): GlucoseVisualSpec =
            GlucoseVisualSpec(
                surface = PresentationSurface.WIDGET,
                glucoseTextSize = GlucoseTrendSizing.REFERENCE_GLUCOSE_TEXT_SP,
                trendHeight = GlucoseTrendSizing.REFERENCE_TREND_HEIGHT_DP,
                spacing = 8f,
            )
    }
}

data class GraphTimePolicy(
    val historyDurationMs: Long,
    val futureDurationMs: Long = 0L,
    val anchorAtLatestCgm: Boolean = true,
)

data class GraphAxisSpec(
    val showRelativeTime: Boolean = true,
    val showNowLabel: Boolean = true,
    val targetScaleSide: AxisSide = AxisSide.RIGHT,
)

enum class AxisSide { LEFT, RIGHT }

data class GraphRangeSpec(
    val lowMgDl: Int,
    val highMgDl: Int,
    val minimumMgDl: Int = 40,
    val confirmationCount: Int = 2,
)

data class GraphDotSpec(
    val diameterDp: Float,
    val outlineWidthDp: Float,
)

data class GraphSpec(
    val time: GraphTimePolicy,
    val axis: GraphAxisSpec,
    val range: GraphRangeSpec,
    val dots: GraphDotSpec,
    val showPredictions: Boolean,
    val showTreatments: Boolean,
)

/** Persisted schema versions. Every incompatible settings change must add a migration. */
object SettingsSchemaVersions {
    const val APPEARANCE = 4
    const val WIDGET = 3
    const val WEAR = 5
    const val COLLECTOR = 2
}

