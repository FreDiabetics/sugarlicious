"""Import only requested vendor weather artwork from user-installed reference APKs.

No APK, executable code, or reference watchface layout is copied into the project.
These assets are not licensed for redistribution by this import operation.
"""
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path
import xml.etree.ElementTree as E
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]


def import_weather(samsung_apk, google_apk, table):
    output = ROOT / "watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi"
    manifest = {"packs": {}, "assets": {}}
    resources = table.read_text(encoding="utf-8-sig")
    with zipfile.ZipFile(samsung_apk) as samsung, zipfile.ZipFile(google_apk) as google:
        def copy(pack, name, archive, entry):
            resource = f"orbit_weather_{pack}_{name}"
            data = archive.read(entry)
            destination = output / (resource + Path(entry).suffix)
            destination.write_bytes(data)
            image = Image.open(io.BytesIO(data)).convert("RGBA")
            manifest["assets"][resource] = {"file": destination.name, "source": entry,
                "sha256": hashlib.sha256(data).hexdigest(), "canvas": image.size,
                "bounds": image.getchannel("A").getbbox() or (0, 0, *image.size)}
            return resource

        names = ["sunny", "cloudy", "fog", "heavy_rain", "heavy_snow", "rain", "snow", "sunny",
                 "thunderstorm", "rain_and_snow", "light_snow", "shower", "fog", "partly_cloud", "wind"]
        for period in ("day", "night"):
            mapping = {}
            for condition, original in enumerate(names, 1):
                if period == "night":
                    original = {"sunny": "clear", "partly_cloud": "partly_cloud_night"}.get(original, original)
                match = re.search(r"drawable/" + original + r"\s+\(\) \(file\) (\S+)", resources)
                if not match:
                    raise ValueError(f"Missing original Samsung resource: {original}")
                mapping[str(condition)] = copy("samsung", original, samsung, match[1])
            manifest["packs"]["samsung_" + period] = mapping

        reference = E.fromstring(google.read(next(n for n in google.namelist() if n.endswith("/watchface.xml"))))
        for period in ("day", "night"):
            font = reference.find(f"BitmapFonts/BitmapFont[@name='weather_icons_pack_1_{period}']")
            mapping = {}
            for item in font:
                condition = int(item.get("name"))
                if not 1 <= condition <= 15:
                    continue
                name = item.get("resource")
                entry = next(n for n in google.namelist() if n.endswith("/" + name + ".png"))
                mapping[str(condition)] = copy("google", name, google, entry)
            if len(mapping) != 15:
                raise ValueError("Incomplete reference Google Weather mapping")
            manifest["packs"]["google_" + period] = mapping
    target = ROOT / "watchfaces/sugarlicious-orbit/reference/weather"
    target.mkdir(parents=True, exist_ok=True)
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--samsung-apk", type=Path, required=True)
    parser.add_argument("--google-reference-apk", type=Path, required=True)
    parser.add_argument("--samsung-resource-table", type=Path, required=True)
    args = parser.parse_args()
    import_weather(args.samsung_apk, args.google_reference_apk, args.samsung_resource_table)
