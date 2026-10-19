package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.gui.screens.menu.MenuRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class EmberMenuScreenMixin {
    @Inject(method="renderWithTooltip",at=@At("TAIL"))
    private void ember$incomingFade(DrawContext context,int mx,int my,float delta,CallbackInfo ci){MenuRuntime.paintIncoming((Screen)(Object)this,context);}
}
