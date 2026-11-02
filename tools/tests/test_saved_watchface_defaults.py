"""Captured native selections must survive generation and application defaults."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class SavedWatchfaceDefaultsTest(unittest.TestCase):
    def test_apex_aurora_preset_uses_the_shared_aurora_palette(self):
        root = ET.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()
        preset = root.find("UserConfigurations/Flavors/Flavor[@id='profile_aurora']")
        for setting in ("materialAccent", "materialSubTheme"):
            selected = preset.find(f"Configuration[@id='{setting}']").get("optionId")
            palette = root.find(f"UserConfigurations/ColorConfiguration[@id='{setting}']/ColorOption[@id='{selected}']")
            self.assertEqual("curated_palette_79", palette.get("displayName"))

    def test_six_new_native_flavors_are_valid_and_cover_tir_graph_marker_and_gradient(self):
        for face in ("sugarlicious-analog", "sugarlicious-orbit"):
            root = ET.parse(ROOT / f"watchfaces/{face}/src/main/res/raw/watchface.xml").getroot()
            flavors = root.findall("UserConfigurations/Flavors/Flavor")
            users = [flavor for flavor in flavors if flavor.get("id", "").startswith("profile_")]
            self.assertEqual(6, len(users), face)
            configs = {item.get("id"): item for item in root.find("UserConfigurations") if item.tag != "Flavors"}
            for flavor in users:
                for selected in flavor.findall("Configuration"):
                    definition = configs[selected.get("id")]
                    allowed = {"TRUE", "FALSE"} if definition.tag == "BooleanConfiguration" else {item.get("id") for item in definition}
                    self.assertIn(selected.get("optionId"), allowed)
                for slot in flavor.findall("ComplicationSlot"):
                    actual = root.find(f".//Scene//ComplicationSlot[@slotId='{slot.get('slotId')}']")
                    self.assertIsNotNone(actual)
                    policy = slot.find("DefaultProviderPolicy")
                    self.assertIn(policy.get("primaryProviderType") or policy.get("defaultSystemProviderType"), actual.get("supportedTypes").split())
            settings = [{item.get("id"): item.get("optionId") for item in flavor.findall("Configuration")} for flavor in users]
            if face.endswith("orbit"):
                self.assertEqual({"3", "4"}, {item["arcContent"] for item in settings})
                self.assertTrue(any(int(item["progressBars"]) >= 4 for item in settings))
                self.assertTrue(any(int(item["progressBars"]) < 4 for item in settings))
            else:
                self.assertTrue(any(int(item["progressBars"]) >= 4 for item in settings))
                self.assertTrue(any(int(item["progressBars"]) < 4 for item in settings))
                self.assertTrue(any(item["progressBars"] in {"1", "3", "5", "7"} for item in settings))
                expected = {slot.get("slotId") for slot in root.findall("Scene/ComplicationSlot") if slot.get("isCustomizable") == "TRUE"}
                for flavor in users:
                    self.assertEqual(expected, {slot.get("slotId") for slot in flavor.findall("ComplicationSlot")})

    def test_apex_upper_left_uses_selected_ranged_iob(self):
        root = ET.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()
        policy = root.find("Scene/ComplicationSlot[@slotId='0']/DefaultProviderPolicy")
        self.assertEqual(policy.get("primaryProviderType"), "RANGED_VALUE")
        self.assertTrue(policy.get("primaryProvider").endswith(".IobRangedValueComplication"))

    def test_orbit_regeneration_keeps_selected_steps_calendar_and_graph(self):
        spec = importlib.util.spec_from_file_location("orbit_defaults_generator", ROOT / "tools/watchface-assets/generate_orbit.py")
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        with tempfile.TemporaryDirectory() as directory:
            generator.generate(Path(directory))
            root = ET.parse(Path(directory) / "raw/watchface.xml").getroot()
        steps = root.find("Scene/ComplicationSlot[@slotId='7']/DefaultProviderPolicy")
        self.assertEqual("STEP_COUNT", steps.get("defaultSystemProvider"))
        self.assertTrue(steps.get("primaryProvider").endswith("StepsComplicationProviderService"))
        calendar = root.find("Scene/ComplicationSlot[@slotId='4']/DefaultProviderPolicy")
        self.assertEqual("NEXT_EVENT", calendar.get("defaultSystemProvider"))
        self.assertEqual("3", root.find("UserConfigurations/ListConfiguration[@id='arcContent']").get("defaultValue"))


if __name__ == "__main__":
    unittest.main()
