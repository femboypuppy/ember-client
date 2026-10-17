package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.EmberBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.LogoDrawer;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** The Ember emblem and wordmark in place of the Minecraft logo, on the title screen only (the end credits keep theirs). */
@Mixin(LogoDrawer.class)
public abstract class LogoDrawerMixin {
    @Inject(method = "draw(Lnet/minecraft/client/gui/DrawContext;IFI)V", at = @At("HEAD"), cancellable = true)
    private void ember$logo(DrawContext context, int screenWidth, float alpha, int y, CallbackInfo ci) {
        if (!EmberBackdrop.enabled() || !(mc.currentScreen instanceof TitleScreen)) return;
        EmberBackdrop.renderTitleLogo(context, y, alpha);
        ci.cancel();
    }
}
