/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;
import meteordevelopment.meteorclient.utils.render.ChamsMaterial;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.Set;

public class Chams extends Module {
    private final SettingGroup sgThroughWalls = settings.createGroup("Through Walls");
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgCrystals = settings.createGroup("Crystals");
    private final SettingGroup sgHand = settings.createGroup("Hand");

    // Through walls

    public final Setting<Set<EntityType<?>>> entities = sgThroughWalls.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Select entities to show through walls.")
        .onlyAttackable()
        .build()
    );

    public final Setting<Shader> shader = sgThroughWalls.add(new EnumSetting.Builder<Shader>()
        .name("shader")
        .description("Renders a shader over of the entities.")
        .defaultValue(Shader.Image)
        .build()
    );

    public final Setting<SettingColor> shaderColor = sgThroughWalls.add(new ColorSetting.Builder()
        .name("color")
        .description("The color that the shader is drawn with.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .visible(() -> shader.get() != Shader.None)
        .build()
    );

    public final Setting<Boolean> ignoreSelfDepth = sgThroughWalls.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Ignores yourself drawing the player.")
        .defaultValue(true)
        .build()
    );

    // Players

    public final Setting<Boolean> players = sgPlayers.add(new BoolSetting.Builder()
        .name("players")
        .description("Enables model tweaks for players.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> ignoreSelf = sgPlayers.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Ignores yourself when tweaking player models.")
        .defaultValue(false)
        .visible(players::get)
        .build()
    );

    public final Setting<Boolean> playersTexture = sgPlayers.add(new BoolSetting.Builder()
        .name("texture")
        .description("Enables player model textures.")
        .defaultValue(false)
        .visible(players::get)
        .build()
    );

    public final Setting<SettingColor> playersColor = sgPlayers.add(new ColorSetting.Builder()
        .name("color")
        .description("Custom player accent when Use Ember accent is off. Alpha always controls opacity.")
        .defaultValue(new SettingColor(198, 135, 254, 150))
        .visible(players::get)
        .build()
    );

    public final Setting<Double> playersScale = sgPlayers.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Players scale.")
        .defaultValue(1.0)
        .min(0.0)
        .visible(players::get)
        .build()
    );

    public final Setting<ChamsMaterial> playerMaterial = sgPlayers.add(new EnumSetting.Builder<ChamsMaterial>()
        .name("material").description("Animated surface used when original player textures are off.")
        .defaultValue(ChamsMaterial.Galaxy).visible(() -> players.get() && !playersTexture.get()).build());
    public final Setting<Boolean> playerAccent = sgPlayers.add(new BoolSetting.Builder()
        .name("use-ember-accent").description("Match player chams to Ember's current accent, including album artwork.")
        .defaultValue(true).visible(() -> players.get()).build());
    public final Setting<Boolean> animateMaterials = sgThroughWalls.add(new BoolSetting.Builder()
        .name("animate-materials").description("Slowly animate the Galaxy, Aurora and Opal materials. Off freezes the surface.")
        .defaultValue(true).build());
    public final Setting<Boolean> shaderAccent = sgThroughWalls.add(new BoolSetting.Builder()
        .name("use-ember-accent").description("Use Ember's accent for through-wall materials, keeping the color's opacity.")
        .defaultValue(true).build());

    // Crystals

    public final Setting<Boolean> crystals = sgCrystals.add(new BoolSetting.Builder()
        .name("crystals")
        .description("Enables model tweaks for end crystals.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> crystalsScale = sgCrystals.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Crystal scale.")
        .defaultValue(0.6)
        .min(0)
        .visible(crystals::get)
        .build()
    );

    public final Setting<Double> crystalsBounce = sgCrystals.add(new DoubleSetting.Builder()
        .name("bounce")
        .description("How high crystals bounce.")
        .defaultValue(0.6)
        .min(0.0)
        .visible(crystals::get)
        .build()
    );

    public final Setting<Double> crystalsRotationSpeed = sgCrystals.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Multiplies the rotation speed of the crystal.")
        .defaultValue(0.3)
        .min(0)
        .visible(crystals::get)
        .build()
    );

    public final Setting<Boolean> crystalsTexture = sgCrystals.add(new BoolSetting.Builder()
        .name("texture")
        .description("Whether to render crystal model textures.")
        .defaultValue(true)
        .visible(crystals::get)
        .build()
    );

    public final Setting<SettingColor> crystalsColor = sgCrystals.add(new ColorSetting.Builder()
        .name("crystal-color")
        .description("The color of the of the crystal.")
        .defaultValue(new SettingColor(198, 135, 254, 255))
        .visible(crystals::get)
        .build()
    );

    // Hand

    public final Setting<Boolean> hand = sgHand.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enables tweaks of hand rendering.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> handTexture = sgHand.add(new BoolSetting.Builder()
        .name("texture")
        .description("Whether to render hand textures.")
        .defaultValue(false)
        .visible(hand::get)
        .build()
    );

    public final Setting<SettingColor> handColor = sgHand.add(new ColorSetting.Builder()
        .name("hand-color")
        .description("Custom hand accent when Use Ember accent is off. Alpha always controls opacity.")
        .defaultValue(new SettingColor(198, 135, 254, 150))
        .visible(hand::get)
        .build()
    );

    public final Setting<ChamsMaterial> handMaterial = sgHand.add(new EnumSetting.Builder<ChamsMaterial>()
        .name("material").description("Material for your first-person arms when original textures are off.")
        .defaultValue(ChamsMaterial.Galaxy).visible(() -> hand.get() && !handTexture.get()).build());
    public final Setting<Boolean> handAccent = sgHand.add(new BoolSetting.Builder()
        .name("use-ember-accent").description("Match hand chams to Ember's accent; turn off to choose your own hand color.")
        .defaultValue(true).visible(() -> hand.get()).build());

    public static final Identifier BLANK = MeteorClient.identifier("textures/blank.png");

    public Chams() {
        super(Categories.Render, "chams", "Tweaks rendering of entities.");
    }

    public boolean shouldRender(Entity entity) {
        return isActive() && !isShader() && entities.get().contains(entity.getType()) && (entity != mc.player || !ignoreSelfDepth.get());
    }

    public Color playerTint() { return tint(playersColor.get(), playerAccent.get()); }
    public Color handTint() { return tint(handColor.get(), handAccent.get()); }
    public Color shaderTint() { return tint(shaderColor.get(), shaderAccent.get()); }
    private static Color tint(Color manual, boolean accent) {
        Color rgb = accent ? EmberPalette.accent() : manual;
        return new Color(rgb.r, rgb.g, rgb.b, manual.a);
    }

    public int overlayMaterial() {
        return switch (shader.get()) { case Galaxy -> 1; case Aurora -> 2; case Opal -> 3; default -> 0; };
    }

    public boolean isShader() {
        return isActive() && shader.get() != Shader.None;
    }

    public enum Shader {
        Image,
        None,
        Galaxy,
        Aurora,
        Opal,
        Solid
    }
}
