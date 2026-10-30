"""Import supplied full-colour brand masters and their visible white symbols without tracing."""
from pathlib import Path
import argparse
import shutil
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
SVG = "http://www.w3.org/2000/svg"
A = "http://schemas.android.com/apk/res/android"
AA = "http://schemas.android.com/aapt"
E.register_namespace("android", A)
E.register_namespace("aapt", AA)


def artwork(path):
    root = E.parse(path).getroot()
    if root.get("viewBox") != "0 0 66 66":
        raise ValueError("Expected supplied 66x66 artwork")
    # Illustrator's SugarWear file contains an obsolete group behind the gradient.
    # Only the final directly painted path is the requested central symbol.
    painted = [c for c in root if c.tag != f"{{{SVG}}}defs"]
    if painted[-1].tag != f"{{{SVG}}}path" or painted[-2].tag != f"{{{SVG}}}rect":
        raise ValueError("Expected final white symbol after opaque gradient circle")
    gradient = root.find(f".//{{{SVG}}}linearGradient")
    if gradient is None or gradient.get("gradientUnits") != "userSpaceOnUse":
        raise ValueError("Expected explicit source gradient geometry")
    return painted[-1].get("d"), gradient


def symbol_svg(data):
    root = E.Element("svg", xmlns=SVG, viewBox="0 0 66 66")
    E.SubElement(root, "path", fill="#ffffff", d=data)
    return E.tostring(root, encoding="unicode") + "\n"


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_symbol(path, data):
    root = E.parse(path).getroot()
    paths = root.findall(".//path")
    if len(paths) != 1:
        raise ValueError(f"Expected one vector path in {path}")
    paths[0].set(f"{{{A}}}pathData", data)
    paths[0].set(f"{{{A}}}fillColor", "#FFFFFFFF")
    write(path, E.tostring(root, encoding="unicode") + "\n")


def background(path, gradient):
    root = E.Element("vector", {f"{{{A}}}width": "108dp", f"{{{A}}}height": "108dp", f"{{{A}}}viewportWidth": "66", f"{{{A}}}viewportHeight": "66"})
    shape = E.SubElement(root, "path", {f"{{{A}}}pathData": "M0,0H66V66H0Z"})
    attr = E.SubElement(shape, f"{{{AA}}}attr", name="android:fillColor")
    attrs = {f"{{{A}}}{k}": v for k, v in {"type": "linear", "startX": gradient.get("x1"), "startY": gradient.get("y1"), "endX": gradient.get("x2"), "endY": gradient.get("y2"), "tileMode": "clamp"}.items()}
    target = E.SubElement(attr, "gradient", attrs)
    for stop in gradient:
        E.SubElement(target, "item", {f"{{{A}}}offset": stop.get("offset"), f"{{{A}}}color": "#FF" + stop.get("stop-color")[1:]})
    write(path, E.tostring(root, encoding="unicode") + "\n")


def import_icons(source, preview_dir, repository_root=ROOT, product="sugarlicious"):
    """Use one importer with product-owned paths; never write into the shared checkout."""
    import cairosvg
    root = Path(repository_root).resolve()
    source = Path(source).resolve()
    preview_dir.mkdir(parents=True, exist_ok=True)
    data, gradient = artwork(source)
    if product == "sugarwear":
        directory = root / "design-assets/sugarwear"
        full_name, symbol_name = "ic_sugarwear.svg", "ic_sugarwear_symbol.svg"
        targets = {"g7watch": ["drawable/ic_sensor_launcher_foreground.xml", "drawable/ic_sensor_launcher_monochrome.xml", "drawable/ic_notification.xml"]}
        backgrounds = ["g7watch/src/main/res/drawable/sugarwear_launcher_background.xml"]
    else:
        directory = root / "design-assets/app-icons"
        full_name, symbol_name = "ic_sugarlicious.svg", "ic_sugarlicious_symbol.svg"
        targets = {
            "app-mobile": ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml", "drawable/ic_foreground.xml", "drawable/ic_sugarlicious_monochrome.xml", "drawable/ic_notification.xml", "drawable/ic_notification_outlined.xml", "drawable-anydpi/ic_notification.xml", "drawable-anydpi/ic_notification_outlined.xml"],
            "app-wear": ["drawable/ic_foreground.xml", "drawable/ic_monochrome_outlined.xml", "drawable/ic_notification.xml"],
        }
        backgrounds = [f"{module}/src/main/res/drawable/ic_launcher_background.xml" for module in targets]
    directory.mkdir(parents=True, exist_ok=True)
    if source != (directory / full_name).resolve():
        shutil.copyfile(source, directory / full_name)
    write(directory / symbol_name, symbol_svg(data))
    for module, files in targets.items():
        for relative in files:
            replace_symbol(root / module / "src/main/res" / relative, data)
    for relative in backgrounds:
        background(root / relative, gradient)
    for module in targets:
        cairosvg.svg2png(url=str(source), write_to=str(root / module / "src/main/ic_launcher-playstore.png"), output_width=512, output_height=512)
    cairosvg.svg2png(url=str(source), write_to=str(preview_dir / "imported-master.png"), output_width=432, output_height=432)
    cairosvg.svg2png(bytestring=symbol_svg(data).encode(), write_to=str(preview_dir / "imported-symbol.png"), output_width=432, output_height=432)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Supplied 66-unit source master")
    parser.add_argument("--product", choices=("sugarlicious", "sugarwear"), default="sugarlicious")
    parser.add_argument("--repository-root", type=Path, default=ROOT)
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args()
    import_icons(args.source, args.preview_dir or args.repository_root / "build/reports/brand-icons", args.repository_root, args.product)
