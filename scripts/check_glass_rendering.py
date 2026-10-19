"""Optional GPU comparison of single-pane and instanced production glass shaders."""
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/ui-tools'))
import moderngl

ctx = moderngl.create_standalone_context(require=330)
shaders = ROOT / 'src/main/resources/assets/ember-client/shaders'
fragment = (shaders / 'liquid_glass.frag').read_text()
single = ctx.program(vertex_shader=(shaders / 'pos_tex_color.vert').read_text(), fragment_shader=fragment)
batch = ctx.program(vertex_shader=(shaders / 'liquid_glass_batch.vert').read_text(),
                    fragment_shader=fragment.replace('#version 330 core', '#version 330 core\n#define GLASS_BATCH'))
assert batch['GlassBatchData'].size == 48 + 64 * 32
quad = ctx.buffer(struct.pack('12f', -1,-1, 1,-1, 1,1, -1,-1, 1,1, -1,1))
batch_vao = ctx.vertex_array(batch, [(quad, '2f', 'pos')])
ctx.enable(moderngl.BLEND)
ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
checks = 0
for w,h in [(480,270), (1280,720), (777,431)]:
    # Column-major pixel projection, with downward Y matching Minecraft's HUD.
    projection = (2/w,0,0,0, 0,-2/h,0,0, 0,0,-1,0, -1,1,0,1)
    identity = (1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1)
    mesh = ctx.buffer(struct.pack('32f', *projection, *identity))
    mesh.bind_to_uniform_block(0)
    for p in [single,batch]:
        p['MeshData'].binding = 0
        p['u_Texture'].value = 0
    scene = ctx.texture((w,h), 4, bytes(value for y in range(h) for x in range(w)
        for value in (x*255//w, y*255//h, (x+y)*191//(w+h), 255)))
    scene.filter = moderngl.LINEAR, moderngl.LINEAR
    scene.repeat_x = scene.repeat_y = False
    scene.use(0)
    outputs = [ctx.simple_framebuffer((w,h), components=4) for _ in range(2)]
    for n in [1, 9, 64, 65, 129]:
        panes = [(float(i*37 % w-12), float(i*29 % h-8), 91.5, 33.25,
                  float(i%13), (i%8)/7) for i in range(n)]
        for mode,output in enumerate(outputs):
            output.use(); output.clear(.1,.15,.2,1)
            ctx.viewport = 0,0,w,h
            if mode == 0:
                single['GlassData'].binding = 1
                for x,y,pw,ph,radius,opacity in panes:
                    opacity = int(255*opacity)/255
                    vertices=[]
                    for u,v in [(0,0),(1,0),(1,1),(0,0),(1,1),(0,1)]:
                        vertices.extend((x-1+u*(pw+2),y-1+v*(ph+2),u,v,1,1,1,opacity))
                    vbo=ctx.buffer(struct.pack(f'{len(vertices)}f',*vertices))
                    vao=ctx.vertex_array(single,[(vbo,'2f 8x 4f','pos','color')])
                    data=ctx.buffer(struct.pack('16f', x,y,pw,ph,radius,3.25,.65,.7,.6,.4,.9,.12,w,h,w/3,h/2))
                    data.bind_to_uniform_block(1);vao.render(moderngl.TRIANGLES)
                    data.release();vao.release();vbo.release()
            else:
                batch['GlassBatchData'].binding=1
                for first in range(0,n,64):
                    chunk=panes[first:first+64]
                    values=[3.25,.65,.7,.12,.6,.4,.9,0,w,h,w/3,h/2]
                    for x,y,pw,ph,radius,opacity in chunk:
                        values.extend((x,y,pw,ph,radius,int(255*opacity)/255,0,0))
                    values.extend([0]*((64-len(chunk))*8))
                    data=ctx.buffer(struct.pack(f'{len(values)}f',*values))
                    data.bind_to_uniform_block(1);batch_vao.render(moderngl.TRIANGLES,instances=len(chunk));data.release()
        a,b=[output.read(components=4) for output in outputs]
        error=max(abs(x-y) for x,y in zip(a,b))
        assert error <= 1, (w,h,n,error)
        checks+=1
    for output in outputs:output.release()
    mesh.release();scene.release()
print(f'Glass GPU checks passed: {checks} pixel comparisons, overlaps, opacity, odd sizes and 64-pane batch boundaries; maximum difference 1/255.')
