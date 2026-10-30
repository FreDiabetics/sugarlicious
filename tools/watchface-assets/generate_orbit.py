"""Generate Orbit's first-party WFF resources from measured reference geometry.

Only explicitly requested vendor weather artwork is imported with provenance;
no executable reference code or layout is consumed. Existing Sugarlicious colors
and ApeX hand resources are shared at build time instead of forked.
"""
import argparse
import csv
import json
import math
import runpy
from pathlib import Path
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
SIZE = 512
ARC_CONTENT_INSET = 8
ARC_CAPTION_RADIUS = 125.5 * 512 / 450
WHITE = "#FFE4E4E4"
P = "[CONFIGURATION.primary"
S = "[CONFIGURATION.secondary"
T = "[CONFIGURATION.tertiary"
FRACTION = "([COMPLICATION.RANGED_VALUE_MAX] <= [COMPLICATION.RANGED_VALUE_MIN] ? 0 : clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1))"
VALID_RANGE = "[COMPLICATION.RANGED_VALUE_MAX] > [COMPLICATION.RANGED_VALUE_MIN]"



def node(parent, tag, **attributes):
    return E.SubElement(parent, tag, {key: str(value) for key, value in attributes.items()})


def transform(parent, target, value):
    return node(parent, "Transform", target=target, value=value)


def group(parent, name, show=None, **attributes):
    result = node(parent, "Group", name=name, x=0, y=0, width=SIZE, height=SIZE, **attributes)
    if show:
        transform(result, "alpha", f"{show} ? 255 : 0")
    return result


def ambient(parent, value="[CONFIGURATION.aodStyle] == \"0\" ? 80 : 0"):
    node(parent, "Variant", mode="AMBIENT", target="alpha", value=value)


def drawing(parent, name=None):
    attrs = dict(x=0, y=0, width=SIZE, height=SIZE)
    if name:
        attrs["name"] = name
    return node(parent, "PartDraw", **attrs)


def arc(parent, cx, cy, radius, start, end, color, thickness, alpha=None, cap="ROUND"):
    part = drawing(parent)
    if alpha is not None:
        part.set("alpha", str(alpha))
    path = node(part, "Arc", centerX=cx, centerY=cy, width=2 * radius, height=2 * radius,
                startAngle=start, endAngle=end, direction="COUNTER_CLOCKWISE" if end < start else "CLOCKWISE")
    node(path, "Stroke", color=color, thickness=thickness, cap=cap)
    return path


def text(parent, expression, x, y, width, height, size, color, align="CENTER"):
    part = node(parent, "PartText", x=x, y=y, width=width, height=height)
    body = node(part, "Text", align=align)
    font = node(body, "Font", family="inter_tight", size=size, weight="SEMI_BOLD", color=color)
    template = node(font, "Template")
    template.text = "%s"
    node(template, "Parameter", expression=expression)
    return part


def circular(parent, expression, radius, start, end, size, color, align="CENTER", clockwise=True, family="inter_tight"):
    # Keep an unwrapped positive interval across north; native renderers differ
    # when a clockwise caption starts with a negative angle.
    start, end = start % 360, end % 360
    part = node(parent, "PartText", x=0, y=0, width=SIZE, height=SIZE)
    body = node(part, "TextCircular", centerX=256, centerY=256, width=radius * 2,
                height=radius * 2, startAngle=start, endAngle=end, direction="CLOCKWISE" if clockwise else "COUNTER_CLOCKWISE",
                align=align, ellipsis="TRUE")
    font = node(body, "Font", family=family, size=size, weight="SEMI_BOLD", color=color)
    template = node(font, "Template")
    template.text = "%s"
    node(template, "Parameter", expression=expression)
    return part


def icon(parent, expression, cx, cy, size, color, angle=0):
    size = round(size)
    part = node(parent, "PartImage", x=round(cx-size/2), y=round(cy-size/2), width=size, height=size, tintColor=color, angle=angle)
    node(part, "Image", resource=expression)


def provider_caption(parent, radius, start=173, end=99):
    """Choose fields from one provider, never create a separate icon slot."""
    condition = node(parent, "Condition")
    expressions = node(condition, "Expressions")
    node(expressions, "Expression", name="hasTitle").text = '[COMPLICATION.TITLE] != null && textLength([COMPLICATION.TITLE]) > 0'
    node(expressions, "Expression", name="hasIcon").text = '[COMPLICATION.MONOCHROMATIC_IMAGE] != null'
    title = circular(node(condition, "Compare", expression="hasTitle"), "[COMPLICATION.TEXT]", radius, start, end, 30, P+".0]", clockwise=False)
    template = title.find(".//Template")
    template.text = "%s %s"
    node(template, "Parameter", expression="[COMPLICATION.TITLE]")
    inline = circular(node(condition, "Compare", expression="hasIcon"), "[COMPLICATION.TEXT]", radius, start, end, 30, P+".0]", clockwise=False)
    font = inline.find(".//Font")
    font.insert(0, E.Element("InlineImage", {"resource": "inline_image_empty", "source": "COMPLICATION.MONOCHROMATIC_IMAGE",
                                          "width": "27", "height": "27", "color": P+".0]"}))
    inline.find(".//Template").text = " %s"
    circular(node(condition, "Default"), "[COMPLICATION.TEXT]", radius, start, end, 30, P+".0]", clockwise=False)


def marker_geometry(cx, cy, radius, start, end, value, minimum, maximum, thickness):
    if not all(math.isfinite(v) for v in (value, minimum, maximum)) or maximum <= minimum:
        return None
    fraction = max(0, min(1, (value-minimum)/(maximum-minimum)))
    angle = math.radians(start + fraction * (end-start))
    return cx + radius * math.sin(angle), cy - radius * math.cos(angle), thickness, thickness * .3


