package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Glint;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    // Every render layer resolves its textures through here each draw, so keep the lookup cheap.
    @Unique
    private static Glint meteor$glint;

    @Inject(method = "getTexture(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/AbstractTexture;", at = @At("HEAD"), cancellable = true)
    private void onGetTexture(Identifier id, CallbackInfoReturnable<AbstractTexture> cir) {
        if (meteor$glint == null) {
            if (Modules.get() == null) return;
            meteor$glint = Modules.get().get(Glint.class);
            if (meteor$glint == null) return;
        }

        AbstractTexture glint = meteor$glint.override(id);
        if (glint != null) cir.setReturnValue(glint);
    }
}
