"""Render exported production hats with their real shader; inspect color, motion and scene depth."""
from pathlib import Path
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageChops, ImageDraw

OUT = ROOT / "build/hat-preview"
data = json.loads((OUT / "geometry.json").read_text())
ctx = moderngl.create_standalone_context(require=330)
shaders = ROOT / "src/main/resources/assets/ember-client/shaders"
program = ctx.program(vertex_shader=(shaders / "holographic_hat.vert").read_text(),
                      fragment_shader=(shaders / "pos_color.frag").read_text())
program["Projection"].binding = 0
program["DynamicTransforms"].binding = 1


def render(item, depth=1, yaw=-.45, pitch=.28, head=True):
    w, h = 440, 420
    texture = ctx.texture((w, h), 4)
    zbuffer = ctx.depth_renderbuffer((w, h))
    fbo = ctx.framebuffer([texture], zbuffer)
    fbo.use()
    ctx.viewport = (0, 0, w, h)
    fbo.clear(.035, .04, .06, 1, depth=depth)
    ctx.enable(moderngl.BLEND | moderngl.DEPTH_TEST)
    ctx.disable(moderngl.CULL_FACE)
    ctx.depth_func = "<="
    ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
    # Orthographic view of an actual head-sized cube with the hat .045 blocks above it.
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    modelview = (cy, -sy*sp, sy*cp, 0, 0, cp, sp, 0, -sy, -cy*sp, cy*cp, 0, 0, -.10, 0, 1)
    projection = (1.2, 0, 0, 0, 0, 1.2*w/h, 0, 0, 0, 0, -.25, 0, 0, 0, 0, 1)
    proj = ctx.buffer(struct.pack("16f", *projection))
    view = ctx.buffer(struct.pack("16f", *modelview))
    proj.bind_to_uniform_block(0)
    view.bind_to_uniform_block(1)
    resources = [proj, view, fbo, zbuffer, texture]

    def draw(positions, colors):
        packed = []
        for i, color in enumerate(colors):
            packed.extend(positions[i*3:i*3+3])
            packed.extend(((color >> 16 & 255)/255, (color >> 8 & 255)/255,
                           (color & 255)/255, (color >> 24)/255))
        buffer = ctx.buffer(struct.pack(f"{len(packed)}f", *packed))
        vao = ctx.vertex_array(program, [(buffer, "3f 4f", "pos", "color")])
        vao.render(moderngl.TRIANGLES)
        resources.extend([vao, buffer])

    if head:
        ctx.depth_mask = True
        for axis in range(3):
            for side in range(2):
                corners = []
                for a, b in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    p = [0, 0, 0]
                    p[axis] = side
                    p[(axis+1) % 3], p[(axis+2) % 3] = a, b
                    corners.append([p[0]*.5-.25, p[1]*.5-.545, p[2]*.5-.25])
                positions = sum((corners[i] for i in (0, 1, 2, 0, 2, 3)), [])
                draw(positions, [0xFF363A47 if axis == 1 else 0xFF252934]*6)
    ctx.depth_mask = False
    draw(item["positions"], item["colors"])
    result = Image.frombytes("RGBA", (w, h), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    ctx.depth_mask = True
    for resource in resources:
        resource.release()
    return result


tiles = []
for style in ("Hologram", "Halo", "Crown", "Witch", "TopHat"):
    frames = [next(i for i in data if i["style"] == style and i["frame"] == n) for n in range(3)]
    first, animated, warm = [render(i) for i in frames]
    assert first.tobytes() != animated.tobytes(), f"{style}: highlights must animate"
    assert animated.tobytes() != warm.tobytes(), f"{style}: custom color must change appearance"
    blocked = render(frames[0], depth=0, head=False)
    assert all(lo == hi for lo, hi in blocked.getextrema()), f"{style}: terrain must occlude hats"
    assert render(frames[0], head=False).tobytes() != blocked.tobytes(), f"{style}: shader must render visible geometry"
    assert render(frames[0], pitch=-.35).tobytes() != first.tobytes(), f"{style}: geometry must remain three-dimensional"
    first.save(OUT / f"{style}.png")
    tiles.append((style, first))

sheet = Image.new("RGB", (1320, 960), "#090a0f")
labels = ImageDraw.Draw(sheet)
labels.text((28, 22), "EMBER / HOLOGRAPHIC HATS", fill="#e9e4fa")
labels.text((28, 46), "Production geometry and shader; reference head cube. In-game pose still needs confirmation.", fill="#9290a5")
for i, (name, tile) in enumerate(tiles):
    x, y = i % 3 * 440, 84 + i // 3 * 438
    sheet.paste(tile.convert("RGB"), (x, y))
    labels.text((x+22, y+10), name, fill="#cbb6fa")
sheet.save(OUT / "hats.png")
print("Hat GPU checks passed: five styles, animation, color response, three-dimensional views and terrain-depth occlusion.")
print(OUT / "hats.png")
