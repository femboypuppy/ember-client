package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import java.util.function.*;
import static org.lwjgl.glfw.GLFW.*;

/** Controls are built on page/layout changes, not allocated during rendering. */
public abstract class MenuControl {
    public final double x,y,w,h;
    public final String label;
    public boolean focused,pressed;
    public BooleanSupplier enabled=()->true;
    protected double hover,press;
    protected MenuControl(String label,double x,double y,double w,double h){this.label=MenuLanguage.text(label);this.x=x;this.y=y;this.w=w;this.h=h;}
    public boolean contains(double mx,double my){return enabled.getAsBoolean()&&mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    public final void render(MenuCanvas c,double mx,double my,double dt){
        hover=MenuAnimation.smooth(hover,contains(mx,my)||focused?1:0,dt,.09);
        press=MenuAnimation.smooth(press,pressed?1:0,dt,.055);
        double old=c.opacity;if(!enabled.getAsBoolean())c.opacity*=.38;
        paint(c,dt);c.opacity=old;
    }
    protected abstract void paint(MenuCanvas c,double dt);
    public boolean click(double mx,double my){pressed=true;return true;}
    public void drag(double mx,double my){}
    public void release(){pressed=false;}
    public boolean key(KeyInput key){return false;}
    public boolean character(CharInput input){return false;}
    protected void title(MenuCanvas c){c.text(label,x+16,y+17,15,MenuTheme.text());}

    public static final class Button extends MenuControl {
        private final Runnable action;
        public boolean primary;
        public Button(String label,double x,double y,double w,double h,Runnable action){super(label,x,y,w,h);this.action=action;}
        @Override protected void paint(MenuCanvas c,double dt){
            double inset=press*1.7-hover*.5;
            if(focused)c.border(x-2,y-2,w+4,h+4,11,MenuTheme.alpha(MenuTheme.accent(),.7));
            c.round(x+inset,y+inset,w-inset*2,h-inset*2,9,primary?MenuTheme.alpha(MenuTheme.accent(),.85):MenuTheme.well());
            c.round(x+inset,y+inset,w-inset*2,h-inset*2,9,MenuTheme.alpha(0x16FFFFFF,hover));
            c.center(label,x+w/2,y+(h-16)/2,15,primary?0xFFFFFFFF:MenuTheme.text());
        }
        @Override public boolean click(double mx,double my){super.click(mx,my);MenuAudio.click();action.run();return true;}
        @Override public boolean key(KeyInput key){if(key.key()==GLFW_KEY_ENTER||key.key()==GLFW_KEY_SPACE){action.run();MenuAudio.click();return true;}return false;}
    }
    public static final class Toggle extends MenuControl {
        private final BooleanSupplier get;private final Consumer<Boolean> set;private final MenuAnimation.Spring knob;
        public Toggle(String label,double x,double y,double w,BooleanSupplier get,Consumer<Boolean> set){super(label,x,y,w,50);this.get=get;this.set=set;knob=new MenuAnimation.Spring(get.getAsBoolean()?1:0);}
        @Override protected void paint(MenuCanvas c,double dt){
            title(c);double target=get.getAsBoolean()?1:0;
            if(ConfigManager.get().motion())knob.update(target,dt*ConfigManager.get().animationSpeed,6,.83);else knob.snap(target);
            double sx=x+w-63,sy=y+13;
            if(focused)c.border(sx-3,sy-3,49,31,15,MenuTheme.alpha(MenuTheme.accent(),.3));
            c.round(sx,sy,43,25,12.5,0x486F7483);c.round(sx,sy,43,25,12.5,MenuTheme.alpha(MenuTheme.accent(),Math.clamp(knob.value,0,1)));
            c.round(sx+3+18*knob.value,sy+3,19,19,9.5,0xFFFDFDFF);
        }
        @Override public boolean click(double mx,double my){set.accept(!get.getAsBoolean());ConfigManager.changed();MenuAudio.click();return true;}
        @Override public boolean key(KeyInput k){if(k.key()==GLFW_KEY_SPACE||k.key()==GLFW_KEY_ENTER){click(0,0);return true;}return false;}
    }
    public static final class Slider extends MenuControl {
        private final DoubleSupplier get;private final DoubleConsumer set;private final double min,max,step;
        private double last=Double.NaN;private String value="";
        public Slider(String label,double x,double y,double w,double min,double max,double step,DoubleSupplier get,DoubleConsumer set){super(label,x,y,w,65);this.get=get;this.set=set;this.min=min;this.max=max;this.step=step;}
        @Override protected void paint(MenuCanvas c,double dt){
            double v=get.getAsDouble();if(v!=last){last=v;value=step>=1?Integer.toString((int)Math.round(v)):Math.round(v*100)+"%";}
            c.text(label,x+16,y+8,15,MenuTheme.text());c.text(value,x+w-68,y+8,13,MenuTheme.dim());
            double sw=w-40,sx=x+20,sy=y+42,t=Math.clamp((v-min)/(max-min),0,1);
            c.round(sx,sy,sw,4,2,0x486F7483);c.round(sx,sy,Math.max(1,sw*t),4,2,MenuTheme.accent());
            if(focused||pressed)c.round(sx+sw*t-12,sy-10,24,24,12,MenuTheme.alpha(MenuTheme.accent(),.22));
            c.shadow(sx+sw*t-7,sy-5,14,14,7,.3);c.round(sx+sw*t-7,sy-5,14,14,7,0xFFFFFFFF);
        }
        private void assign(double mx){double v=min+Math.clamp((mx-x-20)/(w-40),0,1)*(max-min);set.accept(Math.clamp(Math.round(v/step)*step,min,max));ConfigManager.changed();}
        @Override public boolean click(double mx,double my){pressed=true;assign(mx);return true;}
        @Override public void drag(double mx,double my){assign(mx);}
        @Override public boolean key(KeyInput k){int d=k.key()==GLFW_KEY_LEFT?-1:k.key()==GLFW_KEY_RIGHT?1:0;if(d==0)return false;set.accept(Math.clamp(get.getAsDouble()+d*step,min,max));ConfigManager.changed();return true;}
    }
    public static final class Segments extends MenuControl {
        private final String[] options;private final IntSupplier get;private final IntConsumer set;private final MenuAnimation.Spring selection;
        public Segments(String label,double x,double y,double w,String[] options,IntSupplier get,IntConsumer set){super(label,x,y,w,76);this.options=options.clone();for(int i=0;i<options.length;i++)this.options[i]=MenuLanguage.text(options[i]);this.get=get;this.set=set;selection=new MenuAnimation.Spring(get.getAsInt());}
        @Override protected void paint(MenuCanvas c,double dt){
            c.text(label,x+16,y+7,15,MenuTheme.text());double sx=x+16,sy=y+33,sw=(w-32)/options.length;
            c.round(sx,sy,w-32,31,8,MenuTheme.well());
            if(ConfigManager.get().motion())selection.update(get.getAsInt(),dt*ConfigManager.get().animationSpeed,6,.9);else selection.snap(get.getAsInt());
            c.round(sx+selection.value*sw+2,sy+2,sw-4,27,6,MenuTheme.alpha(MenuTheme.accent(),.85));
            for(int i=0;i<options.length;i++)c.center(options[i],sx+(i+.5)*sw,sy+8,13,i==get.getAsInt()?0xFFFFFFFF:MenuTheme.dim());
        }
        @Override public boolean click(double mx,double my){set.accept(Math.clamp((int)((mx-x-16)/((w-32)/options.length)),0,options.length-1));ConfigManager.changed();MenuAudio.click();return true;}
        @Override public boolean key(KeyInput k){int d=k.key()==GLFW_KEY_LEFT?-1:k.key()==GLFW_KEY_RIGHT?1:0;if(d==0)return false;set.accept(Math.floorMod(get.getAsInt()+d,options.length));ConfigManager.changed();return true;}
    }
    public static final class Input extends MenuControl {
        private final Consumer<String> changed;private final String placeholder;private final boolean secret;
        private String value="",shown="";private boolean all;private double elapsed;private int cursor;
        public Input(String placeholder,double x,double y,double w,double h,boolean secret,Consumer<String> changed){super(placeholder,x,y,w,h);this.placeholder=MenuLanguage.text(placeholder);this.secret=secret;this.changed=changed;}
        public String value(){return value;}
        public void value(String text){assign(text);}
        @Override protected void paint(MenuCanvas c,double dt){elapsed+=dt;if(focused)c.border(x-1,y-1,w+2,h+2,10,MenuTheme.alpha(MenuTheme.accent(),.45));c.round(x,y,w,h,9,MenuTheme.well());
            if(all&&focused)c.round(x+10,y+7,Math.min(w-20,c.width(shown,14)+4),h-14,3,MenuTheme.alpha(MenuTheme.accent(),.3));
            c.clippedText(shown.isEmpty()?placeholder:shown,x+12,y+(h-15)/2,14,w-24,shown.isEmpty()?MenuTheme.dim():MenuTheme.text());
            if(focused&&Math.sin(elapsed*6)>0){double caret=Math.min(w-15,12+c.width(secret?shown.substring(0,Math.min(shown.length(),value.codePointCount(0,cursor))):value.substring(0,cursor),14));c.rect(x+caret,y+10,1,h-20,MenuTheme.text());}
        }
        @Override public boolean click(double mx,double my){all=false;cursor=value.length();return true;}
        @Override public boolean character(CharInput input){if(!input.isValidChar())return false;insert(input.asString());return true;}
        private void assign(String text){cursor=text.length();value=text.replaceAll("[\\p{Cntrl}]","");if(value.length()>8192)value=value.substring(0,8192);cursor=Math.min(cursor,value.length());shown=secret?"•".repeat(Math.min(64,value.codePointCount(0,value.length()))):value;changed.accept(value);}
        private void insert(String text){int start=all?0:cursor;String next=(all?"":value.substring(0,cursor))+text+(all?"":value.substring(cursor));assign(next);cursor=Math.min(value.length(),start+text.length());all=false;}
        @Override public boolean key(KeyInput input){boolean control=(input.modifiers()&(GLFW_MOD_CONTROL|GLFW_MOD_SUPER))!=0;int k=input.key();
            if(control&&k==GLFW_KEY_A){all=true;return true;}
            if(control&&k==GLFW_KEY_V){insert(MinecraftClient.getInstance().keyboard.getClipboard());return true;}
            if(control&&k==GLFW_KEY_C&&!secret){MinecraftClient.getInstance().keyboard.setClipboard(value);return true;}
            if(k==GLFW_KEY_HOME){cursor=0;all=false;return true;}
            if(k==GLFW_KEY_END){cursor=value.length();all=false;return true;}
            if(k==GLFW_KEY_LEFT||k==GLFW_KEY_RIGHT){int direction=k==GLFW_KEY_LEFT?-1:1;if(all)cursor=direction<0?0:value.length();else if(direction<0&&cursor>0||direction>0&&cursor<value.length())cursor=value.offsetByCodePoints(cursor,direction);all=false;return true;}
            if(k==GLFW_KEY_BACKSPACE||k==GLFW_KEY_DELETE){
                if(all){assign("");all=false;return true;}int start=cursor,end=cursor;
                if(k==GLFW_KEY_BACKSPACE&&cursor>0)start=value.offsetByCodePoints(cursor,-1);
                if(k==GLFW_KEY_DELETE&&cursor<value.length())end=value.offsetByCodePoints(cursor,1);
                assign(value.substring(0,start)+value.substring(end));cursor=start;return true;
            }
            return false;
        }
    }
    public static final class Wallpaper extends MenuControl {
        private final MenuConfig.Wallpaper wallpaper;
        public Wallpaper(MenuConfig.Wallpaper wallpaper,double x,double y,double w){super(wallpaper.key,x,y,w,132);this.wallpaper=wallpaper;}
        @Override protected void paint(MenuCanvas c,double dt){boolean selected=ConfigManager.get().wallpaper==wallpaper;if(selected||focused)c.border(x-2,y-2,w+4,100,12,MenuTheme.accent());
            WallpaperRenderer.thumbnail(c,wallpaper,x,y,w,96,10);c.clippedText(MenuLanguage.text(wallpaper.key),x+3,y+106,13,w-6,MenuTheme.text());
            if(wallpaper==MenuConfig.Wallpaper.CHROME_LIVE){c.round(x+8,y+8,42,18,9,0xA6000000);c.center("LIVE",x+29,y+12,9,0xFFFFFFFF);}
        }
        @Override public boolean click(double mx,double my){ConfigManager.get().wallpaper=wallpaper;ConfigManager.changed();MenuAudio.click();return true;}
        @Override public boolean key(KeyInput input){if(input.key()==GLFW_KEY_SPACE||input.key()==GLFW_KEY_ENTER){click(0,0);return true;}return false;}
    }
    public static final class ColorPicker extends MenuControl {
        private double hue,saturation,brightness;private boolean hueDrag;
        public ColorPicker(double x,double y,double w){super("Accent color",x,y,w,152);float[] hsb=java.awt.Color.RGBtoHSB((ConfigManager.get().accent>>16)&255,(ConfigManager.get().accent>>8)&255,ConfigManager.get().accent&255,null);hue=hsb[0];saturation=hsb[1];brightness=hsb[2];}
        @Override protected void paint(MenuCanvas c,double dt){c.text(label,x+16,y+8,15,MenuTheme.text());double sx=x+16,sy=y+35,sw=w-105;
            for(int i=0;i<48;i++){int color=java.awt.Color.HSBtoRGB((float)hue,i/47f,1);c.context.fillGradient((int)(sx+i*sw/48),(int)sy,(int)Math.ceil(sx+(i+1)*sw/48),(int)(sy+90),MenuTheme.alpha(color,c.opacity),MenuTheme.alpha(0xFF000000,c.opacity));}
            double cx=sx+saturation*sw,cy=sy+(1-brightness)*90;c.round(cx-5,cy-5,10,10,5,0xFFFFFFFF);c.round(cx-3,cy-3,6,6,3,MenuTheme.accent());
            for(int i=0;i<48;i++)c.rect(x+w-65,sy+i*90/48.0,24,Math.ceil(90/48.0),java.awt.Color.HSBtoRGB(i/47f,1,1));
            c.border(x+w-68,sy+hue*90-3,30,6,3,0xFFFFFFFF);
        }
        private void assign(double mx,double my){if(hueDrag)hue=Math.clamp((my-y-35)/90,0,1);else{saturation=Math.clamp((mx-x-16)/(w-105),0,1);brightness=1-Math.clamp((my-y-35)/90,0,1);}ConfigManager.get().accent=java.awt.Color.HSBtoRGB((float)hue,(float)saturation,(float)brightness)&0xFFFFFF;ConfigManager.changed();}
        @Override public boolean click(double mx,double my){pressed=true;hueDrag=mx>x+w-80;assign(mx,my);return true;}
        @Override public void drag(double mx,double my){assign(mx,my);}
        @Override public boolean key(KeyInput k){if(k.key()!=GLFW_KEY_LEFT&&k.key()!=GLFW_KEY_RIGHT)return false;hue=(hue+(k.key()==GLFW_KEY_LEFT?-.01:.01)+1)%1;ConfigManager.get().accent=java.awt.Color.HSBtoRGB((float)hue,(float)saturation,(float)brightness)&0xFFFFFF;ConfigManager.changed();return true;}
    }
}
