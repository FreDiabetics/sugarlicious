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


def import_icons(sugarwear, sugarlicious, preview_dir):
    import cairosvg
    from PIL import Image, ImageDraw

    preview_dir.mkdir(parents=True, exist_ok=True)
    before = {}
    for name, master in [("SugarWear", ROOT / "design-assets/sugarwear/ic_sugarwear.svg"), ("Sugarlicious", ROOT / "design-assets/app-icons/ic_sugarlicious.svg")]:
        out = preview_dir / f"{name.lower()}-before.png"
        cairosvg.svg2png(url=str(master), write_to=str(out), output_width=264, output_height=264)
        before[name] = out
    wear_path, wear_gradient = artwork(sugarwear)
    sugar_path, sugar_gradient = artwork(sugarlicious)
    for directory, source, data, full_name, symbol_name in [
        (ROOT / "design-assets/sugarwear", sugarwear, wear_path, "ic_sugarwear.svg", "ic_sugarwear_symbol.svg"),
        (ROOT / "design-assets/app-icons", sugarlicious, sugar_path, "ic_sugarlicious.svg", "ic_sugarlicious_symbol.svg"),
    ]:
        shutil.copyfile(source, directory / full_name)
        write(directory / symbol_name, symbol_svg(data))
    for master in (ROOT / "design-assets/app-icons").glob("*.svg"):
        if master.name not in {"ic_sugarlicious.svg", "logo_big.svg"}:
            write(master, symbol_svg(sugar_path))
    shutil.copyfile(sugarlicious, ROOT / "design-assets/app-icons/logo_big.svg")
    write(ROOT / "design-assets/g7watch/ic_notification.svg", symbol_svg(wear_path))
    targets = {
        "app-mobile": ["drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml", "drawable/ic_foreground.xml", "drawable/ic_sugarlicious_monochrome.xml", "drawable/ic_notification.xml", "drawable/ic_notification_outlined.xml", "drawable-anydpi/ic_notification.xml", "drawable-anydpi/ic_notification_outlined.xml"],
        "app-wear": ["drawable/ic_foreground.xml", "drawable/ic_monochrome_outlined.xml", "drawable/ic_notification.xml"],
    }
    for module, files in targets.items():
        for relative in files:
            replace_symbol(ROOT / module / "src/main/res" / relative, sugar_path)
    replace_symbol(ROOT / "ui-shared/src/main/res/drawable/ic_sugarwear_filled.xml", wear_path)
    background(ROOT / "g7watch/src/main/res/drawable/sugarwear_launcher_background.xml", wear_gradient)
    background(ROOT / "app-mobile/src/main/res/drawable/ic_launcher_background.xml", sugar_gradient)
    background(ROOT / "app-wear/src/main/res/drawable/ic_launcher_background.xml", sugar_gradient)
    # Keep existing foreground and monochrome wrappers/insets, replacing only background refs.
    for adaptive in (ROOT / "app-wear/src/main/res/mipmap-anydpi-v26").glob("ic_launcher*.xml"):
        write(adaptive, adaptive.read_text(encoding="utf-8").replace("@mipmap/ic_launcher_background", "@drawable/ic_launcher_background"))
    for stale in (ROOT / "app-wear/src/main/res").glob("mipmap-*/ic_launcher_background.webp"):
        stale.unlink()
    for module in ("app-mobile", "app-wear"):
        cairosvg.svg2png(url=str(sugarlicious), write_to=str(ROOT / module / "src/main/ic_launcher-playstore.png"), output_width=512, output_height=512)
    sheet = Image.new("RGB", (264 * 4, 310 * 2), "#25252c")
    draw = ImageDraw.Draw(sheet)
    for row, (name, source, data) in enumerate([("SugarWear", sugarwear, wear_path), ("Sugarlicious", sugarlicious, sugar_path)]):
        full = preview_dir / f"{name.lower()}-after.png"
        symbol = preview_dir / f"{name.lower()}-symbol.png"
        cairosvg.svg2png(url=str(source), write_to=str(full), output_width=264, output_height=264)
        cairosvg.svg2png(bytestring=symbol_svg(data).encode(), write_to=str(symbol), output_width=264, output_height=264)
        for col, (label, path) in enumerate([("Before master", before[name]), ("Supplied source", full), ("Imported full master", ROOT / ("design-assets/sugarwear/ic_sugarwear.svg" if row == 0 else "design-assets/app-icons/ic_sugarlicious.svg")), ("Symbol only", symbol)]):
            if path.suffix == ".svg":
                rendered = preview_dir / f"{name.lower()}-imported.png"
                cairosvg.svg2png(url=str(path), write_to=str(rendered), output_width=264, output_height=264)
                path = rendered
            tile = Image.open(path).convert("RGBA")
            sheet.paste(tile, (col * 264, row * 310 + 35), tile)
            draw.text((col * 264 + 8, row * 310 + 8), f"{name}: {label}", fill="white")
    sheet.save(preview_dir / "brand-contact-sheet.png")
    print(f"Imported source paths and gradients; review {preview_dir / 'brand-contact-sheet.png'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--sugarwear", type=Path, default=Path("C:/Users/typ1d/Desktop/Sugarlicious/Watchfaces/ic_sugarwear.svg"))
    parser.add_argument("--sugarlicious", type=Path, default=Path("C:/Users/typ1d/Desktop/Sugarlicious/Watchfaces/ic_Sugarlicious.svg"))
    parser.add_argument("--preview-dir", type=Path, default=ROOT / "app-mobile/build/reports/brand-icons")
    args = parser.parse_args()
    import_icons(args.sugarwear, args.sugarlicious, args.preview_dir)
