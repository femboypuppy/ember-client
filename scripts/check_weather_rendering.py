"""Render exported production weather meshes and Minecraft's real fog shader on a GPU."""
from pathlib import Path
from zipfile import ZipFile
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/ui-tools"))
import moderngl
from PIL import Image, ImageDraw, ImageChops

OUT = ROOT / "build/visual-preview"
items = json.loads((OUT / "geometry.json").read_text())
ctx = moderngl.create_standalone_context(require=330)
shaders = ROOT / "src/main/resources/assets/ember-client/shaders"
program = ctx.program(vertex_shader=(shaders / "pos_color.vert").read_text(), fragment_shader=(shaders / "pos_color.frag").read_text())
program["MeshData"].binding = 0

def vao(positions, colors):
    values = []
    for i, c in enumerate(colors):
        x, y, z = positions[i*3:i*3+3]
        values.extend((x, y, -z, (c >> 16 & 255)/255, (c >> 8 & 255)/255, (c & 255)/255, (c >> 24)/255))
    buffer = ctx.buffer(struct.pack(f"{len(values)}f", *values))
    return ctx.vertex_array(program, [(buffer, "3f 4f", "pos", "color")])

weather = {item["name"]: vao(item["positions"], item["colors"]) for item in items if item["kind"].startswith("weather-")}
ground_pos, ground_colors = [], []
for x in range(-22,22,2):
    for z in range(-24,8,2):
        y = -1.5 if x < 0 else -2
        color = (0xFF303438 if (x+z)%4 else 0xFF383B3F) if x < 0 else (0xFF173B51 if (x+z)%4 else 0xFF1B4359)
        for px, pz in [(x,z),(x+2,z),(x+2,z+2),(x+2,z+2),(x,z+2),(x,z)]:
            ground_pos.extend((px,y,-pz));ground_colors.append(color)
ground = vao(ground_pos, ground_colors)

def render(kind=None, depth=1):
    w,h=640,360
    texture=ctx.texture((w,h),4);zbuffer=ctx.depth_renderbuffer((w,h));fbo=ctx.framebuffer([texture],zbuffer)
    fbo.use();fbo.clear(.035,.045,.07,1,depth=depth);ctx.viewport=(0,0,w,h)
    ctx.enable(moderngl.DEPTH_TEST|moderngl.BLEND);ctx.depth_func="<=";ctx.depth_mask=True
    ctx.blend_func=moderngl.SRC_ALPHA,moderngl.ONE_MINUS_SRC_ALPHA
    f=1/math.tan(math.radians(70)/2);near,far=.1,256
    projection=(f/(w/h),0,0,0,0,f,0,0,0,0,(far+near)/(near-far),-1,0,0,2*far*near/(near-far),0)
    c,s=math.cos(.12),math.sin(.12)
    view=(1,0,0,0,0,c,s,0,0,-s,c,0,0,0,0,1)
    uniform=ctx.buffer(struct.pack("32f",*(projection+view)));uniform.bind_to_uniform_block(0)
    ground.render(moderngl.TRIANGLES)
    ctx.depth_mask=False
    if kind:weather[kind].render(moderngl.TRIANGLES)
    result=Image.frombytes("RGBA",(w,h),fbo.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    ctx.depth_mask=True
    for resource in (uniform,fbo,zbuffer,texture):resource.release()
    return result

base=render();occluded=render(depth=.01)
sheet=Image.new("RGB",(1280,1155),(8,9,14));draw=ImageDraw.Draw(sheet)
for i,kind in enumerate(weather):
    image=render(kind)
    assert ImageChops.difference(base,image).convert("RGB").getbbox(), f"{kind} particles must be visible with the production shader"
    assert render(kind,depth=.01).tobytes()==occluded.tobytes(), f"{kind} must remain behind terrain"
    x,y=i%2*640,i//2*385
    sheet.paste(image,(x,y+25));draw.text((x+12,y+7),kind.upper()+" / SOLID LEFT, WATER RIGHT",fill=(225,225,240))
sheet.save(OUT / "weather-preview.png")

game=next(p for p in (ROOT/".gradle/loom-cache/minecraftMaven").rglob("*.jar") if not p.name.endswith("-sources.jar"))
with ZipFile(game) as jar:native_fog=jar.read("assets/minecraft/shaders/include/fog.glsl").decode()
fog=ctx.program(vertex_shader='''#version 330
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}''',fragment_shader=native_fog+'''
out vec4 color;uniform float distanceValue;
void main(){color=apply_fog(vec4(.4,.35,.3,1),distanceValue,distanceValue,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);}''')
fog["Fog"].binding=1;quad=ctx.vertex_array(fog,[])
def fog_pixel(distance,density=.3):
    texture=ctx.texture((4,4),4);fbo=ctx.framebuffer([texture]);fbo.use();ctx.viewport=(0,0,4,4)
    ctx.disable(moderngl.DEPTH_TEST|moderngl.BLEND)
    uniform=ctx.buffer(struct.pack("12f",.25,.2,.4,density,48,224,345.6,384,384,128,0,0));uniform.bind_to_uniform_block(1)
    fog["distanceValue"].value=distance;quad.render(moderngl.TRIANGLES,vertices=3)
    pixel=tuple(fbo.read(components=4)[:4])
    for resource in (uniform,fbo,texture):resource.release()
    return pixel
assert fog_pixel(16)==fog_pixel(48)==fog_pixel(384,0),"Near terrain must stay clear"
assert fog_pixel(136)!=fog_pixel(48),"Mid-distance haze must be visible"
assert fog_pixel(224)==fog_pixel(384),"At 24 chunks, distant haze reaches the selected density"
expected=tuple(round((a*.7+b*.3)*255) for a,b in zip((.4,.35,.3),(.25,.2,.4)))
assert all(abs(a-b)<=1 for a,b in zip(fog_pixel(384)[:3],expected)),"The real Minecraft fog shader must honor the 30% opacity cap"
print("Weather GPU checks passed: five distinct production meshes, terrain occlusion, near clarity and native Minecraft fog at 24 chunks.")
print("Preview:",OUT/"weather-preview.png")
ctx.release()
