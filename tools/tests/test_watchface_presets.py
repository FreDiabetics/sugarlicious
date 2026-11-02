"""Six coordinated native profiles replace the previous flavor inventory."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[2]
class NativePresetsTest(unittest.TestCase):
    def test_exactly_six_new_profiles_with_valid_options(self):
        expected=["profile_standard","profile_ultra","profile_military","profile_polar","profile_aurora","profile_solar"]
        for family in ("sugarlicious-analog","sugarlicious-orbit"):
            root=E.parse(ROOT/f"watchfaces/{family}/src/main/res/raw/watchface.xml").getroot()
            configs=root.find("UserConfigurations")
            flavors=configs.find("Flavors")
            self.assertEqual([f.get("id") for f in flavors],expected)
            self.assertEqual(flavors.get("defaultValue"),expected[0])
            definitions={c.get("id"):c for c in configs if c.tag != "Flavors"}
            styles=[]
            for flavor in flavors:
                selected={c.get("id"):c.get("optionId") for c in flavor.findall("Configuration")}
                for key,value in selected.items():
                    definition=definitions[key]
                    allowed={"TRUE","FALSE"} if definition.tag=="BooleanConfiguration" else {o.get("id") for o in definition}
                    self.assertIn(value,allowed,(family,flavor.get("id"),key,value))
                styles.append(selected["progressBars"])
                if family=="sugarlicious-analog":self.assertNotIn("arcContent",selected)
            self.assertTrue(any(int(s)<4 for s in styles))
            self.assertTrue(any(int(s)>=4 for s in styles))
            self.assertTrue(any(s in {"1","3","5","7"} for s in styles))
            self.assertTrue(any(s in {"0","2","4","6"} for s in styles))
            baseline={c.get("id"):c.get("defaultValue") for c in definitions.values() if c.get("defaultValue") is not None}
            self.assertEqual({c.get("id"):c.get("optionId") for c in flavors[0].findall("Configuration")},baseline)
