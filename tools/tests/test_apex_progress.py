"""ApeX progress options must not stack incompatible indicators."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class ApexProgressTest(unittest.TestCase):
    def setUp(self):
        self.face = ET.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()

    def test_opposite_tick_pair_has_a_moving_long_tick_without_a_circle(self):
        for identity in ('1', '3'):
            content = self.face.find(f"Scene/ComplicationSlot[@slotId='{identity}']/Complication[@type='RANGED_VALUE']")
            marks = content.find("Group[@name='radial-value-ticks']")
            self.assertIsNotNone(marks)
            self.assertEqual(30, len(marks.findall('PartDraw')))
            self.assertIsNone(marks.find('.//Ellipse'))
            self.assertIn('255.0000', marks.find("PartDraw[@name='tick-0-light']/Transform").get('value'))
            self.assertIn('0.0000', marks.find("PartDraw[@name='tick-9-light']/Transform").get('value'))
            for index, part in enumerate(marks.findall('PartDraw')[::3]):
                current = part.find("Line/Transform[@target='startX']").get('value')
                self.assertIn('floor(clamp(', current)
                self.assertIn(f'== {index}', current)
            wrapper = content.find(f"Group[@name='continuous-only-progress-marker-{identity}']")
            self.assertIn('? 0 : 255', wrapper.find('Transform').get('value'))
        for identity in ('0', '2', '4', '5', '6'):
            self.assertIsNone(self.face.find(f"Scene/ComplicationSlot[@slotId='{identity}']//Group[@name='radial-value-ticks']"))

    def test_measured_text_and_icon_geometry_does_not_touch_tracks(self):
        import math
        for identity, start, end, align in [('0','337','361','START'),('1','66','90','START'),('2','181','155','END'),('3','282','246','END')]:
            ranged=self.face.find(f"Scene/ComplicationSlot[@slotId='{identity}']/Complication[@type='RANGED_VALUE']")
            for text in ranged.findall('.//TextCircular'):
                self.assertEqual((start,end,align),tuple(text.get(k) for k in ('startAngle','endAngle','align')))
            image=ranged.find('PartImage');arc=ranged.find('.//Arc')
            angle=math.radians({'0':276,'1':6,'2':96,'3':186}[identity]);radius=float(arc.get('width'))/2
            self.assertAlmostEqual(float(arc.get('centerX'))+radius*math.sin(angle),float(image.find("Transform[@target='x']").get('value').rsplit(':',1)[1])+11,places=3)
            self.assertAlmostEqual(float(arc.get('centerY'))-radius*math.cos(angle),float(image.find("Transform[@target='y']").get('value').rsplit(':',1)[1])+11,places=3)
            self.assertEqual(('22','22'),(image.get('width'),image.get('height')))
            self.assertIn('52.0000',image.find("Transform[@target='width']").get('value'))
            for font in self.face.findall(f"Scene/ComplicationSlot[@slotId='{identity}']//Font"):
                self.assertEqual('29.6',font.get('size'))
        for identity in ('4','5','6'):
            for font in self.face.findall(f"Scene/ComplicationSlot[@slotId='{identity}']//Font"):
                title=any('[COMPLICATION.TITLE]' in p.get('expression','') for p in font.findall('.//Parameter'))
                self.assertEqual('32' if title else '39',font.get('size'))

    def test_geometry_is_idempotent_and_preserves_colors_providers_and_tracks(self):
        import importlib.util
        spec=importlib.util.spec_from_file_location('apex_geometry',ROOT/'tools/watchface-assets/configure_apex_progress.py')
        mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
        def protected(root):
            return [(e.tag,k,v) for e in root.iter() for k,v in e.attrib.items()
                    if k in {'color','tintColor','family','primaryProvider','primaryProviderType'} or e.tag=='Arc' and k in {'startAngle','endAngle','width','height'}]
        before=protected(self.face)
        mod.fit_outer_value_text(self.face)
        self.assertEqual(before,protected(self.face))
        one=ET.tostring(self.face)
        mod.fit_outer_value_text(self.face)
        self.assertEqual(one,ET.tostring(self.face))

    def test_standalone_geometry_export_preserves_the_fixed_native_graph(self):
        import shutil, subprocess, sys, tempfile
        with tempfile.TemporaryDirectory() as directory:
            res=Path(directory)
            for relative in ('raw/watchface.xml','values/strings.xml'):
                target=res/relative;target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(ROOT/'watchfaces/sugarlicious-analog/src/main/res'/relative,target)
            command=[sys.executable,str(ROOT/'tools/watchface-assets/configure_apex_progress.py'),'--resources',str(res)]
            subprocess.run(command,check=True,capture_output=True)
            first=(res/'raw/watchface.xml').read_bytes()
            subprocess.run(command,check=True,capture_output=True)
            self.assertEqual(first,(res/'raw/watchface.xml').read_bytes())
            graph=ET.fromstring(first).find("Scene/ComplicationSlot[@slotId='7']")
            self.assertEqual('LONG_TEXT EMPTY',graph.get('supportedTypes'))
            self.assertTrue(graph.find('DefaultProviderPolicy').get('primaryProvider').endswith('NativeApexGraphComplication'))

    def test_upper_graph_uses_canonical_inner_semicircle(self):
        slot = self.face.find("Scene/ComplicationSlot[@slotId='7']")
        policy = slot.find("DefaultProviderPolicy")
        self.assertTrue(policy.get("primaryProvider").endswith(".NativeApexGraphComplication"))
        bounds = slot.find("BoundingBox")
        self.assertEqual(["91", "91", "330", "97"], [bounds.get(key) for key in ("x", "y", "width", "height")])
        content = slot.find("Complication[@type='LONG_TEXT']")
        self.assertIsNone(content.find(".//RoundRectangle"))
        graph = content.find(".//Group[@name='graph-clipped-content']")
        self.assertEqual("[CONFIGURATION.materialAccent.0]", graph.get("tintColor"))
        mask = graph.find("PartDraw[@renderMode='MASK']/Arc")
        self.assertEqual(["256", "256", "165", "165", "270", "450"],
                         [mask.get(key) for key in ("centerX", "centerY", "width", "height", "startAngle", "endAngle")])
        self.assertEqual("165", mask.find("Stroke").get("thickness"))
        self.assertIsNone(content.find("PartDraw"), "no full semicircle background outside cap mask")
        ink = graph.find("Group[@name='graph-cap-ink']")
        background = ink.find("PartDraw")
        self.assertEqual(["91", "91", "330", "97"], [background.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual("#FF000000", background.find("Rectangle/Fill").get("color"))
        image = ink.find("PartImage")
        self.assertEqual(["91", "91", "330", "97"], [image.get(key) for key in ("x", "y", "width", "height")])

    def test_segmented_upper_right_disables_marker_and_marker_full_fill(self):
        ranged = self.face.find("Scene/ComplicationSlot[@slotId='1']/Complication[@type='RANGED_VALUE']")
        marker = ranged.find(".//Group[@name='progress-marker-1']/Transform[@target='alpha']").get("value")
        condition = marker.rsplit("?", 1)[0].replace("&&", " and ").replace("||", " or ").replace("!", " not ")
        condition = condition.replace("[COMPLICATION.RANGED_VALUE_MAX]", "100").replace("[COMPLICATION.RANGED_VALUE_MIN]", "0")
        for style in range(8):
            resolved = condition.replace("[CONFIGURATION.progressBars]", repr(str(style)))
            self.assertEqual(bool(eval(resolved, {"__builtins__": {}}, {})), style in (4, 5))

    def test_upper_right_value_grows_away_from_the_bar_end(self):
        ranged = self.face.find("Scene/ComplicationSlot[@slotId='1']/Complication[@type='RANGED_VALUE']")
        texts = ranged.findall('.//TextCircular')
        self.assertEqual(3, len(texts))
        for text in texts:
            self.assertEqual('START', text.get('align'))
            self.assertEqual('90', text.get('endAngle'))
            self.assertEqual('CLOCKWISE', text.get('direction'))

    def test_optional_gap_preserves_rounded_fill_and_remaining_ends(self):
        option = self.face.find("UserConfigurations/ListConfiguration[@id='progressBars']")
        self.assertIsNotNone(option)
        self.assertEqual([str(i) for i in range(8)], [o.get("id") for o in option])
        for key in ("progressGap", "progressMarker", "progressGradient", "upperRightMarkers"):
            self.assertIsNone(self.face.find(f"UserConfigurations/BooleanConfiguration[@id='{key}']"))
        for slot_id in range(7):
            ranged = self.face.find(f"Scene/ComplicationSlot[@slotId='{slot_id}']/Complication[@type='RANGED_VALUE']")
            remaining = ranged.find(f".//Group[@name='progress-gap-{slot_id}']")
            self.assertIsNotNone(remaining, slot_id)
            arc = remaining.find("PartDraw/Arc")
            self.assertEqual("COUNTER_CLOCKWISE", arc.get("direction"))
            self.assertEqual("ROUND", arc.find("Stroke").get("cap"))
            self.assertIn("clamp(", arc.find("Transform[@target='endAngle']").get("value"))
            self.assertIn("< 1", remaining.find("Transform[@target='alpha']").get("value"))
            expression = remaining.find("Transform[@target='alpha']").get("value")
            self.assertIn('"TRUE" == "TRUE"' if slot_id == 6 else '"FALSE" == "TRUE"' if slot_id in (4, 5) else "progressBars", expression)

    def test_outer_gap_is_smaller_than_the_previous_orbit_ratio(self):
        for slot_id in range(4):
            ranged = self.face.find(f"Scene/ComplicationSlot[@slotId='{slot_id}']/Complication[@type='RANGED_VALUE']")
            track = next(part.find("Arc") for part in ranged.findall("PartDraw") if part.find("Arc") is not None)
            radius = float(track.get("width")) / 2
            thickness = float(track.find("Stroke").get("thickness"))
            gap = 19.5 * (thickness / radius) / (12 / 59) * 0.8
            expression = ranged.find(f".//Group[@name='progress-gap-{slot_id}']/PartDraw/Arc/Transform[@target='endAngle']").get("value")
            self.assertIn(f"{gap:.6f}", expression)

    def test_bottom_glucose_never_has_a_dot_and_always_keeps_round_gap(self):
        content = self.face.find("Scene//ComplicationSlot[@slotId='6']/Complication[@type='RANGED_VALUE']")
        self.assertIsNone(content.find("Group[@name='progress-marker-6']"))
        self.assertIn('"TRUE" == "TRUE"', content.find("Group[@name='progress-gap-6']/Transform").get("value"))
        self.assertIsNone(content.find(".//Group[@name='indicator-gradient-content']"))

    def test_editor_has_no_removed_keys_or_unresolved_configuration(self):
        import re
        configs = self.face.find('UserConfigurations')
        ids = {c.get('id') for c in configs if c.tag != 'Flavors'}
        removed = {'nightMode', 'dialTheme', 'minuteMarkers', 'showAmPm', 'unreadIndicator', 'aodStyle'}
        self.assertFalse(ids & removed)
        used = set(re.findall(r'\[CONFIGURATION\.([A-Za-z]+)', ET.tostring(self.face, encoding='unicode')))
        self.assertFalse(used - ids)
        for flavor in configs.findall('Flavors/Flavor'):
            for option in flavor.findall('Configuration'):
                self.assertIn(option.get('id'), ids)
        self.assertEqual(['0', '1'], [o.get('id') for o in configs.find("ListConfiguration[@id='indexStyle']")])

    def test_seconds_are_three_smooth_colors_and_off(self):
        hands = self.face.findall('.//SecondHand')
        self.assertEqual(9, len(hands))
        self.assertEqual({'#FFFFFFFF', '[CONFIGURATION.materialAccent.1]', '#FFFF4040'}, {h.get('tintColor') for h in hands})
        for hand in hands:
            self.assertIsNotNone(hand.find('Sweep'))
            self.assertIsNone(hand.find('Tick'))
            self.assertEqual('0', hand.find("Variant[@mode='AMBIENT']").get('value'))
        hub = self.face.find(".//Group[@name='hands-and-indicators']/Group[@name='fixed-center-hub']")
        self.assertEqual(['#FFFFFFFF', '#FF000000'], [f.get('color') for f in hub.findall('.//Fill')])

    def test_graph_has_no_cancelled_date_or_icon_and_stays_within_slot_limit(self):
        self.assertEqual(8, len(self.face.findall('Scene/ComplicationSlot')))
        self.assertIsNone(self.face.find(".//ComplicationSlot[@slotId='8']"))
        self.assertIsNone(self.face.find("UserConfigurations/ListConfiguration[@id='upperMode']"))
        for name in ('original-weekdays-date', 'date-icon-only', 'weekday-arc', 'graph-mode-only'):
            self.assertIsNone(self.face.find(f".//Group[@name='{name}']"))
        graph = self.face.find("Scene/ComplicationSlot[@slotId='7']/Complication[@type='LONG_TEXT']/Group[@name='graph-clipped-content']")
        self.assertIsNotNone(graph)

    def test_ambient_hides_everything_except_graph_glucose_and_time(self):
        for child in self.face.find('Scene'):
            if child.get('name') == 'hands-and-indicators' or child.tag == 'ComplicationSlot' and child.get('slotId') in {'6', '7'}:
                continue
            self.assertEqual('0', child.find("Variant[@mode='AMBIENT'][@target='alpha']").get('value'), child.attrib)



if __name__ == "__main__":
    unittest.main()
