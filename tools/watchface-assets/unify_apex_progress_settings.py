"""Same eight progress choices and editor ordering as Orbit; bottom glucose never has a dot."""
import re
import xml.etree.ElementTree as E


def configure(root, strings):
    configs = root.find("UserConfigurations")
    # Bottom glucose always remains a filled arc with its separate round-ended remainder.
    bottom = root.find("Scene//ComplicationSlot[@slotId='6']/Complication[@type='RANGED_VALUE']")
    if bottom is not None:
        for parent in list(bottom.iter()):
            for child in list(parent):
                if child.get("name", "").startswith("progress-marker-"):
                    parent.remove(child)
        for element in bottom.iter():
            for key, value in list(element.attrib.items()):
                value = re.sub(r'\[CONFIGURATION.progressMarker\]\s*(==|!=)\s*"TRUE"', lambda m: '"FALSE" ' + m.group(1) + ' "TRUE"', value)
                value = re.sub(r'\[CONFIGURATION.progressGap\]\s*(==|!=)\s*"TRUE"', lambda m: '"TRUE" ' + m.group(1) + ' "TRUE"', value)
                element.set(key, value)
    expressions = {
        "progressMarker": '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in (4,5,6,7)) + ')',
        "progressGradient": '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in (1,3,5,7)) + ')',
        "upperRightMarkers": '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in (2,3,6,7)) + ')',
    }
    expressions["progressGap"] = f'!{expressions["progressMarker"]}'
    def rewrite(value):
        for key, expression in expressions.items():
            value = re.sub(r'\[CONFIGURATION\.' + key + r'\]\s*(==|!=)\s*"(TRUE|FALSE)"',
                           lambda m: expression if (m.group(1) == '==') == (m.group(2) == 'TRUE') else f'!{expression}', value)
        return value
    for element in root.iter():
        for attribute, value in list(element.attrib.items()):
            element.set(attribute, rewrite(value))
        if element.text:
            element.text = rewrite(element.text)
    # Only outer slots 0..3 have markers. Middle gauges always use a
    # filled progress arc and a separated rounded remainder, for every style.
    for identity in ("4", "5", "6"):
        content = root.find(f"Scene//ComplicationSlot[@slotId='{identity}']/Complication[@type='RANGED_VALUE']")
        if content is None:
            continue
        for parent in list(content.iter()):
            for child in list(parent):
                if child.get("name", "").startswith("progress-marker-"):
                    parent.remove(child)
        for element in content.iter():
            for key, value in list(element.attrib.items()):
                element.set(key, value.replace(expressions["progressMarker"], '("FALSE" == "TRUE")'))
            if element.text:
                element.text = element.text.replace(expressions["progressMarker"], '("FALSE" == "TRUE")')
    for item in list(configs):
        if item.get("id") in expressions:
            configs.remove(item)
    config = configs.find("ListConfiguration[@id='progressBars']")
    if config is None:
        config = E.SubElement(configs, "ListConfiguration", id="progressBars", displayName="progress_bars", defaultValue="7")
    config.clear()
    config.attrib.update(id="progressBars", displayName="progress_bars", defaultValue="7")
    def label(key, text):
        node = strings.find(f"string[@name='{key}']")
        if node is None:
            node = E.SubElement(strings, "string", name=key)
        node.text = text
    label("progress_bars", "Progress Bars")
    for i, title in enumerate(("Einfarbig", "Farbverlauf", "Segmente", "Segmente · Verlauf", "Einfarbig · Dot", "Farbverlauf · Dot", "Segmente · Dot", "Segmente · Verlauf · Dot")):
        key = f"progress_bars_{i}"
        label(key, title)
        E.SubElement(config, "ListOption", id=str(i), displayName=key)
    for flavor in configs.findall("Flavors/Flavor"):
        for item in list(flavor):
            if item.tag == "Configuration" and item.get("id") in expressions:
                flavor.remove(item)
    order = ["indexStyle", "handStyle", "secondStyle", "materialAccent", "materialSubTheme", "backgroundPattern", "progressBars", "glucoseColor"]
    for item in sorted([c for c in configs if c.get("id") in order], key=lambda c: order.index(c.get("id"))):
        configs.remove(item)
        configs.insert(order.index(item.get("id")), item)
