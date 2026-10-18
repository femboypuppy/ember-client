/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import meteordevelopment.meteorclient.utils.render.EmberWorldAtmosphere;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.AtmosphereFog;
import net.minecraft.client.render.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @ModifyReturnValue(method = "getFogColor", at = @At("RETURN"))
    private Vector4f ember$fogColor(Vector4f original) {
        if (Modules.get() == null) return original;

        Ambience ambience = Modules.get().get(Ambience.class);
        if (ambience.isActive() && ambience.customFogColor.get()) {
            return ambience.fogColor.get().getVec4f();
        }

        return EmberWorldAtmosphere.fogTint(original);
    }

    @ModifyArgs(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void ember$haze(Args args) {
        if (!EmberWorldAtmosphere.fogHazeEnabled()) return;
        var c = Config.get();
        var haze = AtmosphereFog.range(c.fogStart.get(), c.fogEnd.get(), c.fogAmount.get());
        Vector4f color = args.get(2);
        args.set(2, new Vector4f(color.x, color.y, color.z, color.w * haze.opacity()));
        args.set(3, haze.start());
        args.set(4, haze.end());
    }

    @ModifyExpressionValue(method = "getFogBuffer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/render/fog/FogRenderer;fogEnabled:Z"))
    private boolean modifyFogEnabled(boolean original) {
        // The explicit atmospheric haze control owns its fog, independently of vanilla
        // fog removal. Turning Accent fog off returns the usual vanilla/module behavior.
        if (EmberWorldAtmosphere.fogHazeEnabled()) return true;
        if (Modules.get() == null) return original;

        return original && !Modules.get().get(NoRender.class).noFog();
    }
}
