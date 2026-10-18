"""Exercise the production startup screen with recorded drawing and real input methods."""
from pathlib import Path
import os, shutil, subprocess, tempfile
from check_profile_sharing import stubs as shared_stubs

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
P='meteordevelopment/meteorclient/'
stubs=dict(shared_stubs)
stubs[P+'gui/GuiThemes.java']='package meteordevelopment.meteorclient.gui;public class GuiThemes{static final GuiTheme THEME=new GuiTheme();public static GuiTheme get(){return THEME;}}'
stubs[P+'MeteorClient.java']='''package meteordevelopment.meteorclient;
public class MeteorClient {
 public static final String VERSION="1.8.5-dev";public static final Client mc=new Client();public static final java.io.File FOLDER=new java.io.File(".");public static final Log LOG=new Log();
 public static String identifier(String path){return "ember-client:"+path;}public static class Log{public void error(String m,Object...v){}}
 public static class Client {public Object world,options=new Object(),currentScreen;public int stopped;public final Window window=new Window();public Window getWindow(){return window;}public void setScreen(Object screen){currentScreen=screen;}public void scheduleStop(){stopped++;}public Manager getTextureManager(){return new Manager();}}
 public static class Window{public double scale=1;public double getScaleFactor(){return scale;}}
 public static class Manager{public Texture getTexture(String id){if(!id.startsWith("ember-client:textures/menu/"))throw new AssertionError("Wrong namespace");return new Texture();}}
 public static class Texture{public Object getGlTextureView(){return this;}public Object getSampler(){return this;}}
}'''
stubs[P+'systems/config/Config.java']='''package meteordevelopment.meteorclient.systems.config;
import meteordevelopment.meteorclient.utils.render.StartMenuLayout;
public class Config {static final Config INSTANCE=new Config();public static Config get(){return INSTANCE;}public int saves;public void save(){saves++;}
 public final S<StartMenuLayout.Style> startMenu=new S<>(StartMenuLayout.Style.LockScreen);public final S<StartMenuLayout.Wallpaper> startWallpaper=new S<>(StartMenuLayout.Wallpaper.Chrome);public final S<Boolean> startClock24=new S<>(true);
 public static class S<T>{T value;S(T v){value=v;}public T get(){return value;}public void set(T v){value=v;}}
}'''
stubs[P+'utils/Utils.java']='''package meteordevelopment.meteorclient.utils;
public class Utils{public static int width=1600,height=900,projection;public static int getWindowWidth(){return width;}public static int getWindowHeight(){return height;}public static void unscaledProjection(){projection++;}public static void scaledProjection(){projection--;}}
'''
stubs[P+'utils/render/color/Color.java']='''package meteordevelopment.meteorclient.utils.render.color;public class Color{public int r,g,b,a;public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}public int r(){return r;}public int g(){return g;}public int b(){return b;}public int a(){return a;}}'''
stubs[P+'utils/render/EmberAnim.java']='''package meteordevelopment.meteorclient.utils.render;public class EmberAnim{public static class Clock{public float tick(){return 1f/60;}}public static double approach(double a,double b,double dt,double tau){double v=a+(b-a)*(1-Math.exp(-dt/tau));return Math.abs(v-b)<.01?b:v;}}'''
stubs[P+'utils/render/EmberGlass.java']='package meteordevelopment.meteorclient.utils.render;public class EmberGlass{public static int captures;public static void ensureCaptured(){captures++;}}'
stubs[P+'renderer/Renderer2D.java']='''package meteordevelopment.meteorclient.renderer;import meteordevelopment.meteorclient.utils.render.color.Color;public class Renderer2D{public static final Renderer2D TEXTURE=new Renderer2D();public void begin(){}public void texQuad(double x,double y,double w,double h,Color c){if(w<=0||h<=0)throw new AssertionError("Invalid wallpaper size");}public void render(Object view,Object sampler){}}'''
stubs[P+'systems/hud/HudRenderer.java']='''package meteordevelopment.meteorclient.systems.hud;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.utils.render.color.Color;
public class HudRenderer {public static final HudRenderer INSTANCE=new HudRenderer();public void begin(Object c){}public void end(){}public double textHeight(boolean shadow,double scale){return 18*scale*(Hud.get().hasCustomFont()?scale:1);}public double textWidth(String text,boolean shadow,double scale){return text.codePointCount(0,text.length())*textHeight(false,scale)/2;}public void text(String text,double x,double y,Color c,boolean shadow,double scale){double height=textHeight(false,scale);if(text.matches("[0-9]+:[0-9]+.*")&&(height<40||height>400))throw new AssertionError("Clock must fit the viewport");GuiRenderer.text(text,x,y,c,height/18);}}
'''
stubs[P+'systems/hud/Hud.java']='package meteordevelopment.meteorclient.systems.hud;public class Hud{static final Hud INSTANCE=new Hud();public static Hud get(){return INSTANCE;}public boolean custom=true;public boolean hasCustomFont(){return custom;}}'
stubs[P+'renderer/text/FontInfo.java']='package meteordevelopment.meteorclient.renderer.text;public class FontInfo{public enum Type{Regular}}'
stubs[P+'renderer/text/FontFace.java']='package meteordevelopment.meteorclient.renderer.text;public class FontFace{}'
stubs[P+'renderer/text/FontFamily.java']='package meteordevelopment.meteorclient.renderer.text;public class FontFamily{public final FontFace face=new FontFace();public FontFace get(FontInfo.Type type){return face;}}'
stubs[P+'renderer/Fonts.java']='package meteordevelopment.meteorclient.renderer;import meteordevelopment.meteorclient.renderer.text.*;public class Fonts{public static final FontFace DEFAULT_FONT=new FontFace();public static CustomTextRenderer RENDERER;public static final java.util.Map<String,FontFamily> families=new java.util.HashMap<>();public static FontFamily getFamily(String name){return families.get(name);}}'
stubs[P+'renderer/text/CustomTextRenderer.java']='''package meteordevelopment.meteorclient.renderer.text;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.utils.render.color.Color;
public class CustomTextRenderer{public static int created,destroyed;double scale;boolean building;public CustomTextRenderer(FontFace face)throws java.io.IOException{created++;}public void begin(double s){if(building)throw new AssertionError("Nested font batch");scale=s;building=true;}public double getWidth(String text){return text.codePointCount(0,text.length())*9*scale;}public void render(String text,double x,double y,Color c,boolean shadow){if(shadow)throw new AssertionError("Menu type must have no heavy shadow");if(text.matches("[0-9]+:[0-9]+.*")&&18*scale>150)throw new AssertionError("Oversized clock");GuiRenderer.text(text,x,y,c,scale);}public void end(){building=false;}public void destroy(){destroyed++;}}
'''
stubs['net/minecraft/text/Text.java']='package net.minecraft.text;public record Text(String value){public static Text literal(String s){return new Text(s);}}'
stubs['net/minecraft/client/gui/screen/Screen.java']='''package net.minecraft.client.gui.screen;import net.minecraft.text.Text;import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class Screen{public Screen parent;public Screen(Text t){}public void render(DrawContext c,int x,int y,float d){}public void renderBackground(DrawContext c,int x,int y,float d){}public boolean shouldCloseOnEsc(){return true;}public boolean shouldPause(){return true;}public boolean mouseClicked(Click c,boolean d){return false;}public boolean keyPressed(KeyInput k){return false;}public boolean keyReleased(KeyInput k){return false;}public void close(){}}
'''
stubs['net/minecraft/client/gui/screen/TitleScreen.java']='package net.minecraft.client.gui.screen;public class TitleScreen extends Screen{public TitleScreen(){super(null);}}'
for package,cls,arguments in [('world','SelectWorldScreen','Screen parent'),('multiplayer','MultiplayerWarningScreen','Screen parent'),('option','OptionsScreen','Screen parent,Object options')]:
    stubs[f'net/minecraft/client/gui/screen/{package}/{cls}.java']=f'package net.minecraft.client.gui.screen.{package};import net.minecraft.client.gui.screen.Screen;public class {cls} extends Screen{{public {cls}({arguments}){{super(null);this.parent=parent;}}}}'
