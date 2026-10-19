package meteordevelopment.meteorclient.gui.screens.menu;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.render.EmberMenuWallpaper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/** Short incoming fade for native Minecraft child screens; all drawing stays in DrawContext. */
public final class MenuRuntime {
    private static Screen incoming;
    private static long incomingStart;
    public static volatile int resourceGeneration;
    private MenuRuntime() {}
    public static boolean enabled(){return MeteorClient.mc!=null && EmberMenuWallpaper.enabled();}
    public static void incoming(Screen screen){incoming=screen;incomingStart=System.nanoTime();}
    public static void paintIncoming(Screen screen,DrawContext context){
        if(screen!=incoming)return;
        double duration=ConfigManager.get().motion()?.18:.055;
        double t=(System.nanoTime()-incomingStart)*1e-9/duration;
        if(t>=1){incoming=null;return;}
        int alpha=(int)(255*(1-MenuAnimation.cubic(t)));
        context.createNewRootLayer();context.fill(0,0,screen.width,screen.height,alpha<<24);
    }
    public static int fpsLimit(int vanilla,boolean normalMenuReason){
        if(!(MinecraftClient.getInstance().currentScreen instanceof MenuScreen))return vanilla;
        int target=Math.min(ConfigManager.get().fpsLimit,MinecraftClient.getInstance().options.getMaxFps().getValue());
        return normalMenuReason?target:Math.min(vanilla,target);
    }
}
