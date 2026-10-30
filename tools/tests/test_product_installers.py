"""Guard the independent Gradle installation entry points without attached devices."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ProductInstallerTest(unittest.TestCase):
    def test_public_application_ids_are_separate_from_retained_class_namespaces(self):
        for module, expected in (
            ("app-mobile", "app.sugarlicious"),
            ("app-wear", "app.sugarlicious"),
            ("g7watch", "app.sugarwear"),
            ("watchfaces/sugarlicious-analog", "app.sugarlicious.watchfacepush.apex"),
            ("watchfaces/sugarlicious-orbit", "app.sugarlicious.watchfacepush.orbit"),
            ("watchfaces/sugarlicious-digital", "app.sugarlicious.watchfacepush.digital"),
            ("watchfaces/sugarlicious-direct-to-watch", "app.sugarwear.watchfacepush.vigil"),
        ):
            source = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            if module in ("app-mobile", "app-wear", "g7watch"):
                self.assertIn(f'else "{expected}"', source)
                self.assertIn('providers.gradleProperty("legacyProductMigration")', source)
            else:
                self.assertIn(f'applicationId = "{expected}"', source)
            self.assertRegex(source, r'namespace = "app\.aapswear\.')

    def test_vigil_targets_the_independent_collector_with_retained_class_names(self):
        import xml.etree.ElementTree as ET
        root = ET.parse(ROOT / "watchfaces/sugarlicious-direct-to-watch/src/main/res/raw/watchface.xml").getroot()
        for policy in root.iter("DefaultProviderPolicy"):
            provider = policy.get("primaryProvider", "")
            if provider:
                self.assertTrue(provider.startswith("app.sugarwear/app.aapswear.complications."), provider)
        for launch in root.iter("Launch"):
            self.assertEqual("app.sugarwear/app.aapswear.g7watch.G7WatchActivity", launch.get("target"))

    def test_installers_build_only_their_product(self):
        source = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        for task, expected, companion in (
            ("installSugarliciousDebug", {":app-mobile:assembleDebug", ":app-wear:assembleDebug"}, "true"),
            ("installSugarWearDebug", {":g7watch:assembleDebug", ":watchfaces:sugarlicious-direct-to-watch:assembleDebug"}, "false"),
        ):
            block = source.split(f'>("{task}") {{', 1)[1].split("\n}", 1)[0]
            self.assertEqual(expected, set(re.findall(r'"(:[^"\n]+:assembleDebug)"', block)))
            self.assertIn(f"companionProduct.set({companion})", block)
        installer = source.split("abstract class InstallWatchProductDebugTask", 1)[1].split("val watchFaceValidatorCli", 1)[0]
        companion, standalone = installer.split("if (companionProduct.get()) {", 1)[1].split("} else {", 1)
        self.assertNotIn("g7WatchApk.get()", companion)
        self.assertNotIn("vigilApk.get()", companion)
        self.assertNotIn("mobileApk.get()", standalone)
        self.assertNotIn("wearApk.get()", standalone)
        self.assertNotIn("uninstall", installer)
        self.assertIn("!companionProduct.get() || phones.size == 1", installer)

    def test_wear_build_does_not_prepare_vigil(self):
        source = (ROOT / "app-wear/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn(":watchfaces:sugarlicious-direct-to-watch:assembleRelease", source)
        self.assertNotIn("prepareDirectToWatchFace", source)
        self.assertIn('androidResources.ignoreAssetsPattern = "sugarlicious_direct_to_watch.apk:sugarlicious_direct_to_watch_token.txt"', source)


if __name__ == "__main__":
    unittest.main()