stubs['net/minecraft/util/Util.java']='package net.minecraft.util;public class Util{public static Util getOperatingSystem(){return new Util();}public void open(String url){if(!url.startsWith("https://unsplash.com/photos/"))throw new AssertionError("Unexpected wallpaper link");}}'
stubs['org/lwjgl/glfw/GLFW.java']='''package org.lwjgl.glfw;public class GLFW{public static final int GLFW_MOUSE_BUTTON_LEFT=0,GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335,GLFW_KEY_SPACE=32,GLFW_KEY_ESCAPE=256,GLFW_KEY_TAB=258,GLFW_KEY_LEFT=263,GLFW_KEY_RIGHT=262,GLFW_KEY_UP=265,GLFW_KEY_DOWN=264,GLFW_MOD_SHIFT=1,GLFW_MOD_CONTROL=2,GLFW_KEY_A=65,GLFW_KEY_V=86,GLFW_KEY_BACKSPACE=259;}'''
renderer=stubs[P+'gui/renderer/GuiRenderer.java']
renderer=renderer[:renderer.rfind('}')]+'''
 public boolean glassPanel(double x,double y,double w,double h,double radius,double opacity){return false;}
 public void quad(double x,double y,double w,double h,Color a,Color b,Color c,Color d){quad(x,y,w,h,a);}
 public void triangle(double ax,double ay,double bx,double by,double cx,double cy,Color color){commands.add(String.format(Locale.ROOT,"{\\"kind\\":\\"triangle\\",\\"points\\":[%.2f,%.2f,%.2f,%.2f,%.2f,%.2f],\\"color\\":[%d,%d,%d,%d]}",ax,ay,bx,by,cx,cy,color.r,color.g,color.b,color.a));}
}'''
stubs[P+'gui/renderer/GuiRenderer.java']=renderer
ui=stubs[P+'gui/screens/EmberUI.java']
ui=ui[:ui.rfind('}')]+'''public static Color alpha(Color c,float fade){return new Color(c.r,c.g,c.b,(int)(c.a*fade));}public static void bar(GuiRenderer r,double x,double y,double xx,double yy,double thickness,Color c){double dx=xx-x,dy=yy-y,len=Math.hypot(dx,dy);if(len<1e-6)return;double nx=-dy/len*thickness/2,ny=dx/len*thickness/2;r.triangle(x+nx,y+ny,xx+nx,yy+ny,xx-nx,yy-ny,c);r.triangle(x+nx,y+ny,xx-nx,yy-ny,x-nx,y-ny,c);}}'''
stubs[P+'gui/screens/EmberUI.java']=ui

