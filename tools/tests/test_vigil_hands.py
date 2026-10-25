"""Vigil reuses canonical hands and keeps rounded indices clear of provider ink."""
from pathlib import Path
import math
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FACE = ROOT / "watchfaces/sugarlicious-direct-to-watch"


class VigilHandsTest(unittest.TestCase):
    def setUp(self):
        self.face = ET.parse(FACE / "src/main/res/raw/watchface.xml").getroot()

    def test_provider_clock_keeps_full_rendering_and_center_tap_separate_with_eight_slots(self):
        scene = self.face.find("Scene")
        slots = scene.findall(".//ComplicationSlot")
        self.assertEqual(8, len(slots))
        self.assertTrue(all(slot.get("isCustomizable") == "FALSE" for slot in slots))
        hands = scene.find(".//ComplicationSlot[@slotId='7']")
        center = scene.find(".//ComplicationSlot[@slotId='8']")
        self.assertIs(center, list(scene)[-1])
        self.assertEqual(8, len(scene.findall("ComplicationSlot")))
        self.assertEqual(["0", "0", "450", "450"], [hands.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["0", "0", "450", "450"], [hands.find("BoundingBox").get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["200", "200", "50", "50"], [center.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["0", "0", "50", "50"], [center.find("BoundingOval").get(key) for key in ("x", "y", "width", "height")])
        self.assertIsNone(scene.find("AnalogClock"))
        self.assertEqual("ANALOG", self.face.find("Metadata[@key='CLOCK_TYPE']").get("value"))
        build = (ROOT / "complications/build.gradle.kts").read_text()
        for resource in ("hour_hand_transparent", "minute_hand_transparent"):
            self.assertIn('"' + resource + '.png"', build)
            self.assertTrue((ROOT / "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi" / (resource + ".png")).is_file())

    def test_digital_clock_and_hands_both_supply_active_and_ambient_images(self):
        self.assertIsNone(self.face.find("Scene/ComplicationSlot[@slotId='6']"))
        for slot_id in ("0", "1", "5", "7"):
            slot = self.face.find(f".//ComplicationSlot[@slotId='{slot_id}']")
            images = slot.findall(".//PartImage/Image")
            self.assertEqual(["[COMPLICATION.SMALL_IMAGE]", "[COMPLICATION.SMALL_IMAGE_AMBIENT]"], [image.get("resource") for image in images])
            parts = slot.findall(".//PartImage")
            self.assertEqual("0", parts[0].find("Variant[@mode='AMBIENT']").get("value"))
            self.assertEqual("255", parts[1].find("Variant[@mode='AMBIENT']").get("value"))

    def test_header_and_graph_action_mirrors_restore_taps_above_hands(self):
        scene = self.face.find("Scene")
        slots = scene.findall("ComplicationSlot")
        self.assertEqual(["0", "1", "2", "5", "7", "9", "10", "8"], [slot.get("slotId") for slot in slots])
        for original_id, mirror_id in (("0", "9"), ("1", "10")):
            original = scene.find(f"ComplicationSlot[@slotId='{original_id}']")
            mirror = scene.find(f"ComplicationSlot[@slotId='{mirror_id}']")
            expected_action = "DirectToWatchCollectorActionComplication" if mirror_id == "9" else "DirectToWatchGraphActionComplication"
            self.assertTrue(mirror.find("DefaultProviderPolicy").get("primaryProvider").endswith(expected_action))
            self.assertEqual([original.get(key) for key in ("x", "y", "width", "height")], [mirror.get(key) for key in ("x", "y", "width", "height")])
            self.assertNotEqual("0", mirror.get("alpha"))
            self.assertEqual("0", mirror.find("Complication/PartImage").get("alpha"))
        self.assertIsNone(scene.find("ComplicationSlot[@slotId='3']"))
        self.assertIsNone(scene.find("ComplicationSlot[@slotId='4']"))

    def test_native_visibility_switch_hides_hands_and_center_content(self):
        option = self.face.find("UserConfigurations/BooleanConfiguration[@id='showHands']")
        self.assertEqual("TRUE", option.get("defaultValue"))
        for slot_id in ("7", "8"):
            condition = self.face.find(f"Scene/ComplicationSlot[@slotId='{slot_id}']/Complication/Group/Condition")
            self.assertEqual('[CONFIGURATION.showHands] == "TRUE"', condition.find("Expressions/Expression").text)
            self.assertIsNone(condition.find("Default"))
        info = ET.parse(FACE / "src/main/res/xml/watch_face_info.xml").getroot()
        self.assertEqual("true", info.find("Editable").get("value"))

    def test_round_markers_are_symmetric_and_clear_all_occupied_regions(self):
        markers = self.face.find("Scene/Group[@name='vigil-perimeter-markers']")
        lines = markers.findall("PartDraw/Line")
        self.assertEqual(28, len(lines))
        occupied = [(0, 140, 450, 345), (120, 30, 330, 138), (90, 347, 360, 425)]
        points = []
        for line in lines:
            stroke = line.find("Stroke")
            self.assertEqual("ROUND", stroke.get("cap"))
            x1, y1, x2, y2 = [float(line.get(key)) for key in ("startX", "startY", "endX", "endY")]
            self.assertAlmostEqual(217, math.hypot(x2 - 225, y2 - 225), places=3)
            self.assertIn(round(math.hypot(x2 - x1, y2 - y1)), (5, 10))
            clearance = float(stroke.get("thickness")) / 2 + 2
            bounds = min(x1, x2) - clearance, min(y1, y2) - clearance, max(x1, x2) + clearance, max(y1, y2) + clearance
            for left, top, right, bottom in occupied:
                self.assertFalse(bounds[0] < right and bounds[2] > left and bounds[1] < bottom and bounds[3] > top)
            points.append((x2, y2))
        for x, y in points:
            self.assertTrue(any(abs(other_x - (450 - x)) < .001 and abs(other_y - y) < .001 for other_x, other_y in points))


if __name__ == "__main__":
    unittest.main()
