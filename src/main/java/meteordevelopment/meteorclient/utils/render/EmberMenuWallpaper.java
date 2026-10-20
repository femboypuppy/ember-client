package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.screens.menu.*;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.gui.DrawContext;

/** Native resource-backed wallpaper for out-of-world Minecraft child screens. */
public final class EmberMenuWallpaper {
    private static final MenuCanvas CANVAS=new MenuCanvas();
    private static final MenuAnimation.Clock CLOCK=new MenuAnimation.Clock();
    private static WallpaperRenderer wallpaper;
    private EmberMenuWallpaper(){}
    public static boolean enabled(){var c=Config.get();return c!=null && c.startMenu.get()==StartMenuLayout.Style.LockScreen && MeteorClient.mc!=null && MeteorClient.mc.world==null;}
    public static void renderScaled(DrawContext context){
        render(context, false);
    }
    /** The same selection and motion as the desktop, softly dimmed behind native lists/buttons. */
    public static void renderChildBackground(DrawContext context){
        render(context, true);
    }
    private static void render(DrawContext context, boolean child){
        if(wallpaper==null)wallpaper=new WallpaperRenderer();
        var mc=MeteorClient.mc;double unit=Math.max(.05,Math.min(context.getScaledWindowWidth()/1280.0,context.getScaledWindowHeight()/720.0));
        double w=context.getScaledWindowWidth()/unit,h=context.getScaledWindowHeight()/unit;
        context.getMatrices().pushMatrix();context.getMatrices().scale((float)unit,(float)unit);
        CANVAS.context=context;CANVAS.opacity=1;
        wallpaper.update(w,h,mc.mouse.getScaledX(mc.getWindow())/unit,mc.mouse.getScaledY(mc.getWindow())/unit,CLOCK.tick());
        wallpaper.draw(CANVAS,child?.22:0);
        if(child)CANVAS.rect(0,0,w,h,0x38000000);
        context.getMatrices().popMatrix();
    }
    /** Retained for callers of the older menu API. */
    public static void drawPixels(DrawContext context){renderScaled(context);}
}
