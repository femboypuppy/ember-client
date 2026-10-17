/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Fullbright;
import meteordevelopment.meteorclient.systems.modules.render.NoRender;
import meteordevelopment.meteorclient.systems.modules.render.Xray;
import meteordevelopment.meteorclient.utils.render.EmberWorldAtmosphere;
import meteordevelopment.meteorclient.utils.render.CelestialLighting;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Vector3fc;

@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerMixin {
    @Shadow private boolean dirty;
    @Unique private int ember$lastLightingState;

    @Inject(method = "update", at = @At("HEAD"))
    private void ember$refreshLighting(float tickProgress, CallbackInfo ci) {
        int state = EmberWorldAtmosphere.lightingState();
        if (state != ember$lastLightingState) { dirty = true; ember$lastLightingState = state; }
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1))
    private float ember$skyBrightness(float original) {
        return EmberWorldAtmosphere.lightingEnabled() ? CelestialLighting.skyFactor(original, Config.get().worldDarkness.get()) : original;
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 6))
    private float ember$gamma(float original) {
        return EmberWorldAtmosphere.lightingEnabled() ? CelestialLighting.gamma(original, Config.get().worldDarkness.get()) : original;
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putVec3(Lorg/joml/Vector3fc;)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 0))
    private Vector3fc ember$daylightColor(Vector3fc original) { return EmberWorldAtmosphere.lightTint(original); }
    @Shadow
    @Final
    private GpuTexture glTexture;

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", shift = At.Shift.AFTER), cancellable = true)
    private void update$skip(float tickProgress, CallbackInfo ci, @Local Profiler profiler) {
        if ((Modules.get().get(Fullbright.class).getGamma() && !EmberWorldAtmosphere.lightingEnabled()) || Modules.get().isActive(Xray.class)) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(glTexture, ColorHelper.getArgb(255, 255, 255, 255));
            profiler.pop();
            ci.cancel();
        }
    }

    @Inject(method = "getDarkness", at = @At("HEAD"), cancellable = true)
	private void getDarknessFactor(LivingEntity entity, float factor, float tickProgress, CallbackInfoReturnable<Float> info) {
		if (Modules.get().get(NoRender.class).noDarkness()) info.setReturnValue(0.0f);
	}
}
