"""GPU checks for the actual chams surface, settings preview and ESP outline shaders.

Uses the project's local visual-check dependencies in build/ui-tools (moderngl/Pillow).
Writes a material contact sheet to build/visual-preview/esp-materials.png.
"""
from pathlib import Path
import json
import math
import re
import struct
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageDraw, ImageChops, ImageStat

SHADERS = ROOT / "src/main/resources/assets/meteor-client/shaders"
game = next(p for p in (ROOT / ".gradle/loom-cache/minecraftMaven").rglob("*.jar") if not p.name.endswith("-sources.jar"))
jar = ZipFile(game)


def source(path, defines=""):
    seen = set()

    def expand(text):
        def include(match):
            namespace, name = match[1].split(":", 1)
            if match[1] in seen:
                return ""
            seen.add(match[1])
            child = (SHADERS / "include" / name).read_text() if namespace == "meteor-client" else jar.read("assets/" + namespace + "/shaders/include/" + name).decode()
            return expand(re.sub(r"(?m)^#version[^\n]*", "", child))
        return re.sub(r"#moj_import\s+<([^>]+)>", include, text)
    text = expand((SHADERS / path).read_text())
    return re.sub(r"(?m)^#version[^\n]*", "#version 330 core\n" + defines, text, count=1)


