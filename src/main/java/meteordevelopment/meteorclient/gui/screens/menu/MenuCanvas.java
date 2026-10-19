package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;

/** Native GUI commands only. Minecraft owns texture uploads, text atlases, batching and clipping. */
public final class MenuCanvas {
    private record Label(Text text, int width) {}
    private static final Style BODY = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("ember", "ui")));
    private static final Style CLOCK = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("ember", "clock")));
    private final Map<String, Label> bodyCache = new HashMap<>(), clockCache = new HashMap<>();
    public DrawContext context;
    public double opacity = 1;
    public void reset() { bodyCache.clear(); clockCache.clear(); }
    public void rect(double x, double y, double w, double h, int color) {
        if (w > 0 && h > 0) context.fill((int) Math.floor(x), (int) Math.floor(y), (int) Math.ceil(x+w), (int) Math.ceil(y+h), MenuTheme.alpha(color, opacity));
    }
    public void round(double x, double y, double w, double h, double radius, int color) {
        double r = Math.clamp(radius, 0, Math.min(w,h)*.5);
        rect(x,y+r,w,h-r*2,color);
        int rows = Math.max(1,(int)Math.ceil(r));
        for(int i=0;i<rows;i++) {
            double top=i*r/rows, next=(i+1)*r/rows, dy=r-(top+next)*.5;
            double inset=r-Math.sqrt(Math.max(0,r*r-dy*dy));
            rect(x+inset,y+top,w-inset*2,next-top,color);
            rect(x+inset,y+h-next,w-inset*2,next-top,color);
        }
    }
    public void border(double x,double y,double w,double h,double radius,int color) {
        // A continuous one-pixel rim; callers draw the inner surface over the fill.
        round(x-1,y-1,w+2,h+2,radius+1,color);
    }
    public void shadow(double x,double y,double w,double h,double radius,double amount) {
        for(int i=7;i>=1;i--) { double spread=i*2.2; round(x-spread,y-spread+5,w+spread*2,h+spread*2,radius+spread,MenuTheme.alpha(0x16000000,amount*(8-i)/7)); }
    }
    public void glass(WallpaperRenderer wallpaper,double x,double y,double w,double h,double radius,double alpha) {
        double old=opacity;opacity*=alpha;
        shadow(x,y,w,h,radius,1);
        border(x,y,w,h,radius,0x35FFFFFF);
        round(x,y,w,h,radius,ConfigManager.get().lightGlass?0xFFE9EDF5:0xFF191D29);
        wallpaper.sample(this,x,y,w,h,radius,ConfigManager.get().blur?ConfigManager.get().blurIntensity*.82:0);
        round(x,y,w,h,radius,MenuTheme.glass());
        rect(x+radius,y,w-radius*2,1,0x36FFFFFF);
        opacity=old;
    }
    private Label label(String value,boolean clock) {
        var cache=clock?clockCache:bodyCache; Label label=cache.get(value);
        if(label==null) {
            if(cache.size()>=768)cache.clear();
            Text text=Text.literal(value).setStyle(clock?CLOCK:BODY);
            label=new Label(text,MinecraftClient.getInstance().textRenderer.getWidth(text));cache.put(value,label);
        }
        return label;
    }
    public double width(String value,double height) { return label(value,false).width*height/MenuTheme.BODY_CAP_HEIGHT; }
    public void text(String value,double x,double y,double height,int color) { text(value,x,y,height,color,false,false); }
    public void center(String value,double x,double y,double height,int color) { text(value,x,y,height,color,true,false); }
    public void clock(String value,double x,double y,double height,int color) { text(value,x,y,height,color,true,true); }
    private void text(String value,double x,double y,double height,int color,boolean center,boolean clock) {
        if(value==null||value.isEmpty()||opacity<.001)return;
        if((MenuTheme.alpha(color,opacity)>>>24)==0)return;
        // Inter cap heights are 12/70 at the provider sizes; heights here mean visible pixels.
        Label label=label(value,clock);double scale=height/(clock?MenuTheme.CLOCK_CAP_HEIGHT:MenuTheme.BODY_CAP_HEIGHT);
        var m=context.getMatrices();m.pushMatrix();
        m.translate((float)(x-(center?label.width*scale*.5:0)),(float)y);m.scale((float)scale,(float)scale);
        context.drawText(MinecraftClient.getInstance().textRenderer,label.text,0,0,MenuTheme.alpha(color,opacity),false);
        m.popMatrix();
    }
    public void clippedText(String value,double x,double y,double height,double maxWidth,int color) {
        context.enableScissor((int)x,(int)y-3,(int)(x+Math.max(0,maxWidth)),(int)(y+height*1.5));
        text(value,x,y,height,color);context.disableScissor();
    }
    public void line(double ax,double ay,double bx,double by,double thickness,int color) {
        double dx=bx-ax,dy=by-ay;var m=context.getMatrices();m.pushMatrix();m.translate((float)ax,(float)ay);m.rotate((float)Math.atan2(dy,dx));rect(0,-thickness*.5,Math.hypot(dx,dy),thickness,color);m.popMatrix();
    }
    public void icon(Dock.Action action,double x,double y,double size,int color) {
        double k=size/24;var m=context.getMatrices();m.pushMatrix();m.translate((float)x,(float)y);m.scale((float)k,(float)k);
        switch(action) {
            case PLAY -> { for(int i=0;i<14;i++)rect(8,5+i,Math.max(1,12-Math.abs(i-6.5)*1.7),1,color); }
            case ACCOUNTS -> { round(8,3,8,8,4,color);round(4,13,16,8,4,color); }
            case SETTINGS -> { for(int i=0;i<8;i++){double a=i*Math.PI/4;line(12+Math.cos(a)*7,12+Math.sin(a)*7,12+Math.cos(a)*10,12+Math.sin(a)*10,3,color);}round(5,5,14,14,7,color);round(9,9,6,6,3,MenuTheme.glass()|0xFF000000); }
            case DISPLAY -> { border(3,3,18,13,3,color);round(4,4,16,11,2,0xFF262A35);line(12,17,12,21,2,color);line(7,21,17,21,2,color); }
            case POWER -> { for(int i=0;i<28;i++){double a=(.22+i/28.0*1.56)*Math.PI,b=(.22+(i+1)/28.0*1.56)*Math.PI;line(12+Math.sin(a)*8,12-Math.cos(a)*8,12+Math.sin(b)*8,12-Math.cos(b)*8,2.2,color);}line(12,2,12,11,2.2,color); }
        }
        m.popMatrix();
    }
}
