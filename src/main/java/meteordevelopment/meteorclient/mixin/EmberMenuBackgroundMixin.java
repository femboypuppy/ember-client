package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.EmberMenuWallpaper;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class EmberMenuBackgroundMixin {
    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
    private void ember$wallpaper(DrawContext context, float delta, CallbackInfo ci) {
        if (EmberMenuWallpaper.enabled()) {
            EmberMenuWallpaper.renderScaled(context);
            ci.cancel();
        }
    }
}
