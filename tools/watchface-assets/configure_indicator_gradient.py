"""One arc-sized, round-masked gradient implementation for both native faces."""
import math
import re
import xml.etree.ElementTree as E

FRACTION = "([COMPLICATION.RANGED_VALUE_MAX] <= [COMPLICATION.RANGED_VALUE_MIN] ? 0 : clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1))"


def node(parent, tag, **attrs):
    return E.SubElement(parent, tag, {k: str(v) for k, v in attrs.items()})


def configure(root, family):
    for slot in root.findall("Scene//ComplicationSlot"):
        for content in slot.findall("Complication"):
            if content.get("type") not in ("RANGED_VALUE", "GOAL_PROGRESS"):
                continue
            track = content.find(".//Arc")
            if track is None:
                continue
            start, end = float(track.get("startAngle")), float(track.get("endAngle"))
            # Orbit's lower gauge starts with the remaining track, which runs
            # backwards. Derive the fill direction from the gauge, not that remainder.
            if family == "orbit" and slot.get("slotId") == "3":
                start, end = min(start, end), max(start, end)
            cx, cy = float(track.get("centerX")), float(track.get("centerY"))
            radius, thickness = float(track.get("width"))/2, float(track.find("Stroke").get("thickness"))
            span = abs(end-start)
            if span <= 0 or span > 350:
                continue
            fraction = FRACTION
            if content.get("type") == "GOAL_PROGRESS":
                fraction = "([COMPLICATION.GOAL_PROGRESS_TARGET_VALUE] <= 0 ? 0 : clamp([COMPLICATION.GOAL_PROGRESS_VALUE] / [COMPLICATION.GOAL_PROGRESS_TARGET_VALUE], 0, 1))"
            for gradient in list(content.findall(".//Group")):
                if not re.fullmatch(r"(?:(?:outer|inner)-\d+|round|round-goal|progress-gradient-\d+)(?:-gradient)?", gradient.get("name", "")):
                    continue
                if not gradient.get("name", "").endswith("gradient") and not gradient.get("name", "").startswith("progress-gradient-"):
                    continue
                prefix = next((m.group(1) for element in gradient.iter() for v in element.attrib.values()
                               if (m := re.search(r"\[CONFIGURATION\.(primary|secondary|tertiary|materialAccent|materialSubTheme)\.", v))), None)
                if prefix is None:
                    continue
                visibility = [E.fromstring(E.tostring(t)) for t in gradient.findall("Transform[@target='alpha']")]
                name = gradient.get("name")
                gradient.clear()
                box = 2 * math.ceil(radius + thickness/2 + 2)
                center = box/2
                gradient.attrib.update(name=name, x=str(round(cx-center)), y=str(round(cy-center)), width=str(box), height=str(box), angle=str(min(start,end)-8))
                gradient.extend(visibility)
                marker = '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in range(4, 8)) + ')' if family == "orbit" else '[CONFIGURATION.progressMarker] == "TRUE"'
                bottom = family == "orbit" and name in ("round-gradient", "round-goal-gradient") or family == "apex" and slot.get("slotId") in ("4", "5", "6")
                if bottom:
                    marker = '"FALSE" == "TRUE"'
                elif family == "apex":
                    marker += ' && [CONFIGURATION.progressGap] != "TRUE"'
                    if slot.get("slotId") == "1":
                        marker += ' && [CONFIGURATION.upperRightMarkers] != "TRUE"'
                actual_start = 8 if end >= start else 8+span
                actual_end = f"{8} + {fraction} * {span}" if end >= start else f"{8+span} - {fraction} * {span}"

                def group(parent, name):
                    return node(parent, "Group", name=name, x=0, y=0, width=box, height=box)

                # Draw the color directly in a stroked Arc. A sibling MASK
                # does not clip subsequent draw parts in the native renderer.
                fixed = group(gradient, "fixed-gradient-content")
                visible_end = f"({marker}) ? {actual_start + (span if end >= start else -span)} : ({actual_end})"
                for color, overlay in ((f"[CONFIGURATION.{prefix}.2]", False), ("#FFFFFFFF", True)):
                    draw = node(fixed, "PartDraw", x=0, y=0, width=box, height=box)
                    if overlay:
                        draw.set("tintColor", f"[CONFIGURATION.{prefix}.0]")
                    arc = node(draw, "Arc", centerX=center, centerY=center, width=radius*2, height=radius*2,
                               startAngle=actual_start, endAngle=actual_start,
                               direction="CLOCKWISE" if end >= start else "COUNTER_CLOCKWISE")
                    node(arc, "Transform", target="endAngle", value=visible_end)
                    stroke = node(arc, "Stroke", color=color, thickness=thickness, cap="ROUND")
                    if overlay:
                        node(stroke, "SweepGradient", centerX=center, centerY=center, startAngle=8, endAngle=8+span,
                             colors="#FFFFFFFF #00FFFFFF" if end >= start else "#00FFFFFF #FFFFFFFF", positions="0 1")
                # Native SweepGradient clips its angular ends through the round
                # stroke caps on Samsung. Draw those circular caps explicitly.
                full_fraction = f"({marker}) ? 1 : ({fraction})"
                for endpoint, f in (("start", "0"), ("end", f"({full_fraction})")):
                    angle = f"({actual_start} + ({f}) * {end-start}) * 0.017453292519943295"
                    for overlay in (False, True):
                        draw = node(fixed, "PartDraw", name=f"{endpoint}-rounded-cap-{'light' if overlay else 'base'}", x=0, y=0, width=box, height=box)
                        if overlay:
                            node(draw, "Transform", target="alpha", value=f"255 * (1 - ({f}))")
                        circle = node(draw, "Ellipse", x=center-thickness/2, y=center-radius-thickness/2, width=thickness, height=thickness)
                        node(circle, "Transform", target="x", value=f"{center-thickness/2} + {radius} * sin({angle})")
                        node(circle, "Transform", target="y", value=f"{center-thickness/2} - {radius} * cos({angle})")
                        node(circle, "Fill", color=f"[CONFIGURATION.{prefix}.{0 if overlay else 2}]")
