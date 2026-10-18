"""Run the production profile lifecycle in two JVMs with small game collaborators.

Exercises discovery, saved bytes, import validation, duplicate names and load/apply behavior.
"""
from pathlib import Path
import os, shutil, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else Path(shutil.which("java")).parent
P = "meteordevelopment/meteorclient/"
stubs = {
    "net/minecraft/nbt/NbtElement.java": "package net.minecraft.nbt; public interface NbtElement extends java.io.Serializable {}",
    "net/minecraft/nbt/NbtCompound.java": """package net.minecraft.nbt;
public class NbtCompound implements NbtElement {
 public final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public void put(String k,NbtElement v){values.put(k,v);} public void putString(String k,String v){values.put(k,v);}
 public void putBoolean(String k,boolean v){values.put(k,v);} public boolean contains(String k){return values.containsKey(k);}
 public String getString(String k,String d){return (String)values.getOrDefault(k,d);} public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
 public NbtCompound getCompoundOrEmpty(String k){return (NbtCompound)values.getOrDefault(k,new NbtCompound());}
 public NbtList getListOrEmpty(String k){return (NbtList)values.getOrDefault(k,new NbtList());}
} """,
    "net/minecraft/nbt/NbtList.java": "package net.minecraft.nbt; public class NbtList extends java.util.ArrayList<NbtElement> implements NbtElement {}",
    "net/minecraft/nbt/NbtIo.java": """package net.minecraft.nbt;
public class NbtIo {
 public static NbtCompound read(java.io.DataInput input,NbtSizeTracker tracker)throws java.io.IOException{try(var in=new java.io.ObjectInputStream((java.io.InputStream)input)){return (NbtCompound)in.readObject();}catch(ClassNotFoundException e){throw new java.io.IOException(e);}}
 public static void write(NbtCompound tag,java.nio.file.Path path)throws java.io.IOException{try(var out=new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(path))){out.writeObject(tag);}}
 public static NbtCompound read(java.nio.file.Path path)throws java.io.IOException{try(var in=new java.io.ObjectInputStream(java.nio.file.Files.newInputStream(path))){return (NbtCompound)in.readObject();}catch(ClassNotFoundException e){throw new java.io.IOException(e);}}
} """,
    "net/minecraft/nbt/NbtSizeTracker.java": "package net.minecraft.nbt;public class NbtSizeTracker{public static NbtSizeTracker of(long bytes){return new NbtSizeTracker();}}",
    P+"MeteorClient.java": """package meteordevelopment.meteorclient;
public class MeteorClient {public static final java.io.File FOLDER=new java.io.File(System.getProperty("test.folder"));public static final Log LOG=new Log();public static class Log {public void error(String m,Object... args){}}} """,
    P+"utils/Utils.java": "package meteordevelopment.meteorclient.utils; public class Utils {public static boolean nameFilter(String s){return true;}public static boolean ipFilter(String s){return true;}public static String getWorldName(){return \"test\";}}",
    P+"utils/misc/ISerializable.java": "package meteordevelopment.meteorclient.utils.misc; import net.minecraft.nbt.*; public interface ISerializable<T>{NbtCompound toTag();T fromTag(NbtCompound tag);}",
    P+"utils/misc/NbtUtils.java": """package meteordevelopment.meteorclient.utils.misc; import net.minecraft.nbt.*;
public class NbtUtils {public static NbtList listToTag(Iterable<? extends ISerializable<?>> list){NbtList out=new NbtList();for(var item:list)out.add(item.toTag());return out;}
 public static <T> java.util.List<T> listFromTag(NbtList list,java.util.function.Function<NbtElement,T> factory){var out=new java.util.ArrayList<T>();for(var t:list)out.add(factory.apply(t));return out;}} """,
    P+"systems/System.java": """package meteordevelopment.meteorclient.systems; import java.io.*;import net.minecraft.nbt.*;
public class System<T> {private final String name;public NbtCompound data=new NbtCompound();public System(String name){this.name=name;data.putString("value",java.lang.System.getProperty("palette"));}
 public File getFile(){return new File(meteordevelopment.meteorclient.MeteorClient.FOLDER,name+".nbt");}
 public void save(){save(null);}public void save(File folder){try{File f=folder==null?getFile():new File(folder,getFile().getName());f.getParentFile().mkdirs();NbtIo.write(toTag(),f.toPath());}catch(IOException e){throw new RuntimeException(e);}}
 public void load(){load(null);}public void load(File folder){try{File f=folder==null?getFile():new File(folder,getFile().getName());if(f.isFile())fromTag(NbtIo.read(f.toPath()));}catch(IOException e){throw new RuntimeException(e);}}
 public NbtCompound toTag(){return data;} public T fromTag(NbtCompound tag){data=tag;return null;}}
""",
    P+"systems/Systems.java": """package meteordevelopment.meteorclient.systems; public class Systems {public static final java.util.Map<Class<?>,Object> values=new java.util.HashMap<>();@SuppressWarnings("unchecked")public static <T>T get(Class<T> c){return (T)values.get(c);}}""",
    P+"systems/config/Config.java": """package meteordevelopment.meteorclient.systems.config;import net.minecraft.nbt.*;public class Config extends meteordevelopment.meteorclient.systems.System<Config>{static final Config INSTANCE=new Config();public Config(){super("config");}public static Config get(){return INSTANCE;}public NbtCompound appearanceToTag(){return data;}public void applyAppearance(NbtCompound tag){data=tag;}}""",
    P+"gui/GuiThemes.java": """package meteordevelopment.meteorclient.gui;public class GuiThemes {static final Theme THEME=new Theme();public static Theme get(){return THEME;}public static class Theme extends meteordevelopment.meteorclient.systems.System<Theme>{public Theme(){super("theme");}}}""",
    P+"settings/Setting.java": "package meteordevelopment.meteorclient.settings;public class Setting<T>{public String name;T value;public Setting(String n,T v){name=n;value=v;}public T get(){return value;}public void set(T v){value=v;}}",
    P+"settings/SettingGroup.java": "package meteordevelopment.meteorclient.settings;public class SettingGroup{public final java.util.List<Setting<?>> values=new java.util.ArrayList<>();public <T> Setting<T> add(Setting<T> s){values.add(s);return s;}}",
    P+"settings/Settings.java": """package meteordevelopment.meteorclient.settings;import net.minecraft.nbt.*;
public class Settings{final java.util.List<SettingGroup> groups=new java.util.ArrayList<>();public SettingGroup createGroup(String n){var g=new SettingGroup();groups.add(g);return g;}public SettingGroup getDefaultGroup(){return createGroup("General");}
 public NbtCompound toTag(){var tag=new NbtCompound();for(var g:groups)for(var s:g.values)tag.values.put(s.name,s.get());return tag;}
 @SuppressWarnings({"rawtypes","unchecked"})public void fromTag(NbtCompound t){for(var g:groups)for(Setting s:g.values)if(t.values.containsKey(s.name))s.set(t.values.get(s.name));}}
""",
    "org/jetbrains/annotations/NotNull.java": "package org.jetbrains.annotations;public @interface NotNull{}",
    "meteordevelopment/orbit/EventHandler.java": "package meteordevelopment.orbit;public @interface EventHandler{}",
    P+"events/game/GameJoinedEvent.java": "package meteordevelopment.meteorclient.events.game;public class GameJoinedEvent{}",
    "org/apache/commons/io/FileUtils.java": "package org.apache.commons.io;public class FileUtils{public static void deleteDirectory(java.io.File f)throws java.io.IOException{throw new java.io.IOException(\"Unused in this check\");}}",
}
for name, value, default in [("BoolSetting","Boolean","false"),("StringSetting","String",'""'),("StringListSetting","java.util.List<String>","new java.util.ArrayList<>()")]:
    stubs[P+"settings/"+name+".java"] = f"""package meteordevelopment.meteorclient.settings;
public class {name} {{public static class Builder{{String name;{value} value={default};public Builder name(String n){{name=n;return this;}}public Builder description(String s){{return this;}}public Builder filter(java.util.function.Predicate<String> p){{return this;}}public Builder defaultValue({value} v){{value=v;return this;}}public Setting<{value}> build(){{return new Setting<>(name,value);}}}}}}"""
for package, cls, filename in [("modules","Modules","modules"),("hud","Hud","hud"),("macros","Macros","macros"),("waypoints","Waypoints","test-server")]:
    stubs[P+f"systems/{package}/{cls}.java"] = f"""package meteordevelopment.meteorclient.systems.{package};public class {cls} extends meteordevelopment.meteorclient.systems.System<{cls}>{{static final {cls} INSTANCE=new {cls}();public {cls}(){{super("{filename}");}}public static {cls} get(){{return INSTANCE;}}}}"""

