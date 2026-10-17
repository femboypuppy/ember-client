/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.utils.render.EmberBackdrop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Lnet/minecraft/client/network/CookieStorage;)V", at = @At("HEAD"))
    private void tryConnectEvent(MinecraftClient client, ServerAddress address, ServerInfo info, CookieStorage cookieStorage, CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(ServerConnectBeginEvent.get(address, info));
    }

    /** The Ember loading scene shows the status itself (see EmberBackdropScreenMixin); only the cancel button stays. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ember$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!EmberBackdrop.enabled()) return;
        for (Drawable drawable : ((ScreenDrawablesAccessor) this).ember$getDrawables()) {
            if (drawable instanceof ButtonWidget) drawable.render(context, mouseX, mouseY, delta);
        }
        ci.cancel();
    }
}
