"""Exercise production glass batch uploads and hotbar routing without a game/GPU."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
P='meteordevelopment/meteorclient/'
stubs={
'com/mojang/blaze3d/buffers/GpuBufferSlice.java': '''package com.mojang.blaze3d.buffers;public record GpuBufferSlice(java.nio.ByteBuffer data){}''',
'com/mojang/blaze3d/buffers/Std140Builder.java': '''package com.mojang.blaze3d.buffers;public class Std140Builder{java.nio.ByteBuffer b;public static Std140Builder intoBuffer(java.nio.ByteBuffer b){var s=new Std140Builder();s.b=b;return s;}public Std140Builder putVec4(float a,float b,float c,float d){this.b.putFloat(a).putFloat(b).putFloat(c).putFloat(d);return this;}}''',
'com/mojang/blaze3d/buffers/Std140SizeCalculator.java': '''package com.mojang.blaze3d.buffers;public class Std140SizeCalculator{int n;public Std140SizeCalculator putVec4(){n+=16;return this;}public int get(){return n;}}''',
'net/minecraft/client/gl/DynamicUniformStorage.java': '''package net.minecraft.client.gl;import com.mojang.blaze3d.buffers.*;public class DynamicUniformStorage<T extends DynamicUniformStorage.Uploadable>{int size;public static int uploads,clears;public interface Uploadable{void write(java.nio.ByteBuffer b);}public DynamicUniformStorage(String n,int s,int count){size=s;}public GpuBufferSlice write(T v){uploads++;var b=java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.nativeOrder());v.write(b);if(b.position()!=size)throw new AssertionError("Incomplete std140 upload");b.flip();return new GpuBufferSlice(b);}public void clear(){clears++;}}''',
P+'utils/render/color/Color.java': '''package meteordevelopment.meteorclient.utils.render.color;public class Color{public int r,g,b,a;public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}}''',
P+'utils/render/color/EmberPalette.java': '''package meteordevelopment.meteorclient.utils.render.color;public class EmberPalette{public static Color accent(){return new Color(150,100,220,255);}}''',
P+'utils/Utils.java': '''package meteordevelopment.meteorclient.utils;public class Utils{public static int getWindowWidth(){return 1280;}public static int getWindowHeight(){return 720;}}''',
P+'utils/render/EmberAppearance.java': '''package meteordevelopment.meteorclient.utils.render;public class EmberAppearance{public static boolean enabled=true;public static boolean liquid(){return enabled;}public static double motion(){return 1;}public static double glass(){return .65;}public static double glassLiquid(){return .7;}public static double glassTint(){return .12;}public static boolean glassAccent(){return true;}}''',
P+'utils/render/EmberGlass.java': '''package meteordevelopment.meteorclient.utils.render;public class EmberGlass{public static boolean ready(){return true;}public static Object texture(){return null;}public static Object sampler(){return null;}}''',
P+'renderer/MeteorRenderPipelines.java': '''package meteordevelopment.meteorclient.renderer;public class MeteorRenderPipelines{public static final Object UI_LIQUID_GLASS=null,UI_LIQUID_GLASS_BATCH=null;}''',
P+'renderer/Renderer2D.java': '''package meteordevelopment.meteorclient.renderer;public class Renderer2D{public Object triangles;public Renderer2D(boolean t){}public void begin(){}public void end(){}public void texQuad(double x,double y,double w,double h,meteordevelopment.meteorclient.utils.render.color.Color c){}}''',
P+'renderer/MeshRenderer.java': '''package meteordevelopment.meteorclient.renderer;import com.mojang.blaze3d.buffers.*;public class MeshRenderer{public static int draws,total;public static java.util.List<float[]> panes=new java.util.ArrayList<>();int instances=1;GpuBufferSlice data;public static MeshRenderer begin(){return new MeshRenderer();}public MeshRenderer attachments(Object t){return this;}public MeshRenderer pipeline(Object p){return this;}public MeshRenderer fullscreen(){return this;}public MeshRenderer mesh(Object p){return this;}public MeshRenderer instances(int n){if(n<1||n>64)throw new AssertionError("Batch bound");instances=n;return this;}public MeshRenderer sampler(String n,Object a,Object b){return this;}public MeshRenderer uniform(String n,GpuBufferSlice d){data=d;return this;}public void end(){draws++;total+=instances;var b=data.data();if(b.remaining()!=2096)throw new AssertionError("Shader block size mismatch");for(int i=0;i<instances;i++){b.position(48+i*32);float[] p=new float[8];for(int k=0;k<8;k++)p[k]=b.getFloat();panes.add(p);}}}''',
P+'systems/config/Config.java': '''package meteordevelopment.meteorclient.systems.config;public class Config{static final Config C=new Config();public static Config get(){return C;}public Setting pillHotbar=new Setting();public static class Setting{public boolean value=true;public boolean get(){return value;}}}''',
'net/fabricmc/loader/api/FabricLoader.java': '''package net.fabricmc.loader.api;public class FabricLoader{public static FabricLoader getInstance(){return new FabricLoader();}public boolean isModLoaded(String n){return Boolean.getBoolean("test.exordium");}}''',
'net/minecraft/world/GameMode.java': '''package net.minecraft.world;public enum GameMode{SURVIVAL,SPECTATOR}''',
P+'MeteorClient.java': '''package meteordevelopment.meteorclient;public class MeteorClient{public static final Client mc=new Client();public static class Client{public Object currentScreen,world=new Object(),player=new Object();public Options options=new Options();public Manager interactionManager=new Manager();public Mouse mouse=new Mouse();public Window getWindow(){return new Window();}public Object getFramebuffer(){return null;}}public static class Options{public boolean hudHidden;}public static class Manager{public net.minecraft.world.GameMode mode=net.minecraft.world.GameMode.SURVIVAL;public net.minecraft.world.GameMode getCurrentGameMode(){return mode;}}public static class Mouse{public double getScaledX(Window w){return 100;}public double getScaledY(Window w){return 50;}}public static class Window{public double getScaleFactor(){return 2;}}}''',
'HudRenderChecks.java': '''import java.util.*;import meteordevelopment.meteorclient.utils.render.*;import meteordevelopment.meteorclient.renderer.MeshRenderer;import net.minecraft.client.gl.DynamicUniformStorage;import static meteordevelopment.meteorclient.MeteorClient.mc;
public class HudRenderChecks{
static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
public static void main(String[] args){
for(int count:new int[]{0,1,9,63,64,65,128,129}){
 var panes=new ArrayList<LiquidGlassRenderer.Pane>();for(int i=0;i<count;i++)panes.add(new LiquidGlassRenderer.Pane(i*3,-i,100,40,100,.65));
 MeshRenderer.draws=MeshRenderer.total=0;MeshRenderer.panes.clear();DynamicUniformStorage.uploads=0;
 LiquidGlassRenderer.drawAll(panes);
 require(MeshRenderer.draws==(count+63)/64 && DynamicUniformStorage.uploads==MeshRenderer.draws,"One upload and draw per batch");
 require(MeshRenderer.total==count,"Every pane submitted once");
 for(int i=0;i<count;i++){var p=MeshRenderer.panes.get(i);require(p[0]==i*3 && p[1]==-i,"Stable pane order across chunks");require(p[4]==20,"Radius clamped to pane height");require(Math.abs(p[5]-165/255f)<1e-6,"Opacity matches original RGBA8 vertex color");}
}
EmberAppearance.enabled=false;MeshRenderer.draws=0;LiquidGlassRenderer.drawAll(List.of(new LiquidGlassRenderer.Pane(0,0,10,10,2,1)));require(MeshRenderer.draws==0,"Flat mode does not submit glass");EmberAppearance.enabled=true;
int clears=DynamicUniformStorage.clears;LiquidGlassRenderer.flipFrame();require(DynamicUniformStorage.clears>clears,"Batch storage advances with render frames");
boolean exordium=Boolean.getBoolean("test.exordium");require(EmberHotbarPass.separate()==exordium,"Separate only with Exordium and custom hotbar");require(EmberHotbarPass.visible()==exordium,"Native hotbar draws even without Ember HUD elements");
mc.currentScreen=new Object();require(!EmberHotbarPass.separate(),"Screens keep native GUI layering");mc.currentScreen=null;
mc.options.hudHidden=true;require(!EmberHotbarPass.visible(),"F1 hides hotbar");mc.options.hudHidden=false;
mc.interactionManager.mode=net.minecraft.world.GameMode.SPECTATOR;require(!EmberHotbarPass.visible(),"Spectator menu stays vanilla-owned");mc.interactionManager.mode=net.minecraft.world.GameMode.SURVIVAL;
var config=meteordevelopment.meteorclient.systems.config.Config.get();config.pillHotbar.value=false;require(!EmberHotbarPass.separate(),"Disabled custom hotbar stays cached");config.pillHotbar.value=true;
mc.world=null;require(!EmberHotbarPass.visible(),"Disconnect clears hotbar");mc.world=new Object();mc.player=null;require(!EmberHotbarPass.visible(),"No player means no hotbar");
System.out.println("HUD rendering passed: ordered 64-pane batches, exact std140 uploads, one draw per batch, frame rotation, Exordium hotbar routing, screens, F1, spectator and disconnect (Exordium="+exordium+").");}}
'''
}
java=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
with tempfile.TemporaryDirectory(prefix='ember-hud-rendering-') as directory:
    work=Path(directory)
    for name,body in stubs.items():
        p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
    for name in ['LiquidGlassRenderer','EmberHotbarPass']:
        p=work/(P+'utils/render/'+name+'.java');p.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/'src/main/java'/(P+'utils/render/'+name+'.java'),p)
    subprocess.run([str(java/'javac'),'-encoding','UTF-8','-d',str(work/'out'),*map(str,work.rglob('*.java'))],check=True)
    for enabled in ['false','true']:
        subprocess.run([str(java/'java'),'-Dtest.exordium='+enabled,'-cp',str(work/'out'),'HudRenderChecks'],check=True)
