package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Glint;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.TextureTransform;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureTransform.class)
public abstract class TextureTransformMixin {
    // Same as vanilla getGlintTransformation, with the Glint module's speed and scale applied.
    @Inject(method = "getGlintTransformation", at = @At("HEAD"), cancellable = true)
    private static void onGlintTransformation(float scale, CallbackInfoReturnable<Matrix4f> cir) {
        if (Modules.get() == null) return;
        Glint glint = Modules.get().get(Glint.class);
        if (glint == null || !glint.isActive()) return;

        long l = (long) (Util.getMeasuringTimeMs() * MinecraftClient.getInstance().options.getGlintSpeed().getValue() * 8.0 * glint.speed());
        float f = (float) (l % 110000L) / 110000.0F;
        float g = (float) (l % 30000L) / 30000.0F;
        Matrix4f matrix = new Matrix4f().translation(-f, g, 0.0F);
        matrix.rotateZ((float) (Math.PI / 18)).scale(scale * glint.scale());
        cir.setReturnValue(matrix);
    }
}
