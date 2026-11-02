"""Idempotent progress-option update for the existing native ApeX artwork."""
import argparse
import copy
from pathlib import Path
import xml.etree.ElementTree as ET

MARKER = '[CONFIGURATION.progressMarker] == "TRUE"'
VALID = '[COMPLICATION.RANGED_VALUE_MAX] > [COMPLICATION.RANGED_VALUE_MIN]'
FRACTION = '([COMPLICATION.RANGED_VALUE_MAX] <= [COMPLICATION.RANGED_VALUE_MIN] ? 0 : (clamp([COMPLICATION.RANGED_VALUE_VALUE], [COMPLICATION.RANGED_VALUE_MIN], [COMPLICATION.RANGED_VALUE_MAX]) - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]))'


def configure(root, strings):
    configs = root.find('UserConfigurations')
    if configs.find("ListConfiguration[@id='progressBars']") is not None:
        return
    if configs.find("BooleanConfiguration[@id='progressGap']") is None:
        index = list(configs).index(configs.find("BooleanConfiguration[@id='progressGradient']")) + 1
        configs.insert(index, ET.Element('BooleanConfiguration', id='progressGap', displayName='progress_gap', defaultValue='TRUE'))
    label = strings.find("string[@name='progress_gap']")
    if label is None:
        label = ET.SubElement(strings, 'string', name='progress_gap')
    label.text = 'TrennlÃ¼cke'
    for slot in root.findall('Scene//ComplicationSlot'):
        identity = slot.get('slotId')
        ranged = slot.find("Complication[@type='RANGED_VALUE']")
        if ranged is None or identity == '7':
            continue
        if identity == '1':
            # Keep the value's trailing edge fixed beside the final tick.
            # Apply to therapy, glucose-color and ordinary provider text alike.
            for text in ranged.findall('.//TextCircular'):
                text.set('align', 'END')
        effective = f'({MARKER} && [CONFIGURATION.progressGap] != "TRUE"'
        if identity == '1':
            effective += ' && [CONFIGURATION.upperRightMarkers] != "TRUE"'
        effective += ')'
        for element in ranged.iter():
            for key, value in list(element.attrib.items()):
                if MARKER in value and '[CONFIGURATION.progressGap]' not in value:
                    element.set(key, value.replace(MARKER, effective))
        track = next(part for part in ranged.findall('PartDraw') if part.find('Arc') is not None)
        arc = track.find('Arc')
        start, end = float(arc.get('startAngle')), float(arc.get('endAngle'))
        radius = float(arc.get('width')) / 2
        thickness = float(arc.find('Stroke').get('thickness'))
        # Orbit's reference separation, scaled by stroke width/radius.
        gap = 19.5 * (thickness / radius) / ((12 * 512 / 450) / (59 * 512 / 450))
        # Outer tracks need a tighter gap than the small central gauges.
        if identity in {'0', '1', '2', '3'}:
            gap *= 0.8
        active = f'[CONFIGURATION.progressGap] == "TRUE" && {VALID}'
        if identity == '1':
            active += ' && [CONFIGURATION.upperRightMarkers] != "TRUE"'
        transform = track.find("Transform[@target='alpha']")
        if transform is None:
            transform = ET.Element('Transform', target='alpha')
            track.insert(0, transform)
        transform.set('value', f'({active}) ? 0 : 55')
        old = ranged.find(f"Group[@name='progress-gap-{identity}']")
        if old is not None:
            ranged.remove(old)
        group = ET.Element('Group', name=f'progress-gap-{identity}', x='0', y='0', width=track.get('width'), height=track.get('height'))
        ET.SubElement(group, 'Transform', target='alpha', value=f'({active}) && {FRACTION} < 1 ? 55 : 0')
        part = ET.SubElement(group, 'PartDraw', x='0', y='0', width=track.get('width'), height=track.get('height'))
        remaining = copy.deepcopy(arc)
        remaining.set('startAngle', f'{end:g}')
        remaining.set('endAngle', f'{start:g}')
        remaining.set('direction', 'COUNTER_CLOCKWISE')
        ET.SubElement(remaining, 'Transform', target='endAngle', value=f'clamp({start:g} + {gap:.6f} + {end-start:g} * {FRACTION}, {start:g}, {end:g}) - {gap:.6f} * ({FRACTION} == 0)')
        part.append(remaining)
        ranged.insert(list(ranged).index(track) + 1, group)


