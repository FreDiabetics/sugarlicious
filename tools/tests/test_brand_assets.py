"""Supplied Sugarlicious artwork must stay identical in every active runtime variant."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
A = "{http://schemas.android.com/apk/res/android}"


class BrandAssetsTest(unittest.TestCase):
    def test_active_mobile_wear_and_notification_vectors_use_supplied_path(self):
        source = E.parse(ROOT / "design-assets/app-icons/ic_sugarlicious_symbol.svg").getroot()
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

    def test_sugarwear_uses_only_final_visible_source_symbol(self):
        source = E.parse(ROOT / "design-assets/sugarwear/ic_sugarwear.svg").getroot()
        symbol = E.parse(ROOT / "design-assets/sugarwear/ic_sugarwear_symbol.svg").getroot()
        visible = list(source)[-1].get("d")
        self.assertEqual(symbol.find("{http://www.w3.org/2000/svg}path").get("d"), visible)
        runtime = E.parse(ROOT / "ui-shared/src/main/res/drawable/ic_sugarwear_filled.xml").getroot()
        self.assertEqual(len(runtime.findall(".//path")), 1)
        self.assertEqual(runtime.find(".//path").get(A + "pathData"), visible)
        self.assertEqual(runtime.find(".//path").get(A + "fillColor"), "#FFFFFFFF")

    def test_launcher_gradients_match_full_masters_and_keep_symbols_separate(self):
        for master, targets in [
            ("design-assets/app-icons/ic_sugarlicious.svg", ["app-mobile/src/main/res/drawable/ic_launcher_background.xml", "app-wear/src/main/res/drawable/ic_launcher_background.xml"]),
            ("design-assets/sugarwear/ic_sugarwear.svg", ["g7watch/src/main/res/drawable/sugarwear_launcher_background.xml"]),
        ]:
            source = E.parse(ROOT / master).getroot().find(".//{http://www.w3.org/2000/svg}linearGradient")
            for target in targets:
                runtime = E.parse(ROOT / target).getroot().find(".//gradient")
                for svg_key, android_key in [("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY")]:
                    self.assertEqual(source.get(svg_key), runtime.get(A + android_key))
                self.assertEqual([(x.get("offset"), x.get("stop-color").lower()) for x in source], [(x.get(A + "offset"), ("#" + x.get(A + "color")[3:]).lower()) for x in runtime])
        self.assertFalse(list((ROOT / "app-wear/src/main/res").glob("mipmap-*/ic_launcher_background.webp")))

    def test_store_images_are_full_source_renderings(self):
        import io
        import cairosvg
        from PIL import Image, ImageChops, ImageStat
        source = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=(ROOT / "design-assets/app-icons/ic_sugarlicious.svg").read_bytes(), output_width=512, output_height=512))).convert("RGBA")
        for module in ["app-mobile", "app-wear"]:
            actual = Image.open(ROOT / module / "src/main/ic_launcher-playstore.png").convert("RGBA")
            self.assertEqual(actual.size, source.size)
            # Cairo's edge antialiasing can vary slightly across Windows and Linux.
            self.assertLess(max(ImageStat.Stat(ImageChops.difference(source, actual)).mean), 0.75)
