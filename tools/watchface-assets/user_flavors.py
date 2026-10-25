"""Native WFF presets. Keep existing flavors and append six stable user choices."""
import argparse
import copy
from pathlib import Path
import xml.etree.ElementTree as ET


def append_user_flavors(root, strings, face):
    configs = root.find("UserConfigurations")
    flavors = configs.find("Flavors")
    if flavors is None:
        flavors = ET.SubElement(configs, "Flavors", defaultValue="user_0")
    for old in list(flavors):
        if old.get("id", "").startswith("user_"):
            flavors.remove(old)
    flavors.set("defaultValue", "user_0")
    # Current orange/graph baseline first, then saturated and readable alternatives.
    choices = (
        ("Graph Orange", False, "70", "37", True, True, "7"),
        ("TIR Aqua", True, "26", "31", False, False, "0"),
        ("Graph Rot", False, "65", "42", True, False, "4"),
        ("TIR Violett", True, "64", "43", False, True, "1"),
        ("Graph Limette", False, "69", "38", True, False, "6"),
        ("TIR Eis", True, "25", "76", False, True, "3"),
    )
    for index, (name, tir, orbit_color, apex_color, marker, gradient, bars) in enumerate(choices):
        label = f"user_preset_{index}"
        existing = strings.find(f"string[@name='{label}']")
        if existing is None:
            existing = ET.SubElement(strings, "string", name=label)
        existing.text = name
        flavor = ET.SubElement(flavors, "Flavor", id=f"user_{index}", displayName=label)
        values = {item.get("id"): item.get("defaultValue") for item in configs if item.tag != "Flavors" and item.get("defaultValue") is not None}
        if face == "orbit":
            values.update(primary=orbit_color, secondary=orbit_color, tertiary=orbit_color,
                          arcContent="4" if tir else "3", progressBars=bars)
        else:
            values.update(materialAccent=apex_color, materialSubTheme=apex_color,
                          progressMarker=str(marker).upper(), progressGradient=str(gradient).upper())
        for key, value in values.items():
            ET.SubElement(flavor, "Configuration", id=key, optionId=value)
        for actual in root.findall("Scene//ComplicationSlot"):
            if actual.get("isCustomizable") != "TRUE":
                continue
            policy = actual.find("DefaultProviderPolicy")
            if policy is None:
                continue
            slot = ET.SubElement(flavor, "ComplicationSlot", slotId=actual.get("slotId"))
            policy = copy.deepcopy(policy)
            if face == "apex" and actual.get("slotId") == "7":
                provider = "TirRangedValueComplication" if tir else "GlucoseGraphComplication"
                kind = "RANGED_VALUE" if tir else "SMALL_IMAGE"
                policy.attrib.update(primaryProvider=f"app.aapswear/app.aapswear.complications.{provider}", primaryProviderType=kind)
            slot.append(policy)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--face", choices=("apex", "orbit"), required=True)
    args = parser.parse_args()
    root = ET.parse(args.resources / "raw/watchface.xml").getroot()
    strings = ET.parse(args.resources / "values/strings.xml").getroot()
    append_user_flavors(root, strings, args.face)
    for relative, tree in (("raw/watchface.xml", root), ("values/strings.xml", strings)):
        ET.indent(tree, space="  ")
        ET.ElementTree(tree).write(args.resources / relative, encoding="utf-8", xml_declaration=True)
