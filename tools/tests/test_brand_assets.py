"""Supplied Sugarlicious artwork must stay identical in every active runtime variant."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
A = "{http://schemas.android.com/apk/res/android}"


class BrandAssetsTest(unittest.TestCase):
    def test_active_mobile_wear_and_notification_vectors_use_supplied_path(self):
        source = E.parse(ROOT / "design-assets/app-icons/adaptive108/foreground.svg").getroot()
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
                    if name in ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml"]:
                        self.assertEqual(vector.get(A + "viewportWidth"), "108")
                    elif "notification" in name:
                        self.assertEqual(paths[0].get(A + "fillColor"), "#FFFFFFFF")
                        self.assertEqual(vector.get(A + "viewportWidth"), "66")
                    else:
                        self.assertEqual(vector.get(A + "viewportWidth"), "108")
                        self.assertEqual(vector.find("group").get(A + "translateX"), "21")
                    if name not in ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml"]:
                        normalized = vector.find(".//group[@" + A + "name='source108ToLegacy66']")
                        self.assertIsNotNone(normalized)
                        self.assertEqual(normalized.get(A + "scaleX"), str(60/108))

    def test_sugarwear_uses_only_final_visible_source_symbol(self):
        source = E.parse(ROOT / "design-assets/sugarwear/adaptive108/combined.svg").getroot()
        symbol = E.parse(ROOT / "design-assets/sugarwear/adaptive108/foreground.svg").getroot()
        visible = source.findall('.//{http://www.w3.org/2000/svg}path')[-1].get('d')
        self.assertEqual(symbol.find("{http://www.w3.org/2000/svg}path").get("d"), visible)
        runtime = E.parse(ROOT / "ui-shared/src/main/res/drawable/ic_sugarwear_filled.xml").getroot()
        self.assertEqual(len(runtime.findall(".//path")), 1)
        self.assertEqual(runtime.find(".//path").get(A + "pathData"), visible)
        self.assertEqual(runtime.find(".//path").get(A + "fillColor"), "#FFFFFFFF")

    def test_launcher_gradients_match_full_masters_and_keep_symbols_separate(self):
        for master, targets in [
            ("design-assets/app-icons/adaptive108/combined.svg", ["app-mobile/src/main/res/drawable/ic_launcher_background.xml", "app-wear/src/main/res/drawable/ic_launcher_background.xml"]),
            ("design-assets/sugarwear/adaptive108/combined.svg", ["g7watch/src/main/res/drawable/sugarwear_launcher_background.xml"]),
        ]:
            master_root = E.parse(ROOT / master).getroot()
            source = master_root.find(".//{http://www.w3.org/2000/svg}linearGradient")
            for target in targets:
                runtime_root = E.parse(ROOT / target).getroot()
                if master_root.find('.//{http://www.w3.org/2000/svg}image') is not None:
                    self.assertEqual(runtime_root.tag, 'bitmap')
                    self.assertEqual(runtime_root.get(A + 'gravity'), 'fill')
                    import cairosvg, io
                    from PIL import Image, ImageChops
                    background = ROOT / 'design-assets/sugarwear/adaptive108/background.svg'
                    expected = Image.open(io.BytesIO(cairosvg.svg2png(url=str(background),output_width=432,output_height=432))).convert('RGBA')
                    actual = Image.open(ROOT / 'g7watch/src/main/res/drawable-nodpi/sugarwear_launcher_background_art.png').convert('RGBA')
                    self.assertIsNone(ImageChops.difference(expected,actual).getbbox())
                    continue
                runtime = runtime_root.find(".//gradient")
                for svg_key, android_key in [("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY")]:
                    self.assertEqual(source.get(svg_key), runtime.get(A + android_key))
                self.assertEqual([(x.get("offset"), x.get("stop-color").lower()) for x in source], [(x.get(A + "offset"), ("#" + x.get(A + "color")[3:]).lower()) for x in runtime])
        self.assertFalse(list((ROOT / "app-wear/src/main/res").glob("mipmap-*/ic_launcher_background.webp")))

    def test_adaptive_layers_center_exact_source_inside_safe_66_unit_motif(self):
        for directory, modules in [('app-icons', ['app-mobile', 'app-wear']), ('sugarwear', ['g7watch'])]:
            source = E.parse(ROOT / 'design-assets' / directory / 'adaptive108/foreground.svg').getroot()
            data = source.find('{http://www.w3.org/2000/svg}path').get('d')
            for module in modules:
                filename = 'ic_sensor_launcher_foreground.xml' if module == 'g7watch' else 'ic_launcher_foreground.xml'
                runtime = E.parse(ROOT / module / 'src/main/res/drawable' / filename).getroot()
                self.assertEqual(runtime.tag, 'vector')
                self.assertEqual(runtime.get(A + 'viewportWidth'), '108')
                self.assertEqual(runtime.get(A + 'viewportHeight'), '108')
                self.assertEqual(runtime.find('.//path').get(A + 'pathData'), data)
                group = runtime.find('group')
                self.assertEqual(group.get(A + 'scaleX'), str(66/108))
                self.assertEqual(group.get(A + 'scaleY'), str(66/108))
                self.assertEqual(group.get(A + 'translateX'), '21')
                self.assertEqual(group.get(A + 'translateY'), '21')
                mono_name = 'ic_sensor_launcher_monochrome.xml' if module == 'g7watch' else 'ic_launcher_monochrome.xml'
                self.assertEqual((ROOT / module / 'src/main/res/drawable' / filename).read_bytes(), (ROOT / module / 'src/main/res/drawable' / mono_name).read_bytes())

    def test_store_images_are_full_source_renderings(self):
        import io
        import cairosvg
        from PIL import Image, ImageChops, ImageStat
        source = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=(ROOT / "design-assets/app-icons/adaptive108/combined.svg").read_bytes(), output_width=512, output_height=512))).convert("RGBA")
        for module in ["app-mobile", "app-wear"]:
            actual = Image.open(ROOT / module / "src/main/ic_launcher-playstore.png").convert("RGBA")
            self.assertEqual(actual.size, source.size)
            # Cairo's edge antialiasing can vary slightly across Windows and Linux.
            self.assertLess(max(ImageStat.Stat(ImageChops.difference(source, actual)).mean), 0.75)
