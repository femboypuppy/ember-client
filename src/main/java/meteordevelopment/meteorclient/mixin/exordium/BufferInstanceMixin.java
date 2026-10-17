package meteordevelopment.meteorclient.mixin.exordium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.utils.render.EmberGuiPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Leave Exordium's vanilla HUD cache intact when Ember submits items or other GUI elements. */
@Pseudo
@Mixin(targets = "dev.tr7zw.exordium.components.BufferInstance", remap = false)
public abstract class BufferInstanceMixin {
    @ModifyReturnValue(method = "enabled()Z", at = @At("RETURN"), remap = false)
    private boolean meteor$keepCacheForVanillaPass(boolean enabled) {
        return enabled && !EmberGuiPass.isActive();
    }
}
