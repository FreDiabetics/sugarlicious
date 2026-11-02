"""Replace native flavor inventories with six coordinated visual profiles."""
import argparse
import copy
import json
from pathlib import Path
import xml.etree.ElementTree as ET

# Standard preserves the existing source defaults. The other five are shared
# designs with family-specific palette IDs; no extra watchface settings.
PROFILES = (
    ("standard", "Standard", None, None, None, None),
    ("ultra", "Ultra Orange", "70", "37", "3", "5"),
    ("military", "Military", "14", "61", "3", "4"),
    ("polar", "Polar", "999", "999", "4", "0"),
    ("aurora", "Aurora", "64", "43", "4", "3"),
    ("solar", "Solarsturm", "65", "42", "3", "7"),
)


def replace_user_flavors(root, strings, face):
    configs = root.find("UserConfigurations")
    captured = json.loads(Path(__file__).with_name("captured_native_defaults.json").read_text()).get(face, {})
    for identity, value in captured.get("configurations", {}).items():
        configs.find(f"*[@id='{identity}']").set("defaultValue", value)
    for identity, attributes in captured.get("providers", {}).items():
        policy = root.find(f"Scene/ComplicationSlot[@slotId='{identity}']/DefaultProviderPolicy")
        policy.attrib.clear()
        policy.attrib.update(attributes)
    flavors = configs.find("Flavors")
    if flavors is None:
        flavors = ET.SubElement(configs, "Flavors")
    old_labels = {old.get("displayName") for old in flavors}
    flavors.clear()
    flavors.set("defaultValue", "profile_standard")
    for label in list(strings):
        if label.get("name") in old_labels:
            strings.remove(label)
    for identity, name, orbit_color, apex_color, arc, bars in PROFILES:
        label = f"native_profile_{identity}"
        existing = strings.find(f"string[@name='{label}']")
        if existing is None:
            existing = ET.SubElement(strings, "string", name=label)
        existing.text = name
        flavor = ET.SubElement(flavors, "Flavor", id=f"profile_{identity}", displayName=label)
        values = {item.get("id"): item.get("defaultValue") for item in configs if item.tag != "Flavors" and item.get("defaultValue") is not None}
        if face == "orbit" and orbit_color is not None:
            values.update(primary=orbit_color, secondary=orbit_color, tertiary=orbit_color, arcContent=arc, progressBars=bars)
        elif face == "apex" and apex_color is not None:
            values.update(materialAccent=apex_color, materialSubTheme=apex_color, progressBars=bars)
        for key, value in values.items():
            ET.SubElement(flavor, "Configuration", id=key, optionId=value)
        for actual in root.findall("Scene/ComplicationSlot"):
            if actual.get("isCustomizable") != "TRUE":
                continue
            policy = actual.find("DefaultProviderPolicy")
            if policy is not None:
                slot = ET.SubElement(flavor, "ComplicationSlot", slotId=actual.get("slotId"))
                slot.append(copy.deepcopy(policy))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--face", choices=("apex", "orbit"), required=True)
    args = parser.parse_args()
    root = ET.parse(args.resources / "raw/watchface.xml").getroot()
    strings = ET.parse(args.resources / "values/strings.xml").getroot()
    replace_user_flavors(root, strings, args.face)
    for relative, tree in (("raw/watchface.xml", root), ("values/strings.xml", strings)):
        ET.indent(tree, space="  ")
        ET.ElementTree(tree).write(args.resources / relative, encoding="utf-8", xml_declaration=True)
