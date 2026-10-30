"""Import exact 108x108 adaptive layers; retain the legacy 66x66 importer separately."""
from pathlib import Path
import argparse
import copy
import shutil
import xml.etree.ElementTree as E
from import_app_brand_icons import ROOT, SVG, A, AA, write, background


T = "http://schemas.android.com/tools"
E.register_namespace("tools", T)

def symbol_attributes(data):
    attributes = {f"{{{A}}}fillColor": "#FFFFFFFF", f"{{{A}}}pathData": data}
    # Preserve the supplied static vector exactly; Android caches this drawable.
    if len(data) > 3000:
        attributes[f"{{{T}}}ignore"] = "VectorPath"
    return attributes

def load(path):
    root = E.parse(path).getroot()
    if root.get('viewBox') != '0 0 108 108':
        raise ValueError(f'Expected native 108x108 layer: {path}')
    return root


def foreground(data, motif=66):
    root = E.Element('vector', {f'{{{A}}}{k}': v for k, v in dict(width='108dp', height='108dp', viewportWidth='108', viewportHeight='108').items()})
    group = E.SubElement(root, 'group', {f'{{{A}}}{k}': v for k,v in dict(scaleX=str(motif/108), scaleY=str(motif/108), translateX=str((108-motif)/2), translateY=str((108-motif)/2)).items()})
    E.SubElement(group, 'path', symbol_attributes(data))
    return E.tostring(root, encoding='unicode') + '\n'


def replace_normalized(path, data, scale, offset):
    root = E.parse(path).getroot()
    # Re-import safely: unwrap our own coordinate normalization first.
    for parent in root.iter():
        for child in list(parent):
            if child.tag == 'group' and child.get(f'{{{A}}}name') == 'source108ToLegacy66':
                index = list(parent).index(child)
                parent.remove(child)
                for item in list(child):
                    parent.insert(index, item)
    target = root.find('.//path')
    if target is None:
        raise ValueError(path)
    parent = next(p for p in root.iter() if target in list(p))
    parent.remove(target)
    group = E.SubElement(parent, 'group', {f'{{{A}}}{k}': str(v) for k,v in dict(name='source108ToLegacy66', scaleX=scale, scaleY=scale, translateX=offset, translateY=offset).items()})
    E.SubElement(group, 'path', symbol_attributes(data))
    write(path, E.tostring(root, encoding='unicode')+'\n')


