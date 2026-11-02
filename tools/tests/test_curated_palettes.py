"""The measured original palette catalog is preserved without synthetic variants."""
from pathlib import Path
import hashlib
import json
import runpy
import unittest
import xml.etree.ElementTree as E

ROOT=Path(__file__).resolve().parents[2]
CATALOG=runpy.run_path(str(ROOT/'tools/watchface-assets/curated_palettes.py'))

class CuratedPaletteTest(unittest.TestCase):
    def test_original_inventory_is_complete_and_unchanged(self):
        entries=CATALOG['load_palettes']()
        original=sorted((r['name'],r['light'],r['medium'],r['vivid']) for r in entries if r['origin']=='original')
        self.assertEqual(76,len(original))
        self.assertEqual('f2b58d19e5740898a398daa0f7abc2c72b9fef025a0784738959ee81740a445c',hashlib.sha256(json.dumps(original,separators=(',',':')).encode()).hexdigest())
        self.assertEqual(78,len(entries))
        for key in ('id','apex_id','name'):
            self.assertEqual(78,len({r[key] for r in entries}))

    def test_original_orange_and_preserved_olive(self):
        entries={r['id']:r for r in CATALOG['load_palettes']()}
        self.assertEqual(['FFCCB1','FF935C','FF5F0D'],[entries['70'][k] for k in ('light','medium','vivid')])
        self.assertEqual(['D4DAB6','A4AF73','758544'],[entries['14'][k] for k in ('light','medium','vivid')])
        self.assertEqual('61',entries['14']['apex_id'])
        self.assertEqual(['FFFFFF']*3,[entries['999'][k] for k in ('light','medium','vivid')])

    def test_both_faces_use_the_same_original_trios(self):
        entries=CATALOG['load_palettes']()
        for family,keys,idkey in (('sugarlicious-analog',('materialAccent','materialSubTheme'),'apex_id'),('sugarlicious-orbit',('primary','secondary','tertiary'),'id')):
            root=E.parse(ROOT/f'watchfaces/{family}/src/main/res/raw/watchface.xml').getroot()
            for key in keys:
                options=root.find(f"UserConfigurations/ColorConfiguration[@id='{key}']")
                self.assertEqual(78,len(options))
                for entry in entries:
                    tones=options.find(f"ColorOption[@id='{entry[idkey]}']").get('colors').split()
                    self.assertEqual(['#FF'+entry[k] for k in ('light','medium','vivid')],tones[:3])
                    self.assertEqual(4 if key=='materialAccent' else 3,len(tones))

    def test_multicolor_combinations_finish_the_hue_sorted_catalog(self):
        entries=CATALOG['load_palettes']()
        index=next(i for i,r in enumerate(entries) if r['kind']=='combination')
        self.assertTrue(all(r['kind']=='combination' for r in entries[index:]))
        self.assertEqual(11,len(entries)-index)
        self.assertTrue(all('Sanft' not in r['name'] and 'Kräftig' not in r['name'] for r in entries))

if __name__=='__main__': unittest.main()