def fit_outer_value_text(root):
    """Measured geometry only; retain font family, palette, providers and tracks."""
    import math
    caption = {'0': ('337','361','START'), '1': ('66','90','START'),
               '2': ('181','155','END'), '3': ('282','246','END')}
    icon_angles = {'0':276, '1':6, '2':96, '3':186}
    glucose = '[COMPLICATION.RANGED_VALUE_MIN] == 40 && [COMPLICATION.RANGED_VALUE_MAX] == 260'
    for slot in root.findall('Scene/ComplicationSlot'):
        identity = slot.get('slotId')
        if identity in caption:
            for font in slot.findall('.//Font'):
                font.set('size', '29.6')
            ranged = slot.find("Complication[@type='RANGED_VALUE']")
            for text in ranged.findall('.//TextCircular'):
                for key, value in zip(('startAngle','endAngle','align'),caption[identity]):
                    text.set(key,value)
            if identity == '1':
                for font in ranged.findall('.//Font'):
                    font.set('letterSpacing','-0.03')
            track = ranged.find('.//Arc')
            angle = math.radians(icon_angles[identity])
            radius = float(track.get('width'))/2
            cx = float(track.get('centerX')) + radius*math.sin(angle)
            cy = float(track.get('centerY')) - radius*math.cos(angle)
            for image in ranged.findall('PartImage'):
                for transform in list(image.findall('Transform')):
                    if transform.get('target') in {'x','y','width','height'}:
                        image.remove(transform)
                for key,value in [('x',cx-11),('y',cy-11),('width',22),('height',22)]:
                    image.set(key,str(round(value)))
                for key,value in [('x',cx-26),('y',cy-26),('width',52),('height',52)]:
                    ET.SubElement(image,'Transform',target=key,value=f'{glucose} ? {value:.4f} : {dict(x=cx-11,y=cy-11,width=22,height=22)[key]:.4f}')
            # Short-text icons already have smaller image frames; keep their
            # centers while removing the one oversized lower-left frame.
            for image in slot.findall("Complication[@type='SHORT_TEXT']//PartImage"):
                width,height = float(image.get('width')),float(image.get('height'))
                if width > 25:
                    for axis, delta in [('x',(width-25)/2),('y',(height-25)/2)]:
                        position=float(image.get(axis))+delta
                        image.set(axis,str(round(position)))
                        ET.SubElement(image,'Transform',target=axis,value=f'{position:.4f}')
                    image.set('width','25'); image.set('height','25')
        elif identity in {'4','5','6'}:
            for font in slot.findall('.//Font'):
                is_title = any('[COMPLICATION.TITLE]' in p.get('expression','') for p in font.findall('.//Parameter'))
                font.set('size','32' if is_title else '39')
            if identity == '6':
                for image in slot.findall("Complication[@type='RANGED_VALUE']/PartImage"):
                    for transform in list(image.findall('Transform')):
                        if transform.get('target') in {'x','y','width','height'}:
                            image.remove(transform)
                    for key,value in [('x',63.5),('y',117),('width',26),('height',26)]:
                        image.set(key,str(round(value)))
                    for key,value in [('x',48),('y',101.5),('width',57),('height',57)]:
                        ET.SubElement(image,'Transform',target=key,value=f'{glucose} ? {value:g} : {dict(x=63.5,y=117,width=26,height=26)[key]:g}')
                continue
            for part in slot.findall('.//PartText'):
                if part.get('width') not in {'103','107','117'}:
                    continue
                title = any('[COMPLICATION.TITLE]' in p.get('expression','') for p in part.findall('.//Parameter'))
                ranged = part.get('x') == '10' or part.get('y') in {'8','50'}
                part.set('x','3'); part.set('width','117'); part.set('height','48')
                part.set('y',('8' if title else '50') if ranged else ('15' if title else '60'))
                recenter = part.find("Transform[@target='y']")
                if recenter is not None:
                    recenter.set('value', f'[COMPLICATION.TITLE] == "" ? 37.5 : {part.get("y")}')


