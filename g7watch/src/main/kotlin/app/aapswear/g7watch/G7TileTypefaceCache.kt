package app.aapswear.g7watch

import android.graphics.Typeface

/** Two device-owned font weights; clear when Android changes the display/font configuration. */
internal class G7TileTypefaceCache(
    private val load: (Boolean) -> Typeface?,
) {
    private val faces = mutableMapOf<Boolean, Typeface?>()

    @Synchronized
    fun get(bold: Boolean): Typeface? {
        if (!faces.containsKey(bold)) faces[bold] = load(bold)
        return faces[bold]
    }

    @Synchronized
    fun clear() = faces.clear()
}