harness = """import java.nio.file.*;import java.util.*;import meteordevelopment.meteorclient.systems.profiles.*;import meteordevelopment.meteorclient.systems.config.Config;import meteordevelopment.meteorclient.systems.modules.Modules;import meteordevelopment.meteorclient.systems.Systems;import net.minecraft.nbt.*;
public class ProfileSharingCheck {
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  Profiles profiles=new Profiles();Systems.values.put(Profiles.class,profiles);Files.createDirectories(Profiles.FOLDER.toPath());
  Path bundle=Path.of(args[1]);
  if(args[0].equals("produce")){Profile p=new Profile();p.name.set("Blue evening");p.modules.set(true);p.hud.set(true);p.appearance.set(true);ProfileTransfer.saveSnapshot(p);profiles.registerExisting(p);Files.write(bundle,ProfileTransfer.export(p));return;}
  Profile p=ProfileTransfer.importBytes(Files.readAllBytes(bundle));
  require(Config.get().data.getString("value","").equals("yellow"),"Import must not apply settings");
  byte[] saved=Files.readAllBytes(p.getFile().toPath().resolve("modules.nbt"));
  profiles.fromTag(new NbtCompound());profiles.discover();
  require(Arrays.equals(saved,Files.readAllBytes(p.getFile().toPath().resolve("modules.nbt"))),"Discovery must not overwrite imported settings");
  p=profiles.get("Blue evening");require(p.appearance.get()&&p.modules.get()&&p.hud.get(),"Sections must be detected");
  p.load();require(Config.get().data.getString("value","").equals("blue"),"Appearance must restore");require(Modules.get().data.getString("value","").equals("blue"),"Modules must restore");
  Profile duplicate=ProfileTransfer.importBytes(Files.readAllBytes(bundle));require(!duplicate.name.get().equals(p.name.get()),"Duplicate names must preserve both setups");
  require(Arrays.equals(saved,Files.readAllBytes(duplicate.getFile().toPath().resolve("modules.nbt"))),"Shared module bytes must survive");
  int count=profiles.getAll().size();try{ProfileTransfer.importBytes(ProfileArchive.encode("Broken",Map.of("modules.nbt",new byte[]{1,2,3})));throw new AssertionError("Malformed NBT accepted");}catch(java.io.IOException expected){}
  require(profiles.getAll().size()==count,"Failed imports must not register");
  try(var paths=Files.list(Profiles.FOLDER.toPath())){require(paths.noneMatch(f->f.getFileName().toString().startsWith(".import-")),"Failed import staging must be removed");}
  p.modules.set(false);ProfileTransfer.saveSnapshot(p);require(!Files.exists(p.getFile().toPath().resolve("modules.nbt")),"Deselected sections must not remain in a replacement snapshot");
  System.out.println("Profile lifecycle passed across two fresh JVMs: exact saved bytes, discovery without overwrite, appearance and module restoration, duplicate names, malformed data rollback, section replacement.");
 }
} """

