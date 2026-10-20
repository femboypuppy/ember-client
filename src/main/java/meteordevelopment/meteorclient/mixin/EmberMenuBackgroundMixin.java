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
    /**
     * Replace the complete out-of-world background, before vanilla schedules its panorama and
     * fullscreen blur. In 1.21.11 those are deferred GUI passes; replacing just the panorama
     * still leaves the blur and darkening path active on world/server selection screens.
     */
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void ember$childBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (EmberMenuWallpaper.enabled()) {
            EmberMenuWallpaper.renderChildBackground(context);
            ci.cancel();
        }
    }

    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
    private void ember$wallpaper(DrawContext context, float delta, CallbackInfo ci) {
        if (EmberMenuWallpaper.enabled()) {
            EmberMenuWallpaper.renderScaled(context);
            ci.cancel();
        }
    }
}
