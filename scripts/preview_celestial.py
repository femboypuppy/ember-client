"""Export the production meshes to an offline, interactive WebGL review sheet."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "visual-preview"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--screenshot", action="store_true", help="Capture the sheet with an isolated headless Edge profile")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
    javac = str(Path(java_home) / "bin" / "javac") if java_home else shutil.which("javac")
    if not java or not javac:
        raise SystemExit("Set JAVA_HOME to a Java 21 JDK.")
    package = Path("meteordevelopment/meteorclient/utils/render")
    classes = ROOT / "build" / "visual-checks"
    sources = [ROOT / "src/main/java" / package / f"{name}.java" for name in ("CelestialMesh", "CelestialGeometry")]
    sources.append(ROOT / "src/lyricsChecks/java" / package / "CelestialVisualChecks.java")
    sources.append(ROOT / "src/lyricsChecks/java" / package / "CelestialPreviewImage.java")
    subprocess.run([javac, "-d", str(classes), *map(str, sources)], check=True)
    subprocess.run([java, "-cp", str(classes), "meteordevelopment.meteorclient.utils.render.CelestialVisualChecks", str(OUT / "geometry.json")], check=True)
    subprocess.run([java, "-Djava.awt.headless=true", "-cp", str(classes), "meteordevelopment.meteorclient.utils.render.CelestialPreviewImage", str(OUT / "celestial-preview.png")], check=True)
    data = [item for item in json.loads((OUT / "geometry.json").read_text()) if item["kind"] != "floor"]
    (OUT / "index.html").write_text(HTML.replace("__GEOMETRY__", json.dumps(data, separators=(",", ":"))), encoding="utf-8")
    print("Preview:", OUT / "index.html", flush=True)
    if args.screenshot:
        candidates = [Path(os.environ.get("PROGRAMFILES(X86)", "C:/Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe",
                      Path("C:/Program Files/Microsoft/Edge/Application/msedge.exe"),
                      Path("C:/Program Files/Google/Chrome/Application/chrome.exe"),
                      Path("C:/Program Files/BraveSoftware/Brave-Browser/Application/brave.exe")]
        edge = next((path for path in candidates if path.exists()), None)
        if edge is None:
            raise SystemExit("No Chromium browser found. Open the HTML preview in your browser.")
        command = [str(edge), "--headless=new", "--no-first-run", "--disable-extensions", "--hide-scrollbars",
                   "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--window-size=1600,1200",
                   "--virtual-time-budget=3000", "--user-data-dir=" + str(OUT / "edge-profile"),
                   "--screenshot=" + str(OUT / "browser-preview.png"), (OUT / "index.html").as_uri()]
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=55,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except subprocess.TimeoutExpired:
            print("Browser capture timed out; the software PNG and offline HTML are available.")
            return
        if result.returncode or not (OUT / "browser-preview.png").exists():
            print("Browser capture failed; the software PNG and offline HTML are available.")
            return
        print("Browser screenshot:", OUT / "browser-preview.png")


HTML = r'''<!doctype html><html lang="en"><meta charset="utf-8"><title>Ember · Celestial geometry</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#08090e;color:#f2f0f9;font:14px 'Segoe UI',sans-serif}main{max-width:1480px;margin:0 auto;padding:36px 34px}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:25px}.eyebrow{color:#ad95df;font-size:11px;letter-spacing:3px}h1{font-size:31px;font-weight:500;letter-spacing:-1px;margin:8px 0}p{margin:0;color:#9492a6;font-size:12px}label{display:flex;align-items:center;gap:12px;color:#c1b8d6}input{background:transparent;border:1px solid #3c334d;padding:3px;width:40px;height:30px;border-radius:8px}.hero{height:270px;position:relative;border:1px solid #242130;border-radius:18px;overflow:hidden;background:#0b0c13;margin-bottom:22px}.hero canvas{width:100%;height:100%;position:absolute}.hero .caption{position:absolute;left:28px;top:26px}.caption h2{font-weight:450;letter-spacing:-.5px;font-size:24px;margin:7px 0 9px}.hero aside{position:absolute;left:50%;top:64px;width:45%;display:flex;gap:12px}.panel{padding:17px 18px;width:31%;border:1px solid #9d82d522;border-radius:13px;background:#14121d9c;box-shadow:0 8px 30px #0005;backdrop-filter:blur(6px)}.panel small{font-size:10px;color:#eee5fc}.line{height:5px;border-radius:4px;background:#a790cf44;margin-top:13px}.line:nth-child(2n){width:75%;background:#aca4c128}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:17px}.card{height:322px;position:relative;border:1px solid #262231;background:linear-gradient(145deg,#14121c,#0b0c12);border-radius:16px;overflow:hidden}.card canvas{width:100%;height:100%;display:block}.card .label{position:absolute;top:19px;left:20px;pointer-events:none}.card h3{font-size:15px;font-weight:500;margin:0 0 5px}.card small{font-size:10px;color:#8b859c}.tag{position:absolute;bottom:14px;left:20px;color:#796e8f;font-size:10px}.foot{font-size:10px;color:#746e80;margin-top:18px;letter-spacing:.3px}
</style><main><header><div><div class="eyebrow">EMBER / CELESTIAL COLLECTION</div><h1>Light, motion & atmosphere.</h1><p>Production effect geometry · drag a wing to inspect its silhouette · choose an accent to recolor every surface</p></div><label>Client accent <input id="accent" type="color" value="#a78be0"></label></header>
<section class="hero"><canvas id="ambient"></canvas><div class="caption"><div class="eyebrow">ATMOSPHERE</div><h2>A little closer to the stars.</h2><p>Diffuse aurora, soft starlight and subtle constellations.</p></div><aside><div class="panel"><small>RENDER</small><div class="line"></div><div class="line"></div><div class="line"></div></div><div class="panel"><small>APPEARANCE</small><div class="line"></div><div class="line"></div><div class="line"></div></div><div class="panel"><small>MUSIC</small><div class="line"></div><div class="line"></div><div class="line"></div></div></aside></section><section class="grid" id="grid"></section><div class="foot">Geometry preview, not a Minecraft screenshot. The live effects use body attachment, depth testing, frame-independent flutter, pulse expansion and fading.</div></main>
<script>const geometry=__GEOMETRY__;const tint=document.querySelector('#accent');let rgb=[167/255,139/255,224/255];tint.oninput=()=>{const n=parseInt(tint.value.slice(1),16);rgb=[n>>16&255,n>>8&255,n&255].map(x=>x/255);for(const draw of redraw)draw()};const redraw=[];
const captions={Astral:'Translucent feathers · scattered starlight',Seraph:'Pearlescent plumage · soft luminous quills',Prism:'Detached crystal blades · angular highlights',Ripple:'Liquid light · layered concentric ribbons',Orbit:'Orbital stars · rotating luminous bands',Sigil:'Celestial dial · engraved radial details'};
for(const item of geometry){let canvas;if(item.kind==='atmosphere')canvas=document.querySelector('#ambient');else{const card=document.createElement('article');card.className='card';card.innerHTML=`<canvas></canvas><div class="label"><h3>${item.name}</h3><small>${captions[item.name]}</small></div><div class="tag">${item.kind==='wings'?'WINGS / F5 THIRD PERSON':'CUSTOM CIRCLES / GROUND PULSE'}</div>`;document.querySelector('#grid').append(card);canvas=card.querySelector('canvas')}mount(canvas,item)}
function mount(canvas,item){const gl=canvas.getContext('webgl2',{alpha:true,antialias:true,preserveDrawingBuffer:true});if(!gl)return;let yaw=-.08,drag=false,last=0;
function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s}
const program=gl.createProgram();gl.attachShader(program,shader(gl.VERTEX_SHADER,`#version 300 es
precision highp float;layout(location=0)in vec3 p;layout(location=1)in vec4 c;uniform vec2 size;uniform vec3 tint;uniform float yaw;uniform int kind;out vec4 col;void main(){vec2 q;if(kind==0){vec3 a=p;a.y-=1.18;float xx=a.x*cos(yaw)+a.z*sin(yaw);q=vec2(xx*.55/(size.x/size.y),a.y*.67-.13);}else if(kind==1){q=vec2((p.x*.92+p.z*.18)*.67/(size.x/size.y),(-p.z*.43+p.y*.65)*.67-.05);}else q=vec2(p.x/1600.*2.-1.,1.-p.y/900.*2.);vec3 base=vec3(167.,139.,224.)/255.;vec3 f=(c.rgb-base)/(vec3(1.)-base);float pearl=clamp(max(f.r,max(f.g,f.b)),0.,1.);col=vec4(mix(tint,vec3(1.),pearl),c.a);gl_Position=vec4(q,0.,1.);}`));gl.attachShader(program,shader(gl.FRAGMENT_SHADER,`#version 300 es
precision highp float;in vec4 col;out vec4 result;void main(){result=col;}`));gl.linkProgram(program);gl.useProgram(program);
const pos=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,pos);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(item.positions),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,0,0);const colors=new Float32Array(item.colors.length*4);item.colors.forEach((c,i)=>{colors.set([(c>>>16&255)/255,(c>>>8&255)/255,(c&255)/255,(c>>>24)/255],i*4)});const cb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,cb);gl.bufferData(gl.ARRAY_BUFFER,colors,gl.STATIC_DRAW);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,4,gl.FLOAT,false,0,0);gl.enable(gl.BLEND);gl.blendFuncSeparate(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
const us=gl.getUniformLocation(program,'size'),ut=gl.getUniformLocation(program,'tint'),uy=gl.getUniformLocation(program,'yaw'),uk=gl.getUniformLocation(program,'kind');function draw(){const d=devicePixelRatio||1;canvas.width=Math.round(canvas.clientWidth*d);canvas.height=Math.round(canvas.clientHeight*d);gl.viewport(0,0,canvas.width,canvas.height);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.uniform2f(us,canvas.width,canvas.height);gl.uniform3fv(ut,rgb);gl.uniform1f(uy,yaw);gl.uniform1i(uk,item.kind==='wings'?0:item.kind==='circle'?1:2);gl.drawArrays(gl.TRIANGLES,0,item.colors.length)}redraw.push(draw);draw();window.addEventListener('resize',draw);canvas.onpointerdown=e=>{if(item.kind!=='wings')return;drag=true;last=e.clientX;canvas.setPointerCapture(e.pointerId)};canvas.onpointerup=()=>drag=false;canvas.onpointermove=e=>{if(drag){yaw+=(e.clientX-last)*.012;last=e.clientX;draw()}}}
</script></html>'''


if __name__ == "__main__":
    main()
