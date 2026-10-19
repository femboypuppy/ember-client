package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.gui.screens.menu.MenuRuntime;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(InactivityFpsLimiter.class)
public abstract class EmberMenuFpsMixin {
    @Shadow public abstract InactivityFpsLimiter.LimitReason getLimitReason();
    @ModifyReturnValue(method="update",at=@At("RETURN"))
    private int ember$menuLimit(int vanilla){return MenuRuntime.fpsLimit(vanilla,getLimitReason()==InactivityFpsLimiter.LimitReason.OUT_OF_LEVEL_MENU);}
}
