"""Explicit-device, data-preserving migration between the independent product IDs."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PERMISSIONS = frozenset("android.permission." + name for name in (
    "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "POST_NOTIFICATIONS",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
)) | {"com.dexcom.cgm.EXTERNAL_PERMISSION", "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"}
PRODUCTS = {
    "mobile": ("app.aapswear", "app.sugarlicious", "app-mobile", "app.aapswear.mobile.MainActivity", "legacy-mobile.apk"),
    "wear": ("app.aapswear", "app.sugarlicious", "app-wear", "app.aapswear.wear.WearActivity", "legacy-wear.apk"),
    "sugarwear": ("app.aapswear.g7watch", "app.sugarwear", "g7watch", "app.aapswear.g7watch.G7WatchActivity", "legacy-sugarwear.apk"),
}


class MigrationError(Exception):
    """Safe diagnostic code; never include command output or private values."""


def validate_metadata(metadata, expected, directory):
    if metadata.get("applicationId") != expected:
        raise MigrationError("apk_application_id_mismatch")
    elements = metadata.get("elements", [])
    if len(elements) != 1 or not isinstance(elements[0].get("outputFile"), str):
        raise MigrationError("apk_requires_single_output")
    directory = Path(directory).resolve()
    apk = (directory / elements[0]["outputFile"]).resolve()
    if apk.parent != directory or apk.suffix != ".apk":
        raise MigrationError("apk_path_invalid")
    return apk


def extract_grants(package_dump):
    # Restrict to actual granted entries, excluding requested permissions and flags.
    return set(re.findall(r"^\s*([A-Za-z0-9_.]+):\s+granted=true\b", package_dump, re.M)) & PERMISSIONS


def migration_plan(product, phone, watch):
    if product not in {"sugarlicious", "sugarwear", "both"}:
        raise MigrationError("unknown_product")
    if not watch or (product != "sugarwear" and not phone):
        raise MigrationError("explicit_serial_required")
    if phone and phone == watch:
        raise MigrationError("phone_watch_serial_collision")
    plan = []
    if product in {"sugarlicious", "both"}:
        plan.extend((("mobile", phone), ("wear", watch)))
    if product in {"sugarwear", "both"}:
        plan.append(("sugarwear", watch))
    return plan


def package_allowed(text, package):
    return package in re.split(r"[:\s,]+", text.strip())


def component_commands(text, package):
    data = json.loads(text)
    if not isinstance(data, dict) or len(data) > 500:
        raise MigrationError("rollback_component_state_invalid")
    modes = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user", 4: "disable-until-used"}
    commands = []
    for name, state in data.items():
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", name) or type(state) is not int or state not in modes:
            raise MigrationError("rollback_component_state_invalid")
        commands.append(["shell", "pm", modes[state], "--user", "0", package + "/" + name])
    return commands


class Adb:
    def __init__(self, executable, serial):
        if not serial or not re.fullmatch(r"[A-Za-z0-9_.:\[\]-]+", serial):
            raise MigrationError("invalid_serial")
        self.executable, self.serial = executable, serial

    def run(self, *args, optional=False, timeout=30):
        # ADB joins shell arguments for the device shell; quote each remote token.
        if args and args[0] == "shell":
            args = ("shell", *(shlex.quote(token) for token in args[1:]))
        try:
            result = subprocess.run([self.executable, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise MigrationError("adb_timeout_or_unavailable") from error
        if result.returncode and not optional:
            raise MigrationError("adb_command_failed")
        return result.stdout.strip() if result.returncode == 0 else ""

    def installed(self, package):
        return self.run("shell", "pm", "path", package, optional=True).startswith("package:")

    def marker(self, package, name):
        return self.run("shell", "run-as", package, "cat", "no_backup/" + name, optional=True)

    def launch(self, package, activity):
        output = self.run("shell", "am", "start", "-W", "-n", package + "/" + activity, timeout=45)
        if "Error:" in output or "Exception" in output:
            raise MigrationError("activity_launch_failed")

    def install(self, path):
        output = self.run("install", "-r", "-t", str(path), timeout=180)
        if not any(line.strip() == "Success" for line in output.splitlines()):
            raise MigrationError("apk_install_failed")


def device_info(adb, watch):
    if adb.run("get-state") != "device":
        raise MigrationError("device_not_ready")
    characteristics = adb.run("shell", "getprop", "ro.build.characteristics").split(",")
    if ("watch" in [item.strip() for item in characteristics]) != watch:
        raise MigrationError("device_role_mismatch")
    return {"model": adb.run("shell", "getprop", "ro.product.model"), "role": "watch" if watch else "phone"}


def existing_access(adb, package):
    grants = extract_grants(adb.run("shell", "dumpsys", "package", package))
    dnd = package_allowed(adb.run("shell", "settings", "get", "secure", "enabled_notification_policy_access_packages"), package)
    whitelist = adb.run("shell", "dumpsys", "deviceidle", "whitelist")
    battery = any(package in line.split(",") for line in whitelist.splitlines())
    exact = adb.run("shell", "cmd", "appops", "get", package, "SCHEDULE_EXACT_ALARM", optional=True)
    return {"grants": grants, "dnd": dnd, "battery": battery, "exact": bool(re.search(r"SCHEDULE_EXACT_ALARM:\s*allow\b", exact))}


def mirror_access(adb, package, access):
    for permission in sorted(access["grants"]):
        adb.run("shell", "pm", "grant", "--user", "0", package, permission)
    if access["battery"]:
        adb.run("shell", "dumpsys", "deviceidle", "whitelist", "+" + package)
    if access["dnd"]:
        adb.run("shell", "cmd", "notification", "allow_dnd", package, "0")
    if access["exact"]:
        adb.run("shell", "cmd", "appops", "set", package, "SCHEDULE_EXACT_ALARM", "allow")


def rollback(adb, old, new, activity):
    restored = True
    operations = []
    try:
        adb.run("shell", "am", "force-stop", new)
        # Legacy may receive newer data after rollback, even after an acknowledged import.
        # Require a fresh snapshot before the target can ever resume again.
        adb.run("shell", "run-as", new, "rm", "-f", "no_backup/product-migration-complete", "no_backup/product-migration-complete.bak")
    except Exception:
        return False
    try:
        snapshot = adb.marker(old, "product-migration-components.json")
        for command in component_commands(snapshot, old) if snapshot else []:
            operations.append(lambda command=command: adb.run(*command))
    except Exception:
        restored = False
    # Only private migration control markers are removed; preferences/history remain intact.
    operations.extend((
        lambda: adb.run("shell", "run-as", old, "rm", "-f", "no_backup/product-migration-quiesced", "no_backup/product-migration-retired"),
        lambda: adb.run("shell", "pm", "enable", "--user", "0", old),
        lambda: adb.launch(old, activity),
    ))
    for operation in operations:
        try:
            operation()
        except Exception:
            restored = False
    return restored


def migrate(adb, key, apk, bridge):
    old, new, _, activity, _ = PRODUCTS[key]
    before = {"legacy_installed": adb.installed(old), "target_installed": adb.installed(new)}
    result = {"product_surface": key, "before": before}
    access = existing_access(adb, old) if before["legacy_installed"] else None
    terminal = adb.marker(new, "product-migration-complete") if before["target_installed"] else ""
    if terminal in {"acknowledged", "fresh"}:
        adb.install(apk)
        if access:
            mirror_access(adb, new, access)
        adb.launch(new, activity)
        result.update(status="updated_existing_target", after={"target_installed": True, "migration": terminal})
        return result
    try:
        if before["legacy_installed"]:
            adb.run("shell", "pm", "enable", "--user", "0", old)
            adb.install(bridge)
        adb.install(apk)
        if access:
            mirror_access(adb, new, access)
        adb.run("shell", "am", "force-stop", old)
        adb.run("shell", "am", "force-stop", new)
        if before["legacy_installed"]:
            if adb.run("shell", "pidof", old, optional=True):
                raise MigrationError("legacy_process_not_quiescent")
            adb.run("shell", "run-as", old, "mkdir", "-p", "no_backup")
            adb.run("shell", "run-as", old, "touch", "no_backup/product-migration-quiesced")
            adb.run("shell", "run-as", old, "ls", "no_backup/product-migration-quiesced")
            if adb.run("shell", "pidof", old, optional=True):
                raise MigrationError("legacy_process_restarted_before_import")
        adb.launch(new, activity)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if adb.marker(new, "product-migration-failure"):
                raise MigrationError("target_reported_migration_failure")
            terminal = adb.marker(new, "product-migration-complete")
            if terminal in {"acknowledged", "fresh"}:
                if before["legacy_installed"] and terminal != "acknowledged":
                    raise MigrationError("legacy_import_not_acknowledged")
                break
            time.sleep(1)
        else:
            raise MigrationError("migration_acknowledgment_timeout")
        if before["legacy_installed"]:
            adb.run("shell", "am", "force-stop", old)
            adb.run("shell", "pm", "disable-user", "--user", "0", old)
        result.update(status="migrated" if access else "fresh_install", after={"target_installed": True, "migration": terminal, "legacy_disabled": before["legacy_installed"]}, mirrored_runtime_grant_count=len(access["grants"]) if access else 0)
    except Exception as error:
        result.update(status="failed", error_code=str(error) if isinstance(error, MigrationError) else "unexpected_failure")
        if before["legacy_installed"]:
            result["legacy_restart_attempt_succeeded"] = rollback(adb, old, new, activity)
        else:
            adb.run("shell", "am", "force-stop", new, optional=True)
    return result


def artifact_directory(key, sugarwear_root=None):
    if key == "sugarwear" and sugarwear_root is None:
        raise MigrationError("sugarwear_repository_required")
    root = Path(sugarwear_root).resolve() if key == "sugarwear" else ROOT
    return root / PRODUCTS[key][2] / "build/outputs/apk/debug"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone")
    parser.add_argument("--watch", required=True)
    parser.add_argument("--product", choices=("sugarlicious", "sugarwear", "both"), required=True)
    parser.add_argument("--legacy-dir", type=Path, required=True)
    parser.add_argument("--sugarwear-root", type=Path, help="Independent SugarWear checkout (required for SugarWear migration)")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    default_adb = str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")) if sdk else (shutil.which("adb") or "adb")
    parser.add_argument("--adb", default=default_adb)
    args = parser.parse_args(argv)
    report = {"results": []}
    try:
        plan = migration_plan(args.product, args.phone, args.watch)
        prepared = []
        # Validate every requested APK and device before mutating any package.
        for key, serial in plan:
            old, new, module, _, bridge_name = PRODUCTS[key]
            directory = artifact_directory(key, args.sugarwear_root)
            apk = validate_metadata(json.loads((directory / "output-metadata.json").read_text(encoding="utf-8")), new, directory)
            bridge = args.legacy_dir.resolve() / bridge_name
            if not apk.is_file() or not bridge.is_file():
                raise MigrationError("required_apk_missing")
            adb = Adb(args.adb, serial)
            info = device_info(adb, key != "mobile")
            prepared.append((adb, key, apk, bridge, info))
        for adb, key, apk, bridge, info in prepared:
            result = migrate(adb, key, apk, bridge)
            result["device"] = info
            report["results"].append(result)
            if result["status"] == "failed":
                break
        report["status"] = "failed" if any(r["status"] == "failed" for r in report["results"]) else "complete"
    except Exception as error:
        report.update(status="failed", error_code=str(error) if isinstance(error, MigrationError) else "preflight_failed")
    print(json.dumps(report, ensure_ascii=True))
    return 0 if report["status"] == "complete" else 1


if __name__ == "__main__":
    sys.exit(main())
