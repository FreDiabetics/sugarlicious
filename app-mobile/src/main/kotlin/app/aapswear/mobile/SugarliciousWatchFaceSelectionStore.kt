package app.aapswear.mobile

import android.content.Context
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState

/**
 * Keeps all six Sugarlicious watch-face selections on the existing dashboard_ui key, so no
 * parallel persistence store or collector-driven selection state is introduced.
 */
internal object SugarliciousWatchFaceSelectionStore {
    private const val PREFS = "dashboard_ui"
    private const val KEY_FACE_INDEX = "watchFaceIndex"

    fun read(context: Context, fallback: Int = 1): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FACE_INDEX, fallback)
            .coerceIn(sugarliciousWatchFaceCards.indices)

    fun write(context: Context, faceIndex: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FACE_INDEX, faceIndex.coerceIn(sugarliciousWatchFaceCards.indices))
            .apply()
    }

    fun isG6StyleRelevant(
        context: Context,
        state: TherapyDisplayState?,
    ): Boolean =
        isG6StyleRelevant(
            context = context,
            state = state,
            preferences =
                DashboardUiPreferences.read(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ),
        )

    fun isG6StyleRelevant(
        context: Context,
        state: TherapyDisplayState?,
        preferences: DashboardUiPreferences,
    ): Boolean {
        val dashboard = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.dataSource == DataSourcePreference.DEXCOM_G7_WATCH ||
            state?.source == DataSourceId.DEXCOM_G7_WATCH ||
            dashboard.getBoolean(G7_SOURCE_FALLBACK_MIGRATION_KEY, false)
    }

    fun isSelectable(
        faceIndex: Int,
        g6StyleRelevant: Boolean,
    ): Boolean =
        faceIndex in sugarliciousWatchFaceCards.indices &&
            (faceIndex != SUGARLICIOUS_G6_STYLE_FACE_INDEX || g6StyleRelevant)

    fun resolveSelectableFallback(
        savedFaceIndex: Int,
        legacyFallback: Int,
        g6StyleRelevant: Boolean,
    ): Int {
        val saved = savedFaceIndex.coerceIn(sugarliciousWatchFaceCards.indices)
        if (isSelectable(saved, g6StyleRelevant)) return saved
        return legacyFallback.coerceIn(0, SUGARLICIOUS_G6_STYLE_FACE_INDEX - 1)
    }
}
