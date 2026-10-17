package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.EmberBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The last step of joining: the Ember loading scene with the world's real progress, instead of the dirt and chunk map. */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
    @Shadow
    private float loadProgress;

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void ember$background(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!EmberBackdrop.enabled()) return;
        EmberBackdrop.render(context, EmberBackdrop.Layout.LOADING, "Loading world", loadProgress);
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ember$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (EmberBackdrop.enabled()) ci.cancel();
    }
}
