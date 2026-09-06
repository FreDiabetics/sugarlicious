package app.aapswear.mobile

import android.content.Context
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState

/**
 * Keeps the enabled Sugarlicious watch-face selections on the existing dashboard_ui key, so no
 * parallel persistence store or collector-driven selection state is introduced.
 */
internal object SugarliciousWatchFaceSelectionStore {
    private const val PREFS = "dashboard_ui"
    private const val KEY_FACE_INDEX = "watchFaceIndex"
    private const val KEY_FACE_CATALOG_VERSION = "watchFaceCatalogVersion"
    private const val FACE_CATALOG_VERSION = 2

    fun read(context: Context, fallback: Int = 0): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = preferences.getInt(KEY_FACE_INDEX, fallback)
        if (preferences.getInt(KEY_FACE_CATALOG_VERSION, 1) < FACE_CATALOG_VERSION) {
            val migrated = if (stored >= 5) DIRECT_TO_WATCH_FACE_INDEX else 0
            write(context, migrated)
            return migrated
        }
        return stored.coerceIn(sugarliciousWatchFaceCards.indices)
    }

    fun write(context: Context, faceIndex: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FACE_INDEX, faceIndex.coerceIn(sugarliciousWatchFaceCards.indices))
            .putInt(KEY_FACE_CATALOG_VERSION, FACE_CATALOG_VERSION)
            .apply()
    }

    fun isDirectToWatchRelevant(
        context: Context,
        state: TherapyDisplayState?,
    ): Boolean =
        isDirectToWatchRelevant(
            context = context,
            state = state,
            preferences =
                DashboardUiPreferences.read(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ),
        )

    fun isDirectToWatchRelevant(
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
        directToWatchRelevant: Boolean,
    ): Boolean =
        faceIndex in sugarliciousWatchFaceCards.indices &&
            (faceIndex != DIRECT_TO_WATCH_FACE_INDEX || directToWatchRelevant)

    fun resolveSelectableFallback(
        savedFaceIndex: Int,
        legacyFallback: Int,
        directToWatchRelevant: Boolean,
    ): Int {
        val saved = savedFaceIndex.coerceIn(sugarliciousWatchFaceCards.indices)
        if (isSelectable(saved, directToWatchRelevant)) return saved
        return legacyFallback.coerceIn(0, DIRECT_TO_WATCH_FACE_INDEX - 1)
    }
}
