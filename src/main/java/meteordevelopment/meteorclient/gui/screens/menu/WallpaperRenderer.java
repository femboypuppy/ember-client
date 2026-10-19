package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/** Resource-managed offline wallpaper, small prefiltered blur, and shared parallax coordinates. */
public final class WallpaperRenderer {
    private static final Identifier[] SHARP={Identifier.of("ember","textures/menu/chrome.png"),Identifier.of("ember","textures/menu/chrome.png"),Identifier.of("ember","textures/menu/midnight.png")};
    private static final Identifier[] BLUR={Identifier.of("ember","textures/menu/chrome_blur.png"),Identifier.of("ember","textures/menu/chrome_blur.png"),Identifier.of("ember","textures/menu/midnight_blur.png")};
    // Live/static Chrome share artwork, so a reversible two-image blend handles rapid selections.
    private double midnight=ConfigManager.get().wallpaper==MenuConfig.Wallpaper.MIDNIGHT?1:0;
    private double live=ConfigManager.get().wallpaper==MenuConfig.Wallpaper.CHROME_LIVE?1:0;
    private double panX,panY,time,width,height,coverX,coverY,coverW,coverH;
    public void update(double w,double h,double mouseX,double mouseY,double dt) {
        var c=ConfigManager.get();width=w;height=h;
        midnight=MenuAnimation.smooth(midnight,c.wallpaper==MenuConfig.Wallpaper.MIDNIGHT?1:0,dt*c.animationSpeed,c.motion()?.16:.055);
        live=MenuAnimation.smooth(live,c.wallpaper==MenuConfig.Wallpaper.CHROME_LIVE?1:0,dt*c.animationSpeed,.2);
        if(c.motion())time+=dt*c.animationSpeed;
        double tx=c.motion()&&c.parallax?(mouseX/w-.5)*14:0,ty=c.motion()&&c.parallax?(mouseY/h-.5)*10:0;
        if(c.motion()){panX=MenuAnimation.smooth(panX,tx,dt,.35);panY=MenuAnimation.smooth(panY,ty,dt,.35);}else{panX=panY=0;}
        double zoom=1.06+(c.motion()?Math.sin(time*.12)*.012*live:0);
        double scale=Math.max(w/2560,h/1440)*zoom;coverW=2560*scale;coverH=1440*scale;
        coverX=(w-coverW)*.5+panX;coverY=(h-coverH)*.5+panY;
        if(c.motion()){coverX+=Math.sin(time*.09)*7*live;coverY+=Math.cos(time*.07)*4*live;}
    }
    public void draw(MenuCanvas c,double backgroundBlur) {
        if(midnight<.999)full(c,MenuConfig.Wallpaper.CHROME,1,backgroundBlur);
        if(midnight>.001)full(c,MenuConfig.Wallpaper.MIDNIGHT,midnight>.999?1:midnight,backgroundBlur);
        c.context.fillGradient(0,0,(int)width,(int)(height*.45),0x45000000,0x00000000);
        c.context.fillGradient(0,(int)(height*.45),(int)width,(int)height,0x00000000,0x99000000);
        // Soft vignette without a framebuffer, custom pipeline or per-frame image allocation.
        for(int i=0;i<16;i++){double strip=width*.018;int color=MenuTheme.alpha(0x14000000,Math.pow(1-i/16.0,2));c.rect(i*strip,0,strip,height,color);c.rect(width-(i+1)*strip,0,strip,height,color);}
    }
    private void full(MenuCanvas c,MenuConfig.Wallpaper wallpaper,double alpha,double blur) {
        region(c,wallpaper,false,0,0,width,height,alpha);
        if(ConfigManager.get().blur&&blur>.001)region(c,wallpaper,true,0,0,width,height,alpha*blur*ConfigManager.get().blurIntensity);
    }
    private void region(MenuCanvas c,MenuConfig.Wallpaper wallpaper,boolean blur,double x,double y,double w,double h,double alpha) {
        int tw=blur?320:2560,th=blur?180:1440;
        float u=(float)((x-coverX)/coverW*tw),v=(float)((y-coverY)/coverH*th);
        int rw=Math.max(1,(int)Math.ceil(w/coverW*tw)),rh=Math.max(1,(int)Math.ceil(h/coverH*th));
        c.context.drawTexture(RenderPipelines.GUI_TEXTURED,(blur?BLUR:SHARP)[wallpaper.ordinal()],(int)x,(int)y,u,v,(int)Math.ceil(w),(int)Math.ceil(h),rw,rh,tw,th,MenuTheme.alpha(0xFFFFFFFF,alpha*c.opacity));
    }
    public void sample(MenuCanvas c,double x,double y,double w,double h,double radius,double alpha) {
        if(alpha<=.001)return;
        if(midnight<.999)roundedSample(c,MenuConfig.Wallpaper.CHROME,x,y,w,h,radius,alpha);
        if(midnight>.001)roundedSample(c,MenuConfig.Wallpaper.MIDNIGHT,x,y,w,h,radius,alpha*(midnight>.999?1:midnight));
    }
    private void roundedSample(MenuCanvas c,MenuConfig.Wallpaper wallpaper,double x,double y,double w,double h,double radius,double alpha) {
        double r=Math.min(radius,Math.min(w,h)*.5);region(c,wallpaper,true,x,y+r,w,h-2*r,alpha);
        int rows=Math.max(1,(int)Math.ceil(r));
        for(int i=0;i<rows;i++){double a=i*r/rows,b=(i+1)*r/rows,dy=r-(a+b)*.5,inset=r-Math.sqrt(Math.max(0,r*r-dy*dy));region(c,wallpaper,true,x+inset,y+a,w-2*inset,b-a,alpha);region(c,wallpaper,true,x+inset,y+h-b,w-2*inset,b-a,alpha);}
    }
    public static void thumbnail(MenuCanvas c,MenuConfig.Wallpaper wallpaper,double x,double y,double w,double h,double radius) {
        int tw=2560,th=1440;double sourceRatio=tw/(double)th,ratio=w/h;
        int rw=ratio>sourceRatio?tw:(int)(th*ratio),rh=ratio>sourceRatio?(int)(tw/ratio):th;
        float u=(tw-rw)*.5f,v=(th-rh)*.5f;
        double r=Math.min(radius,h/2);int rows=(int)Math.ceil(h);
        for(int i=0;i<rows;i++){double dy=i<r?r-i-.5:i>=h-r?i+.5-(h-r):0;double inset=dy>0?r-Math.sqrt(Math.max(0,r*r-dy*dy)):0;
            c.context.drawTexture(RenderPipelines.GUI_TEXTURED,SHARP[wallpaper.ordinal()],(int)(x+inset),(int)y+i,u+(float)(inset/w*rw),v+(float)(i/h*rh),Math.max(1,(int)(w-2*inset)),1,Math.max(1,(int)(rw*(1-2*inset/w))),Math.max(1,(int)(rh/h)),tw,th,MenuTheme.alpha(0xFFFFFFFF,c.opacity));}
    }
}
