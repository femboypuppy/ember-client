package meteordevelopment.meteorclient.gui.screens.menu;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.screens.EmberTitleScreen;
import meteordevelopment.meteorclient.systems.accounts.AccountType;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.StartMenuLayout;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.glfw.GLFW.*;

/** Native System Settings-style screen. Controls and input use the same transformed layout. */
public final class MenuSettingsScreen extends MenuScreen {
    public enum Section {
        GENERAL("General","Clock, date and language"),APPEARANCE("Appearance","Wallpaper, color and glass"),
        ANIMATIONS("Animations","Motion and interaction"),AUDIO("Audio","Music and interface sounds"),
        PERFORMANCE("Performance","Frame rate and reduced motion"),ACCOUNTS("Accounts","Saved accounts and sign-in"),ABOUT("About","Versions and credits");
        public final String title,description;
        Section(String title,String description){this.title=title;this.description=description;}
    }
    private static final Section[] SECTIONS=Section.values();
    private final EmberTitleScreen home;
    private final MenuAccounts accounts=new MenuAccounts();
    private final List<MenuControl> content=new ArrayList<>(),sheet=new ArrayList<>(),focusOrder=new ArrayList<>();
    private final MenuControl.Button[] sidebar=new MenuControl.Button[7];
    private final boolean[] matched={true,true,true,true,true,true,true};
    private final String footer="Ember Client "+MeteorClient.VERSION;
    private MenuControl.Input search,credential;
    private MenuControl focused,dragging;
    private Section section,pendingSection;
    private String query="";
    private double px,py,pw,ph,cx,cy,cw,bottom,contentHeight,scroll,targetScroll,progress,pageFade=1,modalFade;
    private double layoutW=-1,layoutH=-1,lockedScale=1;
    private boolean closing,modal,relayout;
    private int held=-1,lastAccountsRevision=-1;
    private Screen nativeTarget;
    private AccountType adding=AccountType.Microsoft;
    private MenuAccounts.Entry confirmRemove;