# The real menu runs against a recording renderer to exercise its layout and input paths.
stubs[P+"MeteorClient.java"] = stubs[P+"MeteorClient.java"].replace("public class MeteorClient {", """public class MeteorClient {
 public static final Client mc=new Client();
 public static class Client {public final Keyboard keyboard=new Keyboard();public Window getWindow(){return new Window();}public Object screen;public void setScreen(Object s){screen=s;}public void execute(Runnable action){action.run();}}
 public static class Window {public double getScaleFactor(){return 1;}}
 public static class Keyboard {String value="";public String getClipboard(){return value;}public void setClipboard(String s){value=s;}}
""")
stubs[P+"utils/Utils.java"] = stubs[P+"utils/Utils.java"].replace("public class Utils {", "public class Utils {public static int width=800,height=600;public static int getWindowWidth(){return width;}public static int getWindowHeight(){return height;}")
stubs.update({
    P+"gui/WidgetScreen.java": """package meteordevelopment.meteorclient.gui;
import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class WidgetScreen {protected final GuiTheme theme;protected Object parent;public WidgetScreen(GuiTheme t,String title){theme=t;}public void initWidgets(){}public void clear(){}public void mouseMoved(double x,double y){}protected void onRenderBefore(DrawContext c,float d){}public boolean mouseClicked(Click c,boolean d){return false;}public boolean mouseScrolled(double x,double y,double h,double v){return false;}public boolean keyPressed(KeyInput i){return false;}public boolean charTyped(CharInput i){return false;}public void close(){}}
""",
    P+"gui/GuiTheme.java": """package meteordevelopment.meteorclient.gui;
import meteordevelopment.meteorclient.utils.render.color.Color;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
public class GuiTheme {final Text text=new Text();public double scale(double s){return s;}public Text textRenderer(){return text;}public static class Text {double scale;public void begin(double s){scale=s;}public double getWidth(String text){return text.codePointCount(0,text.length())*8*scale;}public void render(String s,double x,double y,Color c,boolean shadow){GuiRenderer.text(s,x,y,c,scale);}public void end(){}}}
""",
    P+"utils/render/color/Color.java": "package meteordevelopment.meteorclient.utils.render.color;public record Color(int r,int g,int b,int a){}",
    P+"utils/render/EmberAnim.java": "package meteordevelopment.meteorclient.utils.render;public class EmberAnim {public static class Clock{public float tick(){return .016f;}}public static float approach(float a,float b,double dt,double time){return b;}}",
    P+"gui/screens/EmberUI.java": """package meteordevelopment.meteorclient.gui.screens;
import meteordevelopment.meteorclient.utils.render.color.Color;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
public class EmberUI {public static final Color TEXT=new Color(255,255,255,255),TEXT_DIM=new Color(142,142,154,255),DIVIDER=new Color(38,38,46,255),WELL=new Color(31,31,38,255);public static Color bg(){return new Color(8,8,11,235);}public static Color raised(){return new Color(20,20,25,240);}public static Color accent(int a){return new Color(104,152,232,a);}public static void shadow(GuiRenderer r,double x,double y,double w,double h,float f){}public static void rim(GuiRenderer r,double x,double y,double w,double h,double radius,float fade){}public static void gloss(GuiRenderer r,double x,double y,double w,double h,double radius,float fade){}public static void hoverFill(GuiRenderer r,double x,double y,double w,double h,float f){}}
""",
    P+"gui/renderer/GuiRenderer.java": r'''package meteordevelopment.meteorclient.gui.renderer;
import java.util.*;import java.nio.file.*;import meteordevelopment.meteorclient.gui.GuiTheme;import meteordevelopment.meteorclient.utils.render.color.Color;
public class GuiRenderer {
 public GuiTheme theme;static GuiRenderer current;static double top=-1e6,bottom=1e6,left=-1e6,right=1e6;public static final List<String> commands=new ArrayList<>();public static final Map<String,double[]> labels=new HashMap<>();
 public void begin(Object c){current=this;commands.clear();labels.clear();}public void end(){}
 public void scissorStart(double x,double y,double w,double h){left=x;right=x+w;top=y;bottom=y+h;}public void scissorEnd(){left=top=-1e6;right=bottom=1e6;}
 static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
 static void command(String kind,double x,double y,double w,double h,double radius,Color c,String text,double scale){
  if(y+h<top||y>bottom)return;
  commands.add(String.format(Locale.ROOT,"{\"kind\":%s,\"x\":%.2f,\"y\":%.2f,\"w\":%.2f,\"h\":%.2f,\"radius\":%.2f,\"color\":[%d,%d,%d,%d],\"text\":%s,\"scale\":%.2f,\"clip\":[%.2f,%.2f,%.2f,%.2f]}",quote(kind),x,y,w,h,radius,c.r(),c.g(),c.b(),c.a(),quote(text),scale,left,top,right,bottom));
 }
 public void roundedRect(double x,double y,double w,double h,double radius,Color c){command("rect",x,y,w,h,radius,c,"",0);}public void quad(double x,double y,double w,double h,Color c){roundedRect(x,y,w,h,0,c);}
 public void post(Runnable action){action.run();}
 public static void text(String s,double x,double y,Color c,double scale){command("text",x,y,0,18*scale,0,c,s,scale);if(y>=top&&y+18*scale<=bottom)labels.put(s,new double[]{x+2,y+2});}
 public static void save(Path file)throws Exception{Files.createDirectories(file.getParent());Files.writeString(file,"["+String.join(",",commands)+"]");}
}''',
    "net/minecraft/client/gui/DrawContext.java": "package net.minecraft.client.gui;public class DrawContext{}",
    "net/minecraft/client/gui/Click.java": "package net.minecraft.client.gui;public record Click(double x,double y,int button){}",
    "net/minecraft/client/input/KeyInput.java": "package net.minecraft.client.input;public record KeyInput(int key,int modifiers){}",
    "net/minecraft/client/input/CharInput.java": "package net.minecraft.client.input;public record CharInput(int codepoint){}",
    "net/minecraft/util/Util.java": "package net.minecraft.util;public class Util{public static Util getOperatingSystem(){return new Util();}public void open(java.io.File file){}}",
    "org/lwjgl/system/MemoryStack.java": "package org.lwjgl.system;public class MemoryStack implements AutoCloseable{public static MemoryStack stackPush(){return new MemoryStack();}public P mallocPointer(int n){return new P();}public String UTF8(String s){return s;}public void close(){}public static class P{public P put(String s){return this;}public P flip(){return this;}}}",
    "org/lwjgl/util/tinyfd/TinyFileDialogs.java": "package org.lwjgl.util.tinyfd;public class TinyFileDialogs{public static String tinyfd_saveFileDialog(String a,String b,Object c,String d){return null;}public static String tinyfd_openFileDialog(String a,String b,Object c,String d,boolean e){return null;}}",
    "org/lwjgl/glfw/GLFW.java": "package org.lwjgl.glfw;public class GLFW{public static final int GLFW_MOUSE_BUTTON_LEFT=0,GLFW_KEY_ESCAPE=256,GLFW_MOD_CONTROL=2,GLFW_KEY_A=65,GLFW_KEY_V=86,GLFW_KEY_BACKSPACE=259,GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335;}",
})
menu_harness = r'''import java.nio.file.*;import meteordevelopment.meteorclient.gui.*;import meteordevelopment.meteorclient.gui.screens.EmberConfigScreen;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.systems.profiles.*;import meteordevelopment.meteorclient.systems.Systems;import meteordevelopment.meteorclient.utils.Utils;import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class ConfigMenuCheck {
 static class Menu extends EmberConfigScreen{Menu(){super(new GuiTheme());}void draw(){onRenderBefore(new DrawContext(),0);}}
 static void click(Menu menu,String label){double[] point=GuiRenderer.labels.get(label);if(point==null)throw new AssertionError("Missing visible control: "+label);menu.mouseClicked(new Click(point[0],point[1],0),false);menu.draw();}
 public static void main(String[] args)throws Exception {
  Profiles profiles=new Profiles();Systems.values.put(Profiles.class,profiles);Menu menu=new Menu();menu.initWidgets();menu.draw();
  click(menu,"New snapshot");for(int cp:"Blue evening".codePoints().toArray())menu.charTyped(new CharInput(cp));menu.draw();click(menu,"Save current setup");
  if(profiles.get("Blue evening")==null)throw new AssertionError("Menu save failed");
  click(menu,"Copy code");click(menu,"Paste share code");if(profiles.get("Blue evening 2")==null)throw new AssertionError("Menu share/import failed");
  GuiRenderer.save(Path.of(args[0]));
  Utils.height=360;menu.draw();
  if(GuiRenderer.labels.containsKey("Copy code"))throw new AssertionError("Hidden controls should not receive visible hit positions");
  menu.mouseScrolled(700,200,0,-12);menu.draw();if(!GuiRenderer.labels.containsKey("Copy code"))throw new AssertionError("Detail scrolling must reveal sharing actions");
  GuiRenderer.save(Path.of(args[1]));
  System.out.println("Config menu passed: save, clipboard share/import, duplicate preservation, clipped controls and small-window scrolling.");
 }
}'''
def main():
    with tempfile.TemporaryDirectory(prefix="ember-profile-check-") as temp:
        temp = Path(temp)
        sources = temp / "src"
        for name, body in stubs.items():
            file = sources / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(body, encoding="utf-8")
        for name in ["Profile", "Profiles", "ProfileArchive", "ProfileTransfer"]:
            file = sources / (P+f"systems/profiles/{name}.java")
            file.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / "src/main/java" / (P+f"systems/profiles/{name}.java"), file)
        (sources / "ProfileSharingCheck.java").write_text(harness, encoding="utf-8")
        (sources / "ConfigMenuCheck.java").write_text(menu_harness, encoding="utf-8")
        screen = sources / (P+"gui/screens/EmberConfigScreen.java")
        shutil.copyfile(ROOT / "src/main/java" / (P+"gui/screens/EmberConfigScreen.java"),screen)
        out = temp / "classes"
        subprocess.run([str(JAVA / "javac"), "-encoding", "UTF-8", "-d", str(out), *map(str, sources.rglob("*.java"))], check=True)
        for mode, palette in [("produce", "blue"), ("consume", "yellow")]:
            subprocess.run([str(JAVA / "java"), "-cp", str(out), f"-Dtest.folder={temp / mode}", f"-Dpalette={palette}", "ProfileSharingCheck", mode, str(temp / "shared.ember")], check=True)
        subprocess.run([str(JAVA / "java"), "-cp", str(out), f"-Dtest.folder={temp / 'menu'}", "-Dpalette=blue", "ConfigMenuCheck",str(ROOT / "build/visual-preview/config-menu.json"),str(ROOT / "build/visual-preview/config-menu-small.json")],check=True)

if __name__ == "__main__":
    main()
