/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.util.Identifier;

/** Skin the native GUI sprites so item rendering, HUD caching and GUI scale stay vanilla-owned. */
public final class EmberHotbar {
    private static final Identifier BAR = MeteorClient.identifier("hotbar/pill");
    private static final Identifier SELECTION = MeteorClient.identifier("hotbar/selection");
    private static final Identifier OFFHAND_LEFT = MeteorClient.identifier("hotbar/offhand_left");
    private static final Identifier OFFHAND_RIGHT = MeteorClient.identifier("hotbar/offhand_right");

    private EmberHotbar() {}

    public static Identifier sprite(Identifier original) {
        var config = Config.get();
        if (config == null || !config.pillHotbar.get() || !original.getNamespace().equals("minecraft")) return original;
        return switch (original.getPath()) {
            case "hud/hotbar" -> BAR;
            case "hud/hotbar_selection" -> SELECTION;
            case "hud/hotbar_offhand_left" -> OFFHAND_LEFT;
            case "hud/hotbar_offhand_right" -> OFFHAND_RIGHT;
            default -> original;
        };
    }
}
