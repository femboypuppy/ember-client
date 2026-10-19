"""GPU checks of production bloom, depth fog and exported scene-effect meshes (optional local check)."""
from pathlib import Path
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/ui-tools'))
import moderngl
from PIL import Image, ImageDraw, ImageChops

SHADERS = ROOT / 'src/main/resources/assets/ember-client/shaders'
OUT = ROOT / 'build/visual-preview'
OUT.mkdir(parents=True, exist_ok=True)
ctx = moderngl.create_standalone_context(require=330)
quad = ctx.buffer(struct.pack('12f', -1,-1, 1,-1, 1,1, -1,-1, 1,1, -1,1))
programs = {}
vaos = {}
for name in ['scene_fog', 'bloom_extract', 'bloom_composite', 'blur_down', 'blur_up']:
    program = ctx.program(vertex_shader=(SHADERS/'blur.vert').read_text(), fragment_shader=(SHADERS/(name+'.frag')).read_text())
    programs[name] = program
    vaos[name] = ctx.vertex_array(program, [(quad, '2f', 'pos')])

def target(w, h):
    texture = ctx.texture((w,h), 4)
    texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
    texture.repeat_x = texture.repeat_y = False
    return texture, ctx.framebuffer([texture])

def draw(name, output, source, block, data, blend=False):
    output.use()
    ctx.viewport = (0,0,*output.size)
    ctx.disable(moderngl.DEPTH_TEST)
    if blend: ctx.enable(moderngl.BLEND)
    else: ctx.disable(moderngl.BLEND)
    source.use(0)
    program = programs[name]
    program['u_Depth' if name == 'scene_fog' else 'u_Texture'].value = 0
    program[block].binding = 0
    ubo = ctx.buffer(struct.pack(f'{len(data)}f', *data))
    ubo.bind_to_uniform_block(0)
    vaos[name].render(moderngl.TRIANGLES)
    ubo.release()

