"""Supplied Sugarlicious artwork must stay identical in every active runtime variant."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
A = "{http://schemas.android.com/apk/res/android}"


class BrandAssetsTest(unittest.TestCase):
    def test_active_mobile_wear_and_notification_vectors_use_supplied_path(self):
        source = E.parse(ROOT / "design-assets/app-icons/ic_sugarlicious.svg").getroot()
        path = source.find("{http://www.w3.org/2000/svg}path").get("d")
        targets = {
            "app-mobile": ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml", "drawable/ic_foreground.xml", "drawable/ic_sugarlicious_monochrome.xml", "drawable/ic_notification.xml", "drawable/ic_notification_outlined.xml", "drawable-anydpi/ic_notification.xml", "drawable-anydpi/ic_notification_outlined.xml"],
            "app-wear": ["drawable/ic_foreground.xml", "drawable/ic_monochrome_outlined.xml", "drawable/ic_notification.xml"],
        }
        for module, files in targets.items():
            for name in files:
                with self.subTest(module=module, asset=name):
                    vector = E.parse(ROOT / module / "src/main/res" / name).getroot()
                    paths = vector.findall(".//path")
                    self.assertEqual(len(paths), 1)
                    self.assertEqual(paths[0].get(A + "pathData"), path)
                    if "notification" in name:
                        self.assertEqual(paths[0].get(A + "fillColor"), "#FFFFFFFF")
                        self.assertEqual(vector.get(A + "viewportWidth"), "66")
                    else:
                        self.assertEqual(vector.get(A + "viewportWidth"), "108")
                        self.assertEqual(vector.find("group").get(A + "translateX"), "21")
