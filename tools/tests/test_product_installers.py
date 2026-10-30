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
            ("watchfaces/sugarlicious-analog", "app.sugarlicious.watchfacepush.apex"),
            ("watchfaces/sugarlicious-orbit", "app.sugarlicious.watchfacepush.orbit"),
            ("watchfaces/sugarlicious-digital", "app.sugarlicious.watchfacepush.digital"),
        ):
            source = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            if module in ("app-mobile", "app-wear", "g7watch"):
                self.assertIn(f'else "{expected}"', source)
                self.assertIn('providers.gradleProperty("legacyProductMigration")', source)
            else:
                self.assertIn(f'applicationId = "{expected}"', source)
            self.assertRegex(source, r'namespace = "app\.aapswear\.')

    def test_installer_builds_only_sugarlicious(self):
        source = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        block = source.split('tasks.named("installSugarliciousDebug") {', 1)[1].split("\n}", 1)[0]
        self.assertEqual({":app-mobile:assembleDebug", ":app-wear:assembleDebug"}, set(re.findall(r'"(:[^"\n]+:assembleDebug)"', block)))
        self.assertNotIn("installSugarWearDebug", source)
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('":g7watch"', settings)
        self.assertNotIn('":watchfaces:sugarlicious-direct-to-watch"', settings)
        self.assertFalse((ROOT / "g7watch/build.gradle.kts").exists())
        self.assertFalse((ROOT / "watchfaces/sugarlicious-direct-to-watch/build.gradle.kts").exists())

    def test_wear_build_does_not_prepare_vigil(self):
        source = (ROOT / "app-wear/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn(":watchfaces:sugarlicious-direct-to-watch:assembleRelease", source)
        self.assertNotIn("prepareDirectToWatchFace", source)
        self.assertIn('androidResources.ignoreAssetsPattern = "sugarlicious_direct_to_watch.apk:sugarlicious_direct_to_watch_token.txt"', source)


if __name__ == "__main__":
    unittest.main()
