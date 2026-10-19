"""Exercise the actual card painter and layout without a Minecraft/OpenGL context."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = 'meteordevelopment/meteorclient/'
stubs = {
    P+'utils/render/color/Color.java': '''package meteordevelopment.meteorclient.utils.render.color;
public class Color{public int r,g,b,a;public Color(int r,int g,int b){this(r,g,b,255);}public Color(int r,int g,int b,int a){this.r=r;this.g=g;this.b=b;this.a=a;}public String csv(){return r+","+g+","+b+","+a;}}''',
    'net/minecraft/util/Identifier.java': 'package net.minecraft.util;public record Identifier(String path){}',
    P+'MeteorClient.java': '''package meteordevelopment.meteorclient;
public class MeteorClient{public static final Client mc=new Client();public static class Client{public Client getTextureManager(){return this;}public Client getTexture(net.minecraft.util.Identifier i){return this;}public Object getGlTextureView(){return this;}public Object getSampler(){return this;}}}''',
    'com/mojang/blaze3d/systems/RenderSystem.java': '''package com.mojang.blaze3d.systems;
public class RenderSystem{public static final Matrices STACK=new Matrices();public static Matrices getModelViewStack(){return STACK;}public static class Matrices{public int depth;public void pushMatrix(){depth++;}public void identity(){}public void popMatrix(){if(--depth<0)throw new AssertionError("Unbalanced matrix");}}}''',
    P+'renderer/Renderer2D.java': '''package meteordevelopment.meteorclient.renderer;
import java.util.*;public class Renderer2D{public static final Renderer2D COLOR=new Renderer2D(),TEXTURE=new Renderer2D();public static final List<String> commands=new ArrayList<>();public static int passes;boolean building;public void begin(){if(building)throw new AssertionError("Nested shape batch");building=true;}public void render(){if(!building)throw new AssertionError("Missing shape batch");building=false;passes++;}public void render(Object t,Object s){render();}}''',
    P+'utils/render/EmberShapes.java': '''package meteordevelopment.meteorclient.utils.render;
import meteordevelopment.meteorclient.renderer.Renderer2D;import meteordevelopment.meteorclient.utils.render.color.Color;
public class EmberShapes{static void record(String type,double x,double y,double w,double h,double radius,Color c){if(!Double.isFinite(x+y+w+h+radius)||w<0||h<0)throw new AssertionError("Invalid card shape");Renderer2D.commands.add(type+","+x+","+y+","+w+","+h+","+radius+","+c.csv());}
public static void roundedGradient(Renderer2D r,double x,double y,double w,double h,double radius,Color a,Color b){record("rect",x,y,w,h,radius,a);}
public static void roundedQuad(Renderer2D r,double x,double y,double w,double h,double radius,Color c){record("rect",x,y,w,h,radius,c);}
public static void roundedOutline(Renderer2D r,double x,double y,double w,double h,double radius,double t,Color c,boolean corners){record("outline",x,y,w,h,radius,c);}
public static void roundedTexture(Renderer2D r,double x,double y,double w,double radius,double u,double v,double uu,double vv,Color c){record("skin",x,y,w,w,radius,c);}}''',
    P+'utils/render/LiquidGlassRenderer.java': '''package meteordevelopment.meteorclient.utils.render;
public class LiquidGlassRenderer{public static boolean enabled;public static boolean available(){return enabled;}public static boolean draw(double x,double y,double w,double h,double radius,double opacity){if(!Double.isFinite(x+y+w+h)||w<0||h<0)throw new AssertionError("Invalid projected glass");return true;}}''',
    P+'renderer/text/TextRenderer.java': '''package meteordevelopment.meteorclient.renderer.text;
import meteordevelopment.meteorclient.renderer.Renderer2D;import meteordevelopment.meteorclient.utils.render.color.Color;
import java.awt.Font;import java.awt.font.FontRenderContext;import java.awt.geom.AffineTransform;import java.nio.charset.StandardCharsets;import java.util.Base64;
public class TextRenderer{static final TextRenderer INSTANCE=new TextRenderer();Font font;boolean building,measure;double scale;public static int draws;
public TextRenderer(){try{font=Font.createFont(Font.TRUETYPE_FONT,new java.io.File(System.getProperty("ember.test.font")));}catch(Exception e){throw new RuntimeException(e);}}
public static TextRenderer get(){return INSTANCE;}public void begin(double s,boolean only,boolean big){if(building)throw new AssertionError("Nested text batch");building=true;measure=only;scale=s;}public void beginBig(){begin(1,false,true);}public void end(){if(!building)throw new AssertionError("Missing text batch");building=false;}
public double getWidth(String s,boolean shadow){if(!building)throw new AssertionError("Metrics outside font scope");return font.deriveFont((float)(18*scale)).getStringBounds(s,new FontRenderContext(new AffineTransform(),true,true)).getWidth();}
public double getHeight(boolean shadow){return 18.7*scale;}
public double render(String s,double x,double y,Color c,boolean shadow){if(!building||measure)throw new AssertionError("Invalid text draw scope");draws++;Renderer2D.commands.add("text,"+x+","+y+","+scale+","+Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8))+","+c.csv());return x+getWidth(s,shadow);}}''',
    'NametagChecks.java': '''import meteordevelopment.meteorclient.utils.render.*;import meteordevelopment.meteorclient.utils.render.color.Color;import meteordevelopment.meteorclient.renderer.Renderer2D;import java.util.*;import java.nio.file.*;
public class NametagChecks{
static void require(boolean c,String why){if(!c)throw new AssertionError(why);}
static Color white=new Color(245,247,255),green=new Color(116,224,162),accent=new Color(162,143,236),bg=new Color(14,16,23,210);
static EmberNametagCard.Data data(String name,boolean hp,boolean head,boolean bar,boolean details,EmberNametagCard.Style style,double opacity,int width){return new EmberNametagCard.Data(name,hp?"24 HP":"",.85,.16,white,green,accent,bg,null,details?List.of(new EmberNametagCard.Chip("FRIEND",green),new EmberNametagCard.Chip("42 ms",white),new EmberNametagCard.Chip("12.4 m",white)):List.of(),head,bar,true,opacity,width,style);}
public static void main(String[] args)throws Exception{
Path out=Path.of(args[0]);Files.createDirectories(out);
for(var style:EmberNametagCard.Style.values())for(boolean hp:new boolean[]{false,true})for(boolean head:new boolean[]{false,true})for(boolean bar:new boolean[]{false,true})for(boolean details:new boolean[]{false,true})for(int width:new int[]{160,280,420}){
 Renderer2D.commands.clear();Renderer2D.passes=0;
 var d=data("A very long server prefix | EmberPlayer With More Text",hp,head,bar,details,style,.9,width);
 var l=EmberNametagCard.draw(d,100,200,1.1);
 require(l.width()<=width && l.height()>0,"Card respects maximum width");
 require(l.name().x()+l.name().width()<=l.width()/2,"Name bounds stay inside surface");
 if(hp)require(l.name().x()+l.name().width()<l.health().x(),"Name and health cannot overlap");
 require(l.bar().height()<=3,"Health bar has a small fixed height");
 if(style==EmberNametagCard.Style.Compact)require(l.avatar().width()==0 && l.bar().height()==0,"Compact removes avatar/bar height");
 require(Renderer2D.passes<=4,"Shape passes stay bounded for every configuration");
 require(com.mojang.blaze3d.systems.RenderSystem.STACK.depth==0,"Projected glass restores the matrix");
}
for(var style:EmberNametagCard.Style.values()){
 Renderer2D.commands.clear();var l=EmberNametagCard.draw(data("EmberPlayer",true,true,true,true,style,.9,280),0,0,1);
 Files.write(out.resolve(style.name()+".csv"),Renderer2D.commands);
}
var measure=(java.util.function.ToDoubleFunction<String>)(s->s.codePointCount(0,s.length())*7.0);
for(String name:List.of("EmberPlayer","Very long team prefix "+"Player".repeat(100),"\\uD83D\\uDC36 Player "+"Extra".repeat(30)))for(int w=1;w<350;w++){
 String fitted=NametagCardLayout.fit(name,w,measure);require(measure.applyAsDouble(fitted)<=w,"Truncation fits available width");
 for(int i=0;i<fitted.length();i++)if(Character.isHighSurrogate(fitted.charAt(i)))require(i+1<fitted.length()&&Character.isLowSurrogate(fitted.charAt(++i)),"Truncation must keep code points intact");
}
LiquidGlassRenderer.enabled=true;
for(double scale:new double[]{.1,.55,1,3,6})EmberNametagCard.draw(data("Player",true,true,true,true,EmberNametagCard.Style.Glass,0,280),125000,-100,scale);
require(com.mojang.blaze3d.systems.RenderSystem.STACK.depth==0,"Glass transforms stay balanced at every scale");
System.out.println("Nametag checks passed: three styles, all detail/avatar/bar toggles, width limits, independent text scopes, Unicode truncation, projected glass and bounded render passes.");
}}
''',
}
java = Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
from check_profile_sharing import stubs as profile_stubs
for name in ['NbtElement', 'NbtCompound', 'NbtList']:
    key = 'net/minecraft/nbt/'+name+'.java'
    stubs[key] = profile_stubs[key]
module_source = (ROOT/'src/main/java'/(P+'systems/modules/render/Nametags.java')).read_text(encoding='utf-8')
start = module_source.index('public Module fromTag(')
opening = module_source.index('{', start)
depth, end = 1, opening+1
while depth:
    depth += (module_source[end] == '{') - (module_source[end] == '}')
    end += 1
stubs['MigrationChecks.java'] = '''import net.minecraft.nbt.*;import java.util.*;
class Module{public Module fromTag(NbtCompound tag){MigrationChecks c=(MigrationChecks)this;c.sgEquipment.reset();for(var value:tag.getCompoundOrEmpty("settings").getListOrEmpty("groups"))if(value instanceof NbtCompound group && group.getString("name","").equals("Equipment"))for(var item:group.getListOrEmpty("settings"))if(item instanceof NbtCompound s){var setting=c.sgEquipment.get(s.getString("name",""));if(setting!=null)setting.fromTag(s);}return this;}}
class Setting<T>{Object value,initial;Setting(T v){value=initial=v;}void fromTag(NbtCompound t){value=t.values.get("value");}}
public class MigrationChecks extends Module{
 static class Group{Map<String,Setting<?>> fields=new HashMap<>();Group(){fields.put("items",new Setting<>(true));fields.put("item-spacing",new Setting<>(2));fields.put("display-enchants",new Setting<>(false));}Setting<?> get(String name){return fields.get(name);}void reset(){for(var s:fields.values())s.value=s.initial;}}
 final Group sgEquipment=new Group();
 METHOD
 static NbtCompound group(String name,Object... values){var g=new NbtCompound();g.putString("name",name);var list=new NbtList();for(int i=0;i<values.length;i+=2){var s=new NbtCompound();s.putString("name",(String)values[i]);s.values.put("value",values[i+1]);list.add(s);}g.put("settings",list);return g;}
 static NbtCompound tag(NbtCompound... groups){var t=new NbtCompound();var s=new NbtCompound();var list=new NbtList();list.addAll(List.of(groups));s.put("groups",list);t.put("settings",s);return t;}
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){var c=new MigrationChecks();var legacy=group("Players","items",false,"item-spacing",6,"display-enchants",true);c.fromTag(tag(legacy));require(c.sgEquipment.get("items").value.equals(false),"Migrate old items toggle");require(c.sgEquipment.get("item-spacing").value.equals(6),"Migrate spacing");require(c.sgEquipment.get("display-enchants").value.equals(true),"Migrate enchantments");c.fromTag(tag(legacy,group("Equipment","items",true)));require(c.sgEquipment.get("items").value.equals(true),"New explicit values override legacy ones");require(c.sgEquipment.get("item-spacing").value.equals(6),"Migrate absent new values");c.fromTag(tag());require(c.sgEquipment.get("item-spacing").value.equals(2),"Fresh configs retain defaults");System.out.println("Nametag config migration passed: old equipment toggles, spacing, new-value priority and fresh defaults.");}}
'''.replace('METHOD', module_source[start:end])
with tempfile.TemporaryDirectory(prefix='ember-nametags-') as directory:
    work = Path(directory)
    for name, source in stubs.items():
        file = work/name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source, encoding='utf-8')
    for name in ['NametagCardLayout', 'EmberNametagCard']:
        file = work/(P+'utils/render/'+name+'.java')
        file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/'src/main/java'/(P+'utils/render/'+name+'.java'), file)
    subprocess.run([str(java/'javac'), '-encoding','UTF-8','-d',str(work/'out'), *map(str,work.rglob('*.java'))], check=True)
    subprocess.run([str(java/'java'), '-Djava.awt.headless=true', '-Dember.test.font='+str(ROOT/'src/main/resources/assets/ember-client/fonts/Inter.ttf'),
                    '-cp',str(work/'out'),'NametagChecks',str(ROOT/'build/nametag-preview')], check=True)
    subprocess.run([str(java/'java'), '-cp',str(work/'out'),'MigrationChecks'], check=True)
