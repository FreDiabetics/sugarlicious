"""Place user-provided native hand strips without rescaling; render matching picker icons."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "watchfaces/sugarlicious-orbit/src/main/res/drawable-nodpi"
REFERENCE = ROOT / "watchfaces/sugarlicious-orbit/reference/hands"
APEX = ROOT / "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi"


def render(output: Path):
    output.mkdir(parents=True, exist_ok=True)
    for style in range(15):
        for kind in ("hour", "minute"):
            name = f"orbit_{kind}_{style}.png"
            strip = Image.open(REFERENCE / name).convert("RGBA")
            if strip.size != (50, 275):
                raise ValueError(f"Invalid original canvas for {name}: {strip.size}")
            canvas = Image.new("RGBA", (450, 450))
            canvas.paste(strip, (200, 0))
            canvas.save(output / name)
            if style == 6:
                accent = Image.new("RGBA", (450, 450))
                accent.paste(Image.open(REFERENCE / f"orbit_{kind}_6_accent.png").convert("RGBA"), (200, 0))
                accent.save(output / f"orbit_{kind}_6_accent.png")
            elif style == 14:
                accent = Image.new("RGBA", (450, 450))
                top = 98 if kind == "hour" else 34
                ImageDraw.Draw(accent).rectangle((220, top, 229, 161), fill="white")
                accent.save(output / f"orbit_{kind}_14_accent.png")

    second = Image.new("RGBA", (1800, 1800))
    draw = ImageDraw.Draw(second)
    draw.rounded_rectangle((894, 20, 906, 1052), radius=6, fill=(0, 0, 0, 166))
    draw.rounded_rectangle((894, 8, 906, 1040), radius=6, fill="white")
    second.resize((450, 450), Image.Resampling.LANCZOS).save(output / "orbit_second_hand.png")

    for style in range(18):
        preview = Image.new("RGBA", (450, 450), "black")
        for kind, angle in (("hour", 304), ("minute", 48)):
            if style < 15:
                hand = Image.open(output / f"orbit_{kind}_{style}.png").convert("RGBA")
                if style in (6, 14):
                    accent = Image.open(output / f"orbit_{kind}_{style}_accent.png").convert("RGBA")
                    hand = Image.alpha_composite(hand, accent) if style == 14 else Image.alpha_composite(accent, hand)
            else:
                suffix = ("", "_transparent", "_tblack")[style - 15]
                hand = Image.open(APEX / f"{kind}_hand{suffix}.png").convert("RGBA")
            if hand.size != (450, 450):
                hand = hand.resize((450, 450), Image.Resampling.LANCZOS)
            preview.alpha_composite(hand.rotate(-angle, Image.Resampling.BICUBIC, center=(225, 225)))
        preview.resize((300, 300), Image.Resampling.LANCZOS).save(output / f"orbit_hand_preview_{style}.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    render(parser.parse_args().output)
