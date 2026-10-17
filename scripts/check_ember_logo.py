"""Check the production logo's motion, bounds, primitive budget and deferred texture draw."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "meteordevelopment/meteorclient/"
SOURCES = {
    "net/minecraft/util/Identifier.java": "package net.minecraft.util;public record Identifier(String path){}",
    PACKAGE + "MeteorClient.java": '''package meteordevelopment.meteorclient;
public class MeteorClient {public static net.minecraft.util.Identifier identifier(String s){return new net.minecraft.util.Identifier(s);}}''',
    PACKAGE + "utils/render/color/Color.java": '''package meteordevelopment.meteorclient.utils.render.color;
public class Color {public int r,g,b,a;public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}}''',
    PACKAGE + "systems/hud/HudRenderer.java": '''package meteordevelopment.meteorclient.systems.hud;
import meteordevelopment.meteorclient.utils.render.color.Color;import net.minecraft.util.Identifier;import java.util.*;
public class HudRenderer {
 public final List<Runnable> posts=new ArrayList<>();public int textures,lines,quads;public Color tint;
 public final List<String> commands=new ArrayList<>();
 private void finite(double... values){for(double v:values)if(!Double.isFinite(v))throw new AssertionError("Nonfinite geometry");}
 public void post(Runnable r){posts.add(r);}
 public void roundedQuad(double x,double y,double w,double h,double radius,Color c){finite(x,y,w,h,radius);if(w<0||h<0)throw new AssertionError("Negative size");quads++;commands.add(String.format(java.util.Locale.ROOT,"rect,%f,%f,%f,%f,%f,%d,%d,%d,%d",x,y,w,h,radius,c.r,c.g,c.b,c.a));}
 public void line(double x,double y,double u,double v,Color c){finite(x,y,u,v);lines++;commands.add(String.format(java.util.Locale.ROOT,"line,%f,%f,%f,%f,%d,%d,%d,%d",x,y,u,v,c.r,c.g,c.b,c.a));}
 public void roundedTextureRegion(Identifier id,double x,double y,double size,double radius,double u1,double v1,double u2,double v2,Color c,boolean smooth){
  finite(x,y,size,radius);if(!id.path().equals("icon.png")||!smooth)throw new AssertionError("Wrong artwork or sampler");
  if(c.r!=255||c.g!=255||c.b!=255)throw new AssertionError("Do not tint the artwork");textures++;tint=c;
  commands.add(String.format(java.util.Locale.ROOT,"image,%f,%f,%f,%f,%f,%f,%f,%f,%d",x,y,size,radius,u1,v1,u2,v2,c.a));
 }
 public void flush(){for(Runnable task:posts)task.run();posts.clear();}
}''',
    "EmberLogoChecks.java": '''import meteordevelopment.meteorclient.utils.render.EmberLogo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import java.nio.file.*;import java.util.*;
public class EmberLogoChecks {
 static void require(boolean c,String why){if(!c)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  for(double size:new double[]{8,20,50,90})for(double motion:new double[]{0,.1,1,2})for(int i=0;i<500;i++){
   var f=EmberLogo.frame(10,20,size,i*.031,motion);
   require(f.x()>=10&&f.y()>=20&&f.x()+f.size()<=10+size&&f.y()+f.size()<=20+size,"The inner logo must stay in its outer square");
   require(f.radius()>0&&f.radius()<f.size()/2,"The artwork has rounded square corners");
  }
  require(EmberLogo.frame(0,0,50,0,0).equals(EmberLogo.frame(0,0,50,9999,0)),"Zero motion completely disables pulsing and bobbing");
  require(!EmberLogo.frame(0,0,50,0,1).equals(EmberLogo.frame(0,0,50,1,1)),"Motion must visibly change the tile");
  for(double radius:new double[]{0,4,10,20})for(int i=-1000;i<1000;i++){
   double phase=i*.00137;double[] p=EmberLogo.roundedPoint(40,radius,phase),q=EmberLogo.roundedPoint(40,radius,phase+1);
   require(p[0]>=-1e-9&&p[0]<=40+1e-9&&p[1]>=-1e-9&&p[1]<=40+1e-9,"The moving highlight follows the square perimeter");
   require(Math.hypot(p[0]-q[0],p[1]-q[1])<1e-8,"The highlight loops without jumping");
  }
  Color accent=new Color(167,139,224,255);HudRenderer r=new HudRenderer();
  EmberLogo.tile(r,10,10,50,2,1,accent,.5);
  require(r.textures==0&&r.posts.size()==1,"Textures must wait until color and font batches finish");
  require(r.lines==18&&r.quads==2,"Logo geometry has a fixed small budget");r.flush();
  require(r.textures==1&&r.tint.a==128,"One smooth texture draw retains the HUD fade");
  r=new HudRenderer();EmberLogo.tile(r,0,0,50,1,0,accent,1);require(r.lines==0,"Disabled motion removes the traveling highlight");
  r=new HudRenderer();EmberLogo.tile(r,0,0,50,1,1,accent,0);EmberLogo.draw(r,0,0,0,1);require(r.posts.isEmpty()&&r.quads==0,"Hidden and zero-sized logos do no work");
  Path output=Path.of(args[0]);Files.createDirectories(output);
  for(int i=0;i<48;i++){r=new HudRenderer();EmberLogo.tile(r,16,16,96,i/8.0,1,accent,1);r.flush();Files.write(output.resolve(String.format("frame-%02d.csv",i)),r.commands);}
  System.out.println("Ember logo checks passed: rounded bounds, reduced motion, seamless perimeter, deferred white texture, fades and fixed draw budget.");
 }
}''',
}

home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
javac = str(home / "bin/javac.exe") if os.name == "nt" else shutil.which("javac")
java = str(home / "bin/java.exe") if os.name == "nt" else shutil.which("java")
with tempfile.TemporaryDirectory(prefix="ember-logo-checks-") as folder:
    folder = Path(folder)
    for relative, content in SOURCES.items():
        p = folder / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    relative = PACKAGE + "utils/render/EmberLogo.java"
    shutil.copyfile(ROOT / "src/main/java" / relative, folder / relative)
    subprocess.run([javac, "-encoding", "UTF-8", "-d", str(folder), *[str(p) for p in folder.rglob("*.java")]], check=True)
    subprocess.run([java, "-cp", str(folder), "EmberLogoChecks", str(ROOT / "build/logo-preview")], check=True)
