"""ApeX progress options must not stack incompatible indicators."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class ApexProgressTest(unittest.TestCase):
    def setUp(self):
        self.face = ET.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()

    def test_upper_graph_uses_canonical_inner_semicircle(self):
        slot = self.face.find("Scene/ComplicationSlot[@slotId='7']")
        policy = slot.find("DefaultProviderPolicy")
        self.assertTrue(policy.get("primaryProvider").endswith(".GlucoseGraphComplication"))
        bounds = slot.find("BoundingBox")
        self.assertEqual(["91", "91", "330", "97"], [bounds.get(key) for key in ("x", "y", "width", "height")])
        content = slot.find("Complication[@type='SMALL_IMAGE']")
        self.assertIsNone(content.find(".//RoundRectangle"))
        graph = content.find("Group[@name='graph-clipped-content']")
        self.assertEqual("[CONFIGURATION.materialAccent.0]", graph.get("tintColor"))
        mask = graph.find("PartDraw[@renderMode='MASK']/Arc")
        self.assertEqual(["256", "256", "165", "165", "270", "450"],
                         [mask.get(key) for key in ("centerX", "centerY", "width", "height", "startAngle", "endAngle")])
        self.assertEqual("165", mask.find("Stroke").get("thickness"))
        self.assertIsNone(content.find("PartDraw"), "no full semicircle background outside cap mask")
        ink = graph.find("Group[@name='graph-cap-ink']")
        background = ink.find("PartDraw")
        self.assertEqual(["91", "91", "330", "97"], [background.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual("#FF000000", background.find("Rectangle/Fill").get("color"))
        image = ink.find("PartImage")
        self.assertEqual(["91", "91", "330", "97"], [image.get(key) for key in ("x", "y", "width", "height")])

    def test_segmented_upper_right_disables_marker_and_marker_full_fill(self):
        ranged = self.face.find("Scene/ComplicationSlot[@slotId='1']/Complication[@type='RANGED_VALUE']")
        marker = ranged.find("Group[@name='progress-marker-1']/Transform[@target='alpha']").get("value")
        self.assertIn('[CONFIGURATION.upperRightMarkers] != "TRUE"', marker)
        for transform in ranged.findall("Group[@name='progress-fill-1']//Transform[@target='endAngle']"):
            self.assertIn('[CONFIGURATION.upperRightMarkers] != "TRUE"', transform.get("value"))

    def test_optional_gap_preserves_rounded_fill_and_remaining_ends(self):
        option = self.face.find("UserConfigurations/BooleanConfiguration[@id='progressGap']")
        self.assertIsNotNone(option)
        self.assertEqual("TRUE", option.get("defaultValue"))
        for slot_id in range(7):
            ranged = self.face.find(f"Scene/ComplicationSlot[@slotId='{slot_id}']/Complication[@type='RANGED_VALUE']")
            remaining = ranged.find(f"Group[@name='progress-gap-{slot_id}']")
            self.assertIsNotNone(remaining, slot_id)
            arc = remaining.find("PartDraw/Arc")
            self.assertEqual("COUNTER_CLOCKWISE", arc.get("direction"))
            self.assertEqual("ROUND", arc.find("Stroke").get("cap"))
            self.assertIn("clamp(", arc.find("Transform[@target='endAngle']").get("value"))
            self.assertIn("< 1", remaining.find("Transform[@target='alpha']").get("value"))
            self.assertIn('[CONFIGURATION.progressGap] == "TRUE"', remaining.find("Transform[@target='alpha']").get("value"))


if __name__ == "__main__":
    unittest.main()
