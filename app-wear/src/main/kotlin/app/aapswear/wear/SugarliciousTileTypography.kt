package app.aapswear.wear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ProtoLayoutScope
import androidx.wear.protolayout.ResourceBuilders
import app.aapswear.uishared.DeviceTileTypefaceCache
import app.aapswear.uishared.loadDeviceTileTypeface
import app.aapswear.uishared.renderDeviceTileTextBitmap
import java.io.ByteArrayOutputStream

internal class SugarliciousTileTypography(
    private val context: Context,
) {
    private val faces = DeviceTileTypefaceCache { loadDeviceTileTypeface(context, it) }

    private data class TextKey(
        val value: String,
        val size: Float,
        val color: Int,
        val bold: Boolean,
        val scale: Float,
        val density: Int,
        val width: Int,
        val height: Int,
    )

    private data class EncodedText(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private val images = android.util.LruCache<TextKey, EncodedText>(48)
    val contentWidthDp: Float get() = minOf(context.resources.configuration.screenWidthDp, context.resources.configuration.screenHeightDp).coerceAtLeast(1) * .86f

    fun clear() {
        faces.clear()
        images.evictAll()
    }

    fun typeface(bold: Boolean) = faces.get(bold)

    fun text(scope: ProtoLayoutScope, value: String, size: Float, color: Int, bold: Boolean, maxWidthDp: Float = contentWidthDp, maxHeightDp: Float? = null): LayoutElementBuilders.LayoutElement {
        val face = faces.get(bold)
        if (face == null) {
            return LayoutElementBuilders.Text
                .Builder()
                .setText(value)
                .setMaxLines(1)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle
                        .Builder()
                        .setSize(sp(size))
                        .setColor(argb(color))
                        .setPreferredFontFamilies(app.aapswear.uishared.SystemTypography.preferredFontFamily)
                        .setWeight(sugarliciousTileWeight(bold))
                        .build(),
                ).build()
        }
        val density = context.resources.displayMetrics.density
        val width = (maxWidthDp * density).toInt().coerceAtLeast(1)
        val height = maxHeightDp?.let { (it * density).toInt().coerceAtLeast(1) } ?: Int.MAX_VALUE
        val key = TextKey(value, size, color, bold, context.resources.configuration.fontScale, context.resources.configuration.densityDpi, width, height)
        val encoded =
            images.get(key) ?: run {
                val bitmap = renderDeviceTileTextBitmap(context, face, value, size, color, width, height)
                try {
                    val bytes = ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                    EncodedText(bytes, bitmap.width, bitmap.height).also { images.put(key, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        val resource =
            ResourceBuilders.ImageResource
                .Builder()
                .setInlineResource(
                    ResourceBuilders.InlineImageResource
                        .Builder()
                        .setData(encoded.bytes)
                        .setWidthPx(encoded.width)
                        .setHeightPx(encoded.height)
                        .build(),
                ).build()
        return LayoutElementBuilders.Image
            .Builder(scope)
            .setImageResource(resource, "sugarlicious_text_${key.hashCode()}")
            .setWidth(dp(encoded.width / density))
            .setHeight(dp(encoded.height / density))
            .setModifiers(
                ModifiersBuilders.Modifiers
                    .Builder()
                    .setSemantics(
                        ModifiersBuilders.Semantics
                            .Builder()
                            .setContentDescription(value)
                            .build(),
                    ).build(),
            ).build()
    }
}
