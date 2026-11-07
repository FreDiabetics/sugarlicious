package app.sugarlicious.tools.maintenance.assets

import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class OrbitHandsetTest {
    @Test fun `current authored fourth handset matches its installed raster assets`() {
        val root = Path.of(System.getProperty("maintenance.sharedRoot"))
        val canonical = root.resolve("watchfaces/sugarlicious-orbit/src/main/res")
        val resources = Files.createTempDirectory("orbit-current-hands")
        copy(canonical.resolve("raw/watchface.xml"), resources.resolve("raw/watchface.xml"))
        copy(canonical.resolve("values/strings.xml"), resources.resolve("values/strings.xml"))
        importOrbitHandset(root.resolve("design-assets/watchfaces/orbit/hands_4"), resources)
        listOf("hour_4", "minute_4", "hub_4", "second_4").forEach { hand ->
            val name = "drawable-nodpi/orbit_custom_$hand.png"
            val expected = ImageIO.read(resources.resolve(name).toFile())
            val actual = ImageIO.read(canonical.resolve(name).toFile())
            assertEquals(512, actual.width)
            assertEquals(512, actual.height)
            assertContentEquals(
                expected.getRGB(0, 0, 512, 512, null, 0, 512),
                actual.getRGB(0, 0, 512, 512, null, 0, 512),
            )
        }
        listOf("hour_4_accent", "minute_4_accent", "top_4").forEach { obsolete ->
            assertFalse(Files.exists(canonical.resolve("drawable-nodpi/orbit_custom_$obsolete.png")))
        }
    }

    @Test fun `fourth handset uses white hands and one complete fixed hub`() {
        val root = Path.of(System.getProperty("maintenance.sharedRoot"))
        val resources = Files.createTempDirectory("orbit-handset")
        copy(root.resolve("watchfaces/sugarlicious-orbit/src/main/res/raw/watchface.xml"), resources.resolve("raw/watchface.xml"))
        copy(root.resolve("watchfaces/sugarlicious-orbit/src/main/res/values/strings.xml"), resources.resolve("values/strings.xml"))
        val source = root.resolve("design-assets/watchfaces/orbit/hands_4")
        importOrbitHandset(source, resources)
        val first = Files.readAllBytes(resources.resolve("raw/watchface.xml"))
        importOrbitHandset(source, resources)
        assertContentEquals(first, Files.readAllBytes(resources.resolve("raw/watchface.xml")))
        val face = parse(resources.resolve("raw/watchface.xml")).documentElement
        val options =
            face
                .all("ListConfiguration")
                .single { it.getAttribute("id") == "apexHands" }
                .children()
                .filter { it.tagName == "ListOption" }
        assertEquals(listOf("0", "1", "2", "3"), options.map { it.getAttribute("id") })
        val hands = face.all("Group").single { it.getAttribute("name") == "hands-3" }
        val clock = hands.all("AnalogClock").first()
        assertEquals(1, clock.all("HourHand").size)
        assertEquals(1, clock.all("MinuteHand").size)
        assertEquals("orbit_custom_hour_4", clock.all("HourHand").single().getAttribute("resource"))
        assertEquals("orbit_custom_minute_4", clock.all("MinuteHand").single().getAttribute("resource"))
        assertTrue((hands.all("HourHand") + hands.all("MinuteHand")).none { it.hasAttribute("tintColor") })
        assertEquals(
            listOf(
                "#FFFFFFFF",
                "#FFFFFFFF",
                "[CONFIGURATION.tertiary.1]",
                "[CONFIGURATION.tertiary.1]",
                "#FFFF4040",
                "#FFFF4040",
            ),
            hands.all("SecondHand").map { it.getAttribute("tintColor") },
        )
        assertEquals("custom-hub-4", hands.children().last().getAttribute("name"))
        assertTrue(hands.all("PartImage").none { it.getAttribute("name") == "custom-top-4" })
        val hub = ImageIO.read(resources.resolve("drawable-nodpi/orbit_custom_hub_4.png").toFile())
        assertEquals(512, hub.width)
        assertEquals(0xff000000.toInt(), hub.getRGB(256, 256))
        val hour = ImageIO.read(resources.resolve("drawable-nodpi/orbit_custom_hour_4.png").toFile())
        assertEquals(-1, hour.getRGB(243, 150))
        assertEquals(0, hour.getRGB(256, 150))
        val second = ImageIO.read(resources.resolve("drawable-nodpi/orbit_custom_second_4.png").toFile())
        assertEquals(-1, second.getRGB(256, 100))
        assertEquals(if (face.all("ListConfiguration").any { it.getAttribute("id") == "secondsMotion" }) 6 else 3, hands.all("SecondHand").size)
        hands.all("SecondHand").forEach { secondHand ->
            assertEquals("0.5", secondHand.getAttribute("pivotX"))
            assertEquals("0.5", secondHand.getAttribute("pivotY"))
            assertTrue(secondHand.children().any { it.tagName == "Variant" && it.getAttribute("mode") == "AMBIENT" && it.getAttribute("value") == "0" })
        }
        assertEquals(300, ImageIO.read(resources.resolve("drawable-nodpi/orbit_hand_preview_18.png").toFile()).width)
    }
}
