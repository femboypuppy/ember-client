"""Render the actual sky GLSL and exported production meshes; verify depth and palette behavior."""
from pathlib import Path
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageDraw, ImageStat

SHADERS = ROOT / "src/main/resources/assets/meteor-client/shaders"
OUT = ROOT / "build/visual-preview"
geometry = json.loads((OUT / "geometry.json").read_text())
ctx = moderngl.create_standalone_context(require=330)
sky = ctx.program(vertex_shader=(SHADERS / "celestial_sky.vert").read_text(),
                  fragment_shader=(SHADERS / "celestial_sky.frag").read_text())
stars = ctx.program(vertex_shader=(SHADERS / "celestial_sky.vert").read_text(),
                    fragment_shader=(SHADERS / "pos_color.frag").read_text())
sky["MeshData"].binding = 0
sky["SkyData"].binding = 1
stars["MeshData"].binding = 0


def mesh(kind, program):
    item = next(item for item in geometry if item["kind"] == kind)
    values = []
    for i, c in enumerate(item["colors"]):
        values.extend(item["positions"][i*3:i*3+3])
        values.extend(((c >> 16 & 255)/255, (c >> 8 & 255)/255, (c & 255)/255, (c >> 24)/255))
    vbo = ctx.buffer(struct.pack(f"{len(values)}f", *values))
    return ctx.vertex_array(program, [(vbo, "3f 4f", "pos", "color")]) if "color" in program else ctx.vertex_array(program, [(vbo, "3f 16x", "pos")])


shell, points = mesh("sky", sky), mesh("sky-stars", stars)


def render(accent=(.65, .54, .88), style=0, time=5, opacity=.85, depth=1, translation=0, starlight=True):
    w, h = 720, 400
    target = ctx.texture((w, h), 4)
    zbuffer = ctx.depth_renderbuffer((w, h))
    fbo = ctx.framebuffer([target], zbuffer)
    fbo.use()
    fbo.clear(.14, .18, .25, 1, depth=depth)
    ctx.viewport = (0, 0, w, h)
    ctx.enable(moderngl.BLEND | moderngl.DEPTH_TEST)
    ctx.depth_func = "<="
    ctx.depth_mask = False
    ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
    f, near, far = 1/math.tan(math.radians(70)/2), .1, 1024
    proj = (f/(w/h), 0, 0, 0, 0, f, 0, 0, 0, 0, (far+near)/(near-far), -1, 0, 0, 2*far*near/(near-far), 0)
    c, s = math.cos(-.32), math.sin(-.32)
    view = (1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, translation, translation, translation, 1)
    matrices = ctx.buffer(struct.pack("32f", *(proj + view)))
    matrices.bind_to_uniform_block(0)
    parameters = ctx.buffer(struct.pack("8f", *accent, opacity, time, style, 0, 0))
    parameters.bind_to_uniform_block(1)
    shell.render(moderngl.TRIANGLES)
    if starlight:
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE
        points.render(moderngl.TRIANGLES)
    result = Image.frombytes("RGBA", (w, h), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    for resource in (parameters, matrices, fbo, zbuffer, target):
        resource.release()
    ctx.depth_mask = True
    return result


red, blue = render((1, .1, .08)), render((.08, .15, 1))
r, b = ImageStat.Stat(red).mean, ImageStat.Stat(blue).mean
assert r[0] > b[0] + 10 and b[2] > r[2] + 10, "The sky must visibly follow the accent"
assert render(translation=10000).tobytes() == render().tobytes(), "Camera translation must not slide the skybox"
assert render(time=5).tobytes() != render(time=25).tobytes(), "The procedural sky must animate"
assert render(style=0).tobytes() != render(style=1).tobytes() != render(style=2).tobytes(), "Sky styles must look different"
clear = render(opacity=0, starlight=False)
assert max(ImageStat.Stat(clear).stddev[:3]) == 0, "Zero sky intensity must preserve the clear scene"
occluded = render(depth=.5)
assert occluded.tobytes() == clear.tobytes(), "Both dome and distant stars must stay behind terrain"
assert render().tobytes() != render(starlight=False).tobytes(), "Distant stars must actually render"
sheet = Image.new("RGB", (1440, 850), (8, 9, 14))
draw = ImageDraw.Draw(sheet)
for i, (style, accent, label) in enumerate(((0, (.65, .54, .88), "NEBULA / LAVENDER"), (1, (.65, .54, .88), "AURORA / LAVENDER"),
                                           (2, (.65, .54, .88), "DUSK / LAVENDER"), (0, (1, .12, .09), "NEBULA / RED ACCENT"))):
    x, y = i % 2 * 720, i // 2 * 425
    sheet.paste(render(accent, style), (x, y + 25))
    draw.text((x+14, y+7), label, fill=(220, 215, 235))
sheet.save(OUT / "world-sky-preview.png")
print("World sky GLSL passed: accent colors, styles, motion, camera-translation invariance, stars, zero intensity and terrain depth occlusion.")
print("GPU:", ctx.info["GL_RENDERER"])
print("Preview:", OUT / "world-sky-preview.png")
ctx.release()
