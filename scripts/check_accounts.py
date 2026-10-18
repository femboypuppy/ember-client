"""Exercise the production account UI with recorded rendering and queued, credential-free auth."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
from check_profile_sharing import stubs as shared_stubs

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
P = 'meteordevelopment/meteorclient/'
stubs = dict(shared_stubs)
stubs[P+'MeteorClient.java'] = '''package meteordevelopment.meteorclient;
public class MeteorClient {
 public static final java.io.File FOLDER=new java.io.File(".");
 public static final Client mc=new Client();
 public static class Client {public Object world,currentScreen;public final Keyboard keyboard=new Keyboard();public final Window window=new Window();public Session session=new Session("LauncherUser",java.util.UUID.randomUUID());public final java.util.Queue<Runnable> main=new java.util.ArrayDeque<>();public Window getWindow(){return window;}public Session getSession(){return session;}public void setScreen(Object s){currentScreen=s;}public void execute(Runnable r){main.add(r);}public void drain(){while(!main.isEmpty())main.remove().run();}}
 public record Session(String name,java.util.UUID uuid){public String getUsername(){return name;}public java.util.UUID getUuidOrNull(){return uuid;}}
 public static class Window{public double scale=1;public double getScaleFactor(){return scale;}}
 public static class Keyboard{String value="";public String getClipboard(){return value;}public void setClipboard(String v){value=v;}}
}'''
stubs[P+'gui/WidgetScreen.java'] = '''package meteordevelopment.meteorclient.gui;
import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class WidgetScreen {protected final GuiTheme theme;public Object parent;public boolean closed;public WidgetScreen(GuiTheme t,String title){theme=t;parent=meteordevelopment.meteorclient.MeteorClient.mc.currentScreen;}public void initWidgets(){}public void clear(){}public void reload(){initWidgets();}public void mouseMoved(double x,double y){}protected void onRenderBefore(DrawContext c,float d){}public boolean mouseClicked(Click c,boolean d){return false;}public boolean mouseScrolled(double x,double y,double h,double v){return false;}public boolean keyPressed(KeyInput i){return false;}public boolean charTyped(CharInput i){return false;}public void close(){closed=true;}public void removed(){}}
'''
stubs[P+'gui/screens/accounts/AddAccountScreen.java'] = 'package meteordevelopment.meteorclient.gui.screens.accounts;public class AddAccountScreen{public void close(){}}'
stubs[P+'utils/network/MeteorExecutor.java'] = '''package meteordevelopment.meteorclient.utils.network;
public class MeteorExecutor{public static final java.util.Queue<Runnable> pending=new java.util.ArrayDeque<>();public static boolean worker;public static void execute(Runnable r){pending.add(r);}public static void run(){worker=true;try{while(!pending.isEmpty())pending.remove().run();}finally{worker=false;}}}'''
stubs['net/minecraft/util/Uuids.java'] = '''package net.minecraft.util;public class Uuids{public static java.util.UUID getOfflinePlayerUuid(String name){return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}'''
stubs['net/minecraft/util/Util.java'] = 'package net.minecraft.util;public class Util{public static String opened;public static Util getOperatingSystem(){return new Util();}public void open(String url){opened=url;}}'
stubs[P+'systems/accounts/Account.java'] = '''package meteordevelopment.meteorclient.systems.accounts;
import meteordevelopment.meteorclient.MeteorClient;import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
public class Account<T extends Account<?>> {
 public final AccountType type;public final String backing;public final Cache cache=new Cache();public int fetched,logged;
 public Account(AccountType type,String name){this.type=type;backing=name;}
 public String getUsername(){return cache.username.isEmpty()?backing:cache.username;}public AccountType getType(){return type;}public Cache getCache(){return cache;}
 public boolean fetchInfo(){if(!MeteorExecutor.worker)throw new AssertionError("Auth must run off the client thread");fetched++;if(backing.equals("throw"))throw new RuntimeException("SECRET_EXCEPTION");if(backing.equals("bad"))return false;cache.username=type==AccountType.Cracked?backing:type+"User";cache.uuid=(type==AccountType.Cracked?net.minecraft.util.Uuids.getOfflinePlayerUuid(backing):java.util.UUID.nameUUIDFromBytes(cache.username.getBytes())).toString();return true;}
 public boolean login(){if(!MeteorExecutor.worker)throw new AssertionError("Login must run off the client thread");logged++;MeteorClient.mc.session=new MeteorClient.Session(cache.username,java.util.UUID.fromString(cache.uuid));return true;}
 public static class Cache{public String username="",uuid="";public Head getHeadTexture(){return new Head();}public void loadHead(){}}public static class Head{public boolean needsRotate(){return false;}}
}'''
stubs[P+'systems/accounts/TokenAccount.java'] = 'package meteordevelopment.meteorclient.systems.accounts;public interface TokenAccount{String getToken();}'
stubs[P+'systems/accounts/Accounts.java'] = '''package meteordevelopment.meteorclient.systems.accounts;
public class Accounts implements Iterable<Account<?>>{static final Accounts INSTANCE=new Accounts();public static Accounts get(){return INSTANCE;}public final java.util.List<Account<?>> list=new java.util.ArrayList<>();public int saves;void main(){if(meteordevelopment.meteorclient.utils.network.MeteorExecutor.worker)throw new AssertionError("Account collection/save must stay on the client thread");}public void add(Account<?> a){main();list.add(a);save();}public void remove(Account<?> a){main();list.remove(a);save();}public void save(){main();saves++;}public int size(){return list.size();}public java.util.Iterator<Account<?>> iterator(){return list.iterator();}}'''
for cls, kind, token in [('CrackedAccount','Cracked',False), ('MicrosoftAccount','Microsoft',False), ('SessionAccount','Session',True), ('TheAlteningAccount','TheAltening',True)]:
    stubs[P+f'systems/accounts/types/{cls}.java'] = f'''package meteordevelopment.meteorclient.systems.accounts.types;import meteordevelopment.meteorclient.systems.accounts.*;public class {cls} extends Account<{cls}> {"implements TokenAccount" if token else ""}{{public {cls}(String value){{super(AccountType.{kind},value);}}{'public String getToken(){return backing;}' if token else ''}}}'''
stubs[P+'systems/accounts/MicrosoftLogin.java'] = '''package meteordevelopment.meteorclient.systems.accounts;
public class MicrosoftLogin{public static java.util.function.Consumer<String> callback;public static java.util.concurrent.CompletableFuture<String> url;public static int cancelled;
 public static java.util.concurrent.CompletableFuture<String> startLogin(java.util.function.Consumer<String> cb){callback=cb;url=new java.util.concurrent.CompletableFuture<>();return url;}
 public static void cancelLogin(){cancelled++;if(url!=null)url.cancel(false);}
}'''
stubs[P+'gui/renderer/GuiRenderer.java'] = stubs[P+'gui/renderer/GuiRenderer.java'].replace(' public void post(', ''' public void texture(double x,double y,double w,double h,double rotation,Object texture){roundedRect(x,y,w,h,3,new Color(75,83,104,255));}
 public void post(''')
stubs['org/jetbrains/annotations/Nullable.java'] = 'package org.jetbrains.annotations;public @interface Nullable{}'
stubs['org/lwjgl/glfw/GLFW.java'] = '''package org.lwjgl.glfw;public class GLFW{public static final int GLFW_MOUSE_BUTTON_LEFT=0,GLFW_KEY_ESCAPE=256,GLFW_KEY_TAB=258,GLFW_MOD_CONTROL=2,GLFW_MOD_SUPER=8,GLFW_MOD_SHIFT=1,GLFW_KEY_F=70,GLFW_KEY_A=65,GLFW_KEY_V=86,GLFW_KEY_BACKSPACE=259,GLFW_KEY_ENTER=257,GLFW_KEY_KP_ENTER=335,GLFW_KEY_UP=265,GLFW_KEY_DOWN=264;}'''

HARNESS = r'''import java.nio.file.*;import java.lang.reflect.*;
import meteordevelopment.meteorclient.MeteorClient;import meteordevelopment.meteorclient.gui.*;import meteordevelopment.meteorclient.gui.screens.accounts.AccountsScreen;import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;import meteordevelopment.meteorclient.systems.accounts.*;import meteordevelopment.meteorclient.systems.accounts.types.*;import meteordevelopment.meteorclient.utils.Utils;import meteordevelopment.meteorclient.utils.network.MeteorExecutor;import net.minecraft.client.gui.*;import net.minecraft.client.input.*;
public class AccountChecks {
 static class Menu extends AccountsScreen{Menu(){super(new GuiTheme());}void draw(){onRenderBefore(new DrawContext(),0);}}
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void draw(Menu m){m.draw();}
 static void click(Menu m,String label){double[] p=GuiRenderer.labels.get(label);require(p!=null,"Missing control: "+label);double scale=MeteorClient.mc.window.scale;m.mouseClicked(new Click(p[0]/scale,p[1]/scale,0),false);draw(m);}
 static void key(Menu m,int key,int mod){m.keyPressed(new KeyInput(key,mod));draw(m);}
 static void paste(Menu m,String text){MeteorClient.mc.keyboard.setClipboard(text);key(m,65,2);key(m,86,2);}
 static void finish(Menu m){MeteorExecutor.run();MeteorClient.mc.drain();draw(m);}
 static void add(Menu m,String type,String text){click(m,"+ Add account");click(m,type);paste(m,text);click(m,"Add & switch");}
 static void bounds(Menu m)throws Exception{
  Field f=AccountsScreen.class.getDeclaredField("hits");f.setAccessible(true);
  for(Object hit:(java.util.List<?>)f.get(m)){
   Class<?> c=hit.getClass();double[] v=new double[4];int i=0;for(String name:new String[]{"x","y","w","h"}){Method method=c.getDeclaredMethod(name);method.setAccessible(true);v[i++]=(double)method.invoke(hit);}
   require(v[0]>=0&&v[1]>=0&&v[2]>0&&v[3]>0&&v[0]+v[2]<=Utils.width+.01&&v[1]+v[3]<=Utils.height+.01,"Every hit region must stay in the viewport");
  }
 }
 public static void main(String[] args)throws Exception {
  Path out=Path.of(args[0]);Files.createDirectories(out);Utils.width=1100;Utils.height=700;Menu menu=new Menu();menu.initWidgets();draw(menu);
  GuiRenderer.save(out.resolve("empty.json"));add(menu,"Offline","Alice");require(Accounts.get().size()==0,"Do not save unverified accounts");menu.close();require(!menu.closed,"Do not leave a half-applied sign-in");
  key(menu,257,0);require(MeteorExecutor.pending.size()==1,"Repeated inputs cannot submit concurrent logins");finish(menu);
  require(Accounts.get().size()==1&&MeteorClient.mc.session.name().equals("Alice"),"Offline add switches and persists");require(GuiRenderer.labels.containsKey("Current"),"Current account gets a badge");
  add(menu,"Offline","aLiCe");finish(menu);require(Accounts.get().size()==1,"Duplicate offline names cannot create two entries");
  add(menu,"Session","SECRET_TOKEN");draw(menu);require(GuiRenderer.commands.stream().noneMatch(s->s.contains("SECRET_TOKEN")),"Token input stays masked");finish(menu);
  require(Accounts.get().size()==2,"Session account saved");click(menu,"Copy token");require(MeteorClient.mc.keyboard.getClipboard().equals("SECRET_TOKEN"),"Explicit token copying still works");
  GuiRenderer.save(out.resolve("library.json"));
  add(menu,"Session","bad");finish(menu);require(Accounts.get().size()==2,"Failed sign-in must not persist");require(GuiRenderer.labels.containsKey("Could not sign in. Check the account and try again."),"Show a useful error");
  key(menu,256,0);add(menu,"Session","throw");finish(menu);require(GuiRenderer.commands.stream().noneMatch(s->s.contains("SECRET_EXCEPTION")),"Exception details never become labels");key(menu,256,0);
  var unverified=new MicrosoftAccount("UNVERIFIED_SECRET");Accounts.get().add(unverified);draw(menu);require(GuiRenderer.commands.stream().noneMatch(s->s.contains("UNVERIFIED_SECRET")),"Unverified cached accounts never expose their backing credential");Accounts.get().remove(unverified);
  click(menu,"+ Add account");click(menu,"Microsoft");click(menu,"Continue in browser");var oldCallback=MicrosoftLogin.callback;int cancels=MicrosoftLogin.cancelled;
  click(menu,"Cancel sign-in");require(MicrosoftLogin.cancelled==cancels+1,"Browser sign-in cancels immediately");oldCallback.accept("STALE_TOKEN");MeteorClient.mc.drain();require(MeteorExecutor.pending.isEmpty(),"Cancelled completion must not switch or save");
  click(menu,"+ Add account");click(menu,"Microsoft");click(menu,"Continue in browser");MicrosoftLogin.url.complete("https://login.example/test");MeteorClient.mc.drain();draw(menu);click(menu,"Copy link");require(MeteorClient.mc.keyboard.getClipboard().equals("https://login.example/test"),"Sign-in link can be copied");
  GuiRenderer.save(out.resolve("microsoft.json"));MicrosoftLogin.callback.accept("MICROSOFT_REFRESH");MeteorClient.mc.drain();finish(menu);require(Accounts.get().size()==3,"Microsoft completes through the same queued add flow");
  add(menu,"Altening","ALT_TOKEN");finish(menu);require(Accounts.get().size()==4,"Altening type remains supported");
  key(menu,70,2);paste(menu,"Alice");require(GuiRenderer.labels.containsKey("aLiCe"),"Search ignores case");require(!GuiRenderer.labels.containsKey("SessionUser"),"Search filters rows");
  paste(menu,"");key(menu,256,0);menu.closed=false;draw(menu);
  for(int[] size:new int[][]{{640,360},{870,519},{1600,900},{3840,2160},{480,270}})for(double scale:new double[]{1,2,3}){
   Utils.width=size[0];Utils.height=size[1];MeteorClient.mc.window.scale=scale;draw(menu);bounds(menu);click(menu,"+ Add account");bounds(menu);click(menu,"Session");paste(menu,"MASKED_123");require(GuiRenderer.commands.stream().noneMatch(s->s.contains("MASKED_123")),"Mask at every scale");bounds(menu);key(menu,256,0);
  }
  Utils.width=640;Utils.height=360;MeteorClient.mc.window.scale=2;draw(menu);
  menu.mouseScrolled(500/2.0,230/2.0,0,-20);draw(menu);require(GuiRenderer.labels.containsKey("Remove"),"Detail scroll reveals actions in a small window");GuiRenderer.save(out.resolve("compact.json"));
  String session=MeteorClient.mc.session.name();int count=Accounts.get().size();click(menu,"Remove");require(Accounts.get().size()==count,"Removal requires confirmation");click(menu,"Confirm remove");require(Accounts.get().size()==count-1&&MeteorClient.mc.session.name().equals(session),"Removing a saved entry keeps the running session");
  Utils.width=1100;Utils.height=700;MeteorClient.mc.window.scale=1;draw(menu);click(menu,"+ Add account");click(menu,"Offline");paste(menu,"WorldGuard");MeteorClient.mc.world=new Object();click(menu,"Add & switch");require(MeteorExecutor.pending.isEmpty(),"Do not switch a live world session");MeteorClient.mc.world=null;key(menu,256,0);
  click(menu,"+ Add account");click(menu,"Microsoft");click(menu,"Continue in browser");var detached=MicrosoftLogin.callback;menu.removed();detached.accept("DETACHED_TOKEN");MeteorClient.mc.drain();require(MeteorExecutor.pending.isEmpty(),"Detached screen ignores stale sign-in callbacks");
  System.out.println("Account UI passed: four types, queued auth/save, duplicates, errors, cancellation, stale callbacks, masked credentials, explicit token copy, search, current badge, removal confirmation, world guard and 15 size/scale layouts.");
 }
}'''


def main():
    with tempfile.TemporaryDirectory(prefix='ember-account-checks-') as temp:
        temp = Path(temp)
        sources = temp / 'src'
        for name, body in stubs.items():
            path = sources / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
        for name in ['gui/screens/accounts/AccountsScreen', 'systems/accounts/AccountType']:
            path = sources / (P+name+'.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / 'src/main/java' / (P+name+'.java'), path)
        (sources / 'AccountChecks.java').write_text(HARNESS, encoding='utf-8')
        classes = temp / 'classes'
        subprocess.run([str(JAVA/'javac'), '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources.rglob('*.java'))], check=True)
        subprocess.run([str(JAVA/'java'), '-cp', str(classes), 'AccountChecks', str(ROOT/'build/account-preview')], check=True)


if __name__ == '__main__':
    main()