    public MenuSettingsScreen(EmberTitleScreen home,Section section){super("Ember Settings");this.home=home;this.section=section;accounts.refresh();}
    @Override protected double uiScale(){return dragging instanceof MenuControl.Slider?lockedScale:ConfigManager.get().uiScale;}
    @Override protected void layout(){
        viewport();layoutW=vw;layoutH=vh;
        pw=Math.min(1060,vw-40);ph=Math.min(630,vh-56);px=(vw-pw)/2;py=(vh-ph)/2;
        cx=px+232;cy=py+104;cw=pw-256;bottom=py+ph-25;
        String savedQuery=query;search=new MenuControl.Input("Search settings",px+17,py+88,181,34,false,this::searchChanged);search.value(savedQuery);
        for(int i=0;i<7;i++){Section page=SECTIONS[i];sidebar[i]=new MenuControl.Button(page.title,px+13,py+143+i*42,190,35,()->select(page));sidebar[i].enabled=()->!accounts.busy&&matches(page);}
        focus(null);buildContent();
        if(modal){String saved=credential==null?"":credential.value();buildSheet();credential.value(saved);}
    }
    private boolean matches(Section page){return matched[page.ordinal()];}
    private void searchChanged(String value){query=value;String needle=query.toLowerCase(java.util.Locale.ROOT);for(Section page:SECTIONS)matched[page.ordinal()]=needle.isBlank()||(MenuLanguage.text(page.title)+" "+MenuLanguage.text(page.description)).toLowerCase(java.util.Locale.ROOT).contains(needle);if(!matches(section))for(Section page:SECTIONS)if(matches(page)){select(page);break;}}
    private void select(Section next){if(next==(pendingSection==null?section:pendingSection)||accounts.busy)return;pendingSection=next;focus(null);if(dragging!=null)dragging.release();dragging=null;MenuAudio.click();}
    private double row(double y,MenuControl control){content.add(control);return y+control.h+7;}
    private MenuControl.Button button(String label,double y,Runnable action){return new MenuControl.Button(label,cx+12,y,cw-24,40,action);}
    private void info(double y,String... lines){content.add(new Info(cx+12,y,cw-24,lines));}
    private void buildContent(){
        content.clear();var c=ConfigManager.get();double y=cy;
        switch(section){
            case GENERAL->{
                y=row(y,new MenuControl.Segments("Clock",cx,y,cw,new String[]{"12-hour","24-hour"},()->c.clock24?1:0,v->c.clock24=v==1));
                y=row(y,new MenuControl.Toggle("Show date",cx,y,cw,()->c.showDate,v->c.showDate=v));
                y=row(y,new MenuControl.Segments("Menu language",cx,y,cw,new String[]{"System","English","Français"},()->c.language.equals("en_us")?1:c.language.equals("fr_fr")?2:0,v->{c.language=v==1?"en_us":v==2?"fr_fr":"system";MenuLanguage.reload();relayout=true;}));
                y+=12;y=row(y,button("Minecraft options",y,()->leaveFor(new OptionsScreen(this,client.options))));
            }
            case APPEARANCE->{
                info(y,"Wallpaper");y+=38;double tile=(cw-40)/3;var wallpapers=MenuConfig.Wallpaper.values();
                for(int i=0;i<wallpapers.length;i++)content.add(new MenuControl.Wallpaper(wallpapers[i],cx+8+(i%3)*(tile+12),y+(i/3)*142,tile));
                y+=142*((wallpapers.length-1)/3)+150;y=row(y,new MenuControl.ColorPicker(cx,y,cw));
                y=row(y,new MenuControl.Slider("Blur intensity",cx,y,cw,0,1,.01,()->c.blurIntensity,v->c.blurIntensity=v));
                y=row(y,new MenuControl.Slider("UI scale",cx,y,cw,.8,1.25,.01,()->c.uiScale,v->c.uiScale=v));
                y=row(y,new MenuControl.Segments("Glass tint",cx,y,cw,new String[]{"Dark","Light"},()->c.lightGlass?1:0,v->c.lightGlass=v==1));
            }
            case ANIMATIONS->{
                y=row(y,new MenuControl.Toggle("Animations",cx,y,cw,()->c.animations,v->c.animations=v));
                y=row(y,new MenuControl.Slider("Animation speed",cx,y,cw,.4,2,.05,()->c.animationSpeed,v->c.animationSpeed=v));
                y=row(y,new MenuControl.Toggle("Wallpaper parallax",cx,y,cw,()->c.parallax,v->c.parallax=v));
                y=row(y,new MenuControl.Toggle("Dock magnification",cx,y,cw,()->c.magnification,v->c.magnification=v));
                info(y+12,"Motion controls follow Reduce motion in Performance.");y+=60;
            }
            case AUDIO->{
                y=row(y,new MenuControl.Toggle("Menu music",cx,y,cw,()->c.music,v->c.music=v));
                y=row(y,new MenuControl.Slider("Music volume",cx,y,cw,0,1,.01,()->c.musicVolume,v->c.musicVolume=v));
                y=row(y,new MenuControl.Toggle("UI click sounds",cx,y,cw,()->c.clicks,v->c.clicks=v));
                info(y+12,"Uses Minecraft's menu soundtrack.","World sound preferences stay unchanged.");y+=90;
            }
            case PERFORMANCE->{
                y=row(y,new MenuControl.Slider("Menu FPS limit",cx,y,cw,30,240,10,()->c.fpsLimit,v->c.fpsLimit=(int)v));
                y=row(y,new MenuControl.Toggle("Blur",cx,y,cw,()->c.blur,v->c.blur=v));
                y=row(y,new MenuControl.Toggle("Reduce motion",cx,y,cw,()->c.reduceMotion,v->c.reduceMotion=v));
                info(y+12,"Reduced motion keeps short fades and disables movement.","Minecraft's global FPS and minimized-window limits still apply.");y+=90;
            }
            case ACCOUNTS->{
                y=row(y,button("Add an account",y,this::openAdd));
                info(y+6,"Current session: "+client.getSession().getUsername());y+=50;
                for(int i=0;i<accounts.entries.size();i++){
                    var entry=accounts.entries.get(i);String label=entry.name()+"  ·  "+entry.type()+(accounts.current(entry)?"  ·  "+MenuLanguage.text("Current"):"");
                    MenuControl.Button choose=new MenuControl.Button(label,cx+12,y,cw-156,44,()->accounts.signIn(entry.account(),false));choose.enabled=()->!accounts.busy&&!accounts.current(entry);content.add(choose);
                    MenuControl.Button remove=new MenuControl.Button(confirmRemove==entry?"Confirm remove":"Remove",cx+cw-135,y,123,44,()->{
                        if(confirmRemove!=entry){confirmRemove=entry;accounts.message("Click Confirm remove to remove this saved account.",false);}else{accounts.remove(entry);confirmRemove=null;}
                    });remove.enabled=()->!accounts.busy;content.add(remove);y+=56;
                }
                if(accounts.entries.isEmpty()){info(y+12,"No saved accounts yet.");y+=65;}
                y+=24;info(y,accounts.status);y+=65;lastAccountsRevision=accounts.revision;
            }
            case ABOUT->{
                info(y,"EMBER CLIENT","Client version: "+MeteorClient.VERSION,"Minecraft: "+FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString(),
                    "Mod version: "+MeteorClient.MOD_META.getVersion().getFriendlyString(),"Fabric · Yarn mappings · Java 21");y+=175;
                info(y,"Typography: Inter / Rasmus Andersson (OFL)","Nebula wallpaper: BoliviaInteligente / Unsplash","Silk wallpaper: Pawel Czerwinski / Unsplash",
                    "Obsidian wallpaper: Philip Oroni / Unsplash","Summit wallpaper: Benjamin Voros / Unsplash");y+=168;
                y=row(y,button("Use classic Minecraft menu",y,()->{Config.get().startMenu.set(StartMenuLayout.Style.Classic);Config.get().save();leaveFor(new TitleScreen());}));
            }
        }
        contentHeight=y-cy+16;targetScroll=Math.clamp(targetScroll,0,maxScroll());scroll=Math.clamp(scroll,0,maxScroll());refreshFocusOrder();
    }
    private void refreshFocusOrder(){focusOrder.clear();focusOrder.add(search);for(MenuControl.Button side:sidebar)if(side!=null)focusOrder.add(side);focusOrder.addAll(content);}
    private double maxScroll(){return Math.max(0,contentHeight-(bottom-cy));}
    private void leaveFor(Screen target){if(!accounts.canLeave())return;nativeTarget=target;closing=true;}
    @Override public void close(){if(!accounts.canLeave())return;closing=true;}
    @Override protected void paint(double mx,double my){
        if(relayout||Math.abs(vw-layoutW)>.01||Math.abs(vh-layoutH)>.01){relayout=false;layout();}
        if(section==Section.ACCOUNTS&&lastAccountsRevision!=accounts.revision){buildContent();}
        double speed=ConfigManager.get().animationSpeed;
        progress=MenuAnimation.smooth(progress,closing?0:1,dt*speed,ConfigManager.get().motion()?(closing?MenuTheme.SETTINGS_CLOSE_TAU:MenuTheme.SETTINGS_OPEN_TAU):.025);
        if(pendingSection!=null){pageFade=MenuAnimation.smooth(pageFade,0,dt*speed,.045);if(pageFade<.015){section=pendingSection;pendingSection=null;scroll=targetScroll=0;buildContent();}}
        else pageFade=MenuAnimation.smooth(pageFade,1,dt*speed,.07);
        home.scene(canvas,vw,vh,mx,my,dt,MenuAnimation.cubic(progress),false);
        double panelScale=panelScale();double logicalX=(mx-vw/2)/panelScale+vw/2,logicalY=(my-vh/2)/panelScale+vh/2;
        var matrices=canvas.context.getMatrices();matrices.pushMatrix();matrices.translate((float)(vw/2),(float)(vh/2));matrices.scale((float)panelScale,(float)panelScale);matrices.translate((float)(-vw/2),(float)(-vh/2));
        canvas.opacity=MenuAnimation.cubic(progress);canvas.glass(home.wallpaper,px,py,pw,ph,22,1);
        canvas.round(px+7,py+7,207,ph-14,16,ConfigManager.get().lightGlass?0x19FFFFFF:0x18000000);
        canvas.rect(px+216,py+14,1,ph-28,0x20FFFFFF);
        canvas.round(px+20,py+20,11,11,5.5,MenuTheme.RED);canvas.round(px+39,py+20,11,11,5.5,0xFFEDC45C);canvas.round(px+58,py+20,11,11,5.5,0xFF6FCA92);
        canvas.text(MenuLanguage.text("Settings"),px+20,py+52,21,MenuTheme.text());
        search.render(canvas,logicalX,logicalY,dt);
        for(int i=0;i<7;i++){sidebar[i].primary=(pendingSection==null?section:pendingSection)==SECTIONS[i];sidebar[i].render(canvas,logicalX,logicalY,dt);}
        canvas.text(footer,px+20,py+ph-28,11,MenuTheme.dim());
        double opacity=canvas.opacity;canvas.opacity*=pageFade;
        canvas.text(MenuLanguage.text(section.title),cx+12,py+28,27,MenuTheme.text());canvas.text(MenuLanguage.text(section.description),cx+12,py+67,13,MenuTheme.dim());
        targetScroll=Math.clamp(targetScroll,0,maxScroll());scroll=MenuAnimation.smooth(scroll,targetScroll,dt,.065);
        canvas.context.enableScissor((int)cx,(int)cy-1,(int)(cx+cw),(int)bottom);
        matrices.pushMatrix();double slide=ConfigManager.get().motion()?(1-pageFade)*12:0;matrices.translate((float)slide,(float)-scroll);
        for(int i=0;i<content.size();i++){MenuControl control=content.get(i);if(control.y+control.h>=cy+scroll&&control.y<=bottom+scroll)control.render(canvas,logicalX-slide,logicalY+scroll,dt);}
        matrices.popMatrix();canvas.context.disableScissor();canvas.opacity=opacity;
        if(maxScroll()>0){double track=bottom-cy,thumb=Math.max(28,track*track/contentHeight);canvas.round(cx+cw-2,cy+(track-thumb)*scroll/maxScroll(),3,thumb,1.5,0x557E8290);}
        modalFade=MenuAnimation.smooth(modalFade,modal?1:0,dt*speed,ConfigManager.get().motion()?.07:.02);
        if(modalFade>.002)paintSheet(logicalX,logicalY);
        canvas.opacity=1;matrices.popMatrix();
        if(closing&&progress<.003){closing=false;progress=0;if(nativeTarget!=null){Screen next=nativeTarget;nativeTarget=null;home.openNative(next);}client.setScreen(home);}
    }
    private double panelScale(){return ConfigManager.get().motion()?.95+.05*MenuAnimation.cubic(progress):1;}
    @Override public void tick(){super.tick();accounts.refresh();}

