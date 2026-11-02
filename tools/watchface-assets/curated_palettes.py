"""Original shared reference palettes, one option per combination, plus Olive.
Stable IDs preserve the explicitly retained Olive selection. No derived tone swaps.
"""
import argparse
import csv
import colorsys
import runpy
from pathlib import Path
import xml.etree.ElementTree as ET


def load_palettes():
    with Path(__file__).with_name("orbit_palettes.csv").open(encoding="utf-8", newline="") as source:
        entries = list(csv.DictReader(source))
    for key in ("id", "apex_id"):
        if len({entry[key] for entry in entries}) != len(entries):
            raise ValueError(f"Duplicate palette {key}")
    if len(entries) != 78 or sum(entry['origin'] == 'original' for entry in entries) != 76:
        raise ValueError('Expected76 original palettes plus preserved Olive and pure white')
    def order(entry):
        rgb = tuple(int(entry['vivid'][i:i+2],16)/255 for i in (0,2,4))
        hue,saturation,value = colorsys.rgb_to_hsv(*rgb)
        if entry['kind'] == 'combination':
            return (2,hue,entry['name'])
        if max(colorsys.rgb_to_hsv(*(int(entry[t][i:i+2],16)/255 for i in (0,2,4)))[1] for t in ('light','medium','vivid')) < .15:
            return (1,-value,entry['name'])
        degrees=hue*360
        return (0,degrees-360 if degrees>345 else degrees,entry['name'])
    return sorted(entries,key=order)


def shade(color, factor):
    return "".join(f"{round(int(color[i:i+2], 16) * factor):02X}" for i in (0, 2, 4))


def dim(color):
    return shade(color, .32)


def apply_apex_palettes(root, strings):
    entries = load_palettes()
    labels = {'curated_palette_' + entry['id'] for entry in entries}
    for label in list(strings):
        if label.get('name','').startswith('curated_palette_') and label.get('name') not in labels:
            strings.remove(label)
    for identity in ("materialAccent", "materialSubTheme"):
        config = root.find(f"UserConfigurations/ColorConfiguration[@id='{identity}']")
        if config is None:
            raise ValueError(f"Missing configuration {identity}")
        for child in list(config):
            config.remove(child)
        config.set("defaultValue", "37")
        for entry in entries:
            label = "curated_palette_" + entry["id"]
            text = strings.find(f"string[@name='{label}']")
            if text is None:
                text = ET.SubElement(strings, "string", name=label)
            text.text = entry["name"]
            tones = [entry[key] for key in ("light", "medium", "vivid")]
            if identity == "materialAccent":
                tones.append(dim(entry["medium"]))
            ET.SubElement(config, "ColorOption", id=entry["apex_id"], displayName=label,
                          colors=" ".join("#FF" + tone for tone in tones))

    runpy.run_path(str(Path(__file__).with_name("configure_indicator_gradient.py")))["configure"](root, "apex")
    runpy.run_path(str(Path(__file__).with_name("unify_apex_progress_settings.py")))["configure"](root, strings)
    runpy.run_path(str(Path(__file__).with_name("user_flavors.py")))["replace_user_flavors"](root, strings, "apex")
    # The lower-left icon sits just beyond the rounded start cap. Provider
    # image transforms must move too; changing only y leaves live data unchanged.
    lower = root.find("Scene/ComplicationSlot[@slotId='3']")
    for image in lower.findall("Complication[@type='SHORT_TEXT']/PartImage"):
        image.set("x", "54")
        image.set("y", "304")
        image.set("width", "32")
        image.set("height", "32")
    for image in lower.findall("Complication[@type='RANGED_VALUE']/PartImage"):
        image.set("x", "221")
        image.set("y", "434")
        image.set("width", "32")
        image.set("height", "32")
        for transform in image.findall("Transform"):
            if transform.get("target") == "x":
                transform.set("value", "[COMPLICATION.RANGED_VALUE_MIN] == 40 && [COMPLICATION.RANGED_VALUE_MAX] == 260 ? 211 : 221")
            elif transform.get("target") == "y":
                transform.set("value", "[COMPLICATION.RANGED_VALUE_MIN] == 40 && [COMPLICATION.RANGED_VALUE_MAX] == 260 ? 424 : 434")
            elif transform.get("target") in ("width", "height"):
                transform.set("value", "[COMPLICATION.RANGED_VALUE_MIN] == 40 && [COMPLICATION.RANGED_VALUE_MAX] == 260 ? 52 : 32")
    for identity in ("0", "1", "2", "3"):
        slot = root.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
        for marker in slot.findall(".//Group"):
            if marker.get("name", "").startswith("progress-marker-"):
                for stroke in marker.findall(".//Stroke"):
                    stroke.set("thickness", "5.640898")
        for image in slot.findall("Complication[@type='RANGED_VALUE']/PartImage"):
            base = float(image.get("width"))
            size = 36 if identity == "3" else 32
            delta = (size - base) / 2
            for target in ("x", "y", "width", "height"):
                transform = image.find(f"Transform[@target='{target}']")
                if transform is None:
                    transform = ET.SubElement(image, "Transform", target=target, value=image.get(target))
                previous = transform.get("value")
                if "[COMPLICATION.RANGED_VALUE_MAX] == 10" in previous:
                    continue
                value = str(size) if target in ("width", "height") else f"({previous}) - {delta}"
                transform.set("value", f"([COMPLICATION.RANGED_VALUE_MIN] == 0 && [COMPLICATION.RANGED_VALUE_MAX] == 10) ? {value} : ({previous})")
    runpy.run_path(str(Path(__file__).with_name("configure_apex_progress.py")))["fit_outer_value_text"](root)
    runpy.run_path(str(Path(__file__).with_name("segmented_progress.py")))["configure"](root, "apex")
    runpy.run_path(str(Path(__file__).with_name("restore_native_hands.py")))["configure"](root, "apex")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, required=True)
    args = parser.parse_args()
    paths = (args.resources / "raw/watchface.xml", args.resources / "values/strings.xml")
    trees = [ET.parse(path).getroot() for path in paths]
    apply_apex_palettes(*trees)
    for path, tree in zip(paths, trees):
        ET.indent(tree, space="  ")
        ET.ElementTree(tree).write(path, encoding="utf-8", xml_declaration=True)
