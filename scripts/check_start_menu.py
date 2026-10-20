"""Exercise production animation/config code; native Screens compile against Minecraft itself."""
from pathlib import Path
import json, os, re, shutil, struct, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
SOURCE=ROOT/'src/main/java/meteordevelopment/meteorclient/gui/screens/menu'
HARNESS=r'''import java.nio.file.*;import meteordevelopment.meteorclient.gui.screens.menu.*;
public class MenuChecks {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void near(double a,double b,double e,String m){check(Math.abs(a-b)<e,m+": "+a+" != "+b);}
 public static void main(String[] args)throws Exception{
  if(args[0].equals("read")){
   var c=ConfigManager.get();check(c.wallpaper==MenuConfig.Wallpaper.SUMMIT&&c.clock24&&c.reduceMotion,"Preferences restored in fresh JVM");
   near(c.musicVolume,.27,1e-10,"Volume round trip");check(c.accent==0x123ABC&&c.language.equals("fr_fr"),"Color/language round trip");check(!c.motion(),"Reduced motion saved");
   System.out.println("Fresh-session restore passed");return;
  }
  for(int fps:new int[]{15,30,60,144,240}){
   var spring=new MenuAnimation.Spring(.2);spring.kick(-.3);for(int i=0;i<fps;i++)spring.update(1,1.0/fps,2.7,.71);
   var exact=new MenuAnimation.Spring(.2);exact.kick(-.3);exact.update(1,1,2.7,.71);
   near(spring.value,exact.value,1e-12,"Spring value FPS independence");near(spring.velocity,exact.velocity,1e-11,"Spring velocity FPS independence");
   double smooth=0;for(int i=0;i<fps;i++)smooth=MenuAnimation.smooth(smooth,1,1.0/fps,.2);near(smooth,MenuAnimation.smooth(0,1,1,.2),1e-12,"Fade FPS independence");
  }
  check(MenuAnimation.cubic(-1)==0&&MenuAnimation.cubic(2)==1,"Ease clamps time");
  for(double width:new double[]{1024,1280,2560}){
   Dock d=new Dock();d.update(width,720,-100,-100,1,1,true,true,true,1);near(d.left+d.width/2,width/2,1e-9,"Centered dock");
   for(int i=0;i<5;i++)check(d.hit(d.x[i]+d.size[i]/2,d.y[i]+d.size[i]/2)==i,"Shared paint/hit bounds");
   for(int n=0;n<240;n++)d.update(width,720,width/2,636,1.0/120,1,true,true,true,1);
   check(d.size[2]>d.size[1]&&d.size[1]>d.size[0],"Fisheye affects neighbors");for(int i=1;i<5;i++)check(d.x[i]>d.x[i-1]+d.size[i-1]+5,"No overlapping icons");
   double y=d.y[2];d.click(2,true);d.update(width,720,width/2,636,.02,1,true,true,true,1);check(d.y[2]<y,"Upward click bounce");
   d.update(width,720,width/2,636,.02,1,true,false,true,1);for(double size:d.size)near(size,44,1e-9,"Reduced motion disables magnification");
   near(d.y[2],d.top+13,1e-9,"Reduced motion disables bounce");check(d.active==2,"Active marker persists");
  }
  var invalid=new MenuConfig();invalid.uiScale=Double.NaN;invalid.musicVolume=Double.POSITIVE_INFINITY;invalid.wallpaper=null;invalid.language=null;invalid.blurIntensity=-9;invalid.fpsLimit=9999;invalid.validate();
  check(invalid.uiScale==.9&&invalid.musicVolume==.45&&invalid.blurIntensity==0&&invalid.fpsLimit==240,"Validate damaged numbers");check(invalid.wallpaper!=null&&invalid.language.equals("system"),"Validate unknown enum/language");
  check(new MenuConfig().uiScale==.9,"Fresh menus default to 90% scale");
  Path legacy=ConfigManager.PATH.resolveSibling("old-scale.json");Files.createDirectories(legacy.getParent());
  for(double scale:new double[]{.8,.9,1,1.1,1.25}){
   Files.writeString(legacy,"{\"schema\":1,\"uiScale\":"+scale+",\"clock24\":true}");var migrated=ConfigManager.load(legacy);
   near(migrated.uiScale,scale==1?.9:scale,1e-12,"Migrate only previous default scale");check(migrated.schema==2&&migrated.clock24,"Scale migration retains other preferences");
   migrated.validate();near(migrated.uiScale,scale==1?.9:scale,1e-12,"Migration is idempotent");
  }
  Files.writeString(legacy,"{\"schema\":2,\"uiScale\":1}");near(ConfigManager.load(legacy).uiScale,1,1e-12,"New custom 100% scale is retained");
  Path damaged=ConfigManager.PATH.resolveSibling("damaged.json");Files.createDirectories(damaged.getParent());Files.writeString(damaged,"{broken");check(ConfigManager.load(damaged).wallpaper==MenuConfig.Wallpaper.LIVE,"Malformed config fallback");check(Files.readString(damaged).equals("{broken"),"Malformed file preserved");
  String[][] renamed={{"CHROME_LIVE","LIVE"},{"CHROME","OBSIDIAN"},{"MIDNIGHT","SUMMIT"},{"SILK","SILK"}};
  for(String[] pair:renamed){Path old=ConfigManager.PATH.resolveSibling("old-"+pair[0]+".json");Files.writeString(old,"{\"wallpaper\":\""+pair[0]+"\",\"clock24\":true}");var loaded=ConfigManager.load(old);check(loaded.wallpaper.name().equals(pair[1])&&loaded.clock24,"Saved wallpaper "+pair[0]+" migrates");}
  check(MenuConfig.Wallpaper.LIVE.live&&MenuConfig.Wallpaper.LIVE.file.equals(MenuConfig.Wallpaper.NEBULA.file)&&!MenuConfig.Wallpaper.NEBULA.live,"Live shares Nebula art");
  var c=ConfigManager.get();c.clock24=true;c.wallpaper=MenuConfig.Wallpaper.SUMMIT;c.reduceMotion=true;c.musicVolume=.27;c.accent=0x123ABC;c.language="fr_fr";ConfigManager.init();ConfigManager.changed();ConfigManager.flush();
  long deadline=System.nanoTime()+5_000_000_000L;while(!Files.exists(ConfigManager.PATH)&&System.nanoTime()<deadline)Thread.sleep(10);check(Files.exists(ConfigManager.PATH),"Atomic write completed");
  check(!Files.readString(ConfigManager.PATH).contains("credential"),"No account credentials in menu JSON");System.out.println("Animation, dock and atomic persistence passed");
 }
}'''
def main():
 cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
 jars=[]
 for lib in ('com.google.code.gson/gson','org.slf4j/slf4j-api'):
  found=sorted((cache/lib).rglob('*.jar'))
  if not found: raise SystemExit('Run Gradle build first: missing '+lib)
  jars.append(str(found[-1]))
 with tempfile.TemporaryDirectory(prefix='ember-menu-') as directory:
  temp=Path(directory);stub=temp/'net/fabricmc/loader/api/FabricLoader.java';stub.parent.mkdir(parents=True)
  stub.write_text('package net.fabricmc.loader.api;public class FabricLoader{public static FabricLoader getInstance(){return new FabricLoader();}public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of(System.getProperty("test.config"));}}')
  harness=temp/'MenuChecks.java';harness.write_text(HARNESS);classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(jars)
  sources=[str(SOURCE/(n+'.java')) for n in ('MenuConfig','MenuAnimation','Dock','ConfigManager','MenuTheme')]
  subprocess.run([str(JAVA/'javac'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(stub),str(harness),*sources],check=True)
  for phase in ('write','read'):subprocess.run([str(JAVA/'java'),'-Dtest.config='+str(temp/'config'),'-cp',str(classes)+os.pathsep+cp,'MenuChecks',phase],check=True)
 assets=ROOT/'src/main/resources/assets/ember'
 sizes={'':(2560,1440),'_blur':(480,270),'_thumb':(384,216)}
 textures=[(f'textures/menu/{art}{suffix}.png',size) for art in ('nebula','silk','obsidian','summit') for suffix,size in sizes.items()]
 textures+=[(f'textures/menu/icon/{icon}{suffix}.png',(n,n)) for icon in ('play','accounts','settings','display','power') for suffix,n in (('',128),('_64',64))]
 textures+=[('textures/menu/ui/corners.png',(sum(s+2 for s in (4,6,8,12,16,24,32,48,64,96,128)),130)),('textures/menu/ui/shadow.png',(256,256))]
 for name,size in textures:
  data=(assets/name).read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n',name;assert struct.unpack('>II',data[16:24])==size,name
  # Linear filtering is what keeps scaled GUI art smooth instead of showing nearest-neighbour blocks.
  assert json.loads((assets/(name+'.mcmeta')).read_text())['texture']['blur'] is True,name
 for java in ('MenuCanvas','WallpaperRenderer'):
  for path in re.findall(r'"(textures/menu/[a-z_/]+)',(SOURCE/(java+'.java')).read_text(encoding='utf-8')):assert any(name.startswith(path) for name,_ in textures),path
 assert '{4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128}' in (SOURCE/'MenuCanvas.java').read_text(encoding='utf-8'),'Corner sizes match generator'
 for name in ('ui','clock'):
  provider=json.loads((assets/f'font/{name}.json').read_text())['providers'][0];file=provider['file'].removeprefix('ember:')
  assert provider['type']=='ttf' and (assets/'font'/file).is_file();assert (assets/'font'/file).read_bytes()[:4] in (b'\x00\x01\x00\x00',b'OTTO')
  if name=='ui':assert provider['oversample']>=4,'Body text needs enough atlas detail at 1440p'
 for lang in ('en_us','fr_fr'):assert 'wallpaper.live' in json.loads((assets/f'menu_lang/{lang}.json').read_text(encoding='utf-8'))
 assert json.loads((assets/'sounds.json').read_text())['ui.click']['sounds'][0]['type']=='event'
 metadata=json.loads((ROOT/'src/main/resources/fabric.mod.json').read_text());assert any('EmberMenuClient' in str(e) for e in metadata['entrypoints']['client'])
 print('Native resource/font/sound references passed')
if __name__=='__main__':main()
