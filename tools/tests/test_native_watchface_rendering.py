"""Native analog hands must not inherit complication clipping."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E
ROOT = Path(__file__).resolve().parents[2]
class NativeHandsRenderingTest(unittest.TestCase):
    def test_native_hands_are_full_scene_and_gradients_are_stroked(self):
        for family, identity in (("sugarlicious-analog", "7"), ("sugarlicious-orbit", "8")):
            root=E.parse(ROOT / f"watchfaces/{family}/src/main/res/raw/watchface.xml").getroot()
            self.assertEqual(len(root.findall("Scene//ComplicationSlot")),8)
            slot=root.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
            self.assertEqual(slot.find("DefaultProviderPolicy").get("primaryProviderType"),"LONG_TEXT")
            self.assertIsNotNone(root.find("Scene/Group//HourHand"))
            self.assertIsNone(slot.find(".//HourHand"))
            self.assertEqual([slot.find("BoundingBox").get(k) for k in ("x","y","width","height")],["91","91","330","97" if family == "sugarlicious-analog" else "165"])
            for fixed in root.findall(".//Group[@name='fixed-gradient-content']"):
                self.assertFalse(fixed.findall(".//Rectangle"))
                self.assertTrue(fixed.findall("PartDraw/Arc/Stroke/SweepGradient"))
                for arc in fixed.findall("PartDraw/Arc"):
                    self.assertEqual(arc.find("Stroke").get("cap"),"ROUND")

    def test_apex_original_slot_order_and_middle_gauges_without_markers(self):
        root=E.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()
        self.assertEqual([s.get("slotId") for s in root.findall("Scene/ComplicationSlot")],["7","0","1","2","3","4","5","6"])
        for identity in ("4","5","6"):
            slot=root.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
            self.assertFalse([g for g in slot.findall(".//Group") if g.get("name","").startswith("progress-marker-")])
        root=E.parse(ROOT / "watchfaces/sugarlicious-orbit/src/main/res/raw/watchface.xml").getroot()
        arc=root.find(".//Group[@name='round-gradient']/Group[@name='fixed-gradient-content']/PartDraw/Arc")
        self.assertEqual(arc.get("direction"),"CLOCKWISE")
        self.assertEqual(float(arc.get("startAngle")),8)

    def test_round_caps_are_explicit_and_apex_has_no_arc_selector(self):
        for family in ("sugarlicious-analog", "sugarlicious-orbit"):
            root=E.parse(ROOT / f"watchfaces/{family}/src/main/res/raw/watchface.xml").getroot()
            for fixed in root.findall(".//Group[@name='fixed-gradient-content']"):
                for endpoint in ("start","end"):
                    self.assertIsNotNone(fixed.find(f"PartDraw[@name='{endpoint}-rounded-cap-base']/Ellipse/Fill"))
                    self.assertIsNotNone(fixed.find(f"PartDraw[@name='{endpoint}-rounded-cap-light']/Ellipse/Fill"))
            if family == "sugarlicious-analog":
                self.assertEqual(root.find("Scene/ComplicationSlot[@slotId='7']").get("isCustomizable"),"FALSE")
                configs=root.find("UserConfigurations")
                self.assertIsNone(configs.find("ListConfiguration[@id='arcContent']"))
                self.assertIsNone(configs.find("ListConfiguration[@id='upperMode']"))

    def test_lower_left_short_icon_preserves_center_and_live_range_glyphs_keep_padding(self):
        root=E.parse(ROOT / "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").getroot()
        short=root.find("Scene/ComplicationSlot[@slotId='3']/Complication[@type='SHORT_TEXT']/PartImage")
        self.assertEqual((70,320),(float(short.find("Transform[@target='x']").get('value'))+float(short.get('width'))/2,float(short.find("Transform[@target='y']").get('value'))+float(short.get('height'))/2))
        for identity in ('0','1','2','3'):
            slot=root.find(f"Scene/ComplicationSlot[@slotId='{identity}']")
            marker=slot.find(f".//Group[@name='progress-marker-{identity}']")
            self.assertEqual('5.640898',marker.find('.//Stroke').get('thickness'))
            image=slot.find("Complication[@type='RANGED_VALUE']/PartImage")
            self.assertEqual(22,float(image.get('width')))
            value=image.find("Transform[@target='width']").get('value')
            self.assertIn('[COMPLICATION.RANGED_VALUE_MAX] == 260',value)
            self.assertIn('52.0000',value)
            self.assertNotIn('[COMPLICATION.RANGED_VALUE_MAX] == 10',value)

    def test_center_tap_uses_reference_without_moving_or_masking_hands(self):
        for family in ("sugarlicious-analog","sugarlicious-orbit"):
            root=E.parse(ROOT / f"watchfaces/{family}/src/main/res/raw/watchface.xml").getroot()
            hotspot=root.find("Scene/PartDraw[@name='native-hands-center-tap']")
            self.assertEqual([hotspot.get(k) for k in ("x","y","width","height")],["230","230","52","52"])
            self.assertIsNotNone(hotspot.find("Launch"))
            reference=root.find("Scene/ComplicationSlot/Complication/Group[@name='native-hands-opacity-state']/Reference")
            self.assertEqual(reference.get("name"),"nativeHandsOpacity")
            self.assertEqual(reference.get("source"),"alpha")
            hands=next(g for g in root.findall("Scene/Group") if g.get("name") in ("hands","hands-and-indicators"))
            self.assertEqual(hands.find("Transform[@target='alpha']").get("value"),"[REFERENCE.nativeHandsOpacity]")
            self.assertIsNotNone(hands.find(".//HourHand"))
            self.assertIsNone(hands.find(".//*[@renderMode='MASK']"))

    def test_native_state_providers_are_hidden_and_public_graph_stays_small_image(self):
        android="{http://schemas.android.com/apk/res/android}"
        root=E.parse(ROOT / "app-wear/src/main/AndroidManifest.xml").getroot()
        services={s.get(android+"name"):s for s in root.findall("application/service")}
        for family in ("Apex","Orbit"):
            service=services["app.aapswear.complications.Native"+family+"GraphComplication"]
            meta={m.get(android+"name"):m.get(android+"value") for m in service.findall("meta-data")}
            self.assertEqual(meta["android.support.wearable.complications.HIDDEN"],"true")
            self.assertEqual(meta["android.support.wearable.complications.SUPPORTED_TYPES"],"LONG_TEXT")
        for name in ("GlucoseGraphComplication","OrbitGlucoseGraphComplication"):
            service=services["app.aapswear.complications."+name]
            meta={m.get(android+"name"):m.get(android+"value") for m in service.findall("meta-data")}
            self.assertEqual(meta["android.support.wearable.complications.SUPPORTED_TYPES"],"SMALL_IMAGE")
