"""Generate antialiased GUI shapes for the optional pill hotbar; no external image libraries."""
from pathlib import Path
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/ember-client/textures/gui/sprites/hotbar'
SCALE = 4

def chunk(name, data):
    return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data) & 0xffffffff)

def generate(name, width, height, bounds, radius, top_alpha, bottom_alpha, rgb):
    left, top, w, h = bounds
    rows = []
    for py in range(height * SCALE):
        row = bytearray([0])
        for px in range(width * SCALE):
            x, y = (px + .5) / SCALE, (py + .5) / SCALE
            qx = abs(x - left - w / 2) - (w / 2 - radius)
            qy = abs(y - top - h / 2) - (h / 2 - radius)
            distance = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - radius
            t = max(0, min(1, .5 - distance / 1.2))
            coverage = t * t * (3 - 2 * t)
            gradient = max(0, min(1, (y - top) / h))
            alpha = round(coverage * (top_alpha + (bottom_alpha - top_alpha) * gradient))
            row.extend((*rgb, alpha))
        rows.append(row)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width * SCALE, height * SCALE, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(b''.join(rows), 9)) + chunk(b'IEND', b'')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + '.png')).write_bytes(png)
    (OUT / (name + '.png.mcmeta')).write_text('{"gui":{"scaling":{"type":"stretch"}}}\n', encoding='utf-8')

def main():
    generate('pill', 182, 22, (0, 0, 182, 22), 9.5, 125, 150, (9, 12, 8))
    generate('selection', 24, 23, (2, 2, 20, 20), 7, 205, 220, (3, 5, 3))
    generate('offhand_left', 29, 24, (0, 1, 22, 22), 8, 125, 150, (9, 12, 8))
    generate('offhand_right', 29, 24, (7, 1, 22, 22), 8, 125, 150, (9, 12, 8))
    print('Generated pill hotbar GUI sprites at 4x resolution.')

if __name__ == '__main__':
    main()
