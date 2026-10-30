import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("migrate_products", Path(__file__).with_name("migrate_products.py"))
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class MigrationPreflightTests(unittest.TestCase):
    def test_existing_migration_repairs_previously_granted_broadcast_permission(self):
        class ExistingTarget:
            def installed(self, package):
                return True
            def marker(self, *args):
                return "acknowledged"
            def install(self, path):
                pass
            def launch(self, *args):
                pass
        access = {"grants": {"com.dexcom.cgm.EXTERNAL_PERMISSION"}, "battery": False, "dnd": False, "exact": False}
        with patch.object(migration, "existing_access", return_value=access), patch.object(migration, "mirror_access") as mirror:
            result = migration.migrate(ExistingTarget(), "mobile", Path("new.apk"), Path("old.apk"))
        self.assertEqual("updated_existing_target", result["status"])
        self.assertEqual("app.sugarlicious", mirror.call_args.args[1])
        self.assertEqual(access, mirror.call_args.args[2])

    def test_rollback_invalidates_acknowledgment_before_legacy_restart(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []
            def run(self, *command):
                self.commands.append(command)
                return ""
            def marker(self, *args):
                return ""
            def launch(self, *args):
                self.commands.append(("launch", *args))
        adb = FakeAdb()
        self.assertTrue(migration.rollback(adb, "old", "new", "Activity"))
        self.assertIn("product-migration-complete", " ".join(adb.commands[1]))
        self.assertEqual(("launch", "old", "Activity"), adb.commands[-1])

    def test_rollback_does_not_restart_legacy_when_target_cannot_be_stopped(self):
        class UnreachableAdb:
            def run(self, *command):
                raise RuntimeError("offline")
            def launch(self, *args):
                raise AssertionError("must not restart legacy")
        self.assertFalse(migration.rollback(UnreachableAdb(), "old", "new", "Activity"))

    def test_metadata_rejects_wrong_product_before_install(self):
        with self.assertRaises(migration.MigrationError):
            migration.validate_metadata({"applicationId": "app.sugarwear", "elements": [{"outputFile": "app.apk"}]}, "app.sugarlicious", Path("outputs"))

    def test_metadata_accepts_single_expected_apk_and_rejects_path_escape(self):
        metadata = {"applicationId": "app.sugarwear", "elements": [{"outputFile": "g7watch-debug.apk"}]}
        self.assertEqual(Path("outputs/g7watch-debug.apk").resolve(), migration.validate_metadata(metadata, "app.sugarwear", Path("outputs")))
        for filename in ("../other.apk", "config.xml"):
            metadata["elements"][0]["outputFile"] = filename
            with self.assertRaises(migration.MigrationError):
                migration.validate_metadata(metadata, "app.sugarwear", Path("outputs"))

    def test_grants_include_only_previously_granted_allowlisted_runtime_permissions(self):
        dump = """requested permissions:
 android.permission.BLUETOOTH_SCAN
 runtime permissions:
 android.permission.BLUETOOTH_SCAN: granted=true, flags=[ USER_SET]
 android.permission.BLUETOOTH_CONNECT: granted=false, flags=[]
 android.permission.POST_NOTIFICATIONS: granted=true
 android.permission.CAMERA: granted=true
 com.dexcom.cgm.EXTERNAL_PERMISSION: granted=true
 com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE: granted=true
 arbitrary.permission.SECRET: granted=true
"""
        self.assertEqual({"android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS", "com.dexcom.cgm.EXTERNAL_PERMISSION", "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"}, migration.extract_grants(dump))

    def test_plans_require_explicit_distinct_device_roles(self):
        self.assertEqual([("sugarwear", "watch")], migration.migration_plan("sugarwear", None, "watch"))
        self.assertEqual([("mobile", "phone"), ("wear", "watch"), ("sugarwear", "watch")], migration.migration_plan("both", "phone", "watch"))
        for product, phone, watch in (("both", None, "watch"), ("sugarlicious", "same", "same"), ("sugarwear", None, None), ("unknown", "phone", "watch")):
            with self.assertRaises(migration.MigrationError):
                migration.migration_plan(product, phone, watch)

    def test_permission_access_lookup_matches_whole_packages(self):
        self.assertTrue(migration.package_allowed("app.other:app.aapswear", "app.aapswear"))
        self.assertFalse(migration.package_allowed("app.aapswear.extra", "app.aapswear"))

    def test_rollback_component_commands_reject_untrusted_names_or_states(self):
        commands = migration.component_commands('{"app.aapswear.g7watch.G7BootReceiver":0}', "app.aapswear.g7watch")
        self.assertEqual("default-state", commands[0][2])
        android_x = migration.component_commands('{"androidx.profileinstaller.ProfileInstallReceiver":0,"androidx.work.impl.background.systemjob.SystemJobService":1}', "app.aapswear.g7watch")
        self.assertEqual("app.aapswear.g7watch/androidx.profileinstaller.ProfileInstallReceiver", android_x[0][-1])
        self.assertEqual("app.aapswear.g7watch/androidx.work.impl.background.systemjob.SystemJobService", android_x[1][-1])
        for value in ('{"arbitrary; command":1}', '{"androidx.Receiver;echo injected":1}', '{"androidx.Receiver/other.Owner":1}', '{"androidx..Receiver":1}', '{"app.aapswear.g7watch.Receiver":7}', '{"app.aapswear.g7watch.Receiver":true}'):
            with self.assertRaises(migration.MigrationError):
                migration.component_commands(value, "app.aapswear.g7watch")


if __name__ == "__main__":
    unittest.main()