def simplify_editor(root, strings):
    """Canonical ApeX editor/rendering contract, safe to rerun on native XML."""
    configs = root.find('UserConfigurations')
    removed = {'nightMode', 'dialTheme', 'minuteMarkers', 'showAmPm', 'unreadIndicator', 'aodStyle', 'upperMode'}
    for parent in list(root.iter()):
        for item in list(parent):
            if item.get('id') in removed:
                parent.remove(item)
    def label(key, value):
        item = strings.find(f"string[@name='{key}']")
        if item is None:
            item = ET.SubElement(strings, 'string', name=key)
        item.text = value
    label('background_pattern', 'Hintergrund')
    label('upper_content', 'Graph')
    for key, value in [('seconds_white', 'Weiß'), ('seconds_theme', 'Themefarbe'), ('seconds_red', 'Rot')]:
        label(key, value)
    seconds = configs.find("ListConfiguration[@id='secondStyle']")
    seconds[:] = [ET.Element('ListOption', id=i, displayName=name) for i, name in [('0', 'seconds_white'), ('1', 'seconds_theme'), ('3', 'seconds_red'), ('2', 'option_off')]]
    indices = configs.find("ListConfiguration[@id='indexStyle']")
    for item in list(indices)[2:]:
        indices.remove(item)
    scene = root.find('Scene')
    for parent in list(root.iter()):
        for item in list(parent):
            if item.get('name') in {'am-pm', 'unread-indicator', 'minute-lines', 'original-weekdays-date', 'date-icon-only', 'weekday-arc'}:
                parent.remove(item)
            elif item.tag == 'Transform' and '[CONFIGURATION.minuteMarkers]' in item.get('value', ''):
                if '== "0"' in item.get('value'):
                    parent.remove(item)
                else:
                    parent.set('alpha', '0')
                    parent.remove(item)
    for item in root.iter():
        for attr, value in list(item.attrib.items()):
            item.set(attr, value.replace('[CONFIGURATION.nightMode]', '#FFFFFFFF').replace('[CONFIGURATION.dialTheme]', '#FFFFFFFF'))
    for name in ('background-web', 'background-target'):
        group = scene.find(f"Group/Group[@name='{name}']")
        if group is not None:
            group.set('alpha', '145')
            group.find('Transform').set('value', group.find('Transform').get('value').replace('112', '145'))
            for stroke in group.findall('.//Stroke'):
                stroke.set('thickness', '1.5')
    graph = scene.find("ComplicationSlot[@slotId='7']")
    graph.set('supportedTypes', 'SMALL_IMAGE EMPTY')
    for item in list(graph):
        if item.tag == 'Complication' and item.get('type') not in {'SMALL_IMAGE', 'EMPTY'}:
            graph.remove(item)
    content = graph.find("Complication[@type='SMALL_IMAGE']")
    wrapper = content.find("Group[@name='graph-mode-only']")
    if wrapper is not None:
        for item in list(wrapper):
            if item.tag not in {'Transform', 'Variant'}:
                content.append(item)
        content.remove(wrapper)
    # WFF permits eight slots; retire the cancelled supplemental slot.
    for parent in list(root.iter()):
        for child in list(parent):
            if child.tag == 'ComplicationSlot' and child.get('slotId') == '8':
                parent.remove(child)
    for item in list(strings):
        if item.get('name') in {'upper_mode', 'upper_graph', 'upper_date', 'upper_icon'}:
            strings.remove(item)
    for style in scene.findall("Group[@name='hands-and-indicators']/Group/Group"):
        seconds_group = style.find("Group[@name='seconds-variants']")
        original = copy.deepcopy(seconds_group.find('Group/AnalogClock/SecondHand'))
        seconds_group.clear()
        seconds_group.attrib.update(name='seconds-variants', x='0', y='0', width='512', height='512')
        for option, color in [('0', '#FFFFFFFF'), ('1', '[CONFIGURATION.materialAccent.1]'), ('3', '#FFFF4040')]:
            group = ET.SubElement(seconds_group, 'Group', name=f'seconds-color-{option}', x='0', y='0', width='512', height='512')
            ET.SubElement(group, 'Transform', target='alpha', value=f'[CONFIGURATION.secondStyle] == "{option}" ? 255 : 0')
            clock = ET.SubElement(group, 'AnalogClock', x='0', y='0', width='512', height='512')
            hand = copy.deepcopy(original)
            hand.set('tintColor', color)
            for item in list(hand):
                hand.remove(item)
            ET.SubElement(hand, 'Variant', mode='AMBIENT', target='alpha', value='0')
            ET.SubElement(hand, 'Sweep', frequency='15')
            clock.append(hand)
    # Pull the lower-left icon toward the arc's 195 degree start (center 67,307).
    for image in scene.findall("ComplicationSlot[@slotId='3']/Complication/PartImage"):
        image.set('y', '280')
    hands = scene.find("Group[@name='hands-and-indicators']")
    if hands.find("Group[@name='fixed-center-hub']") is None:
        hub = ET.SubElement(hands, 'Group', name='fixed-center-hub', x='0', y='0', width='512', height='512')
        draw = ET.SubElement(hub, 'PartDraw', x='0', y='0', width='512', height='512')
        ET.SubElement(ET.SubElement(draw, 'Ellipse', x='247', y='247', width='18', height='18'), 'Fill', color='#FFFFFFFF')
        ET.SubElement(ET.SubElement(draw, 'Ellipse', x='253', y='253', width='6', height='6'), 'Fill', color='#FF000000')
    hub = hands.find("Group[@name='fixed-center-hub']")
    if hub.find("Transform[@target='alpha']") is None:
        visibility = ET.SubElement(hub, 'Transform', target='alpha', value='[CONFIGURATION.secondStyle] == "2" ? 255 : 0')
        hub.remove(visibility)
        hub.insert(0, visibility)
        ET.SubElement(hub, 'Variant', mode='AMBIENT', target='alpha', value='255')
    # Ambient keeps graph, bottom glucose and time only. No nested stale variants.
    for child in scene:
        keep = child is hands or child.tag == 'ComplicationSlot' and child.get('slotId') in {'6', '7'}
        if not keep:
            variant = child.find("Variant[@mode='AMBIENT'][@target='alpha']")
            if variant is None:
                variant = ET.SubElement(child, 'Variant', mode='AMBIENT', target='alpha')
            variant.set('value', '0')
        elif child.tag == 'ComplicationSlot':
            variant = child.find("Variant[@mode='AMBIENT'][@target='alpha']")
            if variant is not None:
                variant.set('value', '160')
    for flavor in configs.findall('Flavors/Flavor'):
        # Slot 7 is a graph contract; presets must never bind ranged data to it.
        slot = flavor.find("ComplicationSlot[@slotId='7']/DefaultProviderPolicy")
        if slot is not None:
            slot.set('primaryProvider', 'app.sugarlicious/app.aapswear.complications.GlucoseGraphComplication')
            slot.set('primaryProviderType', 'SMALL_IMAGE')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resources', type=Path, required=True)
    args = parser.parse_args()
    face = ET.parse(args.resources / 'raw/watchface.xml', parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))).getroot()
    strings = ET.parse(args.resources / 'values/strings.xml').getroot()
    configure(face, strings)
    if face.find("Scene/ComplicationSlot[@slotId='7']/Complication[@type='LONG_TEXT']") is None:
        simplify_editor(face, strings)
    fit_outer_value_text(face)
    from restore_native_hands import configure as restore_native_graph
    restore_native_graph(face, "apex")
    for name, root in (('raw/watchface.xml', face), ('values/strings.xml', strings)):
        ET.indent(root, space='  ')
        ET.ElementTree(root).write(args.resources / name, encoding='utf-8', xml_declaration=True)
