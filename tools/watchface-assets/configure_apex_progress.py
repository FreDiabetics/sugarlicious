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
    if configs.find("BooleanConfiguration[@id='progressGap']") is None:
        index = list(configs).index(configs.find("BooleanConfiguration[@id='progressGradient']")) + 1
        configs.insert(index, ET.Element('BooleanConfiguration', id='progressGap', displayName='progress_gap', defaultValue='TRUE'))
    label = strings.find("string[@name='progress_gap']")
    if label is None:
        label = ET.SubElement(strings, 'string', name='progress_gap')
    label.text = 'Trennlücke'
    for slot in root.findall('Scene//ComplicationSlot'):
        identity = slot.get('slotId')
        ranged = slot.find("Complication[@type='RANGED_VALUE']")
        if ranged is None or identity == '7':
            continue
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resources', type=Path, required=True)
    args = parser.parse_args()
    face = ET.parse(args.resources / 'raw/watchface.xml').getroot()
    strings = ET.parse(args.resources / 'values/strings.xml').getroot()
    configure(face, strings)
    for name, root in (('raw/watchface.xml', face), ('values/strings.xml', strings)):
        ET.indent(root, space='  ')
        ET.ElementTree(root).write(args.resources / name, encoding='utf-8', xml_declaration=True)