HARNESS=r'''import java.nio.file.*;import meteordevelopment.meteorclient.MeteorClient;import meteordevelopment.meteorclient.gui.screens.EmberTitleScreen;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.utils.Utils;import meteordevelopment.meteorclient.utils.render.*;import meteordevelopment.meteorclient.systems.config.Config;import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class StartMenuChecks {
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void draw(EmberTitleScreen menu,int count){for(int i=0;i<count;i++)menu.render(new DrawContext(),0,0,0);require(Utils.projection==0,"Restore projection after drawing");}
 static void click(EmberTitleScreen menu,double x,double y){double s=MeteorClient.mc.window.scale;menu.mouseClicked(new Click(x/s,y/s,0),false);}
 static void dock(EmberTitleScreen menu,int index){var r=StartMenuLayout.frame(Utils.width,Utils.height,1).buttons()[index];click(menu,r.x()+r.w()/2,r.y()+r.h()/2);}
 static void key(EmberTitleScreen menu,int key){menu.keyPressed(new KeyInput(key,0));}
 static void release(EmberTitleScreen menu,int key){menu.keyReleased(new KeyInput(key,0));}
 public static void main(String[] args)throws Exception{
  var first=EmberTitleScreen.firstScreen();MeteorClient.mc.setScreen(first);draw(first,60);GuiRenderer.save(Path.of(args[0]).resolve("locked.json"));
  require(EmberGlass.captures==0,"Locked screen must not run the blur chain");
  dock(first,0);require(MeteorClient.mc.currentScreen==first,"Unlock click must not launch a world");dock(first,0);require(MeteorClient.mc.currentScreen==first,"Double-click unlock must not leak into Play");
  draw(first,60);GuiRenderer.save(Path.of(args[0]).resolve("dock.json"));require(EmberGlass.captures>0,"Unlocked glass can capture the wallpaper");
  dock(first,3);draw(first,30);GuiRenderer.save(Path.of(args[0]).resolve("customize.json"));
  var panel=StartMenuLayout.frame(Utils.width,Utils.height,1).panel();double u=StartMenuLayout.frame(Utils.width,Utils.height,1).unit();
  click(first,panel.x()+panel.w()*.75,panel.y()+149*u);require(Config.get().startWallpaper.get()==StartMenuLayout.Wallpaper.Midnight,"Wallpaper selection must persist");
  key(first,256);draw(first,30);
  key(first,256);draw(first,30);key(first,257);draw(first,60);key(first,257);require(MeteorClient.mc.currentScreen==first,"Held Enter used to unlock must never activate Play");release(first,257);
  key(first,257);require(MeteorClient.mc.currentScreen instanceof net.minecraft.client.gui.screen.world.SelectWorldScreen,"Play opens world selection");
  require(((net.minecraft.client.gui.screen.Screen)MeteorClient.mc.currentScreen).parent==first,"Child screens return to the same unlocked menu");release(first,257);
  for(double scale:new double[]{1,2,3}){
   MeteorClient.mc.window.scale=scale;
   for(int index:new int[]{0,1,2,4}){
    var menu=new EmberTitleScreen(false);MeteorClient.mc.setScreen(menu);draw(menu,30);int stopped=MeteorClient.mc.stopped;dock(menu,index);
    if(index==4)require(MeteorClient.mc.stopped==stopped+1,"Quit must call the client stop action");
    else require(MeteorClient.mc.currentScreen!=menu,"Dock actions must work at every GUI scale");
   }
  }
  MeteorClient.mc.window.scale=1;
  var menu=new EmberTitleScreen(false);MeteorClient.mc.setScreen(menu);draw(menu,30);key(menu,262);draw(menu,2);key(menu,257);
  require(MeteorClient.mc.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen,"Arrow navigation must select Multiplayer");release(menu,257);
  MeteorClient.mc.setScreen(menu);dock(menu,3);draw(menu,30);key(menu,258);key(menu,257);require(Config.get().startMenu.get()==StartMenuLayout.Style.Classic,"Keyboard can switch to the classic menu");require(!EmberMenuWallpaper.enabled(),"Classic restores the normal panorama path");
  Config.get().startMenu.set(StartMenuLayout.Style.LockScreen);MeteorClient.mc.world=new Object();require(!EmberMenuWallpaper.enabled(),"Never replace in-world backgrounds");MeteorClient.mc.world=null;
  for(int[] size:new int[][]{{640,360},{870,519},{1600,900},{3440,1440},{3840,2160}}){
   Utils.width=size[0];Utils.height=size[1];var f=StartMenuLayout.frame(size[0],size[1],1);for(var r:f.buttons())require(r.x()>=0&&r.y()>=0&&r.x()+r.w()<=size[0]&&r.y()+r.h()<=size[1],"Dock fits the viewport");
   var cover=StartMenuLayout.cover(size[0],size[1],2560,1440);require(cover.x()<=0&&cover.y()<=0&&cover.width()>=size[0]&&cover.height()>=size[1],"Wallpaper covers every aspect ratio");
  }
  require(Config.get().saves>=2,"Preferences are saved");
  require(EmberMenuFont.preferredFace()==meteordevelopment.meteorclient.renderer.Fonts.DEFAULT_FONT,"Missing SF Pro uses bundled Inter");
  var sf=new meteordevelopment.meteorclient.renderer.text.FontFamily();meteordevelopment.meteorclient.renderer.Fonts.families.put("SF Pro Display",sf);require(EmberMenuFont.preferredFace()==sf.face,"Installed SF Pro Display must be preferred");EmberMenuFont.renderer();EmberMenuFont.renderer();require(meteordevelopment.meteorclient.renderer.text.CustomTextRenderer.created==2,"Cache the menu font across frames");require(meteordevelopment.meteorclient.renderer.text.CustomTextRenderer.destroyed==1,"Release the old font when changing faces");
  for(boolean custom:new boolean[]{true,false}){meteordevelopment.meteorclient.systems.hud.Hud.get().custom=custom;Utils.width=1280;Utils.height=720;var clockMenu=new EmberTitleScreen(true);draw(clockMenu,30);}
  System.out.println("Start menu passed: first-click isolation, Enter repeat, real dock actions, child return, 1x/2x/3x input, keyboard navigation, classic mode, in-world guard, offline assets and responsive cover.");
 }
}'''

with tempfile.TemporaryDirectory(prefix='ember-start-menu-') as temp:
    temp=Path(temp);sources=temp/'src';out=temp/'classes'
    for name,body in stubs.items():
        path=sources/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8')
    for name in ['gui/screens/EmberTitleScreen','utils/render/StartMenuLayout','utils/render/EmberMenuWallpaper','utils/render/EmberMenuFont']:
        path=sources/(P+name+'.java');path.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'src/main/java'/(P+name+'.java'),path)
    (sources/'StartMenuChecks.java').write_text(HARNESS,encoding='utf-8')
    result=subprocess.run([str(JAVA/'javac'),'-encoding','UTF-8','-d',str(out),*map(str,sources.rglob('*.java'))],capture_output=True,text=True)
    if result.returncode:
        print(result.stderr)
        raise SystemExit(result.returncode)
    subprocess.run([str(JAVA/'java'),'-cp',str(out),'StartMenuChecks',str(ROOT/'build/start-menu')],check=True)
for name in ['chrome','midnight']:
    image=ROOT/f'src/main/resources/assets/ember-client/textures/menu/{name}.png'
    assert image.read_bytes()[:8]==b'\x89PNG\r\n\x1a\n',f'Missing bundled wallpaper: {name}'
