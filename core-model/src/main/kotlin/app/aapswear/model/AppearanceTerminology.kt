package app.aapswear.model

/**
 * Canonical user-facing names for appearance settings.
 *
 * This catalog deliberately contains no values or persistence. Each app and surface keeps its
 * own settings state; only equivalent controls use the same wording everywhere.
 */
object AppearanceTerminology {
    const val APP_BACKGROUND = "App-Hintergrund"
    const val SURFACE_BACKGROUND = "Tile-Hintergrund"
    const val SURFACE_BORDER = "Tile-Kontur"
    const val PRIMARY_TEXT = "Haupttext"
    const val SECONDARY_TEXT = "Sekundärtext"
    const val ACCENT = "Akzent"

    const val GLUCOSE_VERY_LOW = "Zuckerwert · sehr tief"
    const val GLUCOSE_LOW = "Zuckerwert · tief"
    const val GLUCOSE_IN_RANGE = "Zuckerwert · im Ziel"
    const val GLUCOSE_HIGH = "Zuckerwert · hoch"
    const val GLUCOSE_VERY_HIGH = "Zuckerwert · sehr hoch"
    const val TREND_ARROW = "Trendpfeil"

    const val GRAPH_BACKGROUND = "Graph-Hintergrund"
    const val GRAPH_LOW_AREA = "Tief-Bereich"
    const val GRAPH_TARGET_AREA = "Zielbereich"
    const val GRAPH_HIGH_AREA = "Hoch-Bereich"
    const val GRAPH_LOW_LINE = "Tief-Grenzlinie"
    const val GRAPH_HIGH_LINE = "Hoch-Grenzlinie"
    const val GRAPH_DOT_LOW = "CGM-Punkte · tief"
    const val GRAPH_DOT_IN_RANGE = "CGM-Punkte · im Ziel"
    const val GRAPH_DOT_HIGH = "CGM-Punkte · hoch"
    const val GRAPH_DOT_OUTLINE = "CGM-Punktkontur"
    const val GRAPH_AXIS_TEXT = "Achsenbeschriftung"
    const val GRAPH_AXIS_TICK = "Achsenstriche"
    const val GRAPH_NOW_LINE = "Jetzt-Linie"
    const val GRAPH_DIVIDER = "Graph-Trennlinie"
    const val GRAPH_TARGET_VALUE = "Zielwertlinie"

    const val PREDICTION_IOB = "IOB-Prognose"
    const val PREDICTION_COB = "COB-Prognose"
    const val PREDICTION_UAM = "UAM-Prognose"
    const val PREDICTION_ZERO_TEMP = "ZeroTemp-Prognose"
}