    private void openAdd(){if(accounts.busy)return;modal=true;buildSheet();focus(null);}
    private void buildSheet(){
        sheet.clear();double x=vw/2-210,y=vh/2-148;
        sheet.add(new MenuControl.Segments("Account type",x+18,y+53,384,new String[]{"Microsoft","Offline","Session","Altening"},()->switch(adding){case Microsoft->0;case Cracked->1;case Session->2;default->3;},v->{adding=switch(v){case 0->AccountType.Microsoft;case 1->AccountType.Cracked;case 2->AccountType.Session;default->AccountType.TheAltening;};buildSheet();}));
        sheet.getFirst().enabled=()->!accounts.busy;
        credential=new MenuControl.Input(adding==AccountType.Cracked?"Minecraft username":"Account token",x+26,y+141,368,38,adding!=AccountType.Cracked,value->{});
        credential.enabled=()->!accounts.busy&&adding!=AccountType.Microsoft;sheet.add(credential);
        MenuControl.Button add=new MenuControl.Button(adding==AccountType.Microsoft?"Sign in with Microsoft":"Add & switch",x+26,y+196,368,39,()->accounts.add(adding,credential.value()));add.primary=true;add.enabled=()->!accounts.busy;sheet.add(add);
        MenuControl.Button cancel=new MenuControl.Button("Cancel",x+26,y+243,368,32,()->{if(accounts.canLeave()){modal=false;focus(null);}});cancel.enabled=()->!accounts.busy||accounts.browser;sheet.add(cancel);
    }
    private void paintSheet(double mx,double my){
        double old=canvas.opacity;canvas.rect(0,0,vw,vh,MenuTheme.alpha(0x8A000000,modalFade));canvas.opacity*=modalFade;
        double x=vw/2-210,y=vh/2-148;canvas.glass(home.wallpaper,x,y,420,296,22,1);canvas.text(MenuLanguage.text("Add an account"),x+26,y+22,22,MenuTheme.text());
        for(int i=0;i<sheet.size();i++)sheet.get(i).render(canvas,mx,my,dt);
        if(accounts.busy||accounts.error){canvas.round(x+14,y+284,392,52,10,MenuTheme.glass());canvas.clippedText(MenuLanguage.text(accounts.status),x+24,y+301,12,372,accounts.error?MenuTheme.RED:MenuTheme.dim());}
        canvas.opacity=old;
    }
    private double mouseX(double x){return (x/unit-vw/2)/panelScale()+vw/2;}
    private double mouseY(double y){return (y/unit-vh/2)/panelScale()+vh/2;}
    private void focus(MenuControl control){if(focused!=null)focused.focused=false;focused=control;if(control!=null)control.focused=true;}
    @Override public boolean mouseClicked(Click click,boolean doubled){
        if(click.button()!=GLFW_MOUSE_BUTTON_LEFT||closing)return false;double x=mouseX(click.x()),y=mouseY(click.y());
        if(modal){for(int i=sheet.size()-1;i>=0;i--){var control=sheet.get(i);if(control.contains(x,y)){focus(control);dragging=control;lockedScale=ConfigManager.get().uiScale;return control.click(x,y);}}return true;}
        if(x>=px+13&&x<px+37&&y>=py+12&&y<py+38){close();return true;}
        if(search.contains(x,y)){focus(search);return search.click(x,y);}
        for(MenuControl.Button side:sidebar)if(side.contains(x,y)){focus(side);return side.click(x,y);}
        if(pageFade>.9&&x>=cx&&x<cx+cw&&y>=cy&&y<bottom)for(int i=content.size()-1;i>=0;i--){var control=content.get(i);if(control.contains(x,y+scroll)){focus(control);dragging=control;lockedScale=ConfigManager.get().uiScale;return control.click(x,y+scroll);}}
        focus(null);return true;
    }
    @Override public boolean mouseDragged(Click click,double dx,double dy){if(dragging!=null){dragging.drag(mouseX(click.x()),mouseY(click.y())+(modal?0:scroll));return true;}return false;}
    @Override public boolean mouseReleased(Click click){if(dragging!=null){dragging.release();dragging=null;}for(MenuControl.Button side:sidebar)side.release();return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(!modal&&mouseX(x)>=cx&&mouseY(y)>=cy&&mouseY(y)<bottom)targetScroll-=vertical*43;return true;}
    @Override public boolean charTyped(CharInput input){return focused!=null&&focused.enabled.getAsBoolean()&&focused.character(input);}
    @Override public boolean keyPressed(KeyInput input){
        int key=input.key();if(held==key)return true;
        if(key==GLFW_KEY_ESCAPE){if(modal){if(accounts.canLeave()){modal=false;focus(null);}}else close();return true;}
        if(key==GLFW_KEY_TAB){List<MenuControl> order=modal?sheet:focusOrder;if(order.isEmpty())return true;int index=order.indexOf(focused),direction=(input.modifiers()&GLFW_MOD_SHIFT)!=0?-1:1;
            for(int i=0;i<order.size();i++){index=Math.floorMod(index+direction,order.size());MenuControl control=order.get(index);if(!(control instanceof Info)&&control.enabled.getAsBoolean()){focus(control);if(!modal&&content.contains(control)){if(control.y<cy+targetScroll)targetScroll=control.y-cy;if(control.y+control.h>bottom+targetScroll)targetScroll=control.y+control.h-bottom;}break;}}return true;}
        if(!modal&&(input.modifiers()&(GLFW_MOD_CONTROL|GLFW_MOD_SUPER))!=0&&key==GLFW_KEY_F){focus(search);return true;}
        if(focused!=null&&focused.enabled.getAsBoolean()){if(key==GLFW_KEY_ENTER||key==GLFW_KEY_SPACE)held=key;return focused.key(input);}return true;
    }
    @Override public boolean keyReleased(KeyInput input){if(held==input.key())held=-1;return true;}
    @Override public void removed(){accounts.cancelBrowser();super.removed();}
    private static final class Info extends MenuControl {
        private final String[] lines;
        Info(double x,double y,double w,String... lines){super("",x,y,w,lines.length*29+10);this.lines=lines;}
        @Override public boolean contains(double mx,double my){return false;}
        @Override protected void paint(MenuCanvas canvas,double dt){for(int i=0;i<lines.length;i++)canvas.clippedText(MenuLanguage.text(lines[i]),x+4,y+8+i*29,14,w-8,MenuTheme.dim());}
    }
}
