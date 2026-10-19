package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.config.Config;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.GameMode;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Animate the native item row outside Exordium's cached HUD, keeping the rest cached. */
public final class EmberHotbarPass {
    private static final boolean CACHED_HUD = FabricLoader.getInstance().isModLoaded("exordium");
    private EmberHotbarPass() {}

    public static boolean separate() {
        var c = Config.get();
        // Exordium already bypasses its cache when a screen is open. Keep vanilla layering there.
        return CACHED_HUD && c != null && c.pillHotbar.get() && mc.currentScreen == null;
    }

    public static boolean visible() {
        return separate() && mc.world != null && mc.player != null && !mc.options.hudHidden
            && mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
    }
}