def ranged(parent, name, cx, cy, radius, start, end, thickness, palette, segmented=False, gap=0,
           fraction=FRACTION, valid=VALID_RANGE):
    """One shared bar implementation: background track, fill/gradient, hollow marker."""
    track = group(parent, f"{name}-track", '[CONFIGURATION.progressMarker] != "TRUE"')
    if gap:
        transform(track, "alpha", f'[CONFIGURATION.progressMarker] != "TRUE" && {valid} && {fraction} < 1 ? 255 : 0')
        remaining = arc(track, cx, cy, radius, end, start, "#FF505050", thickness)
        track.set("tintColor", palette+".1]")
        transform(remaining, "endAngle", f"clamp({start} + {gap} + {end-start} * {fraction}, {start}, {end}) - {gap} * ({fraction} == 0)")
    else:
        arc(track, cx, cy, radius, start, end, palette + ".1]", thickness, alpha=60)
    fill = group(parent, f"{name}-fill", f'{valid} && ({fraction} > 0 || [CONFIGURATION.progressMarker] == "TRUE")')
    solid = group(fill, f"{name}-solid", '[CONFIGURATION.progressStyle] == "0"' if segmented else '[CONFIGURATION.progressStyle] == "0" || [CONFIGURATION.progressStyle] == "2"')
    path = arc(solid, cx, cy, radius, start, start, palette + ".2]", thickness)
    path.set("direction", "COUNTER_CLOCKWISE" if end < start else "CLOCKWISE")
    transform(path, "endAngle", f'[CONFIGURATION.progressMarker] == "TRUE" ? {end} : {start} + {fraction} * {end-start}')
    gradient = group(fill, f"{name}-gradient", '[CONFIGURATION.progressStyle] == "1"' if segmented else '[CONFIGURATION.progressStyle] == "1" || [CONFIGURATION.progressStyle] == "3"')
    base = arc(gradient, cx, cy, radius, start, start, palette+".2]", thickness)
    base.set("direction", "COUNTER_CLOCKWISE" if end < start else "CLOCKWISE")
    transform(base, "endAngle", f'[CONFIGURATION.progressMarker] == "TRUE" ? {end} : {start} + {fraction} * {end-start}')
    path = arc(gradient, cx, cy, radius, start, start, "#FFFFFFFF", thickness)
    gradient[-1].set("tintColor", palette+".0]")
    path.set("direction", "COUNTER_CLOCKWISE" if end < start else "CLOCKWISE")
    transform(path, "endAngle", f'[CONFIGURATION.progressMarker] == "TRUE" ? {end} : {start} + {fraction} * {end-start}')
    edge = -3 if end < start else 3
    node(path.find("Stroke"), "SweepGradient", centerX=cx, centerY=cy, startAngle=(start-edge) % 360,
         endAngle=(end+edge) % 360, colors="#FFFFFFFF #00FFFFFF", positions="0 1", direction="COUNTER_CLOCKWISE" if end < start else "CLOCKWISE")
    marker = group(parent, f"{name}-marker", f'[CONFIGURATION.progressMarker] == "TRUE" && {valid}')
    point = drawing(marker)
    contour = thickness * .3  # Thin contour; the open inner diameter remains equal to the track thickness.
    outer = thickness + contour  # Centered stroke preserves a bar-sized open inner diameter.
    circle = node(point, "Ellipse", x=cx-outer/2, y=cy-radius-outer/2, width=outer, height=outer)
    angle = f"(({start} + {fraction} * {end-start}) * 0.017453292519943295)"
    transform(circle, "x", f"{cx} + {radius} * sin({angle}) - {outer/2}")
    transform(circle, "y", f"{cy} - {radius} * cos({angle}) - {outer/2}")
    node(circle, "Stroke", color="#FF000000", thickness=contour)
    if name in ("round", "round-goal"):
        # The lower round complication always remains a conventional filled gauge.
        parent.remove(marker)
        for element in parent.iter("Transform"):
            value = element.get("value", "")
            element.set("value", value.replace('[CONFIGURATION.progressMarker]', '"FALSE"'))
    if segmented:
        # The reference has a segmented FILL, not pale ticks painted over a
        # continuous colored arc. Its current segment extends inward, never
        # outside the outer radius. Preserve the underlying dark rest track.
        step = f'([CONFIGURATION.progressMarker] == "TRUE" ? 11 : floor(clamp({fraction}, 0, 0.99) * 12))'
        marks = group(parent, f"{name}-segments", f'({valid}) && ({fraction} > 0 || [CONFIGURATION.progressMarker] == "TRUE") && ([CONFIGURATION.progressStyle] == "2" || [CONFIGURATION.progressStyle] == "3")')
        mask = group(marks, "segment-mask", renderMode="MASK")
        scale = 512/450
        for index in range(13):
            part = drawing(mask, "segment-current" if index == 12 else f"segment-{index}")
            part.set("angle", str(20+5*index if index < 12 else 20))
            if index == 12:
                transform(part, "angle", f"20 + 5 * {step}")
            node(node(part, "RoundRectangle", x=cx-2.5*scale, y=cy-199.5*scale,
                      width=5*scale, height=(24 if index in (11, 12) else 15)*scale,
                      cornerRadiusX=2.5*scale, cornerRadiusY=2.5*scale), "Fill", color="#FFFFFFFF")
        arc(marks, cx, cy, radius, start, end, palette+".2]", 40*scale, cap="BUTT")
        paint = marks[-1].find("Arc")
        transform(paint, "endAngle", f"23 + 5 * {step}")
        light = group(marks, "segment-gradient", '[CONFIGURATION.progressStyle] == "3"')
        paint = arc(light, cx, cy, radius, start, end, "#FFFFFFFF", 40*scale, cap="BUTT")
        light[-1].set("tintColor", palette+".0]")
        transform(paint, "endAngle", f"23 + 5 * {step}")
        node(paint.find("Stroke"), "SweepGradient", centerX=cx, centerY=cy,
             startAngle=16, endAngle=79, colors="#FFFFFFFF #00FFFFFF", positions="0 1")


