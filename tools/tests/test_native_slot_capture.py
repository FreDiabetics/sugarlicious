"""Native slot captures retain original rendering, not a parallel imitation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("slot_capture", ROOT / "tools/watchface-assets/prepare_slot_capture.py")
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class NativeSlotCaptureTest(unittest.TestCase):
    def test_only_original_complication_layers_and_their_contours_are_retained(self):
        for name, module in (("apex", "sugarlicious-analog"), ("orbit", "sugarlicious-orbit")):
            source = ROOT / "watchfaces" / module / "src/main"
            original = ET.parse(source / "res/raw/watchface.xml").getroot()
            with tempfile.TemporaryDirectory() as directory:
                CAPTURE.prepare(source, Path(directory), name)
                isolated = ET.parse(Path(directory) / "res/raw/watchface.xml").getroot()
                self.assertIsNone(isolated.find("UserConfigurations/Flavors"))
                self.assertTrue(all(child.tag == "ComplicationSlot" or child.get("name") == "center-contours" for child in isolated.find("Scene")))
                slots = isolated.findall("Scene/ComplicationSlot")
                self.assertEqual(7 if name == "apex" else 6, len(slots))
                for slot in slots:
                    native = original.find(f"Scene/ComplicationSlot[@slotId='{slot.get('slotId')}']")
                    self.assertEqual(ET.tostring(native), ET.tostring(slot))


if __name__ == "__main__":
    unittest.main()
