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

SHADERS = ROOT / "src/main/resources/assets/ember-client/shaders"
OUT = ROOT / "build/visual-preview"
geometry = json.loads((OUT / "geometry.json").read_text())
ctx = moderngl.create_standalone_context(require=330)
sky = ctx.program(vertex_shader=(SHADERS / "celestial_sky.vert").read_text(),
                  fragment_shader=(SHADERS / "celestial_sky.frag").read_text())
stars = ctx.program(vertex_shader=(SHADERS / "celestial_sky.vert").read_text(),
                    fragment_shader=(SHADERS / "pos_color.frag").read_text())
sky["Projection"].binding = 0
sky["DynamicTransforms"].binding = 2
sky["SkyData"].binding = 1
stars["Projection"].binding = 0
stars["DynamicTransforms"].binding = 2


def mesh(kind, program):
    item = next(item for item in geometry if item["kind"] == kind)
    values = []
    for i, c in enumerate(item["colors"]):
        values.extend(item["positions"][i*3:i*3+3])
        values.extend(((c >> 16 & 255)/255, (c >> 8 & 255)/255, (c & 255)/255, (c >> 24)/255))
    vbo = ctx.buffer(struct.pack(f"{len(values)}f", *values))
    return ctx.vertex_array(program, [(vbo, "3f 4f", "pos", "color")]) if "color" in program else ctx.vertex_array(program, [(vbo, "3f 16x", "pos")])


shell, points = mesh("sky", sky), mesh("sky-stars", stars)
links = mesh("constellations", stars)


def render(accent=(.65, .54, .88), style=0, time=5, opacity=.85, depth=1, translation=0, starlight=True,
           background=(.14, .18, .25), grass_edge=False, exposure=1, horizon=0, horizon_width=.1, pulse=1, constellations=False):
    w, h = 720, 400
    target = ctx.texture((w, h), 4)
    zbuffer = ctx.depth_renderbuffer((w, h))
    fbo = ctx.framebuffer([target], zbuffer)
    fbo.use()
    fbo.clear(*background, 1, depth=depth)
    ctx.viewport = (0, 0, w, h)
    ctx.enable(moderngl.BLEND | moderngl.DEPTH_TEST)
    ctx.depth_func = "<="
    ctx.depth_mask = False
    ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
    f, near, far = 1/math.tan(math.radians(70)/2), .1, 1024
    proj = (f/(w/h), 0, 0, 0, 0, f, 0, 0, 0, 0, (far+near)/(near-far), -1, 0, 0, 2*far*near/(near-far), 0)
    c, s = math.cos(-.32), math.sin(-.32)
    view = (1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, translation, translation, translation, 1)
    matrices = ctx.buffer(struct.pack("16f", *proj))
    matrices.bind_to_uniform_block(0)
    transforms = ctx.buffer(struct.pack("16f", *view))
    transforms.bind_to_uniform_block(2)
    parameters = ctx.buffer(struct.pack("12f", *accent, opacity, time, style, exposure, 0, horizon, horizon_width, pulse, 0))
    parameters.bind_to_uniform_block(1)
    if opacity > 0:
        shell.render(moderngl.TRIANGLES)
    if starlight:
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE
        points.render(moderngl.TRIANGLES)
        if constellations:
            links.render(moderngl.TRIANGLES)
    if grass_edge:
        # Alpha-covered terrain must be blended after the sky, with its own near depth.
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        edge = ctx.program(vertex_shader='''#version 330
            void main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.-1.,0.,1.); }''',
            fragment_shader='''#version 330
            out vec4 color;void main(){color=vec4(.05,.18,.06,.35);}''')
        edge_vao = ctx.vertex_array(edge, [])
        edge_vao.render(moderngl.TRIANGLES, vertices=3)
        edge_vao.release()
        edge.release()
    result = Image.frombytes("RGBA", (w, h), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    for resource in (parameters, matrices, transforms, fbo, zbuffer, target):
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
assert render(background=(0,0,1)).tobytes() == render(background=(1,0,0)).tobytes(), "Replacement sky must not retain any original blue background"
assert render(grass_edge=True, background=(0,0,1)).tobytes() == render(grass_edge=True, background=(1,0,0)).tobytes(), "Translucent grass edges must composite against the replacement sky"
assert render(opacity=.15, background=(0,0,1)).tobytes() == render(opacity=.15, background=(1,0,0)).tobytes(), "Low sky intensity must dim the sky without exposing vanilla color"
brightness = [sum(ImageStat.Stat(render(starlight=False, exposure=e)).mean[:3]) for e in (.4, 1, 1.65)]
assert brightness[0] < brightness[1] * .5 and brightness[2] > brightness[1] * 1.5, "Bass exposure must darken or brighten the actual sky shader"
assert render(exposure=.4, background=(0,0,1)).tobytes() == render(exposure=.4, background=(1,0,0)).tobytes(), "Bass dimming must not expose vanilla blue"
assert render(horizon=.3, starlight=False).tobytes() != render(horizon=0, starlight=False).tobytes(), "The horizon band must be visible"
assert sum(ImageStat.Stat(render(horizon=.3, pulse=1.8, starlight=False)).mean[:3]) > sum(ImageStat.Stat(render(horizon=.3, pulse=1, starlight=False)).mean[:3]), "Spotify pulses must brighten the horizon band"
assert render(depth=.5, horizon=.3).tobytes() == clear.tobytes(), "Terrain must occlude the horizon band"
assert render(constellations=True).tobytes() != render().tobytes(), "The exported constellation lines must actually be visible"
sheet = Image.new("RGB", (1440, 1275), (8, 9, 14))
draw = ImageDraw.Draw(sheet)
for i, (style, accent, label) in enumerate(((0, (.65, .54, .88), "NEBULA / LAVENDER"), (1, (.65, .54, .88), "AURORA / LAVENDER"),
                                           (2, (.65, .54, .88), "DUSK / LAVENDER"), (0, (1, .12, .09), "NEBULA / RED ACCENT"),
                                           (0, (.65, .54, .88), "HORIZON / CONSTELLATIONS"), (0, (.65, .54, .88), "SLOW SPOTIFY PULSE"))):
    x, y = i % 2 * 720, i // 2 * 425
    sheet.paste(render(accent, style, horizon=.3 if i>=4 else 0, pulse=1.8 if i==5 else 1, constellations=i>=4), (x, y + 25))
    draw.text((x+14, y+7), label, fill=(220, 215, 235))
sheet.save(OUT / "world-sky-preview.png")
print("World sky GLSL passed: native projection, accent colors, motion, bass exposure, full sky coverage, low intensity, translucent grass edges and terrain depth occlusion.")
print("GPU:", ctx.info["GL_RENDERER"])
print("Preview:", OUT / "world-sky-preview.png")
ctx.release()
