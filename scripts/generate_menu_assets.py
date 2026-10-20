"""Generate the start menu's antialiased GUI masks, icons and wallpaper derivatives.

Usage: python scripts/generate_menu_assets.py [--wallpapers DIR]
Requires Pillow. DIR holds the 2560x1440 source JPGs named after MenuConfig art files
(nebula.jpg, silk.jpg, obsidian.jpg, summit.jpg, aurora.jpg, dunes.jpg, forest.jpg,
ocean.jpg); see textures/menu/credits.md for sources. Use --art to rebuild a subset.
Every texture gets a .mcmeta with linear filtering, so GUI scaling stays smooth at any resolution.
"""
from pathlib import Path
import argparse
import json
import math
from PIL import Image, ImageDraw, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/ember/textures/menu'
SS = 8  # supersampling for procedural shapes
# Must match MenuCanvas.CORNER_SIZES; each cell is size+2 wide with a 1px gutter on both sides.
CORNER_SIZES = (4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128)
SHADOW_SIZE, SHADOW_INSET, SHADOW_RADIUS = 256, 80, 48  # must match MenuCanvas.SHADOW_*
ART = ('nebula', 'silk', 'obsidian', 'summit', 'aurora', 'dunes', 'forest', 'ocean')
LINEAR = {'texture': {'blur': True, 'clamp': True}}


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    path.with_name(path.name + '.mcmeta').write_text(json.dumps(LINEAR, indent=2) + '\n', encoding='utf-8')


def white(mask):
    # White RGB everywhere keeps linear filtering free of dark fringes; alpha carries coverage.
    image = Image.new('RGBA', mask.size, (255, 255, 255, 0))
    image.putalpha(mask)
    return image


def corners():
    width = sum(s + 2 for s in CORNER_SIZES)
    atlas = Image.new('L', (width, max(CORNER_SIZES) + 2), 0)
    x = 0
    for s in CORNER_SIZES:
        big = Image.new('L', ((s + 2) * SS, (s + 2) * SS), 0)
        draw = ImageDraw.Draw(big)
        # Top-left quarter disc centered on the cell's inner corner; the inner gutter stays opaque
        # so bilinear samples at the seam never pull in transparency.
        cx = cy = (s + 1) * SS
        draw.ellipse((cx - s * SS, cy - s * SS, cx + s * SS, cy + s * SS), fill=255)
        draw.rectangle((cx, 0, big.width, big.height), fill=255)
        draw.rectangle((0, cy, big.width, big.height), fill=255)
        draw.rectangle((0, 0, big.width, SS - 1), fill=0)
        draw.rectangle((0, 0, SS - 1, big.height), fill=0)
        atlas.paste(big.resize((s + 2, s + 2), Image.BOX), (x, 0))
        x += s + 2
    save(white(atlas), OUT / 'ui/corners.png')


def shadow():
    mask = Image.new('L', (SHADOW_SIZE, SHADOW_SIZE), 0)
    i, r = SHADOW_INSET, SHADOW_RADIUS
    ImageDraw.Draw(mask).rounded_rectangle((i, i, SHADOW_SIZE - i - 1, SHADOW_SIZE - i - 1), radius=r, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(i / 3))
    peak = mask.getpixel((SHADOW_SIZE // 2, SHADOW_SIZE // 2))
    mask = mask.point(lambda v: min(255, round(v * 255 / max(1, peak))))
    save(white(mask), OUT / 'ui/shadow.png')


def icon(name, paint):
    size = 128 * SS
    mask = Image.new('L', (size, size), 0)
    draw = ImageDraw.Draw(mask)
    k = size / 24  # icons are designed on a 24-unit grid

    def p(x, y):
        return x * k, y * k

    def stroke(points, width):
        draw.line([p(*q) for q in points], fill=255, width=round(width * k), joint='curve')
        for q in (points[0], points[-1]):
            cx, cy = p(*q)
            rr = width * k / 2
            draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=255)

    def disc(x, y, r, fill=255):
        draw.ellipse((*p(x - r, y - r), *p(x + r, y + r)), fill=fill)

    paint(draw, p, stroke, disc, k)
    for res, suffix in ((128, ''), (64, '_64')):
        save(white(mask.resize((res, res), Image.LANCZOS)), OUT / f'icon/{name}{suffix}.png')


def play(draw, p, stroke, disc, k):
    tri = [(7.5, 4.5), (20, 12), (7.5, 19.5)]
    draw.polygon([p(*q) for q in tri], fill=255)
    stroke(tri + [tri[0]], 2.6)  # round joins soften the triangle's corners


def accounts(draw, p, stroke, disc, k):
    disc(12, 7.6, 4.3)
    draw.rounded_rectangle((*p(3.6, 13.6), *p(20.4, 21.4)), radius=round(5.2 * k), fill=255)


def settings(draw, p, stroke, disc, k):
    teeth, outer, root = 8, 10.6, 8.2
    points = []
    for i in range(teeth * 8):
        a = (i - 2.5) / (teeth * 8) * math.tau - math.pi / 2  # tooth centered at 12 o'clock
        phase = (i % 8) / 8
        r = outer if phase < 0.75 and phase > 0.125 else root  # flat-topped teeth
        points.append(p(12 + math.cos(a) * r, 12 + math.sin(a) * r))
    draw.polygon(points, fill=255)
    disc(12, 12, root + .2)
    disc(12, 12, 3.4, fill=0)


def display(draw, p, stroke, disc, k):
    draw.rounded_rectangle((*p(2.6, 3.6), *p(21.4, 16.8)), radius=round(2.8 * k), outline=255, width=round(2.1 * k))
    stroke([(12, 17.2), (12, 20.2)], 2.1)
    stroke([(7.6, 20.6), (16.4, 20.6)], 2.1)


def power(draw, p, stroke, disc, k):
    r, cx, cy, width = 8.2, 12, 13, 2.3
    start, end = -55, 235  # degrees clockwise from +x in screen space; leaves a gap at the top
    draw.arc((*p(cx - r, cy - r), *p(cx + r, cy + r)), start=start, end=end, fill=255, width=round(width * k))
    for a in (start, end):
        x, y = cx + math.cos(math.radians(a)) * (r - width / 2), cy + math.sin(math.radians(a)) * (r - width / 2)
        disc(x, y, width / 2)
    stroke([(12, 2.8), (12, 11.4)], width)


def wallpapers(source, names=ART):
    for name in names:
        image = Image.open(source / f'{name}.jpg').convert('RGB')
        if image.size != (2560, 1440):
            image = ImageOps.fit(image, (2560, 1440), Image.Resampling.LANCZOS)
        save(image, OUT / f'{name}.png')
        # Glass samples this small prefiltered copy; bilinear filtering hides its low resolution.
        save(image.resize((480, 270), Image.LANCZOS).filter(ImageFilter.GaussianBlur(5)), OUT / f'{name}_blur.png')
        save(image.resize((384, 216), Image.LANCZOS), OUT / f'{name}_thumb.png')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wallpapers', type=Path)
    parser.add_argument('--art', nargs='+', choices=ART, default=ART, help='Only rebuild these wallpapers')
    args = parser.parse_args()
    corners()
    shadow()
    for name, paint in (('play', play), ('accounts', accounts), ('settings', settings), ('display', display), ('power', power)):
        icon(name, paint)
    if args.wallpapers:
        wallpapers(args.wallpapers, args.art)
    print('Generated menu assets in', OUT)


if __name__ == '__main__':
    main()
