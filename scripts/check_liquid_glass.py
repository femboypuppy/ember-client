"""GPU smoke checks for the actual Liquid Glass shaders (requires moderngl and Pillow).

Run: python scripts/check_liquid_glass.py
Creates a material preview under build/ui-reference; no Minecraft installation is needed.
"""
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageDraw

SHADERS = ROOT / "src/main/resources/assets/meteor-client/shaders"
ctx = moderngl.create_standalone_context(require=330)
program = ctx.program(
    vertex_shader=(SHADERS / "pos_tex_color.vert").read_text(),
    fragment_shader=(SHADERS / "liquid_glass.frag").read_text(),
)
program["MeshData"].binding = 0
program["GlassData"].binding = 1
program["u_Texture"].value = 0


def render(scene, rect=(80, 70, 300, 110), time=1.0, opacity=1.0, radius=14):
    w, h = scene.size
    source = ctx.texture((w, h), 4, scene.convert("RGBA").transpose(Image.Transpose.FLIP_TOP_BOTTOM).tobytes())
    source.filter = (moderngl.LINEAR, moderngl.LINEAR)
    source.repeat_x = source.repeat_y = False
    target = ctx.texture((w, h), 4, source.read())
    fbo = ctx.framebuffer(color_attachments=[target])
    fbo.use()
    ctx.viewport = (0, 0, w, h)
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
    proj = (2/w, 0, 0, 0, 0, -2/h, 0, 0, 0, 0, -1, 0, -1, 1, 0, 1)
    identity = (1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
    matrices = ctx.buffer(struct.pack("32f", *(proj + identity)))
    matrices.bind_to_uniform_block(0)
    x, y, rw, rh = rect
    pane = ctx.buffer(struct.pack("16f", x, y, rw, rh, radius, time, 0.45, 0.75,
                                  0.65, 0.54, 0.88, 0, w, h, x + rw * 0.7, y + 20))
    pane.bind_to_uniform_block(1)
    vertices = []
    for vx, vy in [(x-1, y-1), (x-1, y+rh+1), (x+rw+1, y+rh+1),
                   (x+rw+1, y+rh+1), (x+rw+1, y-1), (x-1, y-1)]:
        vertices += [vx, vy, 0, 0, 1, 1, 1, opacity]
    vbo = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
    vao = ctx.vertex_array(program, [(vbo, "2f 8x 4f", "pos", "color")])
    source.use(0)
    vao.render(moderngl.TRIANGLES)
    result = Image.frombytes("RGBA", (w, h), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    for resource in [vao, vbo, pane, matrices, fbo, target, source]:
        resource.release()
    return result


red = Image.new("RGBA", (640, 360), (200, 30, 20, 255))
blue = Image.new("RGBA", (640, 360), (20, 30, 200, 255))
r, b = render(red), render(blue)
assert r.getpixel((220, 120))[0] > b.getpixel((220, 120))[0] + 35, "Scene red must reach the pane"
assert b.getpixel((220, 120))[2] > r.getpixel((220, 120))[2] + 35, "Scene blue must reach the pane"
assert r.getpixel((80, 70)) == red.getpixel((80, 70)), "Rounded corners must stay transparent"
assert r.getpixel((50, 120)) == red.getpixel((50, 120)), "The shader must not touch pixels outside its pane"
assert render(red, opacity=0).tobytes() == red.tobytes(), "A hidden pane must leave the scene untouched"
assert render(red, time=1).tobytes() == render(red, time=1).tobytes(), "Fixed-time rendering must be deterministic"
assert render(red, time=1).tobytes() != render(red, time=5).tobytes(), "Rim highlights should animate"
small = Image.new("RGBA", (320, 180), (30, 150, 70, 255))
resized = render(small, rect=(25, 25, 230, 75), radius=12)
assert resized.getpixel((0, 0)) == small.getpixel((0, 0)), "Resizing must preserve screen-space sampling"

scene = Image.new("RGBA", (960, 540))
pixels = scene.load()
for y in range(scene.height):
    for x in range(scene.width):
        pixels[x, y] = (int(22 + x / 960 * 50), int(38 + y / 540 * 92), int(50 + x / 960 * 64), 255)
draw = ImageDraw.Draw(scene)
for i in range(10):
    draw.rectangle((i * 115 - 35, 100 + i % 3 * 60, i * 115 + 44, 540), fill=(35 + i * 3, 90 + i * 9, 79 + i * 5, 255))
scene = render(scene, rect=(80, 70, 590, 38), radius=9)
scene = render(scene, rect=(110, 200, 190, 150), radius=12)
scene = render(scene, rect=(480, 165, 350, 270), radius=16)
output = ROOT / "build/ui-reference/liquid-glass-material.png"
output.parent.mkdir(parents=True, exist_ok=True)
scene.save(output)
print(f"PASS: actual GLSL compiled/linked on {ctx.info['GL_RENDERER']}")
print("PASS: scene color, rounded transparency, bounds, opacity, animation, and resize checks")
print(f"Material preview: {output}")
ctx.release()
