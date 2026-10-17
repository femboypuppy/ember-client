"""Run the actual hand editor against a headless window, input and drawing recorder.

Checks screen lifecycle, source selection, cursor movement, history and invalid fields.
JOML comes from the local Gradle cache; no Minecraft launch or native window is needed.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PKG = "meteordevelopment/meteorclient/"
STUBS = {
    PKG + "MeteorClient.java": '''package meteordevelopment.meteorclient;
public class MeteorClient {
 public static final Client mc=new Client();
 public static class Client {
  public Object world=new Object(); public Player player=new Player(); public meteordevelopment.meteorclient.gui.WidgetScreen currentScreen;
  public Window getWindow(){return new Window();}
  public void setScreen(meteordevelopment.meteorclient.gui.WidgetScreen next){if(currentScreen!=null)currentScreen.removed();currentScreen=next;if(next!=null)next.initWidgets();}
 }
 public static class Window {public int getScaleFactor(){return 2;}public long getHandle(){return 1;}}
 public static class Player {public net.minecraft.util.Arm getMainArm(){return net.minecraft.util.Arm.RIGHT;}}
}''',
    PKG + "utils/Utils.java": '''package meteordevelopment.meteorclient.utils;
public class Utils {public static int w=1600,h=900;public static int getWindowWidth(){return w;}public static int getWindowHeight(){return h;}}''',
    PKG + "gui/WidgetScreen.java": '''package meteordevelopment.meteorclient.gui;
import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public abstract class WidgetScreen {
 protected final GuiTheme theme;public WidgetScreen parent;
 public WidgetScreen(GuiTheme t,String title){theme=t;parent=meteordevelopment.meteorclient.MeteorClient.mc.currentScreen;}
 public abstract void initWidgets();public void clear(){}public void reload(){initWidgets();}public void removed(){}public void close(){}
 public boolean shouldPause(){return true;}protected void onRenderBefore(DrawContext c,float d){}
 public void frame(){onRenderBefore(new DrawContext(),.05f);}
 public boolean mouseClicked(Click c,boolean d){return false;}public boolean mouseReleased(Click c){return false;}
 public void mouseMoved(double x,double y){}public boolean mouseScrolled(double x,double y,double h,double v){return false;}
 public boolean keyPressed(KeyInput k){return false;}public boolean charTyped(CharInput c){return false;}
 public Cell add(Widget w){return new Cell(w);}public record Cell(Widget widget){public Cell expandX(){return this;}}
 public static class Widget {public Runnable action;}
}''',
    PKG + "gui/WindowScreen.java": '''package meteordevelopment.meteorclient.gui;
public abstract class WindowScreen extends WidgetScreen {public WindowScreen(GuiTheme t,String title){super(t,title);}}''',
    PKG + "gui/GuiTheme.java": '''package meteordevelopment.meteorclient.gui;
public class GuiTheme {public double textHeight(){return 18;}public double textWidth(String s){return s.length()*9;}
 public WidgetScreen.Widget label(String s){return new WidgetScreen.Widget();}public WidgetScreen.Widget button(String s){return new WidgetScreen.Widget();}}''',
    PKG + "systems/modules/render/HandView.java": '''package meteordevelopment.meteorclient.systems.modules.render;
import meteordevelopment.meteorclient.utils.render.HandLayout;
public class HandView {
 private boolean active;private HandLayout.Pose main=HandLayout.Pose.identity(),off=HandLayout.Pose.identity();
 public boolean isActive(){return active;}public void toggle(){active=!active;}
 public HandLayout.Pose pose(boolean m){return m?main:off;}public void setPose(boolean m,HandLayout.Pose p){if(m)main=p;else off=p;}
 public HandLayout.Snapshot snapshot(){return new HandLayout.Snapshot(main,off);}
 public void restore(HandLayout.Snapshot p){main=p.main();off=p.off();}
 public void mirrorFrom(boolean m){setPose(!m,HandLayout.mirror(pose(m)));}
}''',
    PKG + "utils/render/color/Color.java": '''package meteordevelopment.meteorclient.utils.render.color;
public class Color {public int r,g,b,a;public Color(int r,int g,int b){this(r,g,b,255);}public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}}''',
    PKG + "utils/render/color/EmberPalette.java": '''package meteordevelopment.meteorclient.utils.render.color;
public class EmberPalette {public static Color panel(){return new Color(24,20,32,242);}public static Color accent(){return new Color(167,139,224);}
 public static Color rowActive(){return new Color(61,48,84);}public static Color control(){return new Color(38,32,51);}public static Color controlHover(){return new Color(48,39,69);}}''',
    PKG + "gui/renderer/GuiRenderer.java": '''package meteordevelopment.meteorclient.gui.renderer;
import meteordevelopment.meteorclient.gui.GuiTheme;import meteordevelopment.meteorclient.utils.render.color.Color;
public class GuiRenderer {
 public GuiTheme theme;static int depth;public static int triangles;public static StringBuilder svg=new StringBuilder();
 public void begin(net.minecraft.client.gui.DrawContext d){}public void end(){if(depth!=0)throw new AssertionError("Unbalanced clipping");}
 public void scissorStart(double x,double y,double w,double h){if(w<0||h<0)throw new AssertionError("Invalid clipping");depth++;}
 public void scissorEnd(){depth--;if(depth<0)throw new AssertionError("Unbalanced clipping");}
 private String color(Color c){return String.format("rgb(%d,%d,%d)",c.r,c.g,c.b);}
 public void roundedRect(double x,double y,double w,double h,double radius,Color c){svg.append(String.format(java.util.Locale.ROOT,"<rect x='%f' y='%f' width='%f' height='%f' rx='%f' fill='%s'/>%n",x,y,w,h,radius,color(c)));}
 public void text(String s,double x,double y,Color c,boolean b){svg.append(String.format(java.util.Locale.ROOT,"<text x='%f' y='%f' fill='%s' font-size='18' font-family='sans-serif'>%s</text>%n",x,y+17,color(c),s.replace("&","&amp;").replace("<","&lt;")));}
 public void triangle(double a,double b,double c,double d,double e,double f,Color color){if(!Double.isFinite(a+b+c+d+e+f))throw new AssertionError("Invalid projected geometry");triangles++;svg.append(String.format(java.util.Locale.ROOT,"<polygon points='%f,%f %f,%f %f,%f' fill='%s'/>%n",a,b,c,d,e,f,color(color)));}
}''',
    "net/minecraft/util/Arm.java": "package net.minecraft.util;public enum Arm {RIGHT,LEFT}",
    "net/minecraft/client/gui/DrawContext.java": "package net.minecraft.client.gui;public class DrawContext {}",
    "net/minecraft/client/gui/Click.java": "package net.minecraft.client.gui;public record Click(double x,double y,int button){}",
    "net/minecraft/client/input/KeyInput.java": "package net.minecraft.client.input;public record KeyInput(int key,int modifiers){}",
    "net/minecraft/client/input/CharInput.java": "package net.minecraft.client.input;public record CharInput(int codepoint){}",
    "org/lwjgl/glfw/GLFW.java": '''package org.lwjgl.glfw;
public class GLFW {
 public static final int GLFW_PRESS=1,GLFW_KEY_LEFT_SHIFT=340,GLFW_KEY_RIGHT_SHIFT=344,GLFW_KEY_LEFT_CONTROL=341,GLFW_KEY_RIGHT_CONTROL=345,GLFW_KEY_SPACE=32;
 public static final int GLFW_KEY_ESCAPE=256,GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335,GLFW_KEY_BACKSPACE=259,GLFW_KEY_A=65,GLFW_KEY_Z=90,GLFW_KEY_G=71,GLFW_KEY_R=82,GLFW_KEY_S=83,GLFW_MOD_CONTROL=2,GLFW_MOD_SHIFT=1;
 public static int glfwGetKey(long handle,int key){return 0;}
}''',
    "HandEditorChecks.java": r'''import java.lang.reflect.*;
import java.nio.file.*;
import meteordevelopment.meteorclient.gui.*;
import meteordevelopment.meteorclient.gui.screens.*;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.modules.render.HandView;
import meteordevelopment.meteorclient.utils.render.HandLayout;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import static meteordevelopment.meteorclient.MeteorClient.mc;
public class HandEditorChecks {
 static Object get(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String name,Object v)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
 static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
 static void click(HandEditorScreen e,double x,double y,int button){e.mouseClicked(new Click(x/2,y/2,button),false);}
 static void field(HandEditorScreen e,int axis,String value){try{set(e,"editing",axis);set(e,"buffer",value);e.keyPressed(new KeyInput(257,0));}catch(Exception ex){throw new RuntimeException(ex);}}
 static void apply(HandEditorScreen e){double panel=(double)unchecked(e,"panel");click(e,30+(panel-20+8)/2+10,meteordevelopment.meteorclient.utils.Utils.h-50,0);}
 static Object unchecked(Object o,String key){try{return get(o,key);}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception {
  GuiTheme theme=new GuiTheme();HandView module=new HandView();var original=module.snapshot();
  HandEditorScreen editor=new HandEditorScreen(theme,module);mc.setScreen(editor);editor.frame();
  require(module.isActive()&&!editor.shouldPause(),"Live preview temporarily enables Hand View without pausing");
  require(GuiRenderer.triangles<1000,"Editor geometry stays bounded");
  Path preview=Path.of(args[0]);Files.createDirectories(preview.getParent());Files.writeString(preview,"<svg xmlns='http://www.w3.org/2000/svg' width='1600' height='900'>"+GuiRenderer.svg+"</svg>");
  double cx=(double)get(editor,"cx"),cy=(double)get(editor,"cy");double[][] axes=(double[][])get(editor,"axisScreen");
  double len=Math.hypot(axes[0][0],axes[0][1]);double x=cx+axes[0][0]/len*80,y=cy+axes[0][1]/len*80;
  click(editor,x,y,0);require((int)get(editor,"dragging")==0,"Click selects the red X gizmo");
  editor.mouseMoved((x+axes[0][0]*.5)/2,(y+axes[0][1]*.5)/2);editor.frame();
  require(Math.abs(module.pose(true).x()-.5)<1e-9,"Physical cursor coordinates map to local axis movement");
  editor.mouseReleased(new Click(x/2,y/2,0));var moved=module.snapshot();
  editor.keyPressed(new KeyInput(90,2));require(module.snapshot().equals(original),"Undo restores both hands");
  editor.keyPressed(new KeyInput(90,3));require(module.snapshot().equals(moved),"Redo restores the committed drag");
  editor.frame();cx=(double)get(editor,"cx");cy=(double)get(editor,"cy");
  click(editor,cx,cy,0);editor.mouseMoved((cx+40)/2,(cy-30)/2);editor.frame();
  require(Math.abs((double)get(editor,"cx")-cx-40)<1e-8&&Math.abs((double)get(editor,"cy")-cy+30)<1e-8,"Free movement follows the cursor in both dimensions");
  click(editor,cx+40,cy-30,1);require(module.snapshot().equals(moved),"Right click cancels a drag without creating an undo entry");
  editor.keyPressed(new KeyInput(83,0));editor.frame();axes=(double[][])get(editor,"axisScreen");
  cx=(double)get(editor,"cx");cy=(double)get(editor,"cy");len=Math.hypot(axes[0][0],axes[0][1]);x=cx+axes[0][0]/len*80;y=cy+axes[0][1]/len*80;
  click(editor,x,y,0);editor.mouseMoved((x+axes[0][0]*.5)/2,(y+axes[0][1]*.5)/2);editor.frame();
  require(Math.abs(module.pose(true).sx()-1.35)<1e-9,"Scale gizmo responds along its local axis");
  require(Math.abs((double)get(editor,"cx")-cx)<1e-8&&Math.abs((double)get(editor,"cy")-cy)<1e-8,"Scale preserves the displayed hand pivot");
  editor.mouseReleased(new Click(x/2,y/2,0));editor.keyPressed(new KeyInput(90,2));
  editor.keyPressed(new KeyInput(82,0));editor.frame();
  Method hit=HandEditorScreen.class.getDeclaredMethod("hitAxis");hit.setAccessible(true);
  double[][] u=(double[][])get(editor,"ringU"),v=(double[][])get(editor,"ringV");
  int ring=-1;double startAngle=0;
  for(int a=0;a<3&&ring<0;a++)for(double t=.2;t<6;t+=.2){
   double px=cx+u[a][0]*Math.cos(t)+v[a][0]*Math.sin(t),py=cy+u[a][1]*Math.cos(t)+v[a][1]*Math.sin(t);
   set(editor,"mx",px);set(editor,"my",py);if((int)hit.invoke(editor)==a){ring=a;startAngle=t;break;}
  }
  require(ring>=0,"At least one rotation ring is available");
  x=cx+u[ring][0]*Math.cos(startAngle)+v[ring][0]*Math.sin(startAngle);y=cy+u[ring][1]*Math.cos(startAngle)+v[ring][1]*Math.sin(startAngle);
  click(editor,x,y,0);double endAngle=startAngle+.4;
  editor.mouseMoved((cx+u[ring][0]*Math.cos(endAngle)+v[ring][0]*Math.sin(endAngle))/2,(cy+u[ring][1]*Math.cos(endAngle)+v[ring][1]*Math.sin(endAngle))/2);editor.frame();
  require(Math.abs(HandLayout.component(module.pose(true),HandLayout.Tool.Rotate,ring)-Math.toDegrees(.4))<1e-8,"Dragging a rotation ring follows its projected angle");
  require(Math.abs((double)get(editor,"cx")-cx)<1e-8&&Math.abs((double)get(editor,"cy")-cy)<1e-8,"Rotation keeps the hand pivot fixed");
  editor.keyPressed(new KeyInput(256,0));require(module.snapshot().equals(moved)&&mc.currentScreen==editor,"Escape cancels only the active drag");
  editor.keyPressed(new KeyInput(71,0));
  field(editor,1,"NaN");require(module.snapshot().equals(moved)&&(int)get(editor,"editing")==1,"Invalid input cannot mutate the module");
  field(editor,1,"0.7");require(module.pose(true).y()==.7,"Exact numeric input commits correctly");
  var beforeMirror=module.snapshot();set(editor,"child",true);
  HandMirrorScreen dialog=new HandMirrorScreen(theme,module,()->{});mc.setScreen(dialog);dialog.close();
  require(mc.currentScreen==editor&&module.snapshot().equals(beforeMirror),"Canceling source selection preserves the editing session");
  set(editor,"child",true);dialog=new HandMirrorScreen(theme,module,()->{});mc.setScreen(dialog);
  Method copy=HandMirrorScreen.class.getDeclaredMethod("copy",boolean.class);copy.setAccessible(true);copy.invoke(dialog,true);
  require(module.pose(false).equals(HandLayout.mirror(module.pose(true)))&&module.pose(true).equals(beforeMirror.main()),"Main source mirrors into off hand without modifying the source");
  set(editor,"child",true);dialog=new HandMirrorScreen(theme,module,()->{});mc.setScreen(dialog);copy.invoke(dialog,false);
  require(module.pose(true).equals(HandLayout.mirror(module.pose(false))),"Off-hand source mirrors into main hand");
  editor.frame();apply(editor);require(!module.isActive()&&mc.currentScreen==null,"Apply restores previous enable state and closes");
  var applied=module.snapshot();editor=new HandEditorScreen(theme,module);mc.setScreen(editor);editor.frame();field(editor,0,"2.0");editor.close();
  require(module.snapshot().equals(applied)&&!module.isActive(),"Cancel restores the opening snapshot and module state");
  module.toggle();editor=new HandEditorScreen(theme,module);mc.setScreen(editor);editor.frame();field(editor,0,"3.0");mc.setScreen(null);
  require(module.snapshot().equals(applied)&&module.isActive(),"External screen changes roll back without disabling an originally active module");
  editor=new HandEditorScreen(theme,module);mc.setScreen(editor);meteordevelopment.meteorclient.utils.Utils.w=800;meteordevelopment.meteorclient.utils.Utils.h=600;editor.frame();
  require((double)get(editor,"sidebarMax")>0,"Short windows offer scrolling controls");
  editor.mouseScrolled(30,150,0,-20);editor.frame();require((double)get(editor,"sidebarScroll")>0,"Sidebar wheel scroll does not zoom the viewport");
  field(editor,2,".25");editor.frame();apply(editor);require(module.pose(true).z()==.25,"Apply remains reachable on a short window");
  System.out.println("Hand editor checks passed: cursor gizmos, undo/redo, exact/invalid input, both mirror sources, Apply/Cancel, screen transitions and small windows.");
 }
}''',
}

java_home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
javac = str(java_home / "bin/javac.exe") if os.name == "nt" else shutil.which("javac")
java = str(java_home / "bin/java.exe") if os.name == "nt" else shutil.which("java")
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
jars = list((cache / "caches/modules-2/files-2.1/org.joml/joml").rglob("joml-*.jar"))
if not jars:
    raise SystemExit("JOML not found in Gradle cache. Run the Gradle build first.")
with tempfile.TemporaryDirectory(prefix="ember-hand-editor-") as directory:
    root = Path(directory)
    for relative, content in STUBS.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    for relative in ["utils/render/HandLayout.java", "gui/screens/HandEditorScreen.java", "gui/screens/HandMirrorScreen.java"]:
        target = root / PKG / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "src/main/java" / PKG / relative, target)
    sources = [str(path) for path in root.rglob("*.java")]
    subprocess.run([javac, "-encoding", "UTF-8", "-cp", str(jars[0]), "-d", str(root / "classes"), *sources], check=True)
    subprocess.run([java, "-cp", os.pathsep.join([str(root / "classes"), str(jars[0])]), "HandEditorChecks", str(ROOT / "build/hand-editor-preview.svg")], check=True)
