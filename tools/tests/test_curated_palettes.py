"""Palette variants are permutations, never newly composed color sets."""
from pathlib import Path
import runpy
import unittest
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
CATALOG = runpy.run_path(str(ROOT / "tools/watchface-assets/curated_palettes.py"))


class CuratedPaletteTest(unittest.TestCase):
    def test_each_bold_palette_swaps_exactly_two_original_tones(self):
        options = CATALOG["load_palettes"]()
        self.assertEqual(len(options), 45)
        self.assertEqual(len({o["id"] for o in options}), 45)
        pairs = [o for o in options if o["id"] != "999"]
        for soft, bold in zip(pairs[::2], pairs[1::2]):
            self.assertTrue(soft["name"].endswith(" · Sanft"))
            self.assertTrue(bold["name"].endswith(" · Kräftig"))
            self.assertEqual(int(soft["id"]) + 200, int(bold["id"]))
            self.assertEqual(int(soft["apex_id"]) + 500, int(bold["apex_id"]))
            before = [soft[k] for k in ("light", "medium", "vivid")]
            after = [bold[k] for k in ("light", "medium", "vivid")]
            self.assertEqual(after, before[::-1])
            self.assertEqual(sorted(after), sorted(before))

    def test_requested_main_tones_and_separate_pure_white_option(self):
        options = {o["id"]: o for o in CATALOG["load_palettes"]()}
        self.assertEqual(options["270"]["light"], "F88F33")
        self.assertEqual(options["214"]["light"], "758544")
        self.assertEqual([options["999"][k] for k in ("light", "medium", "vivid")], ["FFFFFF"] * 3)

    def test_both_native_faces_keep_original_tone_counts_and_role_swap(self):
        for family, identities, strong_id, size in (("sugarlicious-analog", ("materialAccent", "materialSubTheme"), "537", (4, 3)), ("sugarlicious-orbit", ("primary", "secondary", "tertiary"), "270", (3, 3, 3))):
            root = E.parse(ROOT / f"watchfaces/{family}/src/main/res/raw/watchface.xml").getroot()
            for identity, count in zip(identities, size):
                config = root.find(f"UserConfigurations/ColorConfiguration[@id='{identity}']")
                self.assertEqual(len(config), 45)
                self.assertEqual(len(config.find(f"ColorOption[@id='{strong_id}']").get("colors").split()), count)
                self.assertEqual(config.find(f"ColorOption[@id='{strong_id}']").get("colors").split()[:3], ["#FFF88F33", "#FFFBB576", "#FFFFDBBD"])

    def test_color_table_order_and_ten_three_color_combinations_at_end(self):
        options = CATALOG["load_palettes"]()
        self.assertEqual(sum(o["kind"] == "combination" for o in options), 20)
        self.assertTrue(all(o["kind"] == "combination" for o in options[-20:]))
        self.assertEqual([o["name"].split(" · ")[0] for o in options[:14:2]], ["Rot", "Orange", "Gelb", "Olivgrün", "Blau", "Violett", "Pink"])
        for option in options[-20:]:
            self.assertEqual(len({option[k] for k in ("light", "medium", "vivid")}), 3)
        neutrals = [o["name"] for o in options if o["name"].split(" · ")[0] in {"Weiß", "Reinweiß", "Hellgrau", "Warmgrau", "Graphit"}]
        self.assertEqual(neutrals, ["Weiß · Sanft", "Weiß · Kräftig", "Reinweiß", "Hellgrau · Sanft", "Hellgrau · Kräftig", "Warmgrau · Sanft", "Warmgrau · Kräftig", "Graphit · Sanft", "Graphit · Kräftig"])


if __name__ == "__main__":
    unittest.main()