def slot(parent, identity, label, types, provider, bounds, curved=False):
    element = node(parent, "ComplicationSlot", slotId=identity, displayName=label,
                   isCustomizable="TRUE", supportedTypes=types+" EMPTY", x=0, y=0, width=SIZE, height=SIZE)
    ambient(element)
    node(element, "DefaultProviderPolicy", primaryProvider=provider,
         primaryProviderType=types.split()[0], defaultSystemProvider="EMPTY", defaultSystemProviderType="EMPTY")
    if not curved:
        node(element, "BoundingBox", x=bounds[0], y=bounds[1], width=bounds[2], height=bounds[3])
    else:
        radius, start, end, thickness = bounds
        outer = radius + 17.07 / 2
        inner = 187.73 - 32 / 2
        node(element, "BoundingArc", centerX=256, centerY=256, width=outer*2, height=outer*2,
             startAngle=start, endAngle=end, thickness=outer-inner, direction="CLOCKWISE", isRoundEdge="FALSE")
    node(element, "Complication", type="EMPTY")
    return element


def weather_symbol(parent, cx, cy, token, pack, angle):
    """Use the reference's 36px artwork box and rotated 80x145 spoke geometry."""
    manifest = json.loads((ROOT / "watchfaces/sugarlicious-orbit/reference/weather/manifest.json").read_text(encoding="utf-8"))
    vendor = ("samsung", "google")[pack]
    art = group(parent, f"weather-art-{pack}", angle=angle)
    for period in ("day", "night"):
        day = f"[{token}.IS_DAY]" if period == "day" else f"![{token}.IS_DAY]"
        for condition, resource in manifest["packs"][vendor + "_" + period].items():
            owner = group(art, f"weather-{pack}-{period}-{condition}-{cx}", f"({day}) && [{token}.CONDITION] == {condition}")
            asset = manifest["assets"][resource]
            width, height = asset["canvas"]
            scale = (36 * 512 / 450) / max(width, height)
            image = node(owner, "PartImage", x=round(cx-width/2*scale), y=round(cy-height/2*scale),
                         width=round(width*scale), height=round(height*scale))
            node(image, "Image", resource=resource)
    unknown = group(parent, f"weather-{pack}-unknown-{cx}", f"[{token}.CONDITION] < 1 || [{token}.CONDITION] > 15")
    circular(unknown, '"—"', 84*512/450-ARC_CONTENT_INSET, angle-14, angle+14, 23, P+".0]", family="SYNC_TO_DEVICE")


def upper_arc(parent):
    upper = group(parent, "upper-arc", '[CONFIGURATION.upperMode] == "0"')
    ambient(upper)
    full_date = group(upper, "full-date", '[CONFIGURATION.arcWidget] == "0"')
    headline = circular(full_date, 'icuText([IS_24_HOUR_MODE] ? "EEEE HH:mm" : "EEEE h:mm a", [UTC_TIMESTAMP])', 142.79, 280, 440, 30, P+".1]")
    headline.find(".//Font").set("weight", "BOLD")
    circular(full_date, 'icuText("d. MMMM", [UTC_TIMESTAMP])', 96.71, 280, 440, 30, "#FF999999")
    button = node(full_date, "Group", name="date-action", x=91, y=91, width=330, height=165)
    node(button, "Launch", target="CALENDAR")

    weather = group(upper, "weather", '[CONFIGURATION.arcWidget] == "1"')
    for action, package in enumerate(("com.samsung.android.watch.weather", "com.google.android.wearable.weather")):
        button = node(weather, "Group", name=f"weather-action-{action}", x=91, y=91, width=330, height=165)
        transform(button, "alpha", f'[CONFIGURATION.weatherLaunch] == "{action}" ? 255 : 0')
        node(button, "Launch", target=package)
    for offset, degrees in enumerate((-70, -35, 0, 35, 70)):
        token = "WEATHER" if offset == 0 else f"WEATHER.HOURS.{offset}"
        station = group(weather, f"forecast-{offset}")
        hours = group(station, "forecast-time", '[SECOND] % 10 <= 4', angle=degrees)
        hour = f"(([HOUR_0_23] + {offset}) % 24)"
        label = circular(hours, hour, ARC_CAPTION_RADIUS, 343, 17, 24*512/450, "#FFB3B3B3", family="SYNC_TO_DEVICE")
        transform(label, "alpha", '[IS_24_HOUR_MODE] ? 255 : 0')
        label.find(".//Template").text = "%s:00"
        label = circular(hours, f"{hour} % 12 == 0 ? 12 : {hour} % 12", ARC_CAPTION_RADIUS, 345, 15, 24*512/450, "#FFB3B3B3", family="SYNC_TO_DEVICE")
        transform(label, "alpha", '[IS_24_HOUR_MODE] ? 0 : 255')
        label.find(".//Template").text = "%s%s"
        node(label.find(".//Template"), "Parameter", expression=f'{hour} < 12 ? "AM" : "PM"')
        condition = node(station, "Condition")
        expressions = node(condition, "Expressions")
        node(expressions, "Expression", name=f"weather{offset}").text = f"[WEATHER.IS_AVAILABLE] && ![WEATHER.IS_ERROR] && [{token}.IS_AVAILABLE]" if offset else "[WEATHER.IS_AVAILABLE] && ![WEATHER.IS_ERROR]"
        available = node(condition, "Compare", expression=f"weather{offset}")
        for pack in range(2):
            glyph = group(available, f"weather-pack-{pack}-{offset}", f'[CONFIGURATION.weatherSymbols] == "{pack}"')
            weather_symbol(glyph, 256, 140*512/450, token, pack, degrees)
        temperature = group(available, "forecast-temperature", '[SECOND] % 10 > 4', angle=degrees)
        label = circular(temperature, f'round([{token}.TEMPERATURE])', ARC_CAPTION_RADIUS, 345, 15, 24*512/450, "#FFB3B3B3", family="SYNC_TO_DEVICE")
        label.find(".//Template").text = "%s°"
        unavailable = group(node(condition, "Default"), "forecast-unavailable", '[SECOND] % 10 > 4', angle=degrees)
        circular(unavailable, '"—"', ARC_CAPTION_RADIUS, 345, 15, 24*512/450, "#FF999999", family="SYNC_TO_DEVICE")

    moon = group(upper, "moon", '[CONFIGURATION.arcWidget] == "2"')
    button = node(moon, "Group", name="moon-action", x=91, y=91, width=330, height=165)
    node(button, "Launch", target="CALENDAR")
    for offset, degrees in enumerate((-70, -35, 0, 35, 70)):
        caption = group(moon, f"moon-caption-{offset}", angle=degrees)
        circular(caption, f'icuText("EEE", [UTC_TIMESTAMP] + {offset*86400000})',
                 ARC_CAPTION_RADIUS, 345, 375, 24*512/450, "#FFB3B3B3", family="SYNC_TO_DEVICE")
        # Original 34px moon, centered at 225/140 in its 450px dial.
        cx, cy = 256, 140*512/450
        radius = 14*512/450
        phase = f"(([MOON_PHASE_POSITION] + {offset}) % 28)"
        circle = group(moon, f"moon-day-{offset}", angle=degrees)
        mask = drawing(circle)
        mask.set("renderMode", "MASK")
        node(node(mask, "Ellipse", x=cx-radius, y=cy-radius, width=2*radius, height=2*radius), "Fill", color="#FFFFFFFF")
        node(node(drawing(circle), "Ellipse", x=cx-radius, y=cy-radius, width=2*radius, height=2*radius), "Fill", color="#FFF2F2F1")
        shadow = drawing(circle)
        half = node(shadow, "Rectangle", x=cx-radius, y=cy-radius, width=radius, height=2*radius)
        transform(half, "x", f"{phase} < 14 ? {cx-radius} : {cx}")
        node(half, "Fill", color="#FF242424")
        terminator = node(drawing(circle), "Ellipse", x=cx-radius, y=cy-radius, width=2*radius, height=2*radius)
        width = f"{2*radius} * abs(cos({phase} * 0.224399475256414))"
        transform(terminator, "width", width)
        transform(terminator, "x", f"{cx} - ({width}) / 2")
        fill = node(terminator, "Fill", color="#FF242424")
        transform(fill, "color", f"{phase} >= 7 && {phase} < 21 ? #FFF2F2F1 : #FF242424")
    return upper


