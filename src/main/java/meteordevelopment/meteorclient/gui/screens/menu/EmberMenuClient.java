package meteordevelopment.meteorclient.gui.screens.menu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;

/** Explicit client entrypoint: config loading is safe before the GUI/GPU exists. */
public final class EmberMenuClient implements ClientModInitializer {
    @Override public void onInitializeClient(){
        ConfigManager.init();
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(Identifier.of("ember","menu"),
            (SynchronousResourceReloader)manager->MenuRuntime.resourceGeneration++);
    }
}
