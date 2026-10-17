"""GPU-check Minecraft's actual lightmap shader with exported Ember lighting parameters."""
from pathlib import Path
import json
import struct
import sys
from zipfile import ZipFile
from io import BytesIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageDraw

OUT = ROOT / "build/visual-preview"
cases = {x["name"]: x for x in json.loads((OUT / "geometry.json").read_text()) if x["kind"] == "lighting"}
game = next(p for p in (ROOT / ".gradle/loom-cache/minecraftMaven").rglob("*.jar") if not p.name.endswith("-sources.jar"))
with ZipFile(game) as jar:
    fragment = jar.read("assets/minecraft/shaders/core/lightmap.fsh").decode()
    stone = Image.open(BytesIO(jar.read("assets/minecraft/textures/block/stone.png"))).convert("RGB")
    grass = Image.open(BytesIO(jar.read("assets/minecraft/textures/block/grass_block_top.png"))).convert("RGB")
ctx = moderngl.create_standalone_context(require=330)
program = ctx.program(vertex_shader="""#version 330
    out vec2 texCoord;
    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); texCoord=p;gl_Position=vec4(p*2.-1.,0,1);}
    """, fragment_shader=fragment)
program["LightmapInfo"].binding = 0
vao = ctx.vertex_array(program, [])


def render(case):
    texture = ctx.texture((16, 16), 4)
    fbo = ctx.framebuffer([texture])
    fbo.use()
    ctx.viewport = (0, 0, 16, 16)
    # Production 1.21.11 Std140 layout: seven scalars, aligned sky RGB, aligned ambient RGB.
    data = ctx.buffer(struct.pack("16f", 0, case["sky"], 1.5, 0, 0, 0, case["gamma"], 0,
                                  *case["tint"], 0, 1, 1, 1, 0))
    data.bind_to_uniform_block(0)
    vao.render(moderngl.TRIANGLES, vertices=3)
    result = Image.frombytes("RGBA", (16, 16), fbo.read(components=4))
    data.release()
    fbo.release()
    texture.release()
    return result


maps = {name: render(case) for name, case in cases.items()}
red, blue, vanilla, dark = (maps[name] for name in ("Red", "Blue", "Vanilla", "Dark"))
r, b = red.getpixel((0, 15)), blue.getpixel((0, 15))
assert r[0] > b[0] + 35 and b[2] > r[2] + 35, "Daylit surfaces must take the actual lightmap accent color"
assert sum(r[:3]) < sum(vanilla.getpixel((0, 15))[:3]) - 100, "Default world darkness must noticeably dim full daytime lighting"
assert sum(dark.getpixel((0, 15))[:3]) < sum(r[:3]) - 50, "Increasing darkness must dim the real lightmap further"
assert red.getpixel((15, 0)) == blue.getpixel((15, 0)), "Torch-only light must retain its own color under different sky accents"
assert red.getpixel((0, 0)) == blue.getpixel((0, 0)), "An unlit cave must not receive fake colored skylight"
balanced = maps["Balanced blue"].getpixel((0, 15))
previous = maps["Previous blue"].getpixel((0, 15))
assert balanced[2] > balanced[1] + 50, "The actual GUI blue must stay distinctly blue in the lightmap"
def luminance(rgb):
    return sum(value * weight for value, weight in zip(rgb[:3], (0.2126, 0.7152, 0.0722)))


assert luminance(balanced) > luminance(previous) * 1.03, "Balanced lighting must improve perceived brightness, not just the blue channel"


def material(texture, case_name, biome=(1, 1, 1), nearby=False):
    light = maps[case_name].getpixel((0, 15))[:3]
    case = cases[case_name]
    surface = case.get("surface", 0)
    additive = [(surface >> shift & 255) * case.get("groundAlpha", 0) if nearby else 0 for shift in (16, 8, 0)]
    result = texture.copy()
    result.putdata([tuple(round(min(255, p[i] * biome[i] * light[i] / 255 + additive[i])) for i in range(3)) for p in pixels(texture)])
    return result


def pixels(texture):
    return [texture.getpixel((x, y)) for y in range(texture.height) for x in range(texture.width)]


near_grass = material(grass, "Balanced blue", (0.48, 0.75, 0.32), True)
average = tuple(sum(p[i] for p in pixels(near_grass)) / (near_grass.width * near_grass.height) for i in range(3))
assert average[2] > average[1] + 10, "Blue nearby illumination must remain visibly blue when composed over green grass"
lit_stone = material(stone, "Balanced blue")
assert len(set(pixels(lit_stone))) >= len(set(pixels(stone))) * 0.9, "The ambient light must retain the block texture's tonal detail"

sheet = Image.new("RGB", (250 * len(maps), 290), (10, 10, 16))
draw = ImageDraw.Draw(sheet)
for i, (name, result) in enumerate(maps.items()):
    draw.text((i * 250 + 10, 10), name.upper(), fill=(225, 220, 235))
    sheet.paste(result.transpose(Image.Transpose.FLIP_TOP_BOTTOM).resize((240, 240), Image.Resampling.NEAREST), (i*250, 35))
sheet.save(OUT / "world-lightmap-preview.png")
sheet = Image.new("RGB", (900, 740), (12, 13, 20))
draw = ImageDraw.Draw(sheet)
columns = ["Vanilla", "Previous blue", "Balanced blue"]
rows = [(stone, (1, 1, 1), False, "Stone / daylight"), (grass, (0.48, 0.75, 0.32), False, "Grass / daylight"),
        (grass, (0.48, 0.75, 0.32), True, "Grass / nearby accent light")]
for col, name in enumerate(columns):
    draw.text((col * 300 + 12, 12), name, fill=(235, 235, 245))
    for row, (texture, biome, nearby, label) in enumerate(rows):
        draw.text((col * 300 + 12, row * 230 + 42), label, fill=(170, 175, 190))
        sheet.paste(material(texture, name, biome, nearby).resize((280, 190), Image.Resampling.NEAREST), (col * 300 + 10, row * 230 + 64))
sheet.save(OUT / "ambient-material-preview.png")
print("Vanilla lightmap GLSL passed: blue hue and visibility on real stone/grass textures, texture detail, adjustable darkness, independent torch color and no invented cave light.")
print("GPU:", ctx.info["GL_RENDERER"])
print("Preview:", OUT / "world-lightmap-preview.png")
ctx.release()
