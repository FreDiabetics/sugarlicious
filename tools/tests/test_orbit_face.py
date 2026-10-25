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
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "tools/watchface-assets/generate_orbit.py"


class OrbitFaceTest(unittest.TestCase):
    def test_ambient_keeps_only_the_grey_seconds_hub(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        hub = xml.find(".//Group[@name='ambient-seconds-hub']")
        self.assertEqual(hub.get("alpha"), "0")
        self.assertEqual(hub.find("Variant[@mode='AMBIENT']").get("value"), "255")
        self.assertEqual(hub.find("PartImage").get("tintColor"), "#FF9E9E9E")

    def test_aod_never_resurrects_the_background_glow(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        glow = xml.find(".//Group[@name='background']")
        self.assertEqual(glow.find("Variant[@mode='AMBIENT'][@target='alpha']").get("value"), "0")

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
        expected = {"secondSweep": "1", "arcContent": "3", "progressBars": "7", "backgroundGlow": "FALSE", "batteryIndicator": "TRUE", "glucoseColor": "TRUE"}
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
            self.assertIn("%s:00", [t.text for t in templates])
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
        self.assertEqual(labels["dialIndex"], "Index")
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
        for key, count in (("dialIndex", 2), ("dialInk", 2), ("secondSweep", 3), ("weatherSymbols", 2), ("weatherLaunch", 2)):
            self.assertEqual(len(config.find(f"ListConfiguration[@id='{key}']")), count, key)
        self.assertIsNone(config.find("ListConfiguration[@id='secondStyle']"))
        roman = xml.find(".//Group[@name='index-1-0']//Font")
        self.assertGreaterEqual(float(roman.get("size")), 64)
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
        self.assertTrue(any(r.startswith("orbit_weather_google_") for r in resources))
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

    def test_weather_matches_measured_reference_spokes_and_alternating_caption(self):
        with tempfile.TemporaryDirectory() as directory:
            self.generator().generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        weather = xml.find(".//Group[@name='weather']")
        for offset, angle in enumerate((-70, -35, 0, 35, 70)):
            station = weather.find(f"Group[@name='forecast-{offset}']")
            label = station.find("Group[@name='forecast-time']/PartText/TextCircular")
            self.assertIsNotNone(label)
            self.assertAlmostEqual(float(label.get("width")), 251 * 512 / 450)
            self.assertAlmostEqual(float(label.get("startAngle")), 343)
            self.assertEqual(float(station.find("Group[@name='forecast-time']").get("angle")), angle)
            self.assertAlmostEqual(float(label.find("Font").get("size")), 24 * 512 / 450)
            art = station.find(".//Group[@name='weather-art-0']")
            self.assertEqual(float(art.get("angle")), angle)
            image = art.find(".//PartImage")
            self.assertAlmostEqual(float(image.get("x"))+float(image.get("width"))/2, 256, delta=1)
            self.assertAlmostEqual(float(image.get("y"))+float(image.get("height"))/2, 140 * 512 / 450, delta=1)
        self.assertIn("[SECOND] % 10", ET.tostring(weather, encoding="unicode"))

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
        self.assertEqual(start[2:], (18, 7.2))  # inner diameter, proportional black contour
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        for owner in xml.findall(".//Group"):
            if not owner.get("name", "").endswith("-marker"):
                continue
            circle = owner.find(".//Ellipse")
            stroke = float(circle.find("Stroke").get("thickness"))
            inner = float(circle.get("width")) - stroke
            self.assertAlmostEqual(stroke / inner, .4)
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
        self.assertTrue({"primary", "secondary", "tertiary", "arcContent", "progressBars", "apexHands", "secondSweep", "aodStyle"} <= names)
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
        self.assertIn("SMALL_IMAGE", slots[-2].get("supportedTypes"))
        self.assertEqual(len(config.find("ColorConfiguration[@id='primary']")), 74)
        self.assertEqual(len(config.find("ColorConfiguration[@id='secondary']")), 74)
        self.assertEqual(len(config.find("ColorConfiguration[@id='tertiary']")), 74)
        self.assertEqual(len(xml.findall("Scene/ComplicationSlot/BoundingArc")), 5)
        outer_row = xml.find("Scene/ComplicationSlot[@slotId='4']/BoundingArc")
        inner_row = xml.find("Scene/ComplicationSlot[@slotId='5']/BoundingArc")
        self.assertGreater(float(outer_row.get("width"))/2-float(outer_row.get("thickness")), float(inner_row.get("width"))/2)
        graph = xml.find("Scene/ComplicationSlot[@slotId='8']")
        self.assertEqual(graph.find("Variant[@mode='AMBIENT'][@target='alpha']").get("value"), '[CONFIGURATION.arcContent] == "3" ? 160 : 0')
        panels = xml.find(".//Group[@name='inner-panels']")
        self.assertIn('[CONFIGURATION.arcContent] != "3"', panels.find("Variant[@mode='AMBIENT']").get("value"))

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
            self.assertEqual(gradient.get("colors"), "#FFFFFFFF #00FFFFFF", "Reference fades light start into vivid end, not vice versa")

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
        self.assertEqual(len(config.find("Flavors")), 12)
        self.assertEqual(len(config.find("ListConfiguration[@id='weatherSymbols']")), 2)
        self.assertEqual(len(config.find("ListConfiguration[@id='weatherLaunch']")), 2)
        weather = xml.find(".//Group[@name='weather']")
        parameters = {p.get("expression", "") for p in weather.findall(".//Parameter")}
        for offset in (0, 1, 2, 3, 4):
            token = "WEATHER" if offset == 0 else f"WEATHER.HOURS.{offset}"
            self.assertTrue(any(f"[{token}.TEMPERATURE]" in p for p in parameters))
        moon = xml.find(".//Group[@name='moon']")
        transforms = [t.get("value", "") for t in moon.findall(".//Transform")]
        self.assertTrue(any("MOON_PHASE_POSITION" in t for t in transforms), "Moon images must follow the real phase")

    def test_outer_icons_follow_reference_radius_and_tangent(self):
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
                self.assertEqual(float(icon.get("angle", "0")), expected[2])

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
        self.assertIn("OrbitGlucoseGraphComplication", ET.tostring(slot, encoding="unicode"))
        image = slot.find(".//PartImage")
        self.assertEqual((float(image.get("width")), float(image.get("height"))), (330, 165))
        self.assertEqual((float(image.get("x")), float(image.get("y"))), (91, 91))
        content = slot.find("Complication[@type='SMALL_IMAGE']")
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

    def test_upper_right_segments_are_masked_fill_not_overlaid_tick_lines(self):
        face = self.generator()
        with tempfile.TemporaryDirectory() as directory:
            face.generate(Path(directory))
            xml = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        segments = xml.find(".//Group[@name='outer-1-segments']")
        mask = segments.find("Group[@renderMode='MASK']")
        self.assertIsNotNone(mask)
        self.assertEqual(len(mask.findall(".//RoundRectangle")), 13)
        self.assertFalse(segments.findall(".//Line"))
        marker = mask.find("PartDraw[@name='segment-current']")
        self.assertIn("floor", marker.find("Transform[@target='angle']").get("value"))
        self.assertAlmostEqual(float(marker.find("RoundRectangle").get("height")), 24*512/450)


if __name__ == "__main__":
    unittest.main()
