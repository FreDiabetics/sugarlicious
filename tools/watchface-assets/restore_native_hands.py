"""Keep native hands outside complication clipping bounds."""
import xml.etree.ElementTree as E


def configure(root, family):
    scene = root.find("Scene")
    identity = "8" if family == "orbit" else "7"
    slot = scene.find(f"ComplicationSlot[@slotId='{identity}']")
    if slot is None:
        return
    content = slot.find("Complication[@type='LONG_TEXT']")
    if content is not None:
        wrapper = content.find("Group[@name='center-tap-native-hands']")
        if wrapper is not None:
            for hands in list(wrapper):
                if hands.tag == "Group":
                    wrapper.remove(hands)
                    scene.append(hands)
            content.remove(wrapper)
        content.set("type", "SMALL_IMAGE")
    slot.set("isCustomizable", "FALSE")
    slot.set("supportedTypes", "SMALL_IMAGE EMPTY")
    slot.find("DefaultProviderPolicy").set("primaryProviderType", "SMALL_IMAGE")
    slot.find("BoundingBox").attrib.update(x="91", y="91", width="330", height="97" if family == "apex" else "165")
    for policy in root.findall(f"UserConfigurations/Flavors/Flavor/ComplicationSlot[@slotId='{identity}']/DefaultProviderPolicy"):
        policy.set("primaryProviderType", "SMALL_IMAGE")
    for hands in list(scene.findall("Group")):
        if hands.get("name") in ("hands", "hands-and-indicators"):
            scene.remove(hands)
            scene.append(hands)
    # Samsung binds existing provider choices by runtime slot position.
    # ApeX has always placed the fixed graph before its seven visible slots.
    if family == "apex":
        scene.remove(slot)
        first = next((i for i, child in enumerate(scene) if child.tag == "ComplicationSlot"), len(scene))
        scene.insert(first, slot)
    # Share a numeric state through WFF Reference; hands stay native and outside
    # the graph slot. Slot ordering and clipping bounds remain unchanged.
    content = slot.find("Complication[@type='SMALL_IMAGE']")
    content.set("type", "LONG_TEXT")
    for image in content.findall(".//Image"):
        if image.get("resource") == "[COMPLICATION.SMALL_IMAGE]":
            image.set("resource", "[COMPLICATION.MONOCHROMATIC_IMAGE]")
    slot.set("supportedTypes", "LONG_TEXT EMPTY")
    slot.find("DefaultProviderPolicy").set("primaryProviderType", "LONG_TEXT")
    provider = "app.sugarlicious/app.aapswear.complications.Native" + ("Orbit" if family == "orbit" else "Apex") + "GraphComplication"
    slot.find("DefaultProviderPolicy").set("primaryProvider", provider)
    for policy in root.findall(f"UserConfigurations/Flavors/Flavor/ComplicationSlot[@slotId='{identity}']/DefaultProviderPolicy"):
        policy.set("primaryProviderType", "LONG_TEXT")
        policy.set("primaryProvider", provider)
    state = content.find("Group[@name='native-hands-opacity-state']")
    if state is None:
        state = E.SubElement(content, "Group", name="native-hands-opacity-state", x="91", y="91", width="1", height="1")
        E.SubElement(state, "Transform", target="alpha", value='[COMPLICATION.TEXT] == "dim" ? 77 : 255')
        E.SubElement(state, "Reference", name="nativeHandsOpacity", source="alpha", defaultValue="255")
        ink = E.SubElement(state, "PartDraw", x="0", y="0", width="1", height="1")
        E.SubElement(E.SubElement(ink, "Rectangle", x="0", y="0", width="1", height="1"), "Fill", color="#01000000")
    for hands in scene.findall("Group"):
        if hands.get("name") in ("hands", "hands-and-indicators"):
            transform = hands.find("Transform[@target='alpha']")
            if transform is None:
                transform = E.Element("Transform", target="alpha")
                hands.insert(0, transform)
            transform.set("value", "[REFERENCE.nativeHandsOpacity]")
    hotspot = scene.find("PartDraw[@name='native-hands-center-tap']")
    if hotspot is None:
        hotspot = E.SubElement(scene, "PartDraw", name="native-hands-center-tap", x="230", y="230", width="52", height="52")
        E.SubElement(hotspot, "Launch", target="app.sugarlicious/app.aapswear.complications." + ("Orbit" if family == "orbit" else "Apex") + "HandsOpacityActivity")
        E.SubElement(E.SubElement(hotspot, "Ellipse", x="0", y="0", width="52", height="52"), "Fill", color="#01000000")
        E.SubElement(hotspot, "Variant", mode="AMBIENT", target="alpha", value="0")
    scene.remove(hotspot)
    scene.append(hotspot)
