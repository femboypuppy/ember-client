"""Exercise production HUD font ownership across the HUD and menu notification passes."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'src/main/java/meteordevelopment/meteorclient/systems/hud/HudRenderer.java'
source = path.read_text(encoding='utf-8')

def block(marker, text=source):
    start = text.index(marker)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

fields = source[source.index('    private final Int2ObjectMap<FontHolder>'):source.index('    public DrawContext drawContext;')].replace('HudRenderer::loadFont', 'HudFontChecks::loadFont')
methods = '\n'.join(block(marker) for marker in [
    'private static int nominalHeight', 'private static double renderedHeight', 'private static int atlasHeight',
    'private static double renderScale', 'private FontHolder getFontHolder', 'private void onCustomFontChanged',
    'private static FontHolder loadFont', 'private static class FontHolder',
])
flush = block('if (hud.hasCustomFont())', block('public void end()'))
test = '''import com.google.common.cache.*;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;import java.time.Duration;import java.nio.ByteBuffer;import java.io.IOException;
public class HudFontChecks {
 static final double SCALE_TO_HEIGHT=1.0/18.0; static final int MAX_ATLAS_HEIGHT=80;
 static final Client mc=new Client();static class Client{Object getFramebuffer(){return null;}}
 final Hud hud=new Hud();static class Hud{double getTextScale(){return 1;}boolean hasCustomFont(){return true;}}
 static class CustomFontChangedEvent{}
 static class Texture{boolean closed;void close(){require(!closed,"Atlas closed twice");closed=true;closedCount++;}Object getGlTextureView(){return this;}Object getSampler(){return this;}}
 static int loaded,closedCount,drawn;
 static class Font{final int height;final Texture texture=new Texture();Font(ByteBuffer b,int h){height=h;loaded++;}int getHeight(){return height;}}
 static class Fonts{static final Face RENDERER=new Face();static class Face{final Face fontFace=this;ByteBuffer readToDirectByteBuffer()throws IOException{return ByteBuffer.allocate(1);}}}
 static class MeteorRenderPipelines{static final Object UI_TEXT=new Object();}
 static class MeshBuilder{boolean building;int vertices;MeshBuilder(Object p){}boolean isBuilding(){return building;}void begin(){require(!building,"Mesh already building");building=true;vertices=0;}}
 static class MeshRenderer{MeshBuilder mesh;static MeshRenderer begin(){return new MeshRenderer();}MeshRenderer attachments(Object o){return this;}MeshRenderer pipeline(Object p){return this;}MeshRenderer mesh(MeshBuilder m){mesh=m;return this;}MeshRenderer sampler(String name,Object v,Object s){require(!((Texture)v).closed,"Drawing a destroyed atlas");return this;}void end(){if(mesh.vertices>0)drawn++;mesh.building=false;}}
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 FIELDS
 METHODS
 void flush(){FLUSH}
 void measure(double scale){getFontHolder(scale,false);}
 void draw(double scale){getFontHolder(scale,true).getMesh().vertices++;}
 public static void main(String[] args){
  HudFontChecks r=new HudFontChecks();double[] scales={.55,.75,.9,1,1.2,1.4};
  for(int frame=0;frame<1000;frame++){
   for(double s:scales){r.measure(s);r.draw(s);}
   int before=drawn;r.flush();require(drawn-before==scales.length,"One draw per used atlas");
   // Even an empty notification overlay measures text for its layout.
   r.measure(.71);r.measure(.88);r.measure(.95);
   before=drawn;r.flush();require(drawn==before,"Empty overlay must not redraw HUD text");
   if(frame==0)require(loaded==6,"Expected six distinct atlas sizes");
   require(loaded==6,"Menu overlay recreated font atlases after warmup: frame="+frame+", loads="+loaded);
   if(frame%10==0){r.draw(.9);before=drawn;r.flush();require(drawn==before+1,"Notifications retain their own draw batch");}
  }
  for(int i=1;i<=10000;i++){double scale=i/1000.0;r.measure(scale);r.draw(scale);r.flush();}
  require(loaded==13,"The entire size ladder must stay bounded at 13 atlases: "+loaded);
  require(closedCount==0,"Active ladder must not thrash textures");
  r.draw(1); // Changing the font also discards any not-yet-flushed old-font text.
  r.onCustomFontChanged(new CustomFontChangedEvent());
  require(closedCount==13,"Font change closes every atlas exactly once");
  require(r.fontCache.size()==0 && r.fontsInUse.isEmpty(),"Font change clears cached and pending fonts");
  r.flush();
  r.draw(1);r.flush();require(loaded==14,"Changed font loads a fresh atlas");
  System.out.println("HUD font checks passed: 1,000 HUD/menu pairs without atlas recreation, independent text batches, 13-size bound and exact font-change cleanup.");
 }
}'''.replace('FIELDS', fields).replace('METHODS', methods).replace('FLUSH', flush)

gradle = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
jars = []
for group, artifact in [('com.google.guava', 'guava'), ('com.google.guava', 'failureaccess'), ('it.unimi.dsi', 'fastutil')]:
    jars.append(next(p for p in (gradle / group / artifact).rglob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
java = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
with tempfile.TemporaryDirectory(prefix='ember-hud-fonts-') as directory:
    work = Path(directory)
    (work / 'HudFontChecks.java').write_text(test, encoding='utf-8')
    cp = os.pathsep.join(map(str, jars))
    subprocess.run([str(java / 'javac'), '-cp', cp, '-encoding', 'UTF-8', '-d', str(work), str(work / 'HudFontChecks.java')], check=True)
    subprocess.run([str(java / 'java'), '-cp', str(work) + os.pathsep + cp, 'HudFontChecks'], check=True)
