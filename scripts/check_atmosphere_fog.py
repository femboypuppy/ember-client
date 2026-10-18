"""Execute the production fog hooks at 8/24/64 chunks, including disabled and liquid cases."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "src/main/java/meteordevelopment/meteorclient/mixin/FogRendererMixin.java").read_text(encoding="utf-8")

def method(signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

HARNESS = '''import java.util.*;
import meteordevelopment.meteorclient.utils.render.AtmosphereFog;
public class FogHookChecks {
 static class Vector4f {float x,y,z,w;Vector4f(float x,float y,float z,float w){this.x=x;this.y=y;this.z=z;this.w=w;}}
 static class Args {final Object[] args;Args(Object... args){this.args=args;}@SuppressWarnings("unchecked") <T>T get(int i){return (T)args[i];}void set(int i,Object v){args[i]=v;}}
 static class Setting<T>{T value;Setting(T v){value=v;}T get(){return value;}}
 static class Config {static final Config INSTANCE=new Config();static Config get(){return INSTANCE;}final Setting<Double> fogStart=new Setting<>(48.0),fogEnd=new Setting<>(224.0),fogAmount=new Setting<>(.3);}
 static class EmberWorldAtmosphere {static boolean haze=true;static boolean fogHazeEnabled(){return haze;}}
 static class NoRender {static boolean hide;boolean noFog(){return hide;}}
 static class Modules {static boolean present=true;static final Modules INSTANCE=new Modules();static Modules get(){return present?INSTANCE:null;}@SuppressWarnings("unchecked") <T>T get(Class<T> type){return (T)new NoRender();}}
 // HOOKS
 static void require(boolean c,String message){if(!c)throw new AssertionError(message);}
 public static void main(String[] args){
  FogHookChecks hook=new FogHookChecks();
  for(int chunks:new int[]{8,24,64}){
   Vector4f color=new Vector4f(.3f,.2f,.5f,1);
   Args data=new Args(null,0,color,0f,100000f,chunks*16*.9f,chunks*16f,chunks*16f,128f);
   hook.ember$haze(data);
   require((float)data.get(3)==48&&(float)data.get(4)==224,"Fog distance cannot scale with chunk view distance");
   Vector4f result=data.get(2);require(Math.abs(result.w-.3)<1e-6&&result.x==color.x&&color.w==1,"Fog opacity uses a fresh color without mutating the sky color");
   require((float)data.get(5)==chunks*16*.9f&&(float)data.get(6)==chunks*16f,"Vanilla render-distance fade distances remain intact");
   for(float distance:new float[]{16,48,136,224,384}){
    float value=Math.clamp((distance-(float)data.get(3))/((float)data.get(4)-(float)data.get(3)),0,1)*result.w;
    require(value>=0&&value<=.300001,"Custom density caps haze at the configured opacity");
    if(distance<=48)require(value==0,"Nearby terrain must stay clear");
    if(distance==384)require(Math.abs(value-.3)<1e-6,"Fog must be visible at a 24-chunk horizon");
   }
  }
  NoRender.hide=true;require(hook.modifyFogEnabled(false),"Explicit custom haze must get a fog buffer even when vanilla fog is hidden");
  EmberWorldAtmosphere.haze=false;Args liquid=new Args(null,0,new Vector4f(.1f,.2f,.3f,1),0f,6f,0f,384f,384f,128f);
  Object[] before=liquid.args.clone();hook.ember$haze(liquid);require(Arrays.equals(before,liquid.args),"Liquid/disabled haze must retain native distances and opacity");
  require(!hook.modifyFogEnabled(true),"NoRender resumes control when custom haze is off");NoRender.hide=false;
  require(hook.modifyFogEnabled(true)&&!hook.modifyFogEnabled(false),"Native fog toggle resumes when custom haze is off");
  System.out.println("Fog hook checks passed: 8/24/64 chunks, explicit near/far distances, opacity cap, nearby clarity, liquid preservation and vanilla-fog removal compatibility.");
 }
}'''
HARNESS = HARNESS.replace("// HOOKS", method("private void ember$haze(") + "\n" + method("private boolean modifyFogEnabled("))
home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
javac = str(home / "bin/javac.exe") if os.name == "nt" else shutil.which("javac")
java = str(home / "bin/java.exe") if os.name == "nt" else shutil.which("java")
with tempfile.TemporaryDirectory(prefix="ember-fog-checks-") as directory:
    root = Path(directory)
    (root / "FogHookChecks.java").write_text(HARNESS, encoding="utf-8")
    relative = "meteordevelopment/meteorclient/utils/render/AtmosphereFog.java"
    target = root / relative
    target.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / "src/main/java" / relative, target)
    subprocess.run([javac, "-encoding", "UTF-8", "-d", directory, *[str(p) for p in root.rglob("*.java")]], check=True)
    subprocess.run([java, "-cp", directory, "FogHookChecks"], check=True)
