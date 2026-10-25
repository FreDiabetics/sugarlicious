"""Render the user's unchanged 450-unit Vigil marker artwork for WFF."""
from pathlib import Path
import argparse
import shutil
import xml.etree.ElementTree as E
import cairosvg

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "design-assets/vigil"
FACE = ROOT / "watchfaces/sugarlicious-direct-to-watch/src/main/res"


def render(source_directory=None):
    SOURCE.mkdir(parents=True, exist_ok=True)
    destination = FACE / "drawable-nodpi"
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("vigil_minutenmarkers", "vigil_hourmarkers"):
        path = SOURCE / (name + ".svg")
        if source_directory:
            shutil.copyfile(source_directory / path.name, path)
        assert E.parse(path).getroot().get("viewBox") == "0 0 450 450"
        artwork = path.read_text(encoding="utf-8")
        if name == "vigil_minutenmarkers":
            artwork = artwork.replace("#afafaf", "#616161")
        cairosvg.svg2png(bytestring=artwork.encode("utf-8"), write_to=str(destination / (name + ".png")), output_width=450, output_height=450)
    for name in ("hour_hand_transparent.png", "minute_hand_transparent.png", "second_hand_transparent.png"):
        shutil.copyfile(ROOT / "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi" / name, destination / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path)
    render(parser.parse_args().source)
