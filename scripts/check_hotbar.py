"""Check the production hotbar motion and GUI submissions without starting Minecraft."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = 'meteordevelopment/meteorclient/'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent

stubs = {
    'com/mojang/blaze3d/pipeline/RenderPipeline.java': 'package com.mojang.blaze3d.pipeline; public class RenderPipeline {}',
    'net/minecraft/util/Identifier.java': '''package net.minecraft.util;
public record Identifier(String namespace, String path) {
 public String getNamespace(){return namespace;} public String getPath(){return path;}
}''',
    P + 'MeteorClient.java': '''package meteordevelopment.meteorclient;
import net.minecraft.util.Identifier;
public class MeteorClient {
 public static final Client mc=new Client();
 public static Identifier identifier(String path){return new Identifier("ember-client",path);}
 public static class Client {public Object world=new Object();}
}''',
    P + 'systems/config/Config.java': '''package meteordevelopment.meteorclient.systems.config;
public class Config {
 private static final Config INSTANCE=new Config(); public static Config get(){return INSTANCE;}
 public final Setting<Boolean> pillHotbar=new Setting<>(true), hotbarAnimation=new Setting<>(true);
 public final Setting<Double> hotbarShadow=new Setting<>(1.0);
 public static class Setting<T>{public T value;Setting(T v){value=v;}public T get(){return value;}}
}''',
    'net/minecraft/client/gui/DrawContext.java': '''package net.minecraft.client.gui;
import java.util.*;import com.mojang.blaze3d.pipeline.RenderPipeline;import net.minecraft.util.Identifier;
public class DrawContext {
 public record Draw(String sprite,int x,int y,int width,int height,int color,float offset){}
 public final List<Draw> calls=new ArrayList<>();public final Matrices matrices=new Matrices();
 public Matrices getMatrices(){return matrices;}
 public void drawGuiTexture(RenderPipeline p,Identifier i,int x,int y,int w,int h){drawGuiTexture(p,i,x,y,w,h,-1);}
 public void drawGuiTexture(RenderPipeline p,Identifier i,int x,int y,int w,int h,int color){calls.add(new Draw(i.getPath(),x,y,w,h,color,matrices.x));}
 public static class Matrices {public int depth;public float x;
  public void pushMatrix(){if(depth++!=0)throw new AssertionError("Unbalanced matrix");}
  public void translate(float x,float y){this.x+=x;}
  public void popMatrix(){if(--depth!=0)throw new AssertionError("Unbalanced matrix");x=0;}
 }
}''',
    'HotbarChecks.java': '''import meteordevelopment.meteorclient.utils.render.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
public class HotbarChecks {
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void near(double a,double b){require(Math.abs(a-b)<.0001,a+" != "+b);}
 static boolean draw(DrawContext c,String sprite,int x,int y,int w,int h){
  return EmberHotbar.draw(c,null,new Identifier("minecraft","hud/"+sprite),x,y,w,h);
 }
 public static void main(String[] args){
  HotbarSelectionMotion m=new HotbarSelectionMotion();
  near(m.position(80,0,true),80); // First draw starts at the selected slot.
  near(m.position(100,10,true),80);
  near(m.position(100,80_000_010,true),97.5);
  near(m.position(100,160_000_010,true),100);
  near(m.position(0,170_000_010,true),100); // Number-key jump across the whole bar.
  double midway=m.position(0,210_000_010,true);
  near(m.position(160,210_000_010,true),midway); // Reversing preserves position.
  double reversed=m.position(160,250_000_010,true);
  require(reversed>midway && reversed<160,"Interrupted glide should approach the new slot");
  near(m.position(160,5_000_000_000L,true),160); // Cached HUD / long frame reaches its target.
  near(m.position(20,5_000_000_001L,false),20);
  near(m.position(20,5_000_000_002L,true),20);
  m.reset();near(m.position(140,5_000_000_003L,true),140);

  DrawContext c=new DrawContext();Config config=Config.get();
  require(draw(c,"hotbar",100,200,182,22),"Skin handles native bar");
  require(c.calls.size()==2 && c.calls.get(0).sprite().equals("hotbar/shadow"),"Shadow must precede background");
  var shadow=c.calls.get(0);require(shadow.x()==82 && shadow.y()==182 && shadow.width()==218 && shadow.height()==58,"Broad shadow extends beyond the original bounds");
  require(draw(c,"hotbar_selection",179,199,24,23),"Skin handles selection");
  near(c.calls.getLast().offset(),0);require(c.matrices.depth==0,"Restore item transform");
  draw(c,"hotbar",300,200,182,22);draw(c,"hotbar_selection",379,199,24,23);
  near(c.calls.getLast().offset(),0); // Same slot after resizing.
  config.hotbarAnimation.value=false;draw(c,"hotbar_selection",459,199,24,23);
  near(c.calls.getLast().offset(),0);
  config.hotbarShadow.value=0.0;draw(c,"hotbar",300,200,182,22);
  require(c.calls.get(c.calls.size()-2).color()>>>24==0,"Zero strength hides shadow");
  for(String side:new String[]{"left","right"}){
   int n=c.calls.size();draw(c,"hotbar_offhand_"+side,50,199,29,24);
   require(c.calls.get(n).sprite().equals("hotbar/offhand_shadow"),"Offhand shadow before sprite");
   require(c.calls.get(n).x()==(side.equals("left")?32:39),"Respect offhand blank margin");
  }
  int n=c.calls.size();
  require(!draw(c,"hotbar_attack_indicator_background",0,0,18,18),"Keep native attack indicator");
  require(!EmberHotbar.draw(c,null,new Identifier("other","hud/hotbar"),0,0,182,22),"Keep other mods' sprites");
  config.pillHotbar.value=false;
  require(!draw(c,"hotbar",0,0,182,22) && c.calls.size()==n,"Disabled skin submits nothing");
  config.pillHotbar.value=true;config.hotbarAnimation.value=true;
  MeteorClient.mc.world=new Object();draw(c,"hotbar",100,200,182,22);draw(c,"hotbar_selection",259,199,24,23);
  near(c.calls.getLast().offset(),0);
  System.out.println("Hotbar checks passed: shadow bounds/order, offhand alignment, native passthrough, smooth/reversed jumps, cached frames, resize and reset.");
 }
}''',
}

with tempfile.TemporaryDirectory(prefix='ember-hotbar-') as directory:
    work = Path(directory)
    for name, source in stubs.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
    for name in ['EmberHotbar', 'HotbarSelectionMotion']:
        dest = work / (P + 'utils/render/' + name + '.java')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / 'src/main/java' / (P + 'utils/render/' + name + '.java'), dest)
    sources = [str(path) for path in work.rglob('*.java')]
    subprocess.run([str(JAVA / 'javac'), '-encoding', 'UTF-8', '-d', str(work / 'out'), *sources], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', str(work / 'out'), 'HotbarChecks'], check=True)
