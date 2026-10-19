"""Run the production rain observer, glyph and categorized settings screen without a game."""
from pathlib import Path
import os, re, shutil, subprocess, tempfile
from check_profile_sharing import stubs as shared_stubs

ROOT = Path(__file__).resolve().parents[1]
P = 'meteordevelopment/meteorclient/'
JAVA = Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))
orbit_version = re.search(r'^orbit\s*=\s*"([^"]+)"', (ROOT/'gradle/libs.versions.toml').read_text(), re.MULTILINE).group(1)
orbit_jars = [p for p in (gradle_home/'caches/modules-2/files-2.1/meteordevelopment/orbit'/orbit_version).rglob('*.jar')
              if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
if not orbit_jars:
    raise SystemExit('Build the client first to resolve its Orbit event-bus dependency.')
ORBIT = str(sorted(orbit_jars)[0])
stubs = dict(shared_stubs)
stubs.pop('meteordevelopment/orbit/EventHandler.java', None)
stubs[P+'MeteorClient.java'] = '''package meteordevelopment.meteorclient;
public class MeteorClient {
 public static final java.io.File FOLDER=new java.io.File(".");public static final Client mc=new Client();public static final meteordevelopment.orbit.EventBus EVENT_BUS=new meteordevelopment.orbit.EventBus();public static final Log LOG=new Log();
 public static class Log{public void error(String s,Object... args){}}
 public static class Client{public World world;public Object player,screen;public final Keyboard keyboard=new Keyboard();public final Window window=new Window();public Window getWindow(){return window;}public void setScreen(Object s){screen=s;}}
 public static class Window{public double scale=1;public double getScaleFactor(){return scale;}}
 public static class Keyboard{String value="";public String getClipboard(){return value;}public void setClipboard(String s){value=s;}}
 public static class World{public boolean weather=true,sky=true;public float strength;public final Properties properties=new Properties();public boolean canHaveWeather(){return weather;}public World getDimension(){return this;}public boolean hasSkyLight(){return sky;}public Properties getLevelProperties(){return properties;}public float getRainGradient(float delta){return strength;}}
 public static class Properties{public boolean raining;public boolean isRaining(){return raining;}}
}'''
stubs[P+'utils/PostInit.java']='package meteordevelopment.meteorclient.utils;public @interface PostInit{}'
stubs[P+'events/game/GameLeftEvent.java']='package meteordevelopment.meteorclient.events.game;public class GameLeftEvent{}'
stubs[P+'events/world/TickEvent.java']='package meteordevelopment.meteorclient.events.world;public class TickEvent{public static class Post{}}'
stubs[P+'systems/hud/elements/EmberNotificationsHud.java']='''package meteordevelopment.meteorclient.systems.hud.elements;
public class EmberNotificationsHud{public enum Kind{INFO}public static final java.util.List<String> messages=new java.util.ArrayList<>();public static void push(int id,String title,String body,Kind kind){messages.add(title);}}'''
stubs[P+'utils/render/WeatherSimulation.java']='package meteordevelopment.meteorclient.utils.render;public class WeatherSimulation{public enum Kind{Rain,Snow,Leaves,Ash,Petals}}'
stubs[P+'utils/render/EmberAnim.java']='package meteordevelopment.meteorclient.utils.render;public class EmberAnim{public static class Clock{public float tick(){return 1f/60;}}public static float approach(float a,float b,double dt,double tau){return b;}public static double approach(double a,double b,double dt,double tau){return b;}}'
stubs[P+'utils/render/color/Color.java']='package meteordevelopment.meteorclient.utils.render.color;public class Color{public int r,g,b,a;public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}public int r(){return r;}public int g(){return g;}public int b(){return b;}public int a(){return a;}}'
stubs[P+'utils/render/color/EmberPalette.java']='package meteordevelopment.meteorclient.utils.render.color;public class EmberPalette{public static final String[] NAMES={"Blue","Gold"};public static Color accent(){return new Color(104,152,232,255);}public static Color swatch(int i){return accent();}public static int selected(){return 0;}public static void select(int i){}}'
stubs[P+'utils/render/EmberAppearance.java']='package meteordevelopment.meteorclient.utils.render;public enum EmberAppearance{Solid,Frosted,LiquidGlass;public static EmberAppearance current(){return LiquidGlass;}public static boolean frosted(){return true;}public static boolean liquid(){return true;}}'
stubs[P+'utils/render/EmberAtmosphere.java']='package meteordevelopment.meteorclient.utils.render;public class EmberAtmosphere{public static void background(Object context,Object theme,int w,int h,double x,double y,double fade,int opacity){}}'
stubs[P+'utils/render/CelestialLighting.java']='package meteordevelopment.meteorclient.utils.render;public class CelestialLighting{public static final double BALANCED_DARKNESS=.3,BALANCED_TINT=.8,BALANCED_BRIGHTNESS=1.3;}'
stubs[P+'utils/render/EmberLogo.java']='package meteordevelopment.meteorclient.utils.render;public class EmberLogo{public static void draw(Object r,double x,double y,double s,double fade){}}'
stubs[P+'utils/misc/MusicReactive.java']='package meteordevelopment.meteorclient.utils.misc;public class MusicReactive{public static String status(){return "Spotify paused";}public static double level(){return 0;}}'
stubs[P+'utils/misc/MusicPlayback.java']='package meteordevelopment.meteorclient.utils.misc;public class MusicPlayback{public static String status(){return "No player";}}'
stubs[P+'utils/misc/MusicLyricsService.java']='package meteordevelopment.meteorclient.utils.misc;public class MusicLyricsService{public record Result(String status){}public static Result update(){return new Result("No lyrics");}}'
stubs[P+'utils/misc/SpotifyPlayback.java']='package meteordevelopment.meteorclient.utils.misc;public class SpotifyPlayback{public static final String REDIRECT="http://127.0.0.1";public static void connect(){}public static void disconnect(){}public static void enableBridge(){}}'
stubs[P+'renderer/text/FontInfo.java']='package meteordevelopment.meteorclient.renderer.text;public record FontInfo(String family,Type type){public enum Type{Regular}}'
stubs[P+'renderer/text/FontFace.java']='package meteordevelopment.meteorclient.renderer.text;public class FontFace{public final FontInfo info=new FontInfo("Inter",FontInfo.Type.Regular);}'
stubs[P+'renderer/text/FontFamily.java']='package meteordevelopment.meteorclient.renderer.text;public class FontFamily{public String getName(){return "Inter";}public FontFace get(FontInfo.Type type){return new FontFace();}}'
stubs[P+'renderer/Fonts.java']='package meteordevelopment.meteorclient.renderer;import meteordevelopment.meteorclient.renderer.text.*;public class Fonts{public static final FontFace DEFAULT_FONT=new FontFace();public static final java.util.List<FontFamily> FONT_FAMILIES=java.util.List.of(new FontFamily());}'
stubs[P+'systems/hud/Hud.java']='package meteordevelopment.meteorclient.systems.hud;public class Hud implements Iterable<HudElement>{public boolean active=true;static final Hud INSTANCE=new Hud();public static Hud get(){return INSTANCE;}public java.util.Iterator<HudElement> iterator(){return java.util.Collections.emptyIterator();}public void resetToDefaultElements(){}}'
stubs[P+'systems/hud/HudElement.java']='package meteordevelopment.meteorclient.systems.hud;public class HudElement{public final Info info=new Info();public static class Info{public String name="";}public boolean isActive(){return true;}public void toggle(){}public void setPos(int x,int y){}public int getWidth(){return 100;}}'
stubs[P+'systems/hud/screens/HudEditorScreen.java']='package meteordevelopment.meteorclient.systems.hud.screens;public class HudEditorScreen{public HudEditorScreen(Object theme){}}'
stubs[P+'systems/hud/HudRenderer.java']='''package meteordevelopment.meteorclient.systems.hud;import meteordevelopment.meteorclient.utils.render.color.Color;
public class HudRenderer{public int primitives;private void bounds(double x,double y,double w,double h){if(x<-.001||y<-.001||x+w>32.001||y+h>32.001)throw new AssertionError("Rain icon exceeds its square");primitives++;}public void roundedQuad(double x,double y,double w,double h,double radius,Color color){bounds(x,y,w,h);}public void quad(double x,double y,double w,double h,Color color){bounds(x,y,w,h);}public void line(double x,double y,double xx,double yy,Color c){primitives++;}}'''
stubs['net/minecraft/util/Util.java']='package net.minecraft.util;public class Util{public static Util getOperatingSystem(){return new Util();}public void open(String url){}}'
stubs['org/lwjgl/glfw/GLFW.java']='''package org.lwjgl.glfw;public class GLFW{public static final int GLFW_MOUSE_BUTTON_LEFT=0,GLFW_KEY_ESCAPE=256,GLFW_MOD_CONTROL=2,GLFW_MOD_SUPER=8,GLFW_MOD_SHIFT=1,GLFW_KEY_A=65,GLFW_KEY_V=86,GLFW_KEY_BACKSPACE=259,GLFW_KEY_DELETE=261,GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335,GLFW_KEY_TAB=258,GLFW_KEY_PAGE_DOWN=267,GLFW_KEY_PAGE_UP=266,GLFW_KEY_HOME=268,GLFW_KEY_END=269;}'''
stubs[P+'gui/WidgetScreen.java']=stubs[P+'gui/WidgetScreen.java'].replace('public void close(){}','public boolean mouseReleased(Click c){return false;}public void close(){}')
ui=stubs[P+'gui/screens/EmberUI.java']
stubs[P+'gui/screens/EmberUI.java']=ui[:ui.rfind('}')]+'public static void toggle(GuiRenderer r,double x,double y,double w,double h,float amount,int fade){} }'
stubs[P+'settings/Setting.java']='package meteordevelopment.meteorclient.settings;public class Setting<T>{final T initial;T value;public Setting(T v){initial=value=v;}public T get(){return value;}public void set(T v){value=v;}public void reset(){value=initial;}}'
# Only retain collaborators needed by this screen, not unrelated settings-builder stubs.
for name in ['BoolSetting','StringSetting','StringListSetting','SettingGroup','Settings']:
    stubs.pop(P+'settings/'+name+'.java',None)
config_source=(ROOT/'src/main/java'/(P+'systems/config/Config.java')).read_text(encoding='utf-8')
screen_source=(ROOT/'src/main/java'/(P+'gui/screens/EmberClientSettingsScreen.java')).read_text(encoding='utf-8')
used=set(re.findall(r'(?:ambience|c|Config.get\(\))\.([A-Za-z][A-Za-z0-9_]*)',screen_source))
used.update(['rainStatusIcon','rainNotifications','rainEndingNotification','customWeather','weatherStyle','weatherDensity','ambience','worldAtmosphere'])
defaults={'Boolean':'true','Double':'0.5','Integer':'4','String':'""','FontFace':'meteordevelopment.meteorclient.renderer.Fonts.DEFAULT_FONT','EmberAppearance':'EmberAppearance.LiquidGlass','CelestialSky':'CelestialSky.Nebula','BassMode':'BassMode.Darken'}
fields=[]
for typ,name in re.findall(r'public final Setting<([^>]+)>\s+(\w+)\s*=',config_source):
    if name not in used:continue
    if typ.split('.')[-1] in defaults:value=defaults[typ.split('.')[-1]]
    elif typ.endswith('StartMenuLayout.Style'):value=typ+'.LockScreen'
    elif typ.endswith('StartMenuLayout.Wallpaper'):value=typ+'.Chrome'
    elif typ.endswith('WeatherSimulation.Kind'):value=typ+'.Rain'
    else:raise AssertionError('Unknown setting type: '+typ)
    fields.append(f'public final Setting<{typ}> {name}=new Setting<>({value});')
stubs[P+'systems/config/Config.java']='''package meteordevelopment.meteorclient.systems.config;
import meteordevelopment.meteorclient.settings.Setting;import meteordevelopment.meteorclient.renderer.text.FontFace;import meteordevelopment.meteorclient.utils.render.EmberAppearance;import meteordevelopment.meteorclient.utils.render.StartMenuLayout;
public class Config{public enum CelestialSky{Nebula,Aurora,Dusk}public enum BassMode{Darken,Glow}static final Config INSTANCE=new Config();public static Config get(){return INSTANCE;}
'''+ '\n'.join(fields)+'}'

HARNESS=r'''import java.nio.file.*;import java.lang.reflect.*;import meteordevelopment.meteorclient.MeteorClient;import meteordevelopment.meteorclient.gui.*;import meteordevelopment.meteorclient.gui.screens.EmberClientSettingsScreen;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.utils.Utils;import meteordevelopment.meteorclient.utils.render.*;import meteordevelopment.meteorclient.systems.config.Config;import meteordevelopment.meteorclient.systems.hud.elements.EmberNotificationsHud;import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class RainAndSettingsChecks{
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static final java.util.List<String> messages=EmberNotificationsHud.messages;
 static void weather(boolean requested,float strength)throws Exception{MeteorClient.mc.world.properties.raining=requested;MeteorClient.mc.world.strength=strength;MeteorClient.EVENT_BUS.post(meteordevelopment.meteorclient.events.world.TickEvent.Post.get());}
 static class Menu extends EmberClientSettingsScreen{Menu(Page page){super(new GuiTheme(),page);}void draw(){for(int i=0;i<3;i++)onRenderBefore(new DrawContext(),0);}}
 static void click(Menu menu,String label){var point=GuiRenderer.labels.get(label);require(point!=null,"Missing visible control: "+label);menu.mouseClicked(new Click(point[0],point[1],0),false);menu.draw();}
 public static void main(String[] args)throws Exception{
  MeteorClient.EVENT_BUS.registerLambdaFactory("meteordevelopment.meteorclient",(method,klass)->(java.lang.invoke.MethodHandles.Lookup)method.invoke(null,klass,java.lang.invoke.MethodHandles.lookup()));
  boolean initialized=false;for(Method method:EmberRainStatus.class.getDeclaredMethods())if(method.isAnnotationPresent(meteordevelopment.meteorclient.utils.PostInit.class)){method.invoke(null);initialized=true;}
  require(initialized&&MeteorClient.EVENT_BUS.isListening(meteordevelopment.meteorclient.events.world.TickEvent.Post.class)&&MeteorClient.EVENT_BUS.isListening(meteordevelopment.meteorclient.events.game.GameLeftEvent.class),"Real post-init registration must connect weather ticks and disconnects to Orbit");
  var c=Config.get();c.customWeather.set(false);MeteorClient.mc.world=new MeteorClient.World();MeteorClient.mc.player=new Object();weather(false,0);weather(true,0);
  require(messages.equals(java.util.List.of("Rain has started"))&&EmberRainStatus.indicatorVisible(),"Start notification and icon appear once");
  for(int i=0;i<1000;i++)weather(true,1);require(messages.size()==1,"Steady rain must not spam notifications");
  weather(false,1);require(messages.size()==2&&messages.get(1).equals("Rain is about to stop")&&EmberRainStatus.indicatorVisible()&&EmberRainStatus.ending(),"The server stop flag announces easing while rain is still visible");
  for(int i=99;i>0;i--)weather(false,i/100f);require(messages.size()==2&&EmberRainStatus.indicatorVisible(),"Keep the icon throughout the fade-out");
  weather(false,0);require(messages.size()==3&&!EmberRainStatus.indicatorVisible(),"Remove the icon only when rain has stopped");
  c.rainNotifications.set(false);weather(true,1);require(messages.size()==3&&EmberRainStatus.indicatorVisible(),"Notifications and icon have independent toggles");c.rainStatusIcon.set(false);require(!EmberRainStatus.indicatorVisible(),"Disabling the icon applies immediately");c.rainStatusIcon.set(true);c.rainNotifications.set(true);weather(true,1);require(messages.size()==3,"Enabling notifications during rain must not invent a new start");
  c.rainEndingNotification.set(false);weather(false,1);require(messages.size()==3,"Ending notification can be disabled independently");weather(false,0);require(messages.size()==4,"Stopped alert still works");
  MeteorClient.mc.world=new MeteorClient.World();weather(true,1);require(messages.size()==4&&EmberRainStatus.indicatorVisible(),"Joining an already-raining world establishes a silent baseline");
  MeteorClient.EVENT_BUS.post(meteordevelopment.meteorclient.events.game.GameLeftEvent.get());require(!EmberRainStatus.indicatorVisible(),"Disconnect clears the badge immediately");
  MeteorClient.mc.world=new MeteorClient.World();weather(false,0);c.customWeather.set(true);weather(false,0);require(messages.get(messages.size()-1).equals("Rain has started")&&EmberRainStatus.indicatorVisible(),"Local rain works without server rain");c.weatherStyle.set(WeatherSimulation.Kind.Snow);weather(true,1);require(EmberRainStatus.indicatorVisible(),"Custom snow must not hide real server rain");
  c.rainEndingNotification.set(true);
  for(var style:WeatherSimulation.Kind.values())if(style!=WeatherSimulation.Kind.Rain){
   c.weatherStyle.set(style);weather(false,0);int baseline=messages.size();require(!EmberRainStatus.indicatorVisible(),"Non-rain particles do not invent server rain");
   weather(true,0);require(EmberRainStatus.indicatorVisible()&&messages.size()==baseline+1&&messages.get(baseline).equals("Rain has started"),style+" cannot suppress server rain start");
   weather(true,1);weather(false,1);require(EmberRainStatus.ending()&&messages.get(messages.size()-1).equals("Rain is about to stop"),style+" cannot suppress the easing alert");
   weather(false,.02f);require(EmberRainStatus.indicatorVisible(),style+" keeps the badge through the fade");weather(false,0);require(!EmberRainStatus.indicatorVisible()&&messages.get(messages.size()-1).equals("Rain has stopped"),style+" cannot suppress rain stopping");
  }
  c.weatherStyle.set(WeatherSimulation.Kind.Rain);weather(true,1);int overlapping=messages.size();c.customWeather.set(false);weather(true,1);require(EmberRainStatus.indicatorVisible()&&messages.size()==overlapping,"Disabling local rain does not stop real rain");
  c.customWeather.set(true);weather(false,1);weather(false,0);require(EmberRainStatus.indicatorVisible()&&messages.size()==overlapping,"Local rain keeps the badge after server rain stops");c.customWeather.set(false);weather(false,0);
  c.customWeather.set(false);c.ambience.set(false);c.worldAtmosphere.set(false);weather(false,0);int before=messages.size();weather(true,1);require(messages.size()==before+1,"Server rain alerts work with atmosphere effects disabled");
  MeteorClient.mc.world=new MeteorClient.World();MeteorClient.mc.world.weather=false;weather(true,1);require(!EmberRainStatus.indicatorVisible(),"Dimensions without weather never show the icon");
  c.ambience.set(true);c.worldAtmosphere.set(true);c.customWeather.set(true);c.weatherStyle.set(WeatherSimulation.Kind.Rain);weather(false,0);require(EmberRainStatus.indicatorVisible(),"Custom rain in a skylit weatherless dimension must match the actual particle renderer");
  c.weatherDensity.set(0.0);weather(false,0);require(!EmberRainStatus.indicatorVisible(),"Zero local density leaves no phantom rain");c.weatherDensity.set(.5);MeteorClient.mc.world.sky=false;weather(false,0);require(!EmberRainStatus.indicatorVisible(),"Local rain needs a skylit dimension");
  var tracker=new RainTracker();require(tracker.update(false,0)==RainTracker.Change.None,"Quiet baseline");require(tracker.update(true,0)==RainTracker.Change.Started,"Rain request precedes visible strength");require(tracker.update(false,1)==RainTracker.Change.Ending,"Easing event");require(tracker.update(true,.7f)==RainTracker.Change.None&&!tracker.ending(),"Rain restarting during fade clears easing without a false fresh start");
  var glyph=new meteordevelopment.meteorclient.systems.hud.HudRenderer();EmberIcons.draw(glyph,EmberIcons.Glyph.RAIN,0,0,32,new meteordevelopment.meteorclient.utils.render.color.Color(255,255,255,255),new meteordevelopment.meteorclient.utils.render.color.Color(0,0,0,255));require(glyph.primitives>4&&glyph.primitives<120,"Rain icon has a bounded primitive budget");
  c.rainNotifications.set(true);c.rainEndingNotification.set(true);c.rainStatusIcon.set(true);c.customWeather.set(false);c.ambience.set(true);c.worldAtmosphere.set(true);
  Utils.width=1000;Utils.height=700;var menu=new Menu(EmberClientSettingsScreen.Page.Weather);menu.draw();
  require(GuiRenderer.labels.containsKey("RAIN STATUS")&&GuiRenderer.labels.containsKey("Rain notifications"),"Weather has its own page");click(menu,"Rain notifications");require(!c.rainNotifications.get(),"Real weather toggle updates config");require(!GuiRenderer.labels.containsKey("Rain ending notification"),"Ending toggle hides when notifications are disabled");click(menu,"Rain status icon");require(!c.rainStatusIcon.get(),"Real icon toggle updates config");
  GuiRenderer.save(Path.of(args[0]).resolve("weather-settings.json"));
  var appearance=new Menu(EmberClientSettingsScreen.Page.Appearance);appearance.draw();require(GuiRenderer.labels.containsKey("SURFACE STYLE")&&!GuiRenderer.labels.containsKey("RAIN STATUS"),"Appearance is focused on materials");
  Utils.height=360;menu=new Menu(EmberClientSettingsScreen.Page.Appearance);menu.draw();require(!GuiRenderer.labels.containsKey("Music & lyrics"),"Small windows clip the bottom navigation rows");menu.mouseScrolled(150,160,0,-100);menu.draw();require(GuiRenderer.labels.containsKey("Music & lyrics"),"Sidebar scrolling reveals the last category");click(menu,"Music & lyrics");require(GuiRenderer.labels.containsKey("PLAYBACK"),"Visible category opens the correct page");
  GuiRenderer.save(Path.of(args[0]).resolve("small-settings.json"));
  for(var page:EmberClientSettingsScreen.Page.values()){menu=new Menu(page);menu.draw();menu.keyPressed(new KeyInput(258,2));menu.draw();}
  Utils.height=700;menu=new Menu(EmberClientSettingsScreen.Page.Scene);menu.draw();require(GuiRenderer.labels.containsKey("BLOOM"),"Scene visuals has its own page");click(menu,"Bloom");require(!c.bloom.get(),"Bloom toggle controls the real setting");
  menu=new Menu(EmberClientSettingsScreen.Page.Players);menu.draw();require(GuiRenderer.labels.containsKey("PLAYER COSMETICS"),"Player cosmetics has its own page");click(menu,"Show on other players");require(!c.cosmeticsOthers.get(),"Player target toggle controls the real setting");
  require(EmberClientSettingsScreen.Page.values().length==13,"All focused settings pages are present");
  System.out.println("Rain and settings passed: real Orbit post-init/tick/disconnect delivery, independent server/custom rain, every non-rain particle style, overlap, dimensions, complete fades, independent toggles, icon bounds and thirteen settings routes including scene visuals and player cosmetics.");
 }
}'''

output=ROOT/'build/rain-status';output.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ember-rain-checks-') as temp:
    temp=Path(temp);sources=temp/'src';classes=temp/'classes'
    for name,body in stubs.items():
        target=sources/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body,encoding='utf-8')
    for name in ['gui/screens/EmberClientSettingsScreen','utils/render/StartMenuLayout','utils/render/RainTracker','utils/render/EmberRainStatus','utils/render/EmberIcons','utils/PostInit','events/world/TickEvent','events/game/GameLeftEvent']:
        target=sources/(P+name+'.java');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'src/main/java'/(P+name+'.java'),target)
    (sources/'RainAndSettingsChecks.java').write_text(HARNESS,encoding='utf-8')
    compiled=subprocess.run([str(JAVA/'javac'),'-encoding','UTF-8','-cp',ORBIT,'-d',str(classes),*map(str,sources.rglob('*.java'))],capture_output=True,text=True)
    if compiled.returncode:
        print(compiled.stderr);raise SystemExit(compiled.returncode)
    subprocess.run([str(JAVA/'java'),'-cp',str(classes)+os.pathsep+ORBIT,'RainAndSettingsChecks',str(output)],check=True)