def run(source_dir, preview_dir, product="sugarlicious", repository_root=ROOT):
    import cairosvg
    from PIL import Image, ImageDraw
    preview_dir.mkdir(parents=True, exist_ok=True)
    sheet = Image.new('RGB', (324*3, 364*2), '#25252c')
    draw = ImageDraw.Draw(sheet)
    for row, (brand, suffix, directory, scale, offset) in enumerate([
        ('SugarWear', '', 'sugarwear', 56/108, 5),
        ('Sugarlicious', '2', 'app-icons', 60/108, 3),
    ]):
        if brand.lower() != product:
            continue
        product_root = repository_root.resolve()
        fg_path = source_dir / f'ic_foreground{suffix}.svg'
        bg_path = source_dir / f'ic_background{suffix}.svg'
        fg, bg = load(fg_path), load(bg_path)
        paths = fg.findall(f'{{{SVG}}}path')
        if len(paths) != 1:
            raise ValueError('Expected one exact source symbol')
        data = paths[0].get('d')
        asset_dir = product_root / 'design-assets' / directory / 'adaptive108'
        asset_dir.mkdir(parents=True, exist_ok=True)
        for source, name in [(fg_path, 'foreground.svg'), (bg_path, 'background.svg')]:
            shutil.copyfile(source, asset_dir / name)
        combined = copy.deepcopy(bg)
        path = copy.deepcopy(paths[0])
        path.attrib.pop('class', None)
        path.set('fill', '#ffffff')
        motif = 66
        group = E.SubElement(combined, f'{{{SVG}}}g', transform=f'translate({(108-motif)/2} {(108-motif)/2}) scale({motif/108})')
        group.append(path)
        write(asset_dir / 'combined.svg', E.tostring(combined, encoding='unicode'))
        targets = ['ui-shared/src/main/res/drawable/ic_sugarwear_filled.xml'] if brand == 'SugarWear' else [
            f'{module}/src/main/res/{rel}' for module, rels in {
                'app-mobile': ['drawable/ic_launcher_monochrome.xml','drawable/ic_foreground.xml','drawable/ic_sugarlicious_monochrome.xml','drawable/ic_notification.xml','drawable/ic_notification_outlined.xml','drawable-anydpi/ic_notification.xml','drawable-anydpi/ic_notification_outlined.xml'],
                'app-wear': ['drawable/ic_foreground.xml','drawable/ic_monochrome_outlined.xml','drawable/ic_notification.xml'],
            }.items() for rel in rels]
        for target in targets:
            notification = brand == 'Sugarlicious' and target.startswith('app-mobile/') and 'notification' in target
            target_path = product_root / target
            if brand == 'SugarWear' and not target_path.is_file():
                canonical = E.parse(product_root / 'shared/sugarlicious' / target).getroot()
                if not any(path.get(f'{{{A}}}pathData') == data for path in canonical.iter('path')):
                    raise ValueError('Update canonical ui-shared SugarWear symbol and its submodule revision first')
            else:
                replace_normalized(target_path, data, 82/108 if notification else scale, -8 if notification else offset)
        modules = ['g7watch'] if brand == 'SugarWear' else ['app-mobile','app-wear']
        gradient = bg.find(f'.//{{{SVG}}}linearGradient')
        rect = bg.find(f'{{{SVG}}}rect')
        raster = bg.find(f'{{{SVG}}}image') is not None
        if not raster:
            if gradient is None or rect is None or rect.get('rx') != rect.get('ry'):
                raise ValueError('Unsupported supplied background geometry')
            radius = rect.get('rx')
            r = float(radius); edge = 108-r
            shape = f'M{radius},0H{edge:g}A{radius},{radius} 0 0 1 108,{radius}V{edge:g}A{radius},{radius} 0 0 1 {edge:g},108H{radius}A{radius},{radius} 0 0 1 0,{edge:g}V{radius}A{radius},{radius} 0 0 1 {radius},0Z'
        for module in modules:
            res = product_root / module / 'src/main/res'
            fg_name = 'ic_sensor_launcher_foreground.xml' if module == 'g7watch' else 'ic_launcher_foreground.xml'
            bg_name = 'sugarwear_launcher_background.xml' if module == 'g7watch' else 'ic_launcher_background.xml'
            # Adaptive colour and monochrome share the same centered safe motif.
            mono_name = 'ic_sensor_launcher_monochrome' if module == 'g7watch' else 'ic_launcher_monochrome'
            write(res / 'drawable' / (mono_name + '.xml'), foreground(data, motif))
            for adaptive in res.glob('mipmap-anydpi-*/ic*launcher*.xml'):
                tree = E.parse(adaptive)
                mono = tree.getroot().find('monochrome')
                if mono is not None:
                    mono.set(f'{{{A}}}drawable', '@drawable/' + mono_name)
                    write(adaptive, E.tostring(tree.getroot(), encoding='unicode') + '\n')
            write(res / 'drawable' / fg_name, foreground(data, motif))
            dest = res / 'drawable' / bg_name
            if raster:
                image_name = bg_name[:-4] + '_art'
                raster_dest = res / 'drawable-nodpi' / (image_name + '.png')
                raster_dest.parent.mkdir(parents=True, exist_ok=True)
                cairosvg.svg2png(url=str(bg_path), write_to=str(raster_dest), output_width=432, output_height=432)
                write(dest, '<bitmap xmlns:android="'+A+'" android:src="@drawable/'+image_name+'" android:gravity="fill" android:filter="true" />\n')
            else:
                background(dest, gradient)
                vector = E.parse(dest).getroot()
                vector.set(f'{{{A}}}viewportWidth','108'); vector.set(f'{{{A}}}viewportHeight','108')
                vector.find('path').set(f'{{{A}}}pathData',shape)
                write(dest,E.tostring(vector,encoding='unicode')+'\n')
            if module != 'g7watch':
                cairosvg.svg2png(url=str(asset_dir/'combined.svg'),write_to=str(product_root/module/'src/main/ic_launcher-playstore.png'),output_width=512,output_height=512)
        for col, name in enumerate(['background','foreground','combined']):
            output=preview_dir/f'{brand.lower()}-{name}.png'
            cairosvg.svg2png(url=str(asset_dir/f'{name}.svg'),write_to=str(output),output_width=324,output_height=324)
            tile=Image.open(output).convert('RGBA'); sheet.paste(tile,(col*324,row*364+32),tile)
            draw.text((col*324+8,row*364+8),f'{brand}: {name} '+(f'safe{motif}/108' if name == 'combined' else 'source108'),fill='white')
    sheet.save(preview_dir/'adaptive108-contact-sheet.png')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--source-dir',type=Path,default=Path('C:/Users/typ1d/Desktop/Sugarlicious/Watchfaces'))
    parser.add_argument('--preview-dir',type=Path,default=ROOT/'app-mobile/build/reports/brand-icons108')
    parser.add_argument('--product', choices=('sugarlicious', 'sugarwear'), default='sugarlicious')
    parser.add_argument('--repository-root', type=Path, default=ROOT)
    args=parser.parse_args();run(args.source_dir,args.preview_dir,args.product,args.repository_root)
