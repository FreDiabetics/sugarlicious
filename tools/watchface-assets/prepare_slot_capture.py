"""Build temporary native-renderer fixtures containing only original complication layers."""

import argparse
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ET.register_namespace("android", "http://schemas.android.com/apk/res/android")


def prepare(source: Path, destination: Path, name: str):
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source / "res", destination / "res", dirs_exist_ok=True)
    xml = destination / "res/raw/watchface.xml"
    tree = ET.parse(xml)
    scene = tree.getroot().find("Scene")
    configurations = tree.getroot().find("UserConfigurations")
    flavors = configurations.find("Flavors")
    if flavors is not None:
        configurations.remove(flavors)
    for child in list(scene):
        if child.tag != "ComplicationSlot" and child.get("name") != "center-contours":
            scene.remove(child)
        elif child.tag == "ComplicationSlot":
            policy = child.find("DefaultProviderPolicy")
            kind = policy.get("primaryProviderType", policy.get("defaultSystemProviderType")) if policy is not None else ""
            if kind in ("SMALL_IMAGE", "LARGE_IMAGE", "MONOCHROMATIC_IMAGE"):
                scene.remove(child)
    tree.write(xml, encoding="utf-8", xml_declaration=True)
    manifest = ET.parse(source / "AndroidManifest.xml")
    root = manifest.getroot()
    root.set("package", f"app.aapswear.watchfacepush.slotcapture{name}")
    android = "{http://schemas.android.com/apk/res/android}"
    application = root.find("application")
    application.set(android + "label", f"Slot Capture {name}")
    application.attrib.pop(android + "dataExtractionRules", None)
    minimum, target = ("36", "37") if name == "orbit" else ("34", "35")
    ET.SubElement(root, "uses-sdk", {android + "minSdkVersion": minimum, android + "targetSdkVersion": target})
    manifest.write(destination / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--name", choices=("apex", "orbit"), required=True)
    args = parser.parse_args()
    prepare(args.source, args.destination, args.name)
