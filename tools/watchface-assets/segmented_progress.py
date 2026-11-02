"""Reference-style radial ticks: a longer current tick, never a second circle."""
import math
import re
import xml.etree.ElementTree as E

FRACTION = '([COMPLICATION.RANGED_VALUE_MAX] <= [COMPLICATION.RANGED_VALUE_MIN] ? 0 : clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1))'
SEGMENTED = '(' + ' || '.join(f'[CONFIGURATION.progressBars] == "{i}"' for i in (2, 3, 6, 7)) + ')'
FULL = '([CONFIGURATION.progressBars] == "6" || [CONFIGURATION.progressBars] == "7")'
GRADIENT = '([CONFIGURATION.progressBars] == "3" || [CONFIGURATION.progressBars] == "7")'


def configure(root, family):
    identities = ('1', '3') if family == 'apex' else ('7',)
    # Retire the experimental all-outer wrapper without touching its geometry.
    if family == 'apex':
        for identity in ('0', '2'):
            content = root.find(f"Scene/ComplicationSlot[@slotId='{identity}']/Complication[@type='RANGED_VALUE']")
            if content is None:
                continue
            for child in list(content):
                if child.get('name', '').startswith('continuous-only-'):
                    position = list(content).index(child)
                    content.remove(child)
                    for item in list(child):
                        if item.tag != 'Transform':
                            content.insert(position, item)
                            position += 1
                elif child.get('name') == 'radial-value-ticks':
                    content.remove(child)
    for identity in identities:
        content = root.find(f"Scene/ComplicationSlot[@slotId='{identity}']/Complication[@type='RANGED_VALUE']")
        if content is None:
            continue
        if family == 'orbit':
            background = content.find("Group[@name='outer-1-track']/Transform[@target='alpha']")
            if background is not None and not background.get('value', '').startswith(f'{SEGMENTED} ?'):
                background.set('value', f'{SEGMENTED} ? 255 : ({background.get("value")})')
        # Remove obsolete static tick artwork, including nested legacy masks.
        for parent in list(content.iter()):
            for child in list(parent):
                if child.get('name', '').endswith('-segments') or child.get('name') == 'radial-value-ticks':
                    parent.remove(child)
        for parent in list(content.iter()):
            for condition in list(parent):
                if condition.tag != 'Condition':
                    continue
                branches = condition.findall('Compare')
                if branches and all(len(branch) == 0 or all(item.get('name') == 'retired-static-ticks' for item in branch) for branch in branches):
                    default = condition.find('Default')
                    if default is not None:
                        position = list(parent).index(condition)
                        parent.remove(condition)
                        for item in list(default):
                            parent.insert(position, item)
                            position += 1
        # Keep all accepted continuous arc/indicator geometry unchanged in other modes.
        for child in list(content):
            name = child.get('name', '')
            if child.tag == 'Group' and not name.startswith('continuous-only-') and any(token in name for token in ('progress-fill-', 'progress-gap-', 'progress-marker-', '-solid', '-gradient', '-marker', '-fill')):
                wrapper = E.Element('Group', name='continuous-only-' + name, x='0', y='0', width=content.get('width', '512'), height=content.get('height', '512'))
                E.SubElement(wrapper, 'Transform', target='alpha', value=f'{SEGMENTED} ? 0 : 255')
                content.insert(list(content).index(child), wrapper)
                content.remove(child)
                wrapper.append(child)
        track = content.find('.//Arc')
        cx, cy = float(track.get('centerX')), float(track.get('centerY'))
        radius = float(track.get('width')) / 2
        thickness = float(track.find('Stroke').get('thickness'))
        start, end = float(track.get('startAngle')), float(track.get('endAngle'))
        prefix = re.search(r'CONFIGURATION\.([A-Za-z]+)\.', track.find('Stroke').get('color')).group(1)
        marks = E.SubElement(content, 'Group', name='radial-value-ticks', x='0', y='0', width='512', height='512')
        E.SubElement(marks, 'Transform', target='alpha', value=f'{SEGMENTED} && [COMPLICATION.RANGED_VALUE_MAX] > [COMPLICATION.RANGED_VALUE_MIN] ? 255 : 0')
        count = 10 if family == 'apex' else 12
        selected = f'floor(clamp({FRACTION}, 0, 0.99) * {count})'
        for index in range(count):
            position = index / (count - 1)
            angle = math.radians(start + 1 + (end - start - 2) * position)
            current = f'({selected}) == {index}'
            for tone in ('base', 'light', 'solid'):
                part = E.SubElement(marks, 'PartDraw', name=f'tick-{index}-{tone}', x='0', y='0', width='512', height='512')
                active = f'({FULL} || {index} <= ({selected}))'
                alpha = f'({GRADIENT}) ? {255 * (1-position):.4f} : 0' if tone == 'light' else (f'({GRADIENT}) ? 255 : 0' if tone == 'base' else f'({GRADIENT}) ? 0 : 255')
                E.SubElement(part, 'Transform', target='alpha', value=f'{active} ? ({alpha}) : 0')
                width = thickness * (.3 if family == 'apex' else 1/3)
                outer = radius + thickness/2 - (width/2 if family == 'orbit' else 0)
                normal_inner = radius - thickness/2 + (width/2 if family == 'orbit' else 0)
                long_inner = radius - thickness*1.1 if family == 'apex' else radius - thickness*.9 + width/2
                line = E.SubElement(part, 'Line', startX=str(cx+normal_inner*math.sin(angle)), startY=str(cy-normal_inner*math.cos(angle)), endX=str(cx+outer*math.sin(angle)), endY=str(cy-outer*math.cos(angle)))
                # Coordinates are stroke centerlines. Orbit's measured 15/21px
                # rounded rectangles include their caps in the total length.
                inner = f'({current} ? {long_inner} : {normal_inner})'
                E.SubElement(line, 'Transform', target='startX', value=f'{cx} + {inner} * {math.sin(angle)}')
                E.SubElement(line, 'Transform', target='startY', value=f'{cy} - {inner} * {math.cos(angle)}')
                color = f'[CONFIGURATION.{prefix}.{0 if tone == "light" else 1 if tone == "solid" else 2}]'
                stroke = E.SubElement(line, 'Stroke', color=color, thickness=str(width), cap='ROUND')
                E.SubElement(stroke, 'Transform', target='thickness', value=f'{current} ? {thickness*.36} : {width}' if family == 'apex' else str(width))
