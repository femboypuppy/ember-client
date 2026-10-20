"""Run the production scene observer with Orbit, checking actual player selection and toggles."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = 'meteordevelopment/meteorclient/'
java_home = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-21'))
javac = str(java_home/'bin/javac.exe') if os.name == 'nt' else shutil.which('javac')
java = str(java_home/'bin/java.exe') if os.name == 'nt' else shutil.which('java')
version = re.search(r'^orbit\s*=\s*"([^"]+)"', (ROOT/'gradle/libs.versions.toml').read_text(), re.MULTILINE).group(1)
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1/meteordevelopment/orbit'/version
orbit = str(next(p for p in cache.rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))))
stubs = {
 P+'MeteorClient.java': '''package meteordevelopment.meteorclient;
 import net.minecraft.entity.player.PlayerEntity;public class MeteorClient {
 public static final meteordevelopment.orbit.IEventBus EVENT_BUS=new meteordevelopment.orbit.EventBus();public static final Client mc=new Client();
 public static class Client{public World world=new World();public PlayerEntity player=new PlayerEntity(0,0,0,1);public Object camera=player;public boolean paused;public final Options options=new Options();public Object getCameraEntity(){return camera;}public boolean isPaused(){return paused;}}
 public static class Options{public boolean first=true;public Options getPerspective(){return this;}public boolean isFirstPerson(){return first;}}
 public static class World{public long time=100;public final java.util.List<PlayerEntity> players=new java.util.ArrayList<>();public java.util.List<PlayerEntity> getPlayers(){return players;}public long getTime(){return time;}}
 }''',
 P+'events/render/Render3DEvent.java': '''package meteordevelopment.meteorclient.events.render;public class Render3DEvent{public double offsetX=0,offsetY=1.6,offsetZ=0;public float tickDelta=.5f;public Object matrices;}''',
 P+'utils/PostInit.java': 'package meteordevelopment.meteorclient.utils;public @interface PostInit{}',
 P+'settings/Setting.java': 'package meteordevelopment.meteorclient.settings;public class Setting<T>{public T value;public Setting(T v){value=v;}public T get(){return value;}public void set(T v){value=v;}}',
 P+'renderer/MeteorRenderPipelines.java': 'package meteordevelopment.meteorclient.renderer;public class MeteorRenderPipelines{public static final Object WORLD_COLORED_LINES_DEPTH=new Object(),WORLD_ACCENT_LIGHT=new Object();}',
 P+'renderer/Renderer3D.java': 'package meteordevelopment.meteorclient.renderer;public class Renderer3D{public Renderer3D(Object a,Object b){}public void begin(){}public void render(Object matrices){}}',
 P+'utils/render/CelestialRenderer.java': '''package meteordevelopment.meteorclient.utils.render;public class CelestialRenderer{public static int size;public static float[] positions;public static void draw(CelestialMesh mesh,Object renderer,double x,double y,double z,double yaw,double pitch){size=mesh.size;positions=java.util.Arrays.copyOf(mesh.positions,mesh.size*3);}}''',
 P+'utils/render/color/EmberPalette.java': '''package meteordevelopment.meteorclient.utils.render.color;public class EmberPalette{public static class Color{public int r=104,g=152,b=232;}public static Color accent(){return new Color();}}''',
 'net/minecraft/entity/effect/StatusEffects.java': 'package net.minecraft.entity.effect;public class StatusEffects{public static final Object BLINDNESS=new Object(),DARKNESS=new Object();}',
 'net/minecraft/entity/player/PlayerEntity.java': '''package net.minecraft.entity.player;public class PlayerEntity{
 public double x,y,z;public int id;public boolean spectator,invisible,dead,blind;public PlayerEntity(double x,double y,double z,int id){this.x=x;this.y=y;this.z=z;this.id=id;}
 public boolean isSpectator(){return spectator;}public boolean isInvisible(){return invisible;}public boolean isAlive(){return !dead;}public boolean hasStatusEffect(Object effect){return blind;}
 public double squaredDistanceTo(double x,double y,double z){return Math.pow(this.x-x,2)+Math.pow(this.y-y,2)+Math.pow(this.z-z,2);}
 public record Position(double x,double y,double z){}public Position getLerpedPos(float delta){return new Position(x,y,z);}
 public float getHeight(){return 1.8f;}public float getWidth(){return .6f;}public int getId(){return id;}
 }'''.replace('public record Position(double x,double y,double z){}','public static class Position{public final double x,y,z;public Position(double x,double y,double z){this.x=x;this.y=y;this.z=z;}}'),
}
config_source=(ROOT/'src/main/java'/(P+'systems/config/Config.java')).read_text()
source=(ROOT/'src/main/java'/(P+'utils/render/EmberSceneEffects.java')).read_text()
used=set(re.findall(r'c\.(\w+)\.', source))
fields=[]
for typ,name in re.findall(r'public final Setting<([^>]+)>\s+(\w+)\s*=',config_source):
    if name in used:
        value = 'true' if typ=='Boolean' else '0.7'
        fields.append(f'public final Setting<{typ}> {name}=new Setting<>({value});')
stubs[P+'systems/config/Config.java']='package meteordevelopment.meteorclient.systems.config;import meteordevelopment.meteorclient.settings.Setting;public class Config{static final Config INSTANCE=new Config();public static Config get(){return INSTANCE;}'+''.join(fields)+'}'
HARNESS='''import meteordevelopment.meteorclient.MeteorClient;import meteordevelopment.meteorclient.systems.config.Config;import meteordevelopment.meteorclient.utils.render.*;import meteordevelopment.meteorclient.events.render.Render3DEvent;import net.minecraft.entity.player.PlayerEntity;
public class SceneObserverChecks{
 static void require(boolean v,String message){if(!v)throw new AssertionError(message);}
 static int frame(){CelestialRenderer.size=0;MeteorClient.EVENT_BUS.post(new Render3DEvent());return CelestialRenderer.size;}
 static void animationFrames(String effect){
  var mc=MeteorClient.mc;
  for(long age:new long[]{100,24_000_000,100_000_000,1_000_000_000})for(int fps:new int[]{60,144,240}){
   float[] previous=null;
   for(int i=0;i<fps;i++){
    double ticks=i*20.0/fps;mc.world.time=age+(long)ticks;
    var event=new Render3DEvent();event.tickDelta=(float)(ticks-Math.floor(ticks));MeteorClient.EVENT_BUS.post(event);
    float[] current=CelestialRenderer.positions;
    require(current!=null&&current.length>0,effect+" has geometry");
    require(previous==null||!java.util.Arrays.equals(previous,current),effect+" must advance every frame at world tick "+age+" and "+fps+" FPS (frame "+i+")");
    previous=current;
   }
  }
  mc.paused=true;mc.world.time=100_000_000;frame();float[] paused=CelestialRenderer.positions;
  var event=new Render3DEvent();event.tickDelta=.9f;MeteorClient.EVENT_BUS.post(event);
  require(java.util.Arrays.equals(paused,CelestialRenderer.positions),effect+" stays frozen while paused");mc.paused=false;
 }
 public static void main(String[] args){
  MeteorClient.EVENT_BUS.registerLambdaFactory("meteordevelopment.meteorclient",(lookup,type)->(java.lang.invoke.MethodHandles.Lookup)lookup.invoke(null,type,java.lang.invoke.MethodHandles.lookup()));
  EmberSceneEffects.init();var mc=MeteorClient.mc;var c=Config.get();c.cosmeticRange.set(32.0);c.geometricParticles.set(false);c.curvedTrails.set(false);mc.world.players.add(mc.player);
  require(frame()==0,"Your own cosmetics stay out of first person");mc.options.first=false;int self=frame();require(self>0,"Third person displays your cosmetics");
  c.cosmeticsSelf.set(false);require(frame()==0,"Self toggle removes your cosmetics immediately");
  var other=new PlayerEntity(2,0,5,2);mc.world.players.add(other);int one=frame();require(one>0,"Nearby other players receive cosmetics");
  other.invisible=true;require(frame()==0,"Invisible players do not reveal themselves through cosmetics");other.invisible=false;other.spectator=true;require(frame()==0,"Spectators have no aura");other.spectator=false;other.dead=true;require(frame()==0,"Dead players have no aura");other.dead=false;
  c.cosmeticsOthers.set(false);require(frame()==0,"Other-player toggle is independent from self");c.cosmeticsOthers.set(true);
  other.x=100;require(frame()==0,"Out-of-range players have no effects");other.x=2;
  c.playerAuras.set(false);c.playerRibbons.set(false);int halo=frame();require(halo>0&&halo<one,"Halo renders independently");c.playerHalos.set(false);require(frame()==0,"All cosmetic toggles off stops drawing");c.playerHalos.set(true);
  c.sceneEffectSpeed.set(1.0);animationFrames("Player halo");c.playerAuras.set(true);c.playerRibbons.set(true);animationFrames("Player aura and ribbons");c.playerAuras.set(false);c.playerRibbons.set(false);
  for(int i=0;i<40;i++)mc.world.players.add(new PlayerEntity(i%8-4,0,6+i/8,i+3));
  int crowded=frame();require(crowded==halo*SceneEffectsGeometry.MAX_PLAYERS,"Crowds are capped at twelve players");
  c.worldAtmosphere.set(false);require(frame()==0,"World atmosphere master switch disables all new effects");c.worldAtmosphere.set(true);mc.player.blind=true;require(frame()==0,"Blindness suppresses cosmetics");mc.player.blind=false;
  mc.world.players.clear();c.geometricParticles.set(true);require(frame()>0,"Ambient geometry does not need visible players");animationFrames("Floating geometry");c.geometricParticles.set(false);c.curvedTrails.set(true);require(frame()>0,"Curved trails work independently of cubes");animationFrames("Curved trails");c.sceneEffectBrightness.set(0.0);require(frame()==0,"Zero ambient brightness clears trails");
  mc.world=null;require(frame()==0,"Disconnect cannot retain effects from the previous world");
  System.out.println("Scene observer passed: real Orbit frames, first/third person, self/other toggles, invisible/spectator/dead filters, range, twelve-player crowd cap, independent halos/auras/ribbons/geometry/trails, zero brightness, master switch and disconnect. Animation advances every frame at 60/144/240 FPS through one billion world ticks and freezes while paused.");
 }
}'''
with tempfile.TemporaryDirectory(prefix='ember-scene-checks-') as temp:
    temp=Path(temp);sources=temp/'src';classes=temp/'classes'
    for path,body in stubs.items():
        target=sources/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(body,encoding='utf-8')
    for name in ['utils/render/EmberSceneEffects','utils/render/SceneEffectsGeometry','utils/render/CelestialMesh']:
        target=sources/(P+name+'.java');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'src/main/java'/(P+name+'.java'),target)
    (sources/'SceneObserverChecks.java').write_text(HARNESS,encoding='utf-8')
    subprocess.run([javac,'-encoding','UTF-8','-cp',orbit,'-d',str(classes),*map(str,sources.rglob('*.java'))],check=True)
    subprocess.run([java,'-cp',str(classes)+os.pathsep+orbit,'SceneObserverChecks'],check=True)
