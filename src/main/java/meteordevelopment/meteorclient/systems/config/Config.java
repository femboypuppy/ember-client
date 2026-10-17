/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.config;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.CelestialLighting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Config extends System<Config> {
    public final Settings settings = new Settings();

    private final SettingGroup sgVisual = settings.createGroup("Visual");
    private final SettingGroup sgModules = settings.createGroup("Modules");
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgMisc = settings.createGroup("Misc");
    public final SettingGroup sgLyrics = settings.createGroup("Lyrics");

    public final Setting<Boolean> lyricsEnabled = sgLyrics.add(new BoolSetting.Builder()
        .name("lyrics-pill")
        .description("Show timed lyrics for the current music player beside the main status pill.")
        .defaultValue(true).build());
    public final Setting<Boolean> desktopMusic = sgLyrics.add(new BoolSetting.Builder()
        .name("detect-desktop-music")
        .description("Read music and playback timing from Windows, including compatible browser tabs. No Spotify login needed.")
        .defaultValue(true).build());
    public final Setting<Boolean> lyricsSearchFallback = sgLyrics.add(new BoolSetting.Builder()
        .name("search-lyrics-by-song")
        .description("Find line-synced lyrics through LRCLIB using title, artist and duration when Spicy Lyrics is unavailable.")
        .defaultValue(true).build());
    public final Setting<Boolean> spicyLyricsEnabled = sgLyrics.add(new BoolSetting.Builder()
        .name("spicy-lyrics-enabled")
        .description("Try Spicy Lyrics first. Requires your publishable client key and a Spotify track ID.")
        .defaultValue(true).build());
    public final Setting<Boolean> lyricsResolveSpotify = sgLyrics.add(new BoolSetting.Builder()
        .name("find-spotify-track-ids")
        .description("Find Spotify IDs with ListenBrainz and verify public track metadata. No Spotify login or developer key needed.")
        .defaultValue(true).build());
    public final Setting<Boolean> lyricsBackground = sgLyrics.add(new BoolSetting.Builder()
        .name("lyrics-background-vocals")
        .description("Show separately timed backing vocals below the lead lyrics.")
        .defaultValue(true).build());
    public final Setting<Boolean> lyricsCreditsAlways = sgLyrics.add(new BoolSetting.Builder()
        .name("lyrics-credits-always")
        .description("Keep provider and contributor credits visible. Otherwise show them for five seconds on each song and on hover.")
        .defaultValue(false).build());
    public final Setting<Boolean> lyricsRomanization = sgLyrics.add(new BoolSetting.Builder()
        .name("lyrics-romanization")
        .description("Use Spicy Lyrics romanized text when supplied. Keep the original script where romanization is unavailable.")
        .defaultValue(false).build());
    public final Setting<Integer> lyricsMaxWidth = sgLyrics.add(new IntSetting.Builder()
        .name("lyrics-max-width")
        .description("Maximum lyric pill width before long lines scroll. Short lines fit their text.")
        .defaultValue(360).range(180, 600).sliderRange(180, 600).build());
    public final Setting<Double> lyricsTextSize = sgLyrics.add(new DoubleSetting.Builder()
        .name("lyrics-text-size")
        .description("Lyric font size. Text keeps this size while scrolling and animating.")
        .defaultValue(0.64).range(0.45, 0.9).sliderRange(0.45, 0.9).build());
    public final Setting<Boolean> songAccent = sgVisual.add(new BoolSetting.Builder()
        .name("song-artwork-accent")
        .description("Use the current song artwork's dominant color for the whole Ember client.")
        .defaultValue(false).build());
    public final Setting<String> spicyLyricsKey = sgLyrics.add(new StringSetting.Builder()
        .name("spicy-lyrics-client-key")
        .description("Your publishable sl_pk_ key, configured to allow native clients without an Origin header.")
        .defaultValue("").build());
    public final Setting<Boolean> spotifyBridgeEnabled = sgLyrics.add(new BoolSetting.Builder()
        .name("spotify-local-bridge").description("Automatically accept local playback updates from the Ember Spicetify extension, including after restarting Minecraft.").defaultValue(true).build());
    public final Setting<String> spotifyClientId = sgLyrics.add(new StringSetting.Builder()
        .name("spotify-client-id")
        .description("Public Spotify app client ID. Redirect URI: http://127.0.0.1:18527/callback. No client secret is needed.")
        .defaultValue("").build());
    public final Setting<Double> lyricsOffset = sgLyrics.add(new DoubleSetting.Builder()
        .name("lyrics-offset-ms")
        .description("Adjust lyric timing for speaker or Bluetooth delay. Positive values advance the lyrics.")
        .defaultValue(0).range(-3000, 3000).sliderRange(-1500, 1500).build());

    // Visual

    public final Setting<Boolean> ambience = sgVisual.add(new BoolSetting.Builder()
        .name("celestial-ambience").description("Accent-colored aurora light, drifting stars and subtle constellations behind Ember's menus.").defaultValue(true).build());
    public final Setting<Boolean> ambienceHud = sgVisual.add(new BoolSetting.Builder()
        .name("ambience-in-game").description("Also show a much fainter atmosphere while playing, outside menus.").defaultValue(false).build());
    public enum CelestialSky { Nebula, Aurora, Dusk }
    public final Setting<Boolean> worldAtmosphere = sgVisual.add(new BoolSetting.Builder()
        .name("world-atmosphere").description("Enable the celestial atmosphere in the 3D world, independently of menus and HUD visibility.").defaultValue(true).build());
    public final Setting<Boolean> accentSky = sgVisual.add(new BoolSetting.Builder()
        .name("accent-skybox").description("Procedural accent-colored sky in dimensions with skylight.").defaultValue(true).build());
    public final Setting<CelestialSky> celestialSky = sgVisual.add(new EnumSetting.Builder<CelestialSky>()
        .name("celestial-sky-style").description("Choose a nebula, aurora or soft dusk skybox.").defaultValue(CelestialSky.Nebula).build());
    public final Setting<Double> skyIntensity = sgVisual.add(new DoubleSetting.Builder()
        .name("accent-sky-intensity").description("Brightness of the complete custom sky. Zero restores the original sky.").defaultValue(0.75).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Boolean> worldLighting = sgVisual.add(new BoolSetting.Builder()
        .name("accent-world-lighting").description("Match daylight on blocks and entities to the accent sky using Minecraft's lightmap.").defaultValue(true).build());
    public final Setting<Double> worldDarkness = sgVisual.add(new DoubleSetting.Builder()
        .name("accent-world-darkness").description("Dim daylight to suit the celestial sky. Torch and other block lighting remain independent.").defaultValue(CelestialLighting.BALANCED_DARKNESS).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> worldLightTint = sgVisual.add(new DoubleSetting.Builder()
        .name("accent-world-light-tint").description("How strongly daylight takes the accent color.").defaultValue(CelestialLighting.BALANCED_TINT).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> vanillaLightMix = sgVisual.add(new DoubleSetting.Builder()
        .name("vanilla-light-mix").description("Blend back to the original daylight and gamma. 100% restores vanilla lighting.").defaultValue(0).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> daylightBrightness = sgVisual.add(new DoubleSetting.Builder()
        .name("daylight-brightness").description("Brightness of sky illumination, independently of its accent color. Does not brighten unlit caves.").defaultValue(CelestialLighting.BALANCED_BRIGHTNESS).range(0.2, 1.6).sliderRange(0.2, 1.6).build());
    public final Setting<Boolean> worldStars = sgVisual.add(new BoolSetting.Builder()
        .name("world-stars").description("Distant celestial stars and world-anchored, depth-tested starlight motes.").defaultValue(true).build());
    public final Setting<Boolean> worldMotes = sgVisual.add(new BoolSetting.Builder()
        .name("world-motes").description("Show nearby floating starlight independently of distant sky stars.").defaultValue(true).build());
    public final Setting<Double> starBrightness = sgVisual.add(new DoubleSetting.Builder()
        .name("star-brightness").description("Brightness of world stars and motes without changing their density.").defaultValue(1).range(0, 2).sliderRange(0, 2).build());
    public final Setting<Boolean> accentFog = sgVisual.add(new BoolSetting.Builder()
        .name("accent-fog").description("A subtle accent tint and atmospheric haze in open-air dimensions.").defaultValue(true).build());
    public final Setting<Double> fogAmount = sgVisual.add(new DoubleSetting.Builder()
        .name("accent-fog-amount").description("Fog tint and distant haze strength. Nearby terrain stays clear.").defaultValue(0.3).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> lightShimmer = sgVisual.add(new DoubleSetting.Builder()
        .name("world-light-shimmer").description("Slow moving pools of accent light across nearby terrain.").defaultValue(0.3).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Boolean> groundLight = sgVisual.add(new BoolSetting.Builder()
        .name("ambience-ground-light").description("Soft additive accent light on nearby ground. Follows terrain without replacing block textures.").defaultValue(true).build());
    public final Setting<Double> groundLightRadius = sgVisual.add(new DoubleSetting.Builder()
        .name("ambience-light-radius").description("Radius of the accent light around your feet, in blocks.").defaultValue(3.4).range(1.5, 7).sliderRange(1.5, 7).build());
    public final Setting<Double> groundLightIntensity = sgVisual.add(new DoubleSetting.Builder()
        .name("ambience-light-intensity").description("Brightness of the colored floor illumination.").defaultValue(0.65).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> ambienceIntensity = sgVisual.add(new DoubleSetting.Builder()
        .name("ambience-intensity").description("Strength of the ambient light and stars.").defaultValue(0.65).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> ambienceDensity = sgVisual.add(new DoubleSetting.Builder()
        .name("ambience-density").description("Number of stars. The particle and geometry counts remain bounded.").defaultValue(0.55).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> ambienceSpeed = sgVisual.add(new DoubleSetting.Builder()
        .name("ambience-speed").description("Star drift, aurora and parallax motion. Zero holds the atmosphere still.").defaultValue(0.8).range(0, 2).sliderRange(0, 2).build());
    public enum BassMode { Darken, Glow }
    public final Setting<Boolean> musicReactive = sgVisual.add(new BoolSetting.Builder()
        .name("music-reactive-atmosphere").description("React only to Spotify desktop audio on Windows. Other applications and Minecraft sounds are excluded.").defaultValue(false).build());
    public final Setting<BassMode> bassMode = sgVisual.add(new EnumSetting.Builder<BassMode>()
        .name("bass-effect").description("Darken the scene on bass, or make it glow brighter.").defaultValue(BassMode.Darken).build());
    public final Setting<Double> bassStrength = sgVisual.add(new DoubleSetting.Builder()
        .name("bass-strength").description("Maximum visual change on a strong bass hit.").defaultValue(0.45).range(0, 1).sliderRange(0, 1).build());
    public final Setting<Double> bassSensitivity = sgVisual.add(new DoubleSetting.Builder()
        .name("bass-sensitivity").description("Sensitivity to low-frequency audio energy.").defaultValue(1.5).range(0.1, 4).sliderRange(0.1, 4).build());
    public final Setting<Double> bassSmoothing = sgVisual.add(new DoubleSetting.Builder()
        .name("bass-smoothing").description("Seconds for bass effects to fade away. Higher values feel softer.").defaultValue(0.45).range(0.08, 1.5).sliderRange(0.08, 1.5).build());
    public final Setting<Boolean> bassSky = sgVisual.add(new BoolSetting.Builder()
        .name("bass-affects-sky").description("React in the accent sky and its matching fog.").defaultValue(true).build());
    public final Setting<Boolean> bassLighting = sgVisual.add(new BoolSetting.Builder()
        .name("bass-affects-lighting").description("React in daylight on blocks and entities.").defaultValue(true).build());
    public final Setting<Boolean> bassGround = sgVisual.add(new BoolSetting.Builder()
        .name("bass-affects-ground").description("React in nearby colored ground light.").defaultValue(true).build());
    public final Setting<Boolean> bassStars = sgVisual.add(new BoolSetting.Builder()
        .name("bass-affects-stars").description("Gently brighten stars and motes with bass.").defaultValue(true).build());

    public final Setting<Boolean> customFont = sgVisual.add(new BoolSetting.Builder()
        .name("custom-font")
        .description("Use a custom font.")
        .defaultValue(true)
        .build()
    );

    public final Setting<meteordevelopment.meteorclient.utils.render.EmberAppearance> appearance = sgVisual.add(
        new EnumSetting.Builder<meteordevelopment.meteorclient.utils.render.EmberAppearance>()
            .name("ember-appearance")
            .description("Choose Solid, Frosted, or Liquid Glass for Ember's menu and HUD.")
            .defaultValue(meteordevelopment.meteorclient.utils.render.EmberAppearance.LiquidGlass)
            .build()
    );

    public final Setting<Double> liquidMotion = sgVisual.add(new DoubleSetting.Builder()
        .name("liquid-glass-motion")
        .description("Liquid Glass animation speed. Zero disables decorative motion.")
        .defaultValue(1.0)
        .range(0, 2)
        .sliderRange(0, 2)
        .visible(() -> appearance.get() == meteordevelopment.meteorclient.utils.render.EmberAppearance.LiquidGlass)
        .build()
    );

    public final Setting<Double> glass = sgVisual.add(new DoubleSetting.Builder()
        .name("ember-glass")
        .description("How solid frosted panels are: low is clear liquid glass, high is opaque frost.")
        .defaultValue(0.5)
        .min(0)
        .max(1)
        .sliderRange(0, 1)
        .visible(() -> appearance.get() != meteordevelopment.meteorclient.utils.render.EmberAppearance.Solid)
        .build()
    );

    public final Setting<Double> glassBlur = sgVisual.add(new DoubleSetting.Builder()
        .name("ember-glass-blur")
        .description("How far the scene behind the glass is smeared. Low keeps it sharp.")
        .defaultValue(0.6)
        .min(0)
        .max(1)
        .sliderRange(0, 1)
        .visible(() -> appearance.get() != meteordevelopment.meteorclient.utils.render.EmberAppearance.Solid)
        .build()
    );

    public final Setting<Double> glassLiquid = sgVisual.add(new DoubleSetting.Builder()
        .name("ember-glass-liquidity")
        .description("How hard the edges bend the scene, and how strongly they split its colour.")
        .defaultValue(0.0)
        .min(0)
        .max(1)
        .sliderRange(0, 1)
        .visible(() -> appearance.get() != meteordevelopment.meteorclient.utils.render.EmberAppearance.Solid)
        .build()
    );

    public final Setting<Double> glassTint = sgVisual.add(new DoubleSetting.Builder()
        .name("ember-glass-tint")
        .description("Dark glass through to light: low is a dark pane, high is white.")
        .defaultValue(0.0)
        .min(0)
        .max(1)
        .sliderRange(0, 1)
        .visible(() -> appearance.get() != meteordevelopment.meteorclient.utils.render.EmberAppearance.Solid)
        .build()
    );

    public final Setting<Boolean> glassAccent = sgVisual.add(new BoolSetting.Builder()
        .name("ember-glass-accent")
        .description("Tint the glass edge with the accent. Off leaves only the colour it picks up from behind.")
        .defaultValue(true)
        .visible(() -> appearance.get() != meteordevelopment.meteorclient.utils.render.EmberAppearance.Solid)
        .build()
    );

    public final Setting<FontFace> font = sgVisual.add(new FontFaceSetting.Builder()
        .name("font")
        .description("Custom font to use.")
        .visible(customFont::get)
        .onChanged(Fonts::load)
        .build()
    );

    public final Setting<Double> rainbowSpeed = sgVisual.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("The global rainbow speed.")
        .defaultValue(0.5)
        .range(0, 10)
        .sliderMax(5)
        .build()
    );

    public final Setting<Boolean> titleScreenCredits = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-credits")
        .description("Show Ember credits on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> titleScreenSplashes = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-splashes")
        .description("Show Ember splash texts on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> customWindowTitle = sgVisual.add(new BoolSetting.Builder()
        .name("custom-window-title")
        .description("Show custom text in the window title.")
        .defaultValue(false)
        .onModuleActivated(setting -> mc.updateWindowTitle())
        .onChanged(value -> mc.updateWindowTitle())
        .build()
    );

    public final Setting<String> customWindowTitleText = sgVisual.add(new StringSetting.Builder()
        .name("window-title-text")
        .description("The text it displays in the window title.")
        .visible(customWindowTitle::get)
        .defaultValue("Minecraft {mc_version} - {ember.name} {ember.version}")
        .onChanged(value -> mc.updateWindowTitle())
        .build()
    );

    public final Setting<SettingColor> friendColor = sgVisual.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("The color used to show friends.")
        .defaultValue(new SettingColor(0, 255, 180))
        .build()
    );

    public final Setting<Boolean> syncListSettingWidths = sgVisual.add(new BoolSetting.Builder()
        .name("sync-list-setting-widths")
        .description("Prevents the list setting screens from moving around as you add & remove elements.")
        .defaultValue(false)
        .build()
    );

    public final Setting<ButtonPosition> accountButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("accounts-button")
        .description("Controls the position and visibility of the accounts button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showAccountStatus = sgVisual.add(new BoolSetting.Builder()
        .name("account-status")
        .description("Shows information about the current account in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    public final Setting<ButtonPosition> proxiesButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("proxies-button")
        .description("Controls the position and visibility of the proxies button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showProxiesStatus = sgVisual.add(new BoolSetting.Builder()
        .name("proxy-status")
        .description("Shows information about the current proxy in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    // Modules

    public final Setting<List<Module>> hiddenModules = sgModules.add(new ModuleListSetting.Builder()
        .name("hidden-modules")
        .description("Prevent these modules from being rendered as options in the clickgui.")
        .build()
    );

    public final Setting<Integer> moduleSearchCount = sgModules.add(new IntSetting.Builder()
        .name("module-search-count")
        .description("Amount of modules and settings to be shown in the module search bar.")
        .defaultValue(8)
        .min(1).sliderMax(12)
        .build()
    );

    public final Setting<Boolean> moduleAliases = sgModules.add(new BoolSetting.Builder()
        .name("search-module-aliases")
        .description("Whether or not module aliases will be used in the module search bar.")
        .defaultValue(true)
        .build()
    );

    // Chat

    public final Setting<String> prefix = sgChat.add(new StringSetting.Builder()
        .name("prefix")
        .description("Prefix.")
        .defaultValue(".")
        .build()
    );

    public final Setting<Boolean> chatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Sends chat feedback when Ember performs certain actions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> deleteChatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("delete-chat-feedback")
        .description("Delete previous matching chat feedback to keep chat clear.")
        .visible(chatFeedback::get)
        .defaultValue(true)
        .build()
    );

    // Misc

    public final Setting<Integer> rotationHoldTicks = sgMisc.add(new IntSetting.Builder()
        .name("rotation-hold")
        .description("Hold long to hold server side rotation when not sending any packets.")
        .defaultValue(4)
        .build()
    );

    public final Setting<Boolean> useTeamColor = sgMisc.add(new BoolSetting.Builder()
        .name("use-team-color")
        .description("Uses player's team color for rendering things like esp and tracers.")
        .defaultValue(true)
        .build()
    );

    public List<String> dontShowAgainPrompts = new ArrayList<>();

    public Config() {
        super("config");
    }

    public static Config get() {
        return Systems.get(Config.class);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        tag.putString("version", MeteorClient.VERSION.toString());
        tag.put("settings", settings.toTag());
        tag.put("dontShowAgainPrompts", listToTag(dontShowAgainPrompts));

        return tag;
    }

    @Override
    public Config fromTag(NbtCompound tag) {
        if (tag.contains("settings")) settings.fromTag(tag.getCompoundOrEmpty("settings"));
        if (tag.contains("dontShowAgainPrompts")) dontShowAgainPrompts = listFromTag(tag, "dontShowAgainPrompts");

        return this;
    }

    private NbtList listToTag(List<String> list) {
        NbtList nbt = new NbtList();
        for (String item : list) nbt.add(NbtString.of(item));
        return nbt;
    }

    private List<String> listFromTag(NbtCompound tag, String key) {
        List<String> list = new ArrayList<>();
        for (NbtElement item : tag.getListOrEmpty(key)) list.add(item.asString().orElse(""));
        return list;
    }

    public enum ButtonPosition {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
        Hidden,
    }
}
