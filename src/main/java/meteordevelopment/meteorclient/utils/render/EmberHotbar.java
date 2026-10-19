/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.lang.ref.WeakReference;

/** Skin native GUI sprites while retaining Minecraft's item rendering and GUI scale. */
public final class EmberHotbar {
    private static final Identifier BAR = MeteorClient.identifier("hotbar/pill");
    private static final Identifier SELECTION = MeteorClient.identifier("hotbar/selection");
    private static final Identifier OFFHAND_LEFT = MeteorClient.identifier("hotbar/offhand_left");
    private static final Identifier OFFHAND_RIGHT = MeteorClient.identifier("hotbar/offhand_right");
    private static final Identifier SHADOW = MeteorClient.identifier("hotbar/shadow");
    private static final Identifier OFFHAND_SHADOW = MeteorClient.identifier("hotbar/offhand_shadow");
    private static final Identifier SELECTION_SHADOW = MeteorClient.identifier("hotbar/selection_shadow");
    private static final HotbarSelectionMotion MOTION = new HotbarSelectionMotion();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static int barX;
    private static long lastRender;

    private EmberHotbar() {}

    public static boolean draw(DrawContext context, RenderPipeline pipeline, Identifier original,
                               int x, int y, int width, int height) {
        var config = Config.get();
        if (config == null || !config.pillHotbar.get()) {
            MOTION.reset();
            return false;
        }
        if (!original.getNamespace().equals("minecraft")) return false;
        double opacity = config.hotbarOpacity.get();
        int shadowColor = tint(config.hotbarShadow.get() * Math.min(1, opacity / .55));
        int backgroundColor = tint(opacity);
        switch (original.getPath()) {
            case "hud/hotbar" -> {
                long now = System.nanoTime();
                if (world.get() != MeteorClient.mc.world || now - lastRender > 500_000_000L) MOTION.reset();
                if (world.get() != MeteorClient.mc.world) world = new WeakReference<>(MeteorClient.mc.world);
                lastRender = now;
                barX = x;
                HotbarBlur.draw(context, x, y, width, height, 9.5f);
                context.drawGuiTexture(pipeline, SHADOW, x - 18, y - 18, width + 36, height + 36, shadowColor);
                context.drawGuiTexture(pipeline, BAR, x, y, width, height, backgroundColor);
            }
            case "hud/hotbar_selection" -> {
                double target = x - (barX - 1);
                double offset = MOTION.position(target, System.nanoTime(), config.hotbarAnimation.get()) - target;
                context.getMatrices().pushMatrix();
                try {
                    context.getMatrices().translate((float) offset, 0);
                    context.drawGuiTexture(pipeline, SELECTION_SHADOW, x - 8, y - 8, width + 16, height + 16, shadowColor);
                    context.drawGuiTexture(pipeline, SELECTION, x, y, width, height, tint(opacity * 1.55));
                } finally {
                    context.getMatrices().popMatrix();
                }
            }
            case "hud/hotbar_offhand_left", "hud/hotbar_offhand_right" -> {
                boolean left = original.getPath().endsWith("left");
                // The native offhand sprite has seven pixels of empty space next to the main bar.
                HotbarBlur.draw(context, x + (left ? 0 : 7), y + 1, 22, 22, 8);
                context.drawGuiTexture(pipeline, OFFHAND_SHADOW, x + (left ? 0 : 7) - 18, y + 1 - 18, 58, 58, shadowColor);
                context.drawGuiTexture(pipeline, left ? OFFHAND_LEFT : OFFHAND_RIGHT, x, y, width, height, backgroundColor);
            }
            default -> { return false; }
        }
        return true;
    }

    private static int tint(double opacity) {
        return ((int) Math.round(Math.clamp(opacity, 0, 1) * 255) << 24) | 0xFFFFFF;
    }
}
