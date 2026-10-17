package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.EmberBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.ReconfiguringScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Ember's forge in place of the panorama on every menu, and its loading scene while joining a
 * server (connecting, logging in, configuring). The world loading screen is in LevelLoadingScreenMixin.
 */
@Mixin(Screen.class)
public abstract class EmberBackdropScreenMixin {
    @Shadow
    @Final
    private List<Drawable> drawables;

    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
    private void ember$forge(DrawContext context, float delta, CallbackInfo ci) {
        if (!EmberBackdrop.enabled()) return;
        EmberBackdrop.render(context, EmberBackdrop.Layout.MENU, null, -1);
        ci.cancel();
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void ember$joining(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!EmberBackdrop.enabled()) return;
        Object self = this;
        String status;
        if (self instanceof ConnectScreen connect) {
            var text = ((ConnectScreenAccessor) connect).ember$getStatus();
            status = text == null ? "Connecting" : text.getString();
        }
        else if (self instanceof ReconfiguringScreen) status = "Configuring";
        else return;

        EmberBackdrop.render(context, EmberBackdrop.Layout.LOADING, status, -1);
        ci.cancel();
    }

    /** The configuring screen's own text sits where the scene's status line is, so only its button stays. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ember$joiningWidgets(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!EmberBackdrop.enabled() || !((Object) this instanceof ReconfiguringScreen)) return;
        ember$renderButtons(drawables, context, mouseX, mouseY, delta);
        ci.cancel();
    }

    @Unique
    private static void ember$renderButtons(List<Drawable> drawables, DrawContext context, int mouseX, int mouseY, float delta) {
        for (Drawable drawable : drawables) {
            if (drawable instanceof ButtonWidget) drawable.render(context, mouseX, mouseY, delta);
        }
    }
}
