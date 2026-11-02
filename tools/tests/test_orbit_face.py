"""Contract checks for generated resource-only Orbit face (no Android emulator)."""
import importlib.util
import hashlib
import math
import re
import struct
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from PIL import Image, ImageFont

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "tools/watchface-assets/generate_orbit.py"


class OrbitFaceTest(unittest.TestCase):
    def test_upper_right_gauge_fits_full_steps_without_ellipsis(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        slot = xml.find(".//ComplicationSlot[@slotId='7']/Complication[@type='RANGED_VALUE']")
        caption = slot.find("PartText/TextCircular")
        self.assertEqual("FALSE", caption.get("ellipsis"))
        font = ImageFont.truetype(str(ROOT / "watchfaces/sugarlicious-orbit/src/main/res/font/inter_tight.ttf"), int(caption.find("Font").get("size")))
        length = float(caption.get("width")) / 2 * math.radians((float(caption.get("endAngle")) - float(caption.get("startAngle"))) % 360)
        self.assertLess(font.getlength("100.000"), length - 12)

    def test_ambient_and_seconds_off_use_white_hub_with_black_center(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        hub = xml.find(".//Group[@name='ambient-seconds-hub']")
        self.assertIn('secondSweep] == "3"', hub.find("Transform").get("value"))
        self.assertEqual(hub.find("Variant[@mode='AMBIENT']").get("value"), "255")
        self.assertEqual([e.find("Fill").get("color") for e in hub.findall("PartDraw/Ellipse")], ["#FFFFFFFF", "#FF000000"])

    def test_aod_never_resurrects_the_background_glow(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        glow = xml.find(".//Group[@name='background']")
        self.assertIsNone(glow)

    def test_retired_settings_have_no_render_or_preset_references(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        serialized = ET.tostring(xml, encoding="unicode")
        for key in ("dialIndex", "minuteStyle", "aodStyle", "backgroundGlow", "weatherSymbols"):
            self.assertNotIn(key, serialized)
        for slot in xml.findall("Scene/ComplicationSlot"):
            self.assertEqual(slot.find("Variant").get("value"), "255" if slot.get("slotId") in ("3", "8") else "0")
        visibility = xml.find(".//Group[@name='graph-mode-only']")
        self.assertEqual(visibility.find("Variant").get("value"), "255")
        for index in range(5):
            caption = xml.find(f".//Group[@name='moon-caption-{index}']//Parameter").get("expression")
            for word in ("MON", "DIE", "MIT", "DON", "FRE", "SAM", "SON"):
                self.assertIn('"'+word+'"', caption)
            self.assertIn("DAY_OF_WEEK", caption)
        for image in xml.findall(".//SecondHand"):
            self.assertEqual(image.find("Variant").get("value"), "0")

    def test_lower_round_gauge_has_no_indicator(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        slot = xml.find("Scene/ComplicationSlot[@slotId='3']")
        self.assertFalse(slot.findall(".//Group[@name='round-marker']"))
        self.assertFalse(slot.findall(".//Group[@name='round-goal-marker']"))

    def test_daily_tir_rows_use_separate_theme_tones_without_extra_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        slot = xml.find("Scene/ComplicationSlot[@slotId='9']")
        for name, tone in (("weekdays", "1"), ("percentages", "0")):
            row = slot.find(f".//Group[@name='daily-tir-{name}']")
            self.assertEqual(row.get("tintColor"), f"[CONFIGURATION.primary.{tone}]")
            self.assertIsNotNone(row.find("PartDraw[@renderMode='MASK']"))
            self.assertIsNotNone(row.find("PartImage/Image[@resource='[COMPLICATION.MONOCHROMATIC_IMAGE]']"))

    def test_defaults_match_captured_orbit_template(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        expected = {"secondSweep": "1", "arcContent": "3", "progressBars": "7", "batteryIndicator": "TRUE", "glucoseColor": "TRUE"}
        actual = {item.get("id"): item.get("defaultValue") for item in xml.find("UserConfigurations")}
        self.assertEqual({key: actual[key] for key in expected}, expected)

    def test_daily_tir_is_one_arc_choice_with_fixed_internal_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        self.assertEqual(len(xml.find("UserConfigurations/ListConfiguration[@id='arcContent']")), 5)
        slot = xml.find("Scene/ComplicationSlot[@slotId='9']")
        self.assertEqual(slot.get("isCustomizable"), "FALSE")
        self.assertEqual(slot.get("supportedTypes"), "MONOCHROMATIC_IMAGE EMPTY")
        self.assertEqual([s.get("slotId") for s in xml.findall("Scene/ComplicationSlot")][-2:], ["8", "9"])
        self.assertTrue(slot.find("DefaultProviderPolicy").get("primaryProvider").endswith("OrbitDailyTirComplication"))
        self.assertIn('[CONFIGURATION.arcContent] == "4"', slot.find(".//Transform").get("value"))
        manifest = ET.parse(ROOT / "app-wear/src/main/AndroidManifest.xml")
        android = "{http://schemas.android.com/apk/res/android}"
        service = next(s for s in manifest.findall(".//service") if s.get(android+"name", "").endswith("OrbitDailyTirComplication"))
        metadata = {m.get(android+"name"): m.get(android+"value") for m in service.findall("meta-data")}
        self.assertEqual(metadata["android.support.wearable.complications.SUPPORTED_TYPES"], "ICON")
    def test_weather_uses_templates_and_shared_moon_centers(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for offset in range(5):
            station = xml.find(f".//Group[@name='forecast-{offset}']")
            templates = station.findall(".//Template")
            self.assertNotIn("%s:00", [t.text for t in templates])
            self.assertIn("%s°", [t.text for t in templates])
            for parameter in station.findall(".//Template/Parameter"):
                self.assertNotIn(' + ":00"', parameter.get("expression"))
            image = station.find(".//Group[@name='weather-art-0']//PartImage")
            moon = xml.find(f".//Group[@name='moon-day-{offset}']//Ellipse")
            for axis, extent in (("x", "width"), ("y", "height")):
                a = float(image.get(axis))+float(image.get(extent))/2
                b = float(moon.get(axis))+float(moon.get(extent))/2
                self.assertAlmostEqual(a, b, delta=1)

    def test_graph_battery_round_caps_stay_below_graph(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
            strings = ET.parse(Path(directory) / "values/strings.xml").getroot()
        labels = {s.get("name"): s.text for s in strings}
        self.assertNotIn("dialIndex", labels)
        self.assertEqual(labels["secondSweep"], "Sekundenzeiger")
        self.assertIsNone(xml.find("UserConfigurations/BooleanConfiguration[@id='handTint']"))
        for path in xml.findall(".//Group[@name='battery-graph']//Arc"):
            stroke = path.find("Stroke")
            self.assertEqual(stroke.get("cap"), "ROUND")
            self.assertGreater(float(path.get("startAngle")), 90)
        for hand in xml.findall(".//SecondHand"):
            self.assertIsNotNone(hand.find("Sweep"))
    def test_arc_content_shares_caption_track_and_keeps_icons_clear_of_center_disc(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        owner = xml.find(".//Group[@name='weather']")
        moon_caption = xml.find(".//Group[@name='moon-caption-0']//TextCircular")
        for caption in owner.findall(".//TextCircular"):
            if caption.find("Font").get("size") != "23":
                self.assertEqual(caption.get("width"), moon_caption.get("width"))
        image = xml.find(".//Group[@name='forecast-0']//Group[@name='weather-art-0']//PartImage")
        center_y = float(image.get("y")) + float(image.get("height"))/2
        radial_inner = 256-center_y-float(image.get("height"))/2
        self.assertGreaterEqual(radial_inner, 67)
        self.assertAlmostEqual(center_y, 140*512/450, delta=1)
        moon = xml.find(".//Group[@name='moon-day-0']//Ellipse")
        self.assertAlmostEqual(float(moon.get("width")), 28*512/450)
        self.assertAlmostEqual(float(moon.get("y"))+float(moon.get("height"))/2, 140*512/450)
    def test_arc_captions_use_rotated_spokes_and_moon_reference_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for section in ("weather", "moon"):
            owner = xml.find(f".//Group[@name='{section}']")
            self.assertFalse(owner.findall(".//PartText/Text"))
            for caption in owner.findall(".//TextCircular"):
                self.assertGreaterEqual(float(caption.get("startAngle")), 0)
        moon = xml.find(".//Group[@name='moon-day-0']")
        self.assertEqual(float(moon.get("angle")), -70)
    def test_editor_consolidates_arc_and_progress_options(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        config = xml.find("UserConfigurations")
        self.assertIsNone(config.find("ListConfiguration[@id='upperMode']"))
        self.assertIsNone(config.find("BooleanConfiguration[@id='progressMarker']"))
        self.assertEqual(len(config.find("ListConfiguration[@id='arcContent']")), 5)
        self.assertEqual(len(config.find("ListConfiguration[@id='progressBars']")), 8)
        self.assertIn('[CONFIGURATION.tertiary.2]', ET.tostring(xml.find(".//Group[@name='index-0-1']"), encoding="unicode"))
    def test_refined_editor_choices_and_graph_center_do_not_overlap(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        config = xml.find("UserConfigurations")
        for key, count in (("dialInk", 2), ("secondSweep", 4), ("weatherLaunch", 2)):
            self.assertEqual(len(config.find(f"ListConfiguration[@id='{key}']")), count, key)
        self.assertIsNone(config.find("ListConfiguration[@id='secondStyle']"))
        self.assertIsNone(xml.find(".//Group[@name='index-1']"))
        lower = xml.find(".//Group[@name='center-disc-graph']//Arc")
        self.assertEqual((lower.get("startAngle"), lower.get("endAngle")), ("90", "270"))
        self.assertIsNotNone(xml.find(".//Group[@name='battery-graph']//Arc"))
        self.assertFalse(xml.findall(".//Tick"))
        for flavor in config.find("Flavors"):
            for selected in flavor.findall("Configuration"):
                definition = next(item for item in config if item.get("id") == selected.get("id"))
                allowed = {"TRUE", "FALSE"} if definition.tag == "BooleanConfiguration" else {option.get("id") for option in definition}
                self.assertIn(selected.get("optionId"), allowed)

    def test_weather_uses_vendor_images_and_day_night_conditions(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        weather = xml.find(".//Group[@name='weather']")
        resources = [p.get("resource", "") for p in weather.findall(".//Image")]
        self.assertTrue(any(r.startswith("orbit_weather_samsung_") for r in resources))
        self.assertFalse(any(r.startswith("orbit_weather_google_") for r in resources))
        self.assertIn("WEATHER.IS_DAY", ET.tostring(weather, encoding="unicode"))
        for image in weather.findall(".//PartImage"):
            self.assertEqual(image.get("width"), image.get("height"))
        self.assertFalse(weather.findall(".//Ellipse"), "do not substitute synthetic glyphs for vendor icons")

    def test_weather_text_follows_arc_with_device_font(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        weather = xml.find(".//Group[@name='weather']")
        self.assertFalse(weather.findall(".//PartText/Text"), "weather captions must follow the arc")
        fonts = weather.findall(".//Font")
        self.assertTrue(fonts)
        self.assertTrue(all(font.get("family") == "SYNC_TO_DEVICE" for font in fonts))

    def test_weather_matches_measured_reference_spokes_with_stable_temperature(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        weather = xml.find(".//Group[@name='weather']")
        for offset, angle in enumerate((-70, -35, 0, 35, 70)):
            station = weather.find(f"Group[@name='forecast-{offset}']")
            label = station.find(".//Group[@name='forecast-temperature']/PartText/TextCircular")
            self.assertIsNotNone(label)
            self.assertAlmostEqual(float(label.get("width")), 251 * 512 / 450)
            self.assertAlmostEqual(float(label.get("startAngle")), 345)
            self.assertEqual(float(station.find(".//Group[@name='forecast-temperature']").get("angle")), angle)
            self.assertAlmostEqual(float(label.find("Font").get("size")), 24 * 512 / 450)
            art = station.find(".//Group[@name='weather-art-0']")
            self.assertEqual(float(art.get("angle")), angle)
            image = art.find(".//PartImage")
            self.assertAlmostEqual(float(image.get("x"))+float(image.get("width"))/2, 256, delta=1)
            self.assertAlmostEqual(float(image.get("y"))+float(image.get("height"))/2, 140 * 512 / 450, delta=1)
        self.assertNotIn("[SECOND]", ET.tostring(weather, encoding="unicode"))

    def test_bounds_and_gradient_wrap_follow_visible_arcs(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        outer = xml.find("Scene/ComplicationSlot[@slotId='0']/BoundingArc")
        self.assertAlmostEqual(float(outer.get("width")), (218.45 + 17.07 / 2) * 2, places=2)
        for gradient in xml.findall(".//SweepGradient"):
            self.assertTrue(0 <= float(gradient.get("startAngle")) < 360)
            self.assertTrue(0 <= float(gradient.get("endAngle")) < 360)
    def test_all_ultra_hands_preserve_reference_silhouette_and_pivot(self):
        assets = ROOT / "watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi"
        reference = ROOT / "watchfaces/sugarlicious-orbit/reference/hands"
        for index in range(15):
            for kind in ("hour", "minute"):
                name = f"orbit_{kind}_{index}.png"
                with self.subTest(style=index, hand=kind):
                    original = Image.open(reference / name).convert("RGBA")
                    actual = Image.open(assets / name).convert("RGBA")
                    self.assertEqual(actual.size, (450, 450))
                    self.assertEqual(original.size, (50, 275))
                    self.assertEqual(actual.crop((200, 0, 250, 275)).getchannel("A").tobytes(), original.getchannel("A").tobytes())
                    outside = actual.getchannel("A")
                    outside.paste(0, (200, 0, 250, 275))
                    self.assertIsNone(outside.getbbox(), "no invented hub, tail or body outside the reference")

    def test_native_editor_hand_icons_fit_platform_limit(self):
        for index in range(18):
            path = ROOT / f"watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi/orbit_hand_preview_{index}.png"
            width, height = struct.unpack(">II", path.read_bytes()[16:24])
            self.assertLessEqual(max(width, height), 400, f"{path.name} crashes Samsung's editor")

    def test_reference_hands_keep_uniform_scale_and_reference_seconds(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for style in range(3):
            choice = xml.find(f".//Group[@name='hands-{style}']")
            for hand in choice.findall(".//HourHand") + choice.findall(".//MinuteHand"):
                self.assertEqual((hand.get("width"), hand.get("height")), ("512", "512"))
                self.assertEqual((hand.get("pivotX"), hand.get("pivotY")), ("0.5", "0.5"))
            suffix = ("", "_transparent", "_tblack")[style]
            self.assertTrue(all(h.get("resource") == "second_hand" + suffix for h in choice.findall(".//SecondHand")))
        assets = ROOT / "watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi"
        second = Image.open(assets / "orbit_second_hand.png").convert("RGBA")
        self.assertEqual(second.size, (450, 450))
        self.assertTrue(2 <= sum(second.getpixel((x, 100))[3] > 128 for x in range(450)) <= 4)
        self.assertTrue(second.getpixel((225, 225))[3] > 128)
        self.assertEqual(second.getpixel((225, 280))[3], 0)

    def test_reconstructed_hand_choices_are_distinct(self):
        assets = ROOT / "watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi"
        pairs = [tuple(hashlib.sha256((assets / filename).read_bytes()).hexdigest()
                       for filename in ([f"orbit_{kind}_{index}.png" for kind in ("hour", "minute")]
                                        + (["orbit_hour_6_accent.png", "orbit_minute_6_accent.png"] if index == 6 else [])))
                 for index in range(15)]
        self.assertEqual(len(set(pairs)), 15)

    def generator(self):
        self.assertTrue(GENERATOR.is_file(), "Orbit generator not implemented")
        spec = importlib.util.spec_from_file_location("orbit", GENERATOR)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_marker_is_hollow_track_sized_and_bar_stays_full(self):
        face = self.generator()
        start = face.marker_geometry(256, 256, 220, 285, 343, 0, 0, 100, 18)
        end = face.marker_geometry(256, 256, 220, 285, 343, 100, 0, 100, 18)
        self.assertAlmostEqual(start[0], 43.4963, places=3)
        self.assertAlmostEqual(start[1], 199.0598, places=3)
        self.assertAlmostEqual(end[0], 191.6782, places=3)
        self.assertAlmostEqual(end[1], 45.6130, places=3)
        self.assertEqual(start[2], 18)  # Open inner diameter equals bar thickness.
        self.assertAlmostEqual(start[3], 5.4)  # Thinner proportional black contour.
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for owner in xml.findall(".//Group"):
            if not owner.get("name", "").endswith("-marker"):
                continue
            circle = owner.find(".//Ellipse")
            stroke = float(circle.find("Stroke").get("thickness"))
            inner = float(circle.get("width")) - stroke
            self.assertAlmostEqual(stroke / inner, .3)
            self.assertIsNone(circle.find("Fill"))
        self.assertIsNone(face.marker_geometry(0, 0, 10, 0, 90, 5, 10, 10, 8))
        self.assertIsNone(face.marker_geometry(0, 0, 10, 0, 90, math.nan, 0, 10, 8))

    def test_generated_face_has_no_ghost_slots_and_all_settings_resolve(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
            strings = ET.parse(Path(directory) / "values/strings.xml").getroot()
        slots = xml.findall("Scene//ComplicationSlot")
        self.assertEqual(sorted(int(slot.get("slotId")) for slot in slots), [0, 2, 3, 4, 5, 7, 8, 9])
        self.assertEqual(len(xml.findall(".//ComplicationSlot[@supportedTypes='MONOCHROMATIC_IMAGE EMPTY']")), 1)
        config = xml.find("UserConfigurations")
        known_strings = {element.get("name") for element in strings}
        for element in xml.iter():
            if "displayName" in element.attrib:
                self.assertIn(element.get("displayName"), known_strings)
        names = {element.get("id") for element in config}
        self.assertTrue({"primary", "secondary", "tertiary", "arcContent", "progressBars", "apexHands", "secondSweep"} <= names)
        self.assertLessEqual(len(config), 20)
        self.assertNotIn("night", names)
        self.assertNotIn("upperMode", names)
        self.assertEqual(len(config.find("ListConfiguration[@id='arcContent']")), 5)
        self.assertTrue(all(g.get("name") for g in xml.findall(".//Group")))
        battery_slot = xml.find("Scene/ComplicationSlot[@slotId='7']")
        battery = battery_slot.find("DefaultProviderPolicy")
        self.assertEqual(battery.get("defaultSystemProvider"), "STEP_COUNT")
        self.assertEqual(battery.get("defaultSystemProviderType"), "RANGED_VALUE")
        for slot in slots:
            self.assertEqual(slot.find("Complication[@type='EMPTY']").tag, "Complication")
        self.assertIn("LONG_TEXT", xml.find("Scene/ComplicationSlot[@slotId='8']").get("supportedTypes"))
        self.assertEqual(len(config.find("ColorConfiguration[@id='primary']")), 45)
        self.assertEqual(len(config.find("ColorConfiguration[@id='secondary']")), 45)
        self.assertEqual(len(config.find("ColorConfiguration[@id='tertiary']")), 45)
        self.assertEqual(len(xml.findall("Scene/ComplicationSlot/BoundingArc")), 5)
        outer_row = xml.find("Scene/ComplicationSlot[@slotId='4']/BoundingArc")
        inner_row = xml.find("Scene/ComplicationSlot[@slotId='5']/BoundingArc")
        self.assertGreater(float(outer_row.get("width"))/2-float(outer_row.get("thickness")), float(inner_row.get("width"))/2)
        graph = xml.find("Scene/ComplicationSlot[@slotId='8']")
        self.assertEqual(graph.find("Variant[@mode='AMBIENT'][@target='alpha']").get("value"), "255")
        panels = xml.find(".//Group[@name='inner-panels']")
        self.assertEqual("0", panels.find("Variant[@mode='AMBIENT']").get("value"))

    def test_every_exposed_setting_has_a_consumer_and_marker_uses_radians(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        rendered = ET.tostring(xml.find("Scene"), encoding="unicode")
        consumed = set(re.findall(r"CONFIGURATION\.([A-Za-z0-9_]+)", rendered))
        offered = {item.get("id") for item in xml.find("UserConfigurations") if item.get("id")}
        self.assertEqual(offered - consumed, set(), "Settings must not be decorative/no-op")
        marker = xml.find(".//Group[@name='outer-0-marker']//Ellipse")
        self.assertIn("0.017453292519943", marker.find("Transform[@target='x']").get("value"))
        for gradient in xml.findall(".//SweepGradient"):
            self.assertNotIn("CONFIGURATION", gradient.get("colors"), "Samsung does not resolve configuration arrays in SweepGradient colors")
            self.assertIn(gradient.get("colors"), ("#FFFFFFFF #00FFFFFF", "#00FFFFFF #FFFFFFFF"))

    def test_numeric_dial_reuses_only_three_cropped_apex_glyphs(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        numeric = xml.find(".//Group[@name='index-0-0']")
        self.assertEqual(len(numeric.findall(".//Image[@resource='sugarlicious_analog_template']")), 3)
        self.assertEqual(len(numeric.findall(".//PartDraw[@renderMode='MASK']")), 3)
        self.assertEqual(len(numeric.findall(".//Font")), 0)

    def test_hand_inventory_presets_and_forecasts_are_real_choices(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        config = xml.find("UserConfigurations")
        hands = config.find("ListConfiguration[@id='apexHands']")
        self.assertIsNone(config.find("ListConfiguration[@id='handStyle']"))
        self.assertEqual(len(hands), 3)
        for option in hands:
            choice = xml.find(f".//Group[@name='hands-{option.get('id')}']")
            self.assertIsNotNone(choice)
            self.assertIsNotNone(choice.find(".//HourHand"))
            self.assertIsNotNone(choice.find(".//MinuteHand"))
            self.assertIsNone(choice.find(".//HourHand").get("tintColor"))
        self.assertEqual(len(config.find("Flavors")), 6)
        self.assertIsNone(config.find("ListConfiguration[@id='weatherSymbols']"))
        self.assertEqual(len(config.find("ListConfiguration[@id='weatherLaunch']")), 2)
        weather = xml.find(".//Group[@name='weather']")
        parameters = {p.get("expression", "") for p in weather.findall(".//Parameter")}
        for offset in (0, 1, 2, 3, 4):
            token = "WEATHER" if offset == 0 else f"WEATHER.HOURS.{offset}"
            self.assertTrue(any(f"[{token}.TEMPERATURE]" in p for p in parameters))
        moon = xml.find(".//Group[@name='moon']")
        transforms = [t.get("value", "") for t in moon.findall(".//Transform")]
        self.assertTrue(any("MOON_PHASE_POSITION" in t for t in transforms), "Moon images must follow the real phase")

    def test_round_gradient_spans_actual_arc_and_keeps_rounded_caps(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for name in ("round", "round-goal"):
            gradient = xml.find(f".//Group[@name='{name}-gradient']")
            self.assertEqual(float(gradient.get("angle")), 212)
            self.assertAlmostEqual(float(gradient.get("x")) + float(gradient.get("width"))/2, 148)
            self.assertAlmostEqual(float(gradient.get("y")) + float(gradient.get("height"))/2, 368)
            fixed = gradient.find("Group[@name='fixed-gradient-content']")
            shader = fixed.find(".//SweepGradient")
            self.assertEqual(float(shader.get("endAngle")) - float(shader.get("startAngle")), 280)
            mask = fixed.find("PartDraw/Arc")
            self.assertEqual(mask.find("Stroke").get("cap"), "ROUND")
            self.assertIsNotNone(mask.find("Transform[@target='endAngle']"))
            self.assertIsNone(gradient.find("Group[@name='indicator-gradient-content']"))

    def test_indicator_keeps_gradient_static_and_fills_only_the_bar_span(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for identity, span in (("0", 57), ("7", 57), ("2", 63)):
            slot = xml.find(f"Scene//ComplicationSlot[@slotId='{identity}']")
            self.assertIsNone(slot.find(".//Group[@name='indicator-gradient-content']"))
            fixed = slot.find(".//Group[@name='fixed-gradient-content']")
            shader = fixed.find(".//SweepGradient")
            self.assertEqual(float(shader.get("endAngle")) - float(shader.get("startAngle")), span)
            self.assertEqual(shader.findall("Transform"), [])
            mask = fixed.find("PartDraw/Arc")
            self.assertEqual(mask.find("Stroke").get("cap"), "ROUND")
            self.assertIn("progressBars", mask.find("Transform").get("value"))

    def test_upper_captions_anchor_before_bar_end_and_grow_left(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for identity, anchor in (("0", 344), ("7", 78)):
            for kind in ("SHORT_TEXT", "RANGED_VALUE"):
                label = xml.find(f".//ComplicationSlot[@slotId='{identity}']/Complication[@type='{kind}']/PartText/TextCircular")
                self.assertEqual(label.get("align"), "END")
                self.assertEqual(float(label.get("endAngle")), anchor)

    def test_provider_trend_icons_never_rotate_flat_into_down(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            root = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for part in root.findall("Scene//ComplicationSlot//PartImage"):
            if part.find("Image[@resource='[COMPLICATION.MONOCHROMATIC_IMAGE]']") is not None:
                self.assertEqual(float(part.get("angle", "0")), 0)
                self.assertIsNone(part.find("Transform[@target='angle']"))

    def test_outer_icons_keep_native_orientation_and_reference_position(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for identity, expected in ((0, (74.45, 203.94, -74)), (7, (323.68, 79.68, 21)), (2, (436.46, 307.75, 286))):
            slot = xml.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
            for kind in ("SHORT_TEXT", "RANGED_VALUE"):
                icon = slot.find(f"Complication[@type='{kind}']/PartImage")
                self.assertIsNotNone(icon)
                self.assertAlmostEqual(float(icon.get("x"))+float(icon.get("width"))/2, expected[0], delta=1)
                self.assertAlmostEqual(float(icon.get("y"))+float(icon.get("height"))/2, expected[1], delta=1)
                self.assertEqual(float(icon.get("angle", "0")), 0)

    def test_round_complication_has_separate_remaining_arc_and_reference_gap(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        track = xml.find(".//Group[@name='round-track']//Arc")
        self.assertEqual(float(track.get("startAngle")), 500)
        self.assertEqual(track.get("direction"), "COUNTER_CLOCKWISE")
        self.assertIn("19.5", track.find("Transform[@target='endAngle']").get("value"))
        self.assertEqual(track.find("Stroke").get("cap"), "ROUND")
        self.assertAlmostEqual(float(track.get("width")), 118*512/450)
        panels = xml.find(".//Group[@name='inner-panels']")
        self.assertTrue(all(arc.find("Stroke").get("cap") == "BUTT" for arc in panels.findall(".//Arc")))
        arcs = panels.findall(".//Arc")
        self.assertEqual(len(arcs), 2)  # upper base and dark inset; lower panels belong to their slot
        self.assertAlmostEqual(float(arcs[0].get("width"))/2 + float(arcs[0].find("Stroke").get("thickness"))/2, 145*512/450)
        for arc in arcs:
            self.assertGreaterEqual(float(arc.get("width"))/2-float(arc.find("Stroke").get("thickness"))/2, 67)
        center = xml.find(".//Group[@name='center-disc']//Ellipse")
        self.assertIsNotNone(center)

    def test_provider_formats_preserve_title_and_inline_icon_without_ghost_slots(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        round_slot = xml.find("Scene/ComplicationSlot[@slotId='3']")
        for kind in ("GOAL_PROGRESS", "MONOCHROMATIC_IMAGE", "SMALL_IMAGE"):
            self.assertIsNotNone(round_slot.find(f"Complication[@type='{kind}']"))
        for identity in (4, 5):
            slot = xml.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
            self.assertIsNotNone(slot.find("Complication[@type='MONOCHROMATIC_IMAGE']"))
            for kind in ("SHORT_TEXT", "RANGED_VALUE"):
                content = slot.find(f"Complication[@type='{kind}']")
                self.assertTrue(any(p.get("expression") == "[COMPLICATION.TITLE]" for p in content.findall(".//Parameter")))
                self.assertTrue(content.findall(".//InlineImage[@source='COMPLICATION.MONOCHROMATIC_IMAGE']"))

    def test_orbit_has_taller_dedicated_graph_and_arc_only_panels(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        slot = xml.find("Scene/ComplicationSlot[@slotId='8']")
        self.assertIn("NativeOrbitGraphComplication", ET.tostring(slot, encoding="unicode"))
        image = slot.find(".//PartImage")
        self.assertEqual((float(image.get("width")), float(image.get("height"))), (330, 165))
        self.assertEqual((float(image.get("x")), float(image.get("y"))), (91, 91))
        content = slot.find("Complication[@type='LONG_TEXT']")
        self.assertFalse(content.findall(".//Stroke[@thickness='2']"), "no independent graph contour")
        clip = content.find(".//Group[@name='graph-clipped']")
        self.assertEqual(clip.get("tintColor"), "[CONFIGURATION.primary.0]")
        mask = clip.find("PartDraw[@renderMode='MASK']//Arc")
        self.assertIsNotNone(mask, "graph must use the actual upper semicircle, not a rounded rectangle")
        self.assertEqual(float(mask.get("startAngle")), 270)
        self.assertEqual(float(mask.get("endAngle")), 450)
        self.assertAlmostEqual(float(mask.find("Stroke").get("thickness")), 165)
        panels = xml.find(".//Group[@name='inner-panels']")
        self.assertIn('[CONFIGURATION.arcContent] != "3"', panels.find("Transform").get("value"))

    def test_reference_ticks_include_caps_in_the_measured_length(self):
        import math
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            root = ET.parse(Path(directory) / 'raw/watchface.xml').getroot()
        ticks = root.find("Scene/ComplicationSlot[@slotId='7']//Group[@name='radial-value-ticks']")
        lines = ticks.findall('PartDraw/Line')[::3]
        self.assertEqual(12, len(lines))
        track = root.find("Scene/ComplicationSlot[@slotId='7']/Complication[@type='RANGED_VALUE']/Group[@name='outer-1-track']")
        self.assertIn('[CONFIGURATION.progressBars] == "7"', track.find('Transform').get('value'))
        self.assertIn('? 255 :', track.find('Transform').get('value'))
        self.assertEqual('60', track.find('PartDraw').get('alpha'))
        for line in lines:
            width = float(line.find('Stroke').get('thickness'))
            length = math.hypot(float(line.get('endX'))-float(line.get('startX')),float(line.get('endY'))-float(line.get('startY'))) + width
            self.assertAlmostEqual(15*512/450, length, delta=.005)
            outer = math.hypot(float(line.get('endX'))-256,float(line.get('endY'))-256)+width/2
            self.assertAlmostEqual(199.5*512/450,outer,places=2)
        round_range = root.find("Scene/ComplicationSlot[@slotId='3']/Complication[@type='RANGED_VALUE']")
        main = next(p for p in round_range.findall('.//PartText') if '[COMPLICATION.TEXT]' in ET.tostring(p,encoding='unicode'))
        self.assertAlmostEqual(36*512/450,float(main.find('.//Font').get('size')))
        self.assertIn('340', main.find("Transform[@target='y']").get('value'))
        self.assertEqual('TRUE',main.find('Text').get('isAutoSize'))

    def test_upper_right_ticks_share_apex_gradient_and_moving_indicator(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        ticks = xml.find("Scene/ComplicationSlot[@slotId='7']//Group[@name='radial-value-ticks']")
        self.assertIsNotNone(ticks)
        self.assertEqual(36, len(ticks.findall('PartDraw')))
        self.assertFalse(ticks.findall('.//Group[@renderMode="MASK"]'))
        self.assertIn('floor(clamp(', ticks.find("PartDraw/Line/Transform").get('value'))
        light = ticks.findall("PartDraw[@name='tick-0-light']/Transform")[0]
        self.assertIn('255.0000', light.get('value'))
        self.assertIn('0.0000', ticks.find("PartDraw[@name='tick-11-light']/Transform").get('value'))
        for identity in ('0', '2', '3'):
            self.assertIsNone(xml.find(f"Scene/ComplicationSlot[@slotId='{identity}']//Group[@name='radial-value-ticks']"))


if __name__ == "__main__":
    unittest.main()
