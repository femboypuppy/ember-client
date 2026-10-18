/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.gui.screens.EmberTitleScreen;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.player.TitleScreenCredits;
import meteordevelopment.meteorclient.utils.render.EmberMenuWallpaper;
import meteordevelopment.meteorclient.utils.render.StartMenuLayout;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    public TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ember$openStartMenu(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (EmberMenuWallpaper.enabled()
                && meteordevelopment.meteorclient.MeteorClient.mc.currentScreen == (Object) this) {
            EmberTitleScreen menu = EmberTitleScreen.firstScreen();
            meteordevelopment.meteorclient.MeteorClient.mc.setScreen(menu);
            menu.render(context, mouseX, mouseY, delta);
            ci.cancel();
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void ember$menuSwitch(CallbackInfo ci) {
        if (Config.get() == null) return;
        addDrawableChild(
                ButtonWidget.builder(
                                Text.literal("Ember menu"),
                                button -> {
                                    Config.get().startMenu.set(StartMenuLayout.Style.LockScreen);
                                    Config.get().save();
                                    meteordevelopment.meteorclient.MeteorClient.mc.setScreen(
                                            new EmberTitleScreen(false));
                                })
                        .dimensions(10, height - 36, 106, 20)
                        .build());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Config.get().titleScreenCredits.get()) TitleScreenCredits.render(context);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (Config.get().titleScreenCredits.get()
                && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (TitleScreenCredits.onClicked(click.x(), click.y())) cir.setReturnValue(true);
        }
    }
}
