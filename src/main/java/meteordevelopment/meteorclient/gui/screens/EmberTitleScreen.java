package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.screens.menu.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.StartMenuLayout;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.input.KeyInput;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import static org.lwjgl.glfw.GLFW.*;

/** Native Screen/DrawContext desktop; child screens return to the same unlocked instance. */
public final class EmberTitleScreen extends MenuScreen {
    private static boolean shown,migrated;
    public final WallpaperRenderer wallpaper=new WallpaperRenderer();
    public final Dock dock=new Dock();
    private final MenuAnimation.Spring dockIntro=new MenuAnimation.Spring(0);
    private boolean locked,playSheet;
    private int held=-1,pending=-1;
    private double age,unlockAge,pendingAge,outgoingFade,playFade;
    private int playFocus;
    private long second=-1;
    private boolean clock24;
    private String locale="",time="",date="";
    private final String footer="Ember Client "+MeteorClient.VERSION;
    private String credit="",creditLocale="";
    private MenuConfig.Wallpaper creditWallpaper;
    private double mouseX=-1,mouseY=-1;
    private DateTimeFormatter timeFormat,dateFormat;
    private Screen outgoing;
    private MenuControl.Button singleplayer,multiplayer,cancel;

    public EmberTitleScreen(boolean locked){
        super("Ember Client");this.locked=locked;shown=true;
        if(!locked){unlockAge=1;dockIntro.snap(1);}
        if(!migrated && Config.get()!=null && !java.nio.file.Files.exists(ConfigManager.PATH)){
            migrated=true;var cfg=ConfigManager.get();cfg.clock24=Config.get().startClock24.get();
            if(Config.get().startWallpaper.get()==StartMenuLayout.Wallpaper.Midnight)cfg.wallpaper=MenuConfig.Wallpaper.MIDNIGHT;
            ConfigManager.changed();
        }
    }
    public static EmberTitleScreen firstScreen(){return new EmberTitleScreen(!shown);}
    @Override public boolean shouldCloseOnEsc(){return false;}
    @Override protected void layout(){viewport();buildPlaySheet();}
    private void buildPlaySheet(){
        double x=vw/2-170,y=vh/2-42;
        singleplayer=new MenuControl.Button("Singleplayer",x,y,340,40,()->openNative(new SelectWorldScreen(this)));
        multiplayer=new MenuControl.Button("Multiplayer",x,y+50,340,40,()->openNative(client.options.skipMultiplayerWarning?new MultiplayerScreen(this):new MultiplayerWarningScreen(this)));
        cancel=new MenuControl.Button("Cancel",x,y+102,340,32,()->playSheet=false);
    }
    private void updateClock(){
        var cfg=ConfigManager.get();String language=MenuLanguage.locale();long now=System.currentTimeMillis()/1000;
        if(timeFormat==null||clock24!=cfg.clock24||!locale.equals(language)){
            locale=language;clock24=cfg.clock24;Locale formatLocale=language.equals("fr_fr")?Locale.FRANCE:Locale.US;
            timeFormat=DateTimeFormatter.ofPattern(clock24?"HH:mm":"h:mm a",formatLocale);
            dateFormat=DateTimeFormatter.ofPattern(language.equals("fr_fr")?"EEEE d MMMM":"EEEE, MMMM d",formatLocale);second=-1;
        }
        if(second!=now){second=now;var local=LocalDateTime.now();time=local.format(timeFormat);date=local.format(dateFormat);}
    }
    public void scene(MenuCanvas c,double width,double height,double mx,double my,double delta,double dim,boolean interactive){
        var cfg=ConfigManager.get();age+=delta*cfg.animationSpeed;if(!locked)unlockAge+=delta*cfg.animationSpeed;
        wallpaper.update(width,height,mx,my,delta);wallpaper.draw(c,dim*.85);
        double clockFade=MenuAnimation.cubic(age/(cfg.motion()?MenuTheme.CLOCK_INTRO_SECONDS:.08));
        double reveal=locked?0:MenuAnimation.cubic(unlockAge/(cfg.motion()?MenuTheme.DOCK_INTRO_SECONDS:.08));
        if(cfg.motion())dockIntro.update(reveal,delta*cfg.animationSpeed,2.6,.65);else dockIntro.snap(reveal);
        double old=c.opacity;c.opacity*=clockFade*(1-dim*.55);
        updateClock();
        c.text("EMBER",29,25,18,MenuTheme.WHITE);
        c.round(width-150,20,120,36,12,0x17FFFFFF);c.center(MenuLanguage.text("Accounts"),width-90,30,14,MenuTheme.WHITE);
        double clockY=height*.17-(cfg.motion()?(1-clockFade)*12:0);
        if(cfg.showDate)c.center(date,width*.5,clockY-31,19,MenuTheme.WHITE);
        c.clock(time,width*.5,clockY,MenuTheme.CLOCK_SIZE,MenuTheme.WHITE);
        c.opacity=old*Math.clamp(reveal,0,1)*(1-dim*.5);
        dock.update(width,height,mx,my,delta,dockIntro.value,interactive&&!locked&&!playSheet&&outgoing==null,cfg.motion(),cfg.magnification,cfg.animationSpeed);
        if(reveal>.002){
            c.glass(wallpaper,dock.left,dock.top,dock.width,dock.height,25,1);
            for(int i=0;i<5;i++){
                double size=dock.size[i],x=dock.x[i],y=dock.y[i];
                if(dock.tooltip[i]>.01)c.round(x-4,y-4,size+8,size+8,15,MenuTheme.alpha(0x25FFFFFF,dock.tooltip[i]));
                c.icon(Dock.ACTIONS[i],x+size*.19,y+size*.19,size*.62,i==4?MenuTheme.RED:MenuTheme.text());
                if(dock.active==i)c.round(x+size*.5-2,dock.top+66,4,4,2,MenuTheme.accent());
                if(dock.tooltip[i]>.01&&!playSheet){String label=MenuLanguage.text(actionLabel(i));double w=c.width(label,13)+24,fade=dock.tooltip[i];
                    c.glass(wallpaper,x+size*.5-w*.5,dock.top-39-(1-fade)*4,w,27,9,fade);
                    c.center(label,x+size*.5,dock.top-32-(1-fade)*4,13,MenuTheme.alpha(MenuTheme.text(),fade));
                }
            }
            c.center(MenuLanguage.text("Change menu"),width*.5,dock.top+dock.height+14,13,MenuTheme.DIM);
        }
        c.opacity=old*clockFade;
        if(locked)c.center(MenuLanguage.text("Click anywhere or press Enter"),width*.5,height*.79,15,MenuTheme.DIM);
        c.text(footer,24,height-26,12,MenuTheme.DIM);
        if(creditWallpaper!=cfg.wallpaper||!creditLocale.equals(locale)){creditWallpaper=cfg.wallpaper;creditLocale=locale;credit=MenuLanguage.text("Wallpaper")+": "+cfg.wallpaper.artist;}
        c.text(credit,width-24-c.width(credit,12),height-26,12,MenuTheme.DIM);c.opacity=old;
    }
    @Override protected void paint(double mx,double my){
        if(mouseX>=0&&(Math.abs(mx-mouseX)>.2||Math.abs(my-mouseY)>.2))dock.focused=-1;mouseX=mx;mouseY=my;
        scene(canvas,vw,vh,mx,my,dt,0,true);
        playFade=MenuAnimation.smooth(playFade,playSheet?1:0,dt*ConfigManager.get().animationSpeed,ConfigManager.get().motion()?.065:.02);
        if(playFade>.002){
            double old=canvas.opacity;canvas.opacity*=playFade;
            var matrices=canvas.context.getMatrices();matrices.pushMatrix();double scale=ConfigManager.get().motion()?.96+.04*MenuAnimation.cubic(playFade):1;
            matrices.translate((float)(vw*.5),(float)(vh*.5));matrices.scale((float)scale,(float)scale);matrices.translate((float)(-vw*.5),(float)(-vh*.5));
            singleplayer.focused=playFocus==0;multiplayer.focused=playFocus==1;cancel.focused=playFocus==2;
            canvas.rect(0,0,vw,vh,0x42000000);canvas.glass(wallpaper,vw/2-194,vh/2-110,388,256,22,1);
            canvas.center(MenuLanguage.text("Let's play"),vw/2,vh/2-85,24,MenuTheme.text());
            singleplayer.render(canvas,mx,my,dt);multiplayer.render(canvas,mx,my,dt);cancel.render(canvas,mx,my,dt);
            matrices.popMatrix();canvas.opacity=old;
        }
        if(pending>=0){pendingAge+=dt;if(pendingAge>(ConfigManager.get().motion()?.12:.025)){int action=pending;pending=-1;activate(action);}}
        if(outgoing!=null){outgoingFade+=dt/(ConfigManager.get().motion()?.15:.055);canvas.rect(0,0,vw,vh,MenuTheme.alpha(0xFF000000,MenuAnimation.cubic(outgoingFade)));
            if(outgoingFade>=1){Screen next=outgoing;outgoing=null;MenuRuntime.incoming(next);client.setScreen(next);}}
    }
    private static String actionLabel(int index){return switch(index){case 0->"Play";case 1->"Accounts";case 2->"Settings";case 3->"Display";default->"Quit";};}
    public void openSettings(MenuSettingsScreen.Section section){client.setScreen(new MenuSettingsScreen(this,section));}
    public void openNative(Screen next){playSheet=false;outgoingFade=0;outgoing=next;}
    private void activate(int index){switch(index){case 0->{playSheet=true;playFocus=0;}case 1->openSettings(MenuSettingsScreen.Section.ACCOUNTS);case 2->openSettings(MenuSettingsScreen.Section.GENERAL);case 3->openSettings(MenuSettingsScreen.Section.APPEARANCE);case 4->{ConfigManager.flush();client.scheduleStop();}}}
    private void clickDock(int i){dock.click(i,ConfigManager.get().motion());MenuAudio.click();pending=i;pendingAge=0;}
    @Override public boolean mouseClicked(Click click,boolean doubled){
        if(click.button()!=GLFW_MOUSE_BUTTON_LEFT)return false;
        if(locked){locked=false;unlockAge=0;held=-1;return true;}
        if(pending>=0||outgoing!=null)return true;
        double x=click.x()/unit,y=click.y()/unit;
        if(playSheet){if(playFade<.9)return true;for(MenuControl control:new MenuControl[]{singleplayer,multiplayer,cancel})if(control.contains(x,y)){control.click(x,y);return true;}if(x<vw/2-194||x>vw/2+194||y<vh/2-110||y>vh/2+146)playSheet=false;return true;}
        if(x>=vw-150&&x<vw-30&&y>=20&&y<56){openSettings(MenuSettingsScreen.Section.ACCOUNTS);return true;}
        if(Math.abs(x-vw/2)<100&&y>=dock.top+dock.height+7&&y<dock.top+dock.height+37){openSettings(MenuSettingsScreen.Section.APPEARANCE);return true;}
        int i=dock.hit(x,y);if(i>=0)clickDock(i);return true;
    }
    @Override public boolean keyPressed(KeyInput input){
        int key=input.key();if(held==key)return true;
        if(locked){if(key==GLFW_KEY_ENTER||key==GLFW_KEY_KP_ENTER||key==GLFW_KEY_SPACE){locked=false;unlockAge=0;held=key;dock.focused=0;}return true;}
        if(key==GLFW_KEY_ESCAPE){if(playSheet)playSheet=false;else{locked=true;unlockAge=0;dock.active=-1;}return true;}
        if(playSheet){
            if(key==GLFW_KEY_TAB||key==GLFW_KEY_DOWN||key==GLFW_KEY_UP){int direction=key==GLFW_KEY_UP||(key==GLFW_KEY_TAB&&(input.modifiers()&GLFW_MOD_SHIFT)!=0)?-1:1;playFocus=Math.floorMod(playFocus+direction,3);return true;}
            if(key==GLFW_KEY_ENTER||key==GLFW_KEY_KP_ENTER||key==GLFW_KEY_SPACE){held=key;MenuControl.Button control=playFocus==0?singleplayer:playFocus==1?multiplayer:cancel;control.click(0,0);control.release();return true;}
            return true;
        }
        if(key==GLFW_KEY_TAB||key==GLFW_KEY_RIGHT||key==GLFW_KEY_LEFT){int direction=key==GLFW_KEY_LEFT||(key==GLFW_KEY_TAB&&(input.modifiers()&GLFW_MOD_SHIFT)!=0)?-1:1;dock.focused=Math.floorMod(dock.focused+direction,5);return true;}
        if(key==GLFW_KEY_ENTER||key==GLFW_KEY_KP_ENTER||key==GLFW_KEY_SPACE){held=key;if(playSheet)singleplayer.click(0,0);else if(pending<0)clickDock(Math.max(0,dock.focused));return true;}
        return super.keyPressed(input);
    }
    @Override public boolean mouseReleased(Click click){singleplayer.release();multiplayer.release();cancel.release();return true;}
    @Override public boolean keyReleased(KeyInput input){if(input.key()==held)held=-1;return true;}
}