def generate(output):
    output = Path(output)
    strings = E.Element("resources")

    def label(key, value):
        e = node(strings, "string", name=key)
        e.text = value
        return key

    root = E.Element("WatchFace", width="512", height="512")
    node(root, "Metadata", key="CLOCK_TYPE", value="ANALOG")
    node(root, "Metadata", key="PREVIEW_TIME", value="10:08:32")
    configs = node(root, "UserConfigurations")

    def choices(identity, title, options, default="0"):
        c = node(configs, "ListConfiguration", id=identity, displayName=label(identity, title), defaultValue=default)
        for index, title in enumerate(options):
            node(c, "ListOption", id=index, displayName=label(f"{identity}_{index}", title))

    def switch(identity, title, default=False):
        node(configs, "BooleanConfiguration", id=identity, displayName=label(identity, title), defaultValue="TRUE" if default else "FALSE")

    # Numeric reference measurements only, not imported artwork or executable XML.
    with Path(__file__).with_name("orbit_palettes.csv").open(encoding="utf-8", newline="") as stream:
        palettes = list(csv.DictReader(stream))
    for identity, title, default in (("primary", "Zifferblatt", "70"), ("secondary", "Komplikationen", "70"), ("tertiary", "Akzente", "0")):
        c = node(configs, "ColorConfiguration", id=identity, displayName=label(identity, title), defaultValue=default)
        for entry in palettes:
            node(c, "ColorOption", id=entry["id"], displayName=label(f'{identity}_{entry["id"]}', entry["name"]),
                 colors=" ".join("#FF"+entry[tone] for tone in ("light", "medium", "vivid")))
    # New key deliberately retires persisted reference-hand IDs without resetting themes.
    choices("apexHands", "Zeiger", ["ApeX · Standard", "ApeX · Transparent", "ApeX · Schwarz"])
    for index, option in enumerate(configs.find("ListConfiguration[@id='apexHands']")):
        option.set("icon", f"orbit_hand_preview_{index + 15}")
    choices("secondSweep", "Sekundenzeiger", ["Weiß", "Getönt", "Rot"], default="1")
    choices("dialIndex", "Index", ["Arabisch", "Römisch"])
    choices("dialInk", "Indexfarbe", ["Weiß", "Theme"])
    choices("arcContent", "Arc", ["Datum", "Wetter", "Mond", "Graph", "TIR"], default="3")
    choices("weatherSymbols", "Wettersymbole", ["Samsung Weather", "Google Weather"])
    choices("weatherLaunch", "Wetter-App", ["Samsung Weather", "Google Weather"])
    weather_art = json.loads((ROOT / "watchfaces/sugarlicious-orbit/reference/weather/manifest.json").read_text(encoding="utf-8"))
    for key in ("weatherSymbols", "weatherLaunch"):
        for pack, option in enumerate(configs.find(f"ListConfiguration[@id='{key}']")):
            option.set("icon", weather_art["packs"][("samsung", "google")[pack] + "_day"]["8"])
    choices("aodStyle", "AOD", ["Gedimmt", "Ziffern und Zeiger", "Nur Zeiger"])
    choices("progressBars", "Progress Bars", ["Einfarbig", "Farbverlauf", "Segmente", "Segmente · Verlauf", "Einfarbig · Dot", "Farbverlauf · Dot", "Segmente · Dot", "Segmente · Verlauf · Dot"], default="7")
    choices("minuteStyle", "Minuten", ["Rund · Stunden", "Kantig · Stunden", "Rund · Minuten", "Kantig · Minuten"], default="2")
    switch("batteryIndicator", "Akkustand", True)
    switch("backgroundGlow", "Hintergrund")
    switch("glucoseColor", "Glukosefarbe", True)
    flavors = node(configs, "Flavors", defaultValue="0")
    for index, (primary, secondary, tertiary, hands, face_index, arc_mode) in enumerate((
        ("71", "59", "65", "0", "0", "1"),
        ("43", "34", "54", "13", "1", "2"),
        ("23", "25", "30", "12", "0", "0"),
        ("59", "40", "45", "11", "1", "1"),
        ("71", "59", "65", "10", "2", "2"),
        ("22", "21", "1", "9", "0", "0"),
    )):
        flavor = node(flavors, "Flavor", id=index, displayName=label(f"preset_{index}", f"Design {index+1}"))
        for key, value in (("primary", primary), ("secondary", secondary), ("tertiary", tertiary), ("apexHands", str(index % 3)), ("dialIndex", str(int(face_index) % 2)), ("arcContent", arc_mode)):
            node(flavor, "Configuration", id=key, optionId=value)

    scene = node(root, "Scene", backgroundColor="#FF000000")
    face = group(scene, "dial")
    glow = group(face, "background", '[CONFIGURATION.backgroundGlow] == "TRUE"')
    # Ambient alpha overrides the active visibility transform; a dimming value
    # would resurrect even a disabled full-dial glow whenever AOD begins.
    ambient(glow, "0")
    node(node(drawing(glow), "Ellipse", x=0, y=0, width=512, height=512), "Fill", color=P+".2]")
    glow.set("alpha", "96")
    glow.find("Transform").set("value", '[CONFIGURATION.backgroundGlow] == "TRUE" ? 96 : 0')
    # Concentric reference panels, only the open lower-left quadrant is reserved for the round slot.
    panels = group(face, "inner-panels", '[CONFIGURATION.upperMode] == "0"')
    ambient(panels, '[CONFIGURATION.upperMode] == "0" && [CONFIGURATION.aodStyle] == "0" ? 80 : 0')
    panels.set("tintColor", P+".0]")
    outer, split, inner = 145*512/450, 108.5*512/450, 59*512/450
    arc(panels, 256, 256, (outer+split)/2, 270, 450, "#FF363636", outer-split, cap="BUTT")
    arc(panels, 256, 256, (split+inner)/2, 270, 450, "#FF242424", split-inner, cap="BUTT")
    center = group(face, "center-disc", tintColor=S+".2]")
    ambient(center)
    full_center = group(center, "center-disc-arc", '[CONFIGURATION.upperMode] == "0"')
    node(node(drawing(full_center), "Ellipse", x=189, y=189, width=134, height=134), "Fill", color="#FF202020")
    lower_center = group(center, "center-disc-graph", '[CONFIGURATION.upperMode] == "1"')
    arc(lower_center, 256, 256, 33.5, 90, 270, "#FF202020", 67, cap="BUTT")

    indices = group(face, "indices")
    ambient(indices, '[CONFIGURATION.aodStyle] == "2" ? 0 : 110')
    values = [["12", "3", "6", "9"], ["XII", "III", "VI", "IX"]]
    for style, words in enumerate(values):
        variant = group(indices, f"index-{style}", f'[CONFIGURATION.dialIndex] == "{style}"')
        for tone, color in enumerate((WHITE, T+".2]")):
            colored = group(variant, f"index-{style}-{tone}", f'[CONFIGURATION.dialInk] == "{tone}"')
            if style == 0:
                # Exact measured alpha bounds in canonical 450px ApeX artwork.
                # MASK crops the shared source; no altered duplicate PNG to drift.
                for digit, sx, sy, sw, sh, cx, cy in (
                    ("12", 196, 6, 55, 27, 256, 61),
                    ("6", 209, 419, 33, 27, 256, 466),
                    ("9", 3, 209, 27, 32, 55, 256),
                ):
                    scale = 1.7
                    sprite = group(colored, f"apex-digit-{digit}-{tone}")
                    if digit == "9":
                        sprite.set("angle", "90")
                        sprite.set("pivotX", str(55/512))
                        sprite.set("pivotY", "0.5")
                    left, top = round(cx-sw*scale/2), round(cy-sh*scale/2)
                    mask = drawing(sprite)
                    mask.set("renderMode", "MASK")
                    node(node(mask, "Rectangle", x=left-1, y=top-1, width=round(sw*scale)+2, height=round(sh*scale)+2), "Fill", color="#FFFFFFFF")
                    image = node(sprite, "PartImage", x=round(left-sx*scale), y=round(top-sy*scale),
                                 width=765, height=765, tintColor=color)
                    node(image, "Image", resource="sugarlicious_analog_template")
                continue
            for index, (x, y, w, h) in enumerate(((188, 18, 136, 82), (424, 218, 80, 76), (202, 425, 108, 80), (8, 218, 86, 76))):
                if index == 1:
                    continue  # reference places a date capsule instead of the three
                text(colored, f'"{words[index]}"', x, y, w, h, 68 if style == 0 else 66, color)
    date = group(indices, "date-capsule")
    node(node(drawing(date), "RoundRectangle", x=426, y=233, width=58, height=37, cornerRadiusX=6, cornerRadiusY=6), "Fill", color=S+".1]")
    date.find("PartDraw").set("alpha", "55")
    text(date, "[DAY]", 424, 230, 62, 42, 30, T+".0]")

    minutes = group(face, "minute-markers")
    ambient(minutes, '[CONFIGURATION.aodStyle] == "0" ? 70 : 0')
    for index in range(60):
        angle = math.radians(index*6)
        if index % 15 == 0:
            length, thick = 12, 7
        elif index % 5 == 0:
            length, thick = 7, 6
        else:
            length, thick = 3, 3
        for rounded in (False, True):
            predicate = '[CONFIGURATION.minuteStyle] == "0" || [CONFIGURATION.minuteStyle] == "2"' if rounded else '[CONFIGURATION.minuteStyle] == "1" || [CONFIGURATION.minuteStyle] == "3"'
            if index % 5:
                predicate = f'({predicate}) && ([CONFIGURATION.minuteStyle] == "2" || [CONFIGURATION.minuteStyle] == "3")'
            mark = group(minutes, f"minute-{index}-{rounded}", predicate)
            line = node(drawing(mark), "Line", startX=256+(251-length)*math.sin(angle), startY=256-(251-length)*math.cos(angle), endX=256+251*math.sin(angle), endY=256-251*math.cos(angle))
            node(line, "Stroke", color=P+".1]", thickness=thick, cap="ROUND" if rounded else "BUTT")

    providers = "app.sugarlicious/app.aapswear.complications."
    for identity, name, start, end, provider in (
        (0, "Oben links", 284, 341, "IobRangedValueComplication"),
        (1, "Oben rechts", 19, 76, "PhoneBatteryComplication"),
        (2, "Unten rechts", 103, 166, "CobRangedValueComplication"),
    ):
        # Slot 1 in internal 0.1 builds requested a range from a text-only phone
        # provider. WFF persists that invalid binding across APK updates. Give only
        # this corrected slot a new stable ID; retain every other assignment.
        slot_id = 7 if identity == 1 else identity
        bounds_start, bounds_end = ((280, 344), (14, 79), (100, 171))[identity]
        owner = slot(face, slot_id, label(f"slot_{identity}", name), "RANGED_VALUE SHORT_TEXT", providers+provider, (218.45, bounds_start, bounds_end, 17.07), curved=True)
        if identity == 1:
            # Captured favorite 240: Samsung Health steps, with a portable system fallback.
            policy = owner.find("DefaultProviderPolicy")
            policy.attrib.clear()
            policy.set("primaryProvider", "com.samsung.android.wear.shealth/com.samsung.android.wear.shealth.complications.steps.StepsComplicationProviderService")
            policy.set("primaryProviderType", "RANGED_VALUE")
            policy.set("defaultSystemProvider", "STEP_COUNT")
            policy.set("defaultSystemProviderType", "RANGED_VALUE")
        short = node(owner, "Complication", type="SHORT_TEXT")
        value = node(owner, "Complication", type="RANGED_VALUE")
        ranged(value, f"outer-{identity}", 256, 256, 218.45, start, end, 17.07, S, identity == 1)
        caption_start, caption_end, icon_angle = ((300, 344, -74), (27, 72, 21), (166, 113, 106))[identity]
        icon_radius = (166 if identity != 2 else 165)*512/450
        a = math.radians(icon_angle)
        for content in (short, value):
            numeric_steps = identity == 1 and content is value
            caption = circular(content, "[COMPLICATION.TEXT]", 187.73,
                               25 if numeric_steps else caption_start, 79 if numeric_steps else caption_end,
                               30 if numeric_steps else 32, S+".0]",
                               align="START" if identity == 2 else "END", clockwise=identity != 2)
            if numeric_steps:
                caption.find("TextCircular").set("ellipsis", "FALSE")
            icon(content, "[COMPLICATION.MONOCHROMATIC_IMAGE]", 256+icon_radius*math.sin(a),
                 256-icon_radius*math.cos(a), 28*512/450, S+".0]", angle=icon_angle+(180 if identity == 2 else 0))

    owner = slot(face, 3, label("slot_3", "Unten links"), "RANGED_VALUE GOAL_PROGRESS SHORT_TEXT MONOCHROMATIC_IMAGE SMALL_IMAGE", providers+"GlucoseTrendRangedValueComplication", (67, 287, 162, 162))
    owner.find("BoundingBox").tag = "BoundingOval"
    for kind in ("SHORT_TEXT", "RANGED_VALUE", "GOAL_PROGRESS"):
        content = node(owner, "Complication", type=kind)
        background = drawing(content)
        background.set("tintColor", P+".0]")
        node(node(background, "Ellipse", x=67, y=287, width=162, height=162), "Fill", color="#FF262626")
        if kind == "RANGED_VALUE":
            ranged(content, "round", 148, 368, 59*512/450, 220, 500, 12*512/450, P, gap=19.5)
        elif kind == "GOAL_PROGRESS":
            target = "[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]"
            fraction = f"({target} <= 0 ? 0 : clamp([COMPLICATION.GOAL_PROGRESS_VALUE] / {target}, 0, 1))"
            ranged(content, "round-goal", 148, 368, 59*512/450, 220, 500, 12*512/450, P,
                   gap=19.5, fraction=fraction, valid=f"{target} > 0")
        text(content, "[COMPLICATION.TITLE]", 95, 314, 106, 27, 22, S+".1]")
        if kind == "RANGED_VALUE":
            semantic = node(content, "Condition")
            expressions = node(semantic, "Expressions")
            node(expressions, "Expression", name="glucose").text = '[CONFIGURATION.glucoseColor] == "TRUE" && [COMPLICATION.RANGED_VALUE_MIN] == 40 && [COMPLICATION.RANGED_VALUE_MAX] == 260 && (textLength([COMPLICATION.RANGED_VALUE_COLORS]) == 7 || textLength([COMPLICATION.RANGED_VALUE_COLORS]) == 9)'
            text(node(semantic, "Compare", expression="glucose"), "[COMPLICATION.TEXT]", 85, 344, 126, 47, 39, "[COMPLICATION.RANGED_VALUE_COLORS]")
            text(node(semantic, "Default"), "[COMPLICATION.TEXT]", 85, 344, 126, 47, 39, T+".0]")
        else:
            text(content, "[COMPLICATION.TEXT]", 85, 344, 126, 47, 39, T+".0]")
        icon(content, "[COMPLICATION.MONOCHROMATIC_IMAGE]", 148, 421, 32, S+".1]")

    content = node(owner, "Complication", type="MONOCHROMATIC_IMAGE")
    icon(content, "[COMPLICATION.MONOCHROMATIC_IMAGE]", 148, 368, 91, P+".0]")
    content = node(owner, "Complication", type="SMALL_IMAGE")
    mask = drawing(content)
    mask.set("renderMode", "MASK")
    node(node(mask, "Ellipse", x=67, y=287, width=162, height=162), "Fill", color="#FFFFFFFF")
    node(node(content, "PartImage", x=67, y=287, width=162, height=162), "Image", resource="[COMPLICATION.SMALL_IMAGE]")

    for identity, radius, start, end, provider in ((4, 144.5, 99, 173, "DateComplication"), (5, 95.57, 99, 173, "GlucoseDeltaComplication")):
        owner = slot(face, identity, label(f"slot_{identity}", "Innen außen" if identity == 4 else "Innen"), "SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE", providers+provider, (radius, start, end, 36), curved=True)
        if identity == 4:
            policy = owner.find("DefaultProviderPolicy")
            policy.attrib.update(primaryProvider="com.android.providers.calendar/com.android.providers.calendar.complication.NextEventProviderService", primaryProviderType="SHORT_TEXT", defaultSystemProvider="NEXT_EVENT", defaultSystemProviderType="SHORT_TEXT")
        bounds = owner.find("BoundingArc")
        diameter = (radius + 40.96 / 2) * 2
        bounds.set("width", str(diameter))
        bounds.set("height", str(diameter))
        bounds.set("thickness", "40.96")
        bounds.set("startAngle", "90")
        bounds.set("endAngle", "180")
        for kind in ("SHORT_TEXT", "RANGED_VALUE"):
            content = node(owner, "Complication", type=kind)
            background = group(content, f"inner-{identity}-{kind}-background", tintColor=P+".0]")
            arc(background, 256, 256, radius, 90, 180, "#FF363636", 40.96, cap="BUTT")
            separator = node(drawing(background), "Line", startX=256+59*512/450, startY=259.41,
                             endX=256+(152 if identity == 4 else 102)*512/450, endY=259.41)
            node(separator, "Stroke", color="#FF000000", thickness=6*512/450)
            if kind == "RANGED_VALUE":
                ranged(content, f"inner-{identity}", 256, 256, radius, 180, 90, 40.96, P)
            provider_caption(content, radius)
        content = node(owner, "Complication", type="MONOCHROMATIC_IMAGE")
        a = math.radians(136)
        icon(content, "[COMPLICATION.MONOCHROMATIC_IMAGE]", 256+radius*math.sin(a), 256-radius*math.cos(a), 32, P+".0]", angle=-44)

    upper_arc(face)

    graph_group = group(face, "upper-graph", '[CONFIGURATION.upperMode] == "1"')
    ambient(graph_group, '[CONFIGURATION.upperMode] == "1" ? 160 : 0')
    # Retire the old ApeX-backed slot 6 so existing favorites bind the new provider on update.
    owner = slot(graph_group, 8, label("slot_8", "Graph"), "SMALL_IMAGE", providers+"OrbitGlucoseGraphComplication", (91, 91, 330, 165))
    owner.set("isCustomizable", "FALSE")  # fixed canonical CGM provider; no empty Arc-mode picker box
    content = node(owner, "Complication", type="SMALL_IMAGE")
    # Fill the exact upper inner semicircle: no separate frame and no orphan corner wedges.
    arc(content, 256, 256, 82.5, 270, 450, "#FF000000", 165, cap="BUTT")
    clip = group(content, "graph-clipped", tintColor=P+".0]")
    mask = drawing(clip)
    mask.set("renderMode", "MASK")
    node(node(mask, "Arc", centerX=256, centerY=256, width=165, height=165, startAngle=270, endAngle=450), "Stroke", color="#FFFFFFFF", thickness=165, cap="BUTT")
    node(node(clip, "PartImage", x=91, y=91, width=330, height=165), "Image", resource="[COMPLICATION.SMALL_IMAGE]")

    tir = slot(face, 9, label("slot_9", "TIR · 5 Tage"), "MONOCHROMATIC_IMAGE", providers+"OrbitDailyTirComplication", (91, 91, 330, 165))
    tir.set("isCustomizable", "FALSE")
    tir.find("Variant").set("value", "0")
    content = node(tir, "Complication", type="MONOCHROMATIC_IMAGE")
    visible = group(content, "daily-tir-only", '[CONFIGURATION.arcContent] == "4"')
    # Two radial masks of the same provider image: no extra editable slots and no
    # baked-in colours. Weekdays use the medium theme tone, percentages the light tone.
    for name, tone, diameter, thickness in (("weekdays", ".1]", 290, 40), ("percentages", ".0]", 125, 125)):
        row = node(visible, "Group", name="daily-tir-"+name, x=0, y=0, width=SIZE, height=SIZE, tintColor=P+tone)
        mask = drawing(row)
        mask.set("renderMode", "MASK")
        node(node(mask, "Arc", centerX=256, centerY=256, width=diameter, height=diameter, startAngle=270, endAngle=450), "Stroke", color="#FFFFFFFF", thickness=thickness, cap="BUTT")
        node(node(row, "PartImage", x=91, y=91, width=330, height=165), "Image", resource="[COMPLICATION.MONOCHROMATIC_IMAGE]")

    battery = group(face, "battery", '[CONFIGURATION.batteryIndicator] == "TRUE"')
    ambient(battery)
    # Rounded caps must fit wholly below y=256: asin((6.82/2)/55.75)
    # is 3.51 degrees. Four degrees leaves a small safe clearance.
    for mode, start, end in (("arc", 0, 360), ("graph", 94, 266)):
        owner = group(battery, "battery-"+mode, f'[CONFIGURATION.upperMode] == "{0 if mode == "arc" else 1}"')
        arc(owner, 256, 256, 55.75, start, end, S+".0]", 6.82, alpha=60, cap="ROUND")
        indicator = arc(owner, 256, 256, 55.75, start, start, S+".0]", 6.82, alpha=200, cap="ROUND")
        transform(indicator, "endAngle", f"{start} + {end-start} * clamp([BATTERY_PERCENT], 0, 100) / 100")

    # Flat sibling groups avoid the editor's nested configuration/hand crash.
    hands = group(face, "hands")
    for style in range(3):
        suffix = ("", "_transparent", "_tblack")[style]
        variant = group(hands, f"hands-{style}", f'[CONFIGURATION.apexHands] == "{style}"')
        clock = node(variant, "AnalogClock", x=0, y=0, width=512, height=512)
        for tag, kind in (("HourHand", "hour"), ("MinuteHand", "minute")):
            node(clock, tag, resource=f"{kind}_hand{suffix}",
                 x=0, y=0, width=512, height=512, pivotX="0.5", pivotY="0.5")
        for seconds in range(3):
            mode = group(variant, f"seconds-{style}-{seconds}", f'[CONFIGURATION.secondSweep] == "{seconds}"')
            clock = node(mode, "AnalogClock", x=0, y=0, width=512, height=512)
            hand = node(clock, "SecondHand", resource="second_hand"+suffix,
                        tintColor=("#FFFFFFFF", T+".1]", "#FFFF4040")[seconds],
                        x=0, y=0, width=512, height=512, pivotX="0.5", pivotY="0.5")
            ambient(hand, "0")
            node(hand, "Sweep", frequency=15)

    # Preserve the canonical seconds-hand hub in AOD without drawing a frozen
    # seconds hand. Crop the existing artwork instead of inventing a new pivot.
    hub = group(hands, "ambient-seconds-hub", alpha=0)
    ambient(hub, "255")
    mask = drawing(hub)
    mask.set("renderMode", "MASK")
    node(node(mask, "Ellipse", x=247, y=247, width=18, height=18), "Fill", color="#FFFFFFFF")
    node(node(hub, "PartImage", x=0, y=0, width=512, height=512, tintColor="#FF9E9E9E"), "Image", resource="second_hand")

    # WFF only permits selectable complications directly under Scene, not Group.
    # Keep each slot unique; visibility belongs to its content, never to ghost icon slots.
    for parent in list(face.iter()):
        for child in list(parent):
            if child.tag != "ComplicationSlot":
                continue
            parent.remove(child)
            if child.get("slotId") == "8":
                child.find("Variant[@mode='AMBIENT'][@target='alpha']").set("value", '[CONFIGURATION.upperMode] == "1" ? 160 : 0')
                content = child.find("Complication[@type='SMALL_IMAGE']")
                visibility = group(content, "graph-mode-only", '[CONFIGURATION.upperMode] == "1"')
                for item in list(content):
                    if item is not visibility:
                        content.remove(item)
                        visibility.append(item)
            if child is not tir:
                scene.append(child)
    # Samsung persists providers by positional runtime IDs. Append new slots only
    # after all existing slots, including the nested graph, have been hoisted.
    scene.append(tir)
    # Put hands above provider rendering as in the physical reference.
    face.remove(hands)
    scene.append(hands)

    # Consolidated editor choices drive every existing rendering path, including
    # the fixed graph slot and battery mask. Retire the old independent keys.
    marker_on = '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in range(4, 8)) + ')'
    for element in root.iter():
        for attribute, value in list(element.attrib.items()):
            value = value.replace('[CONFIGURATION.upperMode] == "0"', '[CONFIGURATION.arcContent] != "3"')
            value = value.replace('[CONFIGURATION.upperMode] == "1"', '[CONFIGURATION.arcContent] == "3"')
            value = value.replace('[CONFIGURATION.arcWidget]', '[CONFIGURATION.arcContent]')
            value = value.replace('[CONFIGURATION.progressMarker] == "TRUE"', marker_on)
            value = value.replace('[CONFIGURATION.progressMarker] != "TRUE"', f'!{marker_on}')
            for style in range(4):
                value = value.replace(f'[CONFIGURATION.progressStyle] == "{style}"', f'([CONFIGURATION.progressBars] == "{style}" || [CONFIGURATION.progressBars] == "{style+4}")')
            element.set(attribute, value)
    order = ["dialIndex", "dialInk", "apexHands", "secondSweep", "minuteStyle", "aodStyle", "backgroundGlow", "primary", "secondary", "tertiary", "arcContent", "weatherSymbols", "weatherLaunch", "progressBars", "batteryIndicator", "glucoseColor"]
    for key in order:
        item = next(item for item in configs if item.get("id") == key)
        configs.remove(item)
        configs.insert(order.index(key), item)

    runpy.run_path(str(ROOT / "tools/watchface-assets/user_flavors.py"))["append_user_flavors"](root, strings, "orbit")
    for name, tree in (("raw/watchface.xml", root), ("values/strings.xml", strings)):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        E.indent(tree, space="  ")
        E.ElementTree(tree).write(target, encoding="utf-8", xml_declaration=True)

    runpy.run_path(str(ROOT / "tools/watchface-assets/configure_trend_icon_geometry.py"))["configure"](output / "raw/watchface.xml")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
