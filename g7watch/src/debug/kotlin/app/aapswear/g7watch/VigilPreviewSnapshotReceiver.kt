package app.aapswear.g7watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withTranslation
import app.aapswear.complications.VigilHands
import app.aapswear.complications.renderVigilClock
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Debug-only device-font capture using the production WFF geometry and canonical hand assets. */
class VigilPreviewSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val source = BitmapFactory.decodeFile(File(context.filesDir, "vigil-preview-source.png").path) ?: return
        require(source.width == source.height && source.width in 450..2_000)
        val output = renderVigilPreview(context, source)
        File(context.filesDir, "vigil-preview-2210.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val ACTION = "app.aapswear.g7watch.DEBUG_VIGIL_PREVIEW_2210"
    }
}

internal fun renderVigilPreview(context: Context, source: Bitmap, includeDecorations: Boolean = true, replaceClock: Boolean = true): Bitmap {
    val document =
        context.assets.open("vigil_preview_definition.xml").use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }

    fun Element.number(name: String, fallback: Float = 0f): Float = getAttribute(name).toFloatOrNull() ?: fallback

    fun Element.bounds() = RectF(number("x"), number("y"), number("x") + number("width"), number("y") + number("height"))

    fun Element.alpha() = number("alpha", 255f).toInt()
    val clock =
        (0 until document.getElementsByTagName("ComplicationSlot").length)
            .map { document.getElementsByTagName("ComplicationSlot").item(it) as Element }
            .single { it.getAttribute("slotId") == "5" }
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val scale = source.width / document.documentElement.number("width")
    canvas.scale(scale, scale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val clockBounds = clock.bounds()
    if (replaceClock) {
        canvas.drawRect(clockBounds, Paint().apply { color = Color.BLACK })
        canvas.drawBitmap(renderVigilClock(context, "22:10"), null, clockBounds, paint)
    }
    if (!includeDecorations) return output

    val groups = document.getElementsByTagName("Group")
    val markers = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "vigil-perimeter-markers" }
    canvas.withTranslation(markers.number("x"), markers.number("y")) {
        val parts = markers.getElementsByTagName("PartDraw")
        for (index in 0 until parts.length) {
            val part = parts.item(index) as Element
            val line = part.getElementsByTagName("Line").item(0) as Element
            val stroke = line.getElementsByTagName("Stroke").item(0) as Element
            paint.color = Color.parseColor(stroke.getAttribute("color"))
            paint.alpha = Color.alpha(paint.color) * markers.alpha() / 255 * part.alpha() / 255
            paint.strokeWidth = stroke.number("thickness")
            paint.strokeCap = Paint.Cap.valueOf(stroke.getAttribute("cap"))
            canvas.drawLine(part.number("x") + line.number("startX"), part.number("y") + line.number("startY"), part.number("x") + line.number("endX"), part.number("y") + line.number("endY"), paint)
        }
    }

    paint.alpha = 255
    canvas.drawBitmap(VigilHands.render(context, 22, 10), 0f, 0f, paint)
    return output
}
