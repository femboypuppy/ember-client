package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.gui.screens.EmberTitleScreen;
import meteordevelopment.meteorclient.utils.render.EmberMenuWallpaper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MinecraftClient.class)
public abstract class EmberMenuMinecraftMixin {
    @ModifyVariable(method="setScreen",at=@At("HEAD"),argsOnly=true)
    private Screen ember$replaceTitle(Screen requested){return requested instanceof TitleScreen && EmberMenuWallpaper.enabled()?EmberTitleScreen.firstScreen():requested;}
}
