"""Keep the provider's common square canvas, but size its ink in the host face."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
GLUCOSE = "[COMPLICATION.RANGED_VALUE_MIN] == 40 &amp;&amp; [COMPLICATION.RANGED_VALUE_MAX] == 260"


def configure(path):
    source = path.read_text(encoding="utf-8")

    def complication(match):
        def image(found):
            tag, body = found.groups()
            if '[COMPLICATION.MONOCHROMATIC_IMAGE]' not in body or 'trend-host-canvas' in body:
                return found.group(0)
            attributes = dict(re.findall(r'(\w+)="([^"]*)"', tag))
            x, y, width, height = [float(attributes[k]) for k in ('x', 'y', 'width', 'height')]
            enlarged = round(width * 125 / 60)
            shift = (enlarged - width) / 2
            indent = re.search(r'\n( +)<Image', body).group(1)
            transforms = f'\n{indent}<!-- trend-host-canvas: equal ink height for single and double arrows -->'
            for target, large, normal in [('x', x-shift, x), ('y', y-shift, y), ('width', enlarged, width), ('height', enlarged, height)]:
                transforms += f'\n{indent}<Transform target="{target}" value="{GLUCOSE} ? {large:g} : {normal:g}" />'
            return tag + body.rstrip() + transforms + '\n' + indent[:-2] + '</PartImage>'

        return re.sub(r'(<PartImage\b[^>]*>)([\s\S]*?)</PartImage>', image, match.group(0))

    updated = re.sub(r'<Complication type="RANGED_VALUE">[\s\S]*?</Complication>', complication, source)
    if updated != source:
        path.write_text(updated, encoding="utf-8", newline="\n")


if __name__ == '__main__':
    for family in ('sugarlicious-orbit', 'sugarlicious-analog'):
        configure(ROOT / 'watchfaces' / family / 'src/main/res/raw/watchface.xml')
