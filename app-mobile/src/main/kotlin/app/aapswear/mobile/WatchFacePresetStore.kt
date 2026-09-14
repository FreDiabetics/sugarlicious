package app.aapswear.mobile

import android.content.Context

/** Keeps the last phone-side complication configuration for each Sugarlicious watch face. */
internal object WatchFacePresetStore {
    private const val PREFS = "sugarlicious_watchface_presets"
    private const val GLOBAL_PRESET_PREFS = "complication_setup"
    private const val GLOBAL_PRESET_KEY = "selected_ids"

    internal val supportedFaceIndices: IntRange
        get() = sugarliciousWatchFaceCards.indices

    fun read(
        context: Context,
        faceIndex: Int,
    ): List<Int> {
        val normalized = normalizeFaceIndex(faceIndex)
        val stored =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(normalized), null)
                ?.let(::decode)

        return stored.orEmpty()
    }

    fun readAll(context: Context): List<List<Int>> = supportedFaceIndices.map { faceIndex -> read(context, faceIndex) }

    fun save(
        context: Context,
        faceIndex: Int,
        complicationIds: List<Int>,
    ) {
        val normalized = normalizeFaceIndex(faceIndex)
        val ids = complicationIds.validPresetIds()
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(normalized), ids.joinToString(","))
            .apply()
    }

    /**
     * Makes one face's saved preset the active Data-Layer preset before that face is pushed.
     * The existing global key is intentionally kept because the Watch service already consumes it.
     */
    fun activate(
        context: Context,
        faceIndex: Int,
    ): List<Int> {
        val ids = read(context, faceIndex)
        context
            .getSharedPreferences(GLOBAL_PRESET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(GLOBAL_PRESET_KEY, ids.joinToString(","))
            .apply()
        return ids
    }

    private fun key(faceIndex: Int): String = "face_${faceIndex}_complications"

    private fun normalizeFaceIndex(faceIndex: Int): Int = faceIndex.coerceIn(supportedFaceIndices.first, supportedFaceIndices.last)

    private fun decode(value: String): List<Int> =
        value
            .split(',')
            .mapNotNull(String::toIntOrNull)
            .validPresetIds()

    private fun List<Int>.validPresetIds(): List<Int> =
        filter { id -> id in SugarliciousComplicationVariantIds }
            .distinct()
            .take(4)
}