# Fog uses actual projection inversion and radial depth, not render-distance uniforms.
w,h=600,180
near,far=.05,1024
fx=1/math.tan(math.radians(70)/2)/(w/h)
fy=fx*(w/h)
a=(far+near)/(near-far)
b=2*far*near/(near-far)
inverse=(1/fx,0,0,0, 0,1/fy,0,0, 0,0,0,1/b, 0,0,-1,a/b)
distances=[16,48,136,224,384,None]
depths=[]
for y in range(h):
    for x in range(w):
        distance=distances[x//100]
        if distance is None:depths.append(1);continue
        ray=math.sqrt(((2*(x+.5)/w-1)/fx)**2+((2*(y+.5)/h-1)/fy)**2+1)
        view_z=-distance/ray
        depths.append((a*view_z+b)/(-view_z)*.5+.5)
depth=ctx.texture((w,h),1,struct.pack(f'{len(depths)}f',*depths),dtype='f4')
depth.filter=(moderngl.NEAREST,moderngl.NEAREST)
fog,fog_fbo=target(w,h)
draw('scene_fog',fog_fbo,depth,'SceneFogData',(*inverse,.4,.55,.9,1,48,224,.6,0))
pixels=fog_fbo.read(components=4)
values=[pixels[((h//2)*w+i*100+50)*4+3]/255 for i in range(6)]
assert values[0]==0 and values[1]<.01 and .27<values[2]<.33 and abs(values[3]-.6)<.015 and abs(values[4]-.6)<.015 and values[5]==0, values
fog_image=Image.frombytes('RGBA',(w,h),pixels).convert('RGB')
fog_image.save(OUT/'scene-fog.png')
draw('scene_fog',fog_fbo,depth,'SceneFogData',(*inverse,.4,.55,.9,1,48,224,0,0))
assert max(fog_fbo.read(components=4)[3::4])==0, 'Zero fog density must be transparent'

# A bright source must glow into nearby pixels while the dark scene stays sharp.
w,h=512,256
image=Image.new('RGBA',(w,h),(15,20,28,255))
painter=ImageDraw.Draw(image)
painter.rectangle((245,100,265,156),fill=(210,235,255,255))
source=ctx.texture((w,h),4,image.tobytes());source.filter=(moderngl.LINEAR,moderngl.LINEAR)
levels=[target(max(1,w>>(i+1)),max(1,h>>(i+1))) for i in range(4)]
settings=(.78,.65,0,0)
draw('bloom_extract',levels[0][1],source,'BloomData',settings)
for i in range(1,4):
    tw,th=levels[i-1][0].size
    draw('blur_down',levels[i][1],levels[i-1][0],'BlurData',(.5/tw,.5/th,1.6,0))
for i in range(3,0,-1):
    tw,th=levels[i][0].size
    draw('blur_up',levels[i-1][1],levels[i][0],'BlurData',(.5/tw,.5/th,1.6,0))
result,result_fbo=target(w,h);result.write(image.tobytes())
ctx.blend_func=(moderngl.SRC_ALPHA,moderngl.ONE)
draw('bloom_composite',result_fbo,levels[0][0],'BloomData',settings,True)
after=Image.frombytes('RGBA',(w,h),result_fbo.read(components=4))
assert sum(after.getpixel((230,128))[:3]) > sum(image.getpixel((230,128))[:3])+12, 'Bloom must spread beyond bright geometry'
assert after.getpixel((20,20))[:3]==image.getpixel((20,20))[:3], 'Distant dark pixels remain sharp and unchanged'
result.write(image.tobytes())
draw('bloom_composite',result_fbo,levels[0][0],'BloomData',(.78,0,0,0),True)
assert ImageChops.difference(image.convert('RGB'),Image.frombytes('RGBA',(w,h),result_fbo.read(components=4)).convert('RGB')).getbbox() is None, 'Zero bloom strength restores the scene exactly'
sheet=Image.new('RGB',(w*2,h));sheet.paste(image.convert('RGB'));sheet.paste(after.convert('RGB'),(w,0));sheet.save(OUT/'scene-bloom.png')

# Render production geometry with the same shader and additive depth-tested pipeline.
program=ctx.program(vertex_shader=(SHADERS/'pos_color.vert').read_text(),fragment_shader=(SHADERS/'pos_color.frag').read_text())
program['MeshData'].binding=0
meshes=json.loads((OUT/'scene-effects.json').read_text())
w,h=640,400
f=1/math.tan(math.radians(60)/2);near,far=.1,256
projection=(f/(w/h),0,0,0, 0,f,0,0, 0,0,(far+near)/(near-far),-1, 0,0,2*far*near/(near-far),0)
identity=(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1)
ubo=ctx.buffer(struct.pack('32f',*(projection+identity)));ubo.bind_to_uniform_block(0)
sheet=Image.new('RGB',(w*3,h*2))
for index,item in enumerate(meshes):
    data=[]
    for i,c in enumerate(item['colors']):
        x,y,z=item['positions'][i*3:i*3+3]
        data.extend((x,y,-z,(c>>16&255)/255,(c>>8&255)/255,(c&255)/255,(c>>24)/255))
    vbo=ctx.buffer(struct.pack(f'{len(data)}f',*data));vao=ctx.vertex_array(program,[(vbo,'3f 4f','pos','color')])
    texture=ctx.texture((w,h),4);zbuffer=ctx.depth_renderbuffer((w,h));output=ctx.framebuffer([texture],zbuffer)
    output.use();ctx.viewport=(0,0,w,h);output.clear(.025,.03,.05,1,depth=1)
    ctx.enable(moderngl.DEPTH_TEST|moderngl.BLEND);ctx.depth_func='<=';ctx.depth_mask=False;ctx.blend_func=(moderngl.SRC_ALPHA,moderngl.ONE)
    vao.render(moderngl.TRIANGLES)
    rendered=Image.frombytes('RGBA',(w,h),output.read(components=4)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).convert('RGB')
    output.clear(.025,.03,.05,1,depth=0);vao.render(moderngl.TRIANGLES)
    blocked=Image.frombytes('RGBA',(w,h),output.read(components=4)).convert('RGB')
    assert ImageChops.difference(blocked,Image.new('RGB',(w,h),blocked.getpixel((0,0)))).getbbox() is None, item['name']+' must respect terrain depth'
    assert ImageChops.difference(rendered,blocked).getbbox(), item['name']+' must draw visible geometry'
    if item['name'] == 'curved trails':
        a, b = rendered.tobytes(), blocked.tobytes()
        changed = sum(1 for p in range(0,len(a),3) if max(abs(a[p+i]-b[p+i]) for i in range(3)) > 10)
        assert changed > 30, 'Curved trails must be visibly distributed in front of the camera'
    ImageDraw.Draw(rendered).text((15,12),item['name'],fill='white')
    sheet.paste(rendered,((index%3)*w,(index//3)*h))
    ctx.depth_mask=True
    for obj in [vao,vbo,output,zbuffer,texture]:obj.release()
sheet.save(OUT/'scene-effects.png')
print('GPU scene checks passed: projection-correct fog distances, clear nearby terrain/sky, zero fog, threshold bloom, glow spread, sharp dark pixels, zero bloom strength, rendered ambient/aura/halo/ribbon meshes and terrain occlusion.')
