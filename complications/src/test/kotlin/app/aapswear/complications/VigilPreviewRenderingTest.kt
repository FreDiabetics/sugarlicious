package app.aapswear.complications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Rebuild the selector artwork from the real WFF bounds and current provider previews. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VigilPreviewRenderingTest {
    @Test fun `Vigil preview uses current active providers at their actual WFF bounds`() {
        val definition = File("../watchfaces/sugarlicious-direct-to-watch/src/main/res/raw/watchface.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(definition)
        val size = document.documentElement.getAttribute("width").toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) })
        canvas.drawColor(Color.BLACK)
        android.provider.Settings.System
            .putString(
                org.robolectric.RuntimeEnvironment
                    .getApplication()
                    .contentResolver,
                android.provider.Settings.System.TIME_12_24,
                "24",
            )
        // Neutral selector styling, like the other Watch-menu complication previews.
        // Data, geometry and text still come from the actual providers below.
        org.robolectric.RuntimeEnvironment
            .getApplication()
            .getSharedPreferences(DirectToWatchPreferences.NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("graph_color_cgm_in", Color.WHITE)
            .putInt("graph_color_cgm_high", Color.LTGRAY)
            .putInt("graph_color_cgm_low", Color.GRAY)
            .apply()
        val slots = document.getElementsByTagName("ComplicationSlot")
        val rendered = mutableListOf<String>()
        for (index in 0 until slots.length) {
            val slot = slots.item(index) as Element
            if (slot.getAttribute("alpha") == "0") continue
            val images = slot.getElementsByTagName("PartImage")
            if (images.length > 0 && (0 until images.length).all { (images.item(it) as Element).getAttribute("alpha") == "0" }) continue
            val policy = slot.getElementsByTagName("DefaultProviderPolicy").item(0) as Element
            val name = policy.getAttribute("primaryProvider").substringAfter('/')
            val type = Class.forName(name).asSubclass(androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService::class.java)
            val controller = Robolectric.buildService(type).create()
            val service = controller.get()
            val data = service.getPreviewData(ComplicationType.SMALL_IMAGE) as SmallImageComplicationData
            val drawable = requireNotNull(data.smallImage.image.loadDrawable(service))
            val x = slot.getAttribute("x").toFloat()
            val y = slot.getAttribute("y").toFloat()
            val width = slot.getAttribute("width").toFloat()
            val height = slot.getAttribute("height").toFloat()
            val saved = canvas.save()
            canvas.translate(x, y)
            drawable.setBounds(0, 0, width.toInt(), height.toInt())
            drawable.draw(canvas)
            canvas.restoreToCount(saved)
            rendered += slot.getAttribute("slotId")
            controller.destroy()
        }
        assertEquals(listOf("0", "1", "2", "5", "7", "8"), rendered)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertTrue((140..389).any { y -> (50..400).any { x -> bitmap.getPixel(x, y) != Color.BLACK } })
        val output = File("build/reports/vigil-current-preview.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
