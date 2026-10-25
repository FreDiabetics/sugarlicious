"""Vigil reuses canonical hands and keeps rounded indices clear of provider ink."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FACE = ROOT / "watchfaces/sugarlicious-direct-to-watch"


class VigilHandsTest(unittest.TestCase):
    def setUp(self):
        self.face = ET.parse(FACE / "src/main/res/raw/watchface.xml").getroot()

    def test_provider_clock_keeps_full_rendering_and_center_tap_separate_with_eight_slots(self):
        scene = self.face.find("Scene")
        slots = scene.findall(".//ComplicationSlot")
        self.assertEqual(8, len(slots))
        self.assertTrue(all(slot.get("isCustomizable") == "FALSE" for slot in slots))
        hands = scene.find(".//ComplicationSlot[@slotId='7']")
        center = scene.find(".//ComplicationSlot[@slotId='8']")
        self.assertIs(center, list(scene)[-1])
        self.assertEqual(8, len(scene.findall("ComplicationSlot")))
        self.assertEqual(["0", "0", "450", "450"], [hands.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["0", "0", "450", "450"], [hands.find("BoundingBox").get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["200", "200", "50", "50"], [center.get(key) for key in ("x", "y", "width", "height")])
        self.assertEqual(["0", "0", "50", "50"], [center.find("BoundingOval").get(key) for key in ("x", "y", "width", "height")])
        self.assertIsNone(scene.find("AnalogClock"))
        self.assertEqual("ANALOG", self.face.find("Metadata[@key='CLOCK_TYPE']").get("value"))
        build = (ROOT / "complications/build.gradle.kts").read_text()
        for resource in ("hour_hand_transparent", "minute_hand_transparent", "second_hand_transparent"):
            self.assertIn('"' + resource + '.png"', build)
            self.assertTrue((ROOT / "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi" / (resource + ".png")).is_file())

    def test_digital_clock_and_hands_both_supply_active_and_ambient_images(self):
        self.assertIsNone(self.face.find("Scene/ComplicationSlot[@slotId='6']"))
        for slot_id in ("0", "1", "5"):
            slot = self.face.find(f".//ComplicationSlot[@slotId='{slot_id}']")
            images = slot.findall(".//PartImage/Image")
            self.assertEqual(["[COMPLICATION.SMALL_IMAGE]", "[COMPLICATION.SMALL_IMAGE_AMBIENT]"], [image.get("resource") for image in images])
            parts = slot.findall(".//PartImage")
            self.assertEqual("0", parts[0].find("Variant[@mode='AMBIENT']").get("value"))
            self.assertEqual("255", parts[1].find("Variant[@mode='AMBIENT']").get("value"))

    def test_header_and_graph_action_mirrors_restore_taps_above_hands(self):
        scene = self.face.find("Scene")
        slots = scene.findall("ComplicationSlot")
        self.assertEqual(["0", "1", "2", "5", "7", "9", "10", "8"], [slot.get("slotId") for slot in slots])
        for original_id, mirror_id in (("0", "9"), ("1", "10")):
            original = scene.find(f"ComplicationSlot[@slotId='{original_id}']")
            mirror = scene.find(f"ComplicationSlot[@slotId='{mirror_id}']")
            expected_action = "DirectToWatchCollectorActionComplication" if mirror_id == "9" else "DirectToWatchGraphActionComplication"
            self.assertTrue(mirror.find("DefaultProviderPolicy").get("primaryProvider").endswith(expected_action))
            self.assertEqual([original.get(key) for key in ("x", "y", "width", "height")], [mirror.get(key) for key in ("x", "y", "width", "height")])
            self.assertNotEqual("0", mirror.get("alpha"))
            self.assertEqual("0", mirror.find("Complication/PartImage").get("alpha"))
        self.assertIsNone(scene.find("ComplicationSlot[@slotId='3']"))
        self.assertIsNone(scene.find("ComplicationSlot[@slotId='4']"))

    def test_native_visibility_switch_hides_hands_and_center_content(self):
        option = self.face.find("UserConfigurations/BooleanConfiguration[@id='showHands']")
        self.assertEqual("FALSE", option.get("defaultValue"))
        for slot_id in ("7", "8"):
            condition = self.face.find(f"Scene/ComplicationSlot[@slotId='{slot_id}']/Complication/Group/Condition")
            self.assertEqual('[CONFIGURATION.showHands] == "TRUE"', condition.find("Expressions/Expression").text)
            self.assertIsNone(condition.find("Default"))
        info = ET.parse(FACE / "src/main/res/xml/watch_face_info.xml").getroot()
        self.assertEqual("true", info.find("Editable").get("value"))

    def test_digital_time_can_be_hidden_in_both_display_modes(self):
        option = self.face.find("UserConfigurations/BooleanConfiguration[@id='showDigitalTime']")
        self.assertEqual("TRUE", option.get("defaultValue"))
        condition = self.face.find("Scene/ComplicationSlot[@slotId='5']/Complication/Condition")
        self.assertEqual('[CONFIGURATION.showDigitalTime] == "TRUE"', condition.find("Expressions/Expression").text)
        self.assertEqual(2, len(condition.findall("Compare/Group/PartImage")))
        self.assertIsNone(condition.find("Default"))

    def test_minute_markers_have_an_independent_visibility_switch(self):
        self.assertIsNotNone(self.face.find("UserConfigurations/BooleanConfiguration[@id='showMinuteMarkers']"))
        self.assertEqual('[CONFIGURATION.showMinuteMarkers] == "TRUE"', self.face.find(".//Group[@name='vigil-canonical-markers']/Condition/Expressions/Expression").text)

    def test_markers_preserve_user_artwork_and_follow_hand_visibility(self):
        markers = self.face.find("Scene/Group[@name='vigil-perimeter-markers']")
        self.assertEqual('[CONFIGURATION.showHands] == "TRUE"', markers.find('Condition/Expressions/Expression').text)
        self.assertIsNone(markers.find('.//PartDraw'))
        images = markers.findall('.//PartImage')
        self.assertEqual(['vigil_minutenmarkers', 'vigil_hourmarkers'], [image.find('Image').get('resource') for image in images])
        for image in images:
            self.assertEqual(['0', '0', '450', '450'], [image.get(key) for key in ('x', 'y', 'width', 'height')])
            svg = ET.parse(ROOT / 'design-assets/vigil' / (image.find('Image').get('resource') + '.svg')).getroot()
            self.assertEqual('0 0 450 450', svg.get('viewBox'))

    def test_native_seconds_share_center_opacity_mask_and_offer_red_or_medium_gray(self):
        color = self.face.find("UserConfigurations/ColorConfiguration[@id='secondColor']")
        self.assertEqual('red', color.get('defaultValue'))
        self.assertEqual(['#FFFF3B30', '#FF9E9E9E'], [option.get('colors') for option in color])
        group = self.face.find(".//Group[@name='vigil-native-hands']")
        self.assertEqual('160', group.find("Variant[@mode='AMBIENT']").get('value'))
        self.assertEqual('[COMPLICATION.SMALL_IMAGE]', group.find("PartImage[@renderMode='MASK']/Image").get('resource'))
        hands = group.findall('AnalogClock/SecondHand')
        self.assertEqual(2, len(hands))
        self.assertEqual('[CONFIGURATION.secondColor.0]', hands[-1].get('tintColor'))
        self.assertTrue(all(hand.find('Sweep') is not None for hand in hands))
        self.assertTrue(all(clock.find("Variant[@mode='AMBIENT']").get('value') == '0' for clock in group.findall('AnalogClock') if clock.find('SecondHand') is not None))


if __name__ == "__main__":
    unittest.main()