ctx = moderngl.create_standalone_context(require=330)
identity = struct.pack("16f", 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
target = ctx.texture((256, 256), 4)
fbo = ctx.framebuffer([target])
fbo.use()
ctx.viewport = (0, 0, 256, 256)


def block(program, name, data, binding):
    if name not in program:
        return None
    program[name].binding = binding
    buffer = ctx.buffer(data + bytes(max(0, program[name].size - len(data))))
    buffer.bind_to_uniform_block(binding)
    return buffer


preview = ctx.program(vertex_shader=source("pos_tex_color.vert"), fragment_shader=source("chams_preview.frag"))
block(preview, "MeshData", identity * 2, 0)
vertices = []
for x, y, u, v in [(-1, -1, 0, 0), (1, -1, 1, 0), (1, 1, 1, 1), (-1, -1, 0, 0), (1, 1, 1, 1), (-1, 1, 0, 1)]:
    vertices.extend([x, y, u, v, .8, .8, .8, .6])
vbo = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
vao = ctx.vertex_array(preview, [(vbo, "2f 2f 4f", "pos", "texCoords", "color")])


def render_preview(material, time=0, accent=(.42, .58, 1.0)):
    data = block(preview, "MaterialData", struct.pack("8f", time, material, 1, .22, *accent, 1), 1)
    fbo.clear()
    vao.render()
    result = Image.frombytes("RGBA", (256, 256), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    data.release()
    return result


panels = []
for material, name in enumerate(("Solid", "Galaxy", "Aurora", "Opal")):
    defines = f"#define MATERIAL {material}\n#define ANIMATED\n"
    world = ctx.program(vertex_shader=source("chams_surface.vert", defines), fragment_shader=source("chams_surface.frag", defines))
    # Link every production model variant, including its pause/frozen variant.
    frozen = ctx.program(vertex_shader=source("chams_surface.vert", f"#define MATERIAL {material}\n"), fragment_shader=source("chams_surface.frag", f"#define MATERIAL {material}\n"))
    frozen.release()
    world.release()
    still = render_preview(material)
    assert still.getchannel("A").getextrema() == (153, 153), "Material must preserve configured opacity"
    later = render_preview(material, 8)
    difference = sum(ImageStat.Stat(ImageChops.difference(still.convert("RGB"), later.convert("RGB"))).mean)
    assert difference > 2 if material else difference == 0, "Animated materials change while Solid remains stable"
    assert ImageChops.difference(still, render_preview(material)).getbbox() is None, "Frozen preview is deterministic"
    assert ImageChops.difference(still.convert("RGB"), render_preview(material, accent=(1, .25, .3)).convert("RGB")).getbbox(), "Every material must follow custom accent colors"
    panels.append((name, still))

# Compile and draw the production through-wall shader with a real alpha mask.
post = ctx.program(vertex_shader=source("post-process/base.vert"), fragment_shader=source("post-process/image.frag"))
positions = ctx.buffer(struct.pack("12f", -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1))
post_vao = ctx.vertex_array(post, [(positions, "2f", "pos")])
block(post, "PostData", struct.pack("4f", 256, 256, 2, 0), 2)
mask = Image.new("RGBA", (256, 256)); ImageDraw.Draw(mask).rectangle((64, 64, 192, 192), fill=(255, 255, 255, 255))
mask_tex = ctx.texture((256, 256), 4, mask.tobytes()); mask_tex.use(0)
image_tex = ctx.texture((1, 1), 4, bytes([255, 255, 255, 255])); image_tex.use(1)
post["u_Texture"].value = 0; post["u_TextureI"].value = 1
for material in (-1, 0, 1, 2, 3):
    ubo = block(post, "ImageData", struct.pack("8f", .42, .58, 1, .6, material, 1, 0, 0), 3)
    fbo.clear(); post_vao.render()
    output = Image.frombytes("RGBA", (256, 256), fbo.read(components=4))
    assert output.getpixel((0, 0))[3] == 0 and abs(output.getpixel((128, 128))[3] - 153) <= 1, "Material overlays cannot leak outside the model or ignore opacity"
    ubo.release()

outline = ctx.program(vertex_shader=source("post-process/base.vert"), fragment_shader=source("post-process/outline.frag"))
outline_vao = ctx.vertex_array(outline, [(positions, "2f", "pos")])
block(outline, "PostData", struct.pack("4f", 256, 256, 0, 0), 2)
outline["u_Texture"].value = 0
for mode in range(3):
    ubo = block(outline, "OutlineData", struct.pack("ifif4f", 3, .2, mode, 2, 1, .22, 0, 0), 3)
    fbo.clear(); outline_vao.render()
    output = Image.frombytes("RGBA", (256, 256), fbo.read(components=4))
    assert output.getpixel((0, 0))[3] == 0, "ESP cannot tint unrelated pixels"
    assert (output.getpixel((128, 128))[3] > 0) == (mode != 0), "Respect line-only ESP mode"
    assert (output.getpixel((62, 128))[3] > 0) == (mode != 1), "Respect fill-only ESP mode"
    ubo.release()

out = ROOT / "build/visual-preview"; out.mkdir(parents=True, exist_ok=True)
sheet = Image.new("RGB", (1024, 294), (13, 15, 22)); draw = ImageDraw.Draw(sheet)
for i, (name, img) in enumerate(panels):
    sheet.paste(img.convert("RGB"), (i * 256, 30)); draw.text((i * 256 + 12, 10), name, fill=(220, 227, 245))
sheet.save(out / "esp-materials.png")

# Render the exported production box mesh and a Minecraft-proportioned mannequin.
geometry_path = out / "esp-geometry.json"
if geometry_path.exists():
    geometry = json.loads(geometry_path.read_text())
    sy, cy = math.sin(-.55), math.cos(-.55)
    sp, cp = math.sin(.22), math.cos(.22)

    def rotate(x, y, z):
        xx, zz = x * cy + z * sy, -x * sy + z * cy
        return xx, y * cp - zz * sp, y * sp + zz * cp

    def mesh_preview(parts, rounded, material, alpha=1):
        faces, lines = [], []
        mesh = geometry["rounded" if rounded else "square"]
        for ox, oy, oz, w, h, d, u, v, uw, vh in parts:
            for face in mesh["faces"]:
                nx, ny, nz = rotate(face["nx"], face["ny"], face["nz"])
                if nz < -.05:
                    continue
                values, depth = [], 0
                for i in range(4):
                    px, py, pz = face["vertices"][i * 3:i * 3 + 3]
                    rx, ry, rz = rotate(ox + px * w, oy + py * h, oz + pz * d)
                    depth += rz
                    tu = pz if abs(face["nx"]) > .6 else px
                    tv = pz if abs(face["ny"]) > .6 else 1 - py
                    light = int((.68 + .2 * max(ny, 0) + .12 * abs(nz)) * 255) / 255
                    values.append([rx * .82, ry * .82, u + tu * uw, v + tv * vh, light, light, light, alpha])
                faces.append((depth / 4, values))
            if rounded:
                for edge in mesh["edges"]:
                    a = rotate(ox + edge[0] * w, oy + edge[1] * h, oz + edge[2] * d)
                    b = rotate(ox + edge[3] * w, oy + edge[4] * h, oz + edge[5] * d)
                    lines.append(((a[0] * .82 + 1) * 128, (1 - a[1] * .82) * 128,
                                  (b[0] * .82 + 1) * 128, (1 - b[1] * .82) * 128))
        vertices = []
        for _, face in sorted(faces):
            for index in (0, 1, 2, 0, 2, 3):
                vertices.extend(face[index])
        buffer = ctx.buffer(struct.pack(f"{len(vertices)}f", *vertices))
        array = ctx.vertex_array(preview, [(buffer, "2f 2f 4f", "pos", "texCoords", "color")])
        uniform = block(preview, "MaterialData", struct.pack("8f", 0, material, 1, .22, .42, .58, 1, 1), 1)
        fbo.clear(); array.render()
        image = Image.frombytes("RGBA", (256, 256), fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        assert image.getpixel((0, 0))[3] == 0, "The 3D preview must stay inside its stage"
        background = Image.new("RGBA", image.size, (17, 20, 29, 255)); marks = ImageDraw.Draw(background)
        for yy in range(158, 240, 13):
            marks.line((22, yy, 234, yy), fill=(30, 34, 44, 255))
        background.alpha_composite(image)
        if rounded:
            overlay = Image.new("RGBA", image.size); stroke = ImageDraw.Draw(overlay)
            for line in lines:
                stroke.line(line, fill=(107, 148, 255, 200), width=1)
            background.alpha_composite(overlay)
        uniform.release(); array.release(); buffer.release()
        return background.convert("RGB")

    parts = [(-.25, .5, -.25, .5, .5, .5, .125, .125, .125, .125),
             (-.25, -.25, -.125, .5, .75, .25, .3125, .3125, .125, .1875),
             (-.5, -.25, -.125, .25, .75, .25, .6875, .3125, .0625, .1875),
             (.25, -.25, -.125, .25, .75, .25, .5625, .8125, .0625, .1875),
             (-.25, -1, -.125, .25, .75, .25, .0625, .3125, .0625, .1875),
             (0, -1, -.125, .25, .75, .25, .3125, .8125, .0625, .1875)]
    samples = [("Rounded / etched ESP", mesh_preview([(-.65, -.65, -.65, 1.3, 1.3, 1.3, 0, 0, 1, 1)], True, -1, .2))]
    samples += [(name, mesh_preview(parts, False, material, .8)) for material, (name, _) in enumerate(panels)]
    stage = Image.new("RGB", (256 * len(samples), 294), (13, 15, 22)); labels = ImageDraw.Draw(stage)
    for i, (name, image) in enumerate(samples):
        stage.paste(image, (i * 256, 30)); labels.text((i * 256 + 12, 10), name, fill=(220, 227, 245))
    stage.save(out / "esp-3d-previews.png")
print("ESP GPU checks passed: all model shader variants, pause/animation, custom accents, opacity, masked overlays, outline modes and preview shaders.")
