package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.utils.misc.SpotifyPlayback;
import meteordevelopment.meteorclient.utils.misc.MusicPlayback;
import meteordevelopment.meteorclient.utils.misc.MusicLyricsService;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberAppearance;
import meteordevelopment.meteorclient.utils.render.CelestialLighting;
import meteordevelopment.meteorclient.utils.render.StartMenuLayout;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.*;

/** A single, categorized settings surface with clipped content and consistent controls. */
public class EmberClientSettingsScreen extends WidgetScreen {
    public enum Page {
        Appearance("Appearance", "Surfaces, accent colors and glass"),
        StartMenu("Start menu", "Wallpaper, clock and menu style"),
        Atmosphere("Atmosphere", "Menu ambience, sky and horizon glow"),
        Stars("Stars & meteors", "Stars, motes, constellations and shooting stars"),
        Lighting("World lighting", "Daylight, foliage color and ground glow"),
        Weather("Weather", "Rain alerts, local weather, wind and fog"),
        Effects("Effects", "Movement, water wakes, placements and block breaks"),
        Scene("Scene visuals", "Bloom, floating geometry and curved light trails"),
        Players("Player cosmetics", "Auras, bright halos and orbiting ribbons"),
        Bass("Bass reaction", "Spotify bass and visual reactions"),
        Typography("Typography", "Choose a readable font for Ember"),
        Widgets("HUD widgets", "Choose what appears in your game"),
        Music("Music & lyrics", "Players, vocals and synchronization");
        final String title, subtitle;
        Page(String title, String subtitle) { this.title = title; this.subtitle = subtitle; }
    }
    private record Hit(double x, double y, double w, double h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private static final double NAV = 164, ROW = 48;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> animations = new HashMap<>();
    private final Map<Setting<String>, Double> fieldPositions = new HashMap<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private final GuiRenderer renderer = new GuiRenderer();
    private Page page;
    private double px, py, pw, ph, mx, my, contentX, contentY, contentW, viewportH;
    private double scroll, scrollTarget, contentHeight, pageFade = 1;
    private double navScroll, navScrollTarget, navTop, navViewportH;
    private double lastNavViewportH = -1;
    private String dragging;
    private Setting<String> editing;
    private String editBuffer = "";
    private boolean selectAll, confirmReset;
    private double openFade;

    public EmberClientSettingsScreen(GuiTheme theme) { this(theme, Page.Appearance); }
    public EmberClientSettingsScreen(GuiTheme theme, Page page) { super(theme, "Ember settings"); this.page = page; }
    @Override public void initWidgets() { clear(); }
    @Override public void mouseMoved(double x, double y) {
        mx = x * mc.getWindow().getScaleFactor(); my = y * mc.getWindow().getScaleFactor();
        super.mouseMoved(x, y);
    }
    private void selectPage(Page next) {
        if (page == next) return;
        commitField(); dragging = null; confirmReset = false;
        page = next; scroll = scrollTarget = contentHeight = 0; pageFade = 0;
        revealCategory();
    }

    private void revealCategory() {
        double top = page.ordinal() * 40;
        if (top < navScrollTarget) navScrollTarget = top;
        if (top + 36 > navScrollTarget + navViewportH) navScrollTarget = top + 36 - navViewportH;
    }

    @Override protected void onRenderBefore(DrawContext graphics, float delta) {
        double dt = clock.tick();
        openFade = Math.min(1, openFade + dt / 0.18); pageFade = Math.min(1, pageFade + dt / 0.16);
        pw = Math.min(760, getWindowWidth() - 32); ph = Math.min(600, getWindowHeight() - 32);
        px = (getWindowWidth() - pw) / 2.0; py = (getWindowHeight() - ph) / 2.0;
        contentX = px + NAV + 24; contentY = py + 104; contentW = pw - NAV - 48; viewportH = ph - 132;
        navTop = py + 94; navViewportH = Math.max(1, ph - 156);
        if (lastNavViewportH != navViewportH) { revealCategory(); lastNavViewportH = navViewportH; }
        navScrollTarget = Math.clamp(navScrollTarget, 0, Math.max(0, Page.values().length * 40 - navViewportH));
        navScroll = EmberAnim.approach(navScroll, navScrollTarget, dt, .065);
        scrollTarget = Math.clamp(scrollTarget, 0, Math.max(0, contentHeight - viewportH));
        scroll = EmberAnim.approach(scroll, scrollTarget, dt, 0.065);
        hits.clear(); fieldPositions.clear();
        meteordevelopment.meteorclient.utils.render.EmberAtmosphere.background(graphics, theme, getWindowWidth(), getWindowHeight(), mx, my, openFade, 145);
        GuiRenderer r = renderer; r.theme = theme; r.begin(graphics);
        EmberUI.shadow(r, px, py, pw, ph, (float) openFade);
        EmberUI.rim(r, px, py, pw, ph, 14, (float) openFade);
        r.roundedRect(px, py, pw, ph, 14, EmberUI.bg());
        EmberUI.gloss(r, px, py, pw, ph, 14, (float) openFade);
        r.roundedRect(px + 9, py + 9, NAV - 10, ph - 18, 9, EmberUI.raised());
        label(r, "EMBER", px + 24, py + 26, 1.04, EmberUI.TEXT, NAV - 40);
        label(r, "CLIENT SETTINGS", px + 24, py + 51, 0.58, EmberUI.TEXT_DIM, NAV - 40);
        r.scissorStart(px + 16, navTop, NAV - 24, navViewportH);
        double ny = navTop - navScroll;
        for (Page option : Page.values()) {
            boolean selected = page == option;
            double hover = anim("nav-" + option, selected || hovered(px + 17, ny, NAV - 26, 36) ? 1 : 0, dt);
            if (hover > 0.01) r.roundedRect(px + 17, ny, NAV - 26, 36, 7, accent((int) ((selected ? 44 : 20) * hover)));
            if (selected) r.roundedRect(px + 17, ny + 10, 3, 16, 1.5, EmberPalette.accent());
            label(r, option.title, px + 29, ny + 9, 0.78, selected ? EmberUI.TEXT : EmberUI.TEXT_DIM, NAV - 45);
            if (ny >= navTop && ny + 36 <= navTop + navViewportH)
                hits.add(new Hit(px + 17, ny, NAV - 26, 36, () -> selectPage(option)));
            ny += 40;
        }
        r.scissorEnd();
        if (Page.values().length * 40 > navViewportH) {
            double thumb = Math.max(18, navViewportH * navViewportH / (Page.values().length * 40));
            r.roundedRect(px + NAV - 9, navTop + (navViewportH - thumb) * navScroll / Math.max(1, Page.values().length * 40 - navViewportH), 2, thumb, 1, accent(100));
        }
        label(r, "Changes save automatically", px + 24, py + ph - 45, 0.53, EmberUI.TEXT_DIM, NAV - 36);
        label(r, page.title, contentX, py + 24, 1.2, EmberUI.TEXT, contentW - 30);
        label(r, page.subtitle, contentX, py + 58, 0.73, EmberUI.TEXT_DIM, contentW);
        r.quad(contentX, py + 88, contentW, 1, EmberUI.DIVIDER);
        action(r, px + pw - 45, py + 18, 28, 28, "X", this::close, false, dt);
        int firstContentHit = hits.size();
        r.scissorStart(contentX, contentY, contentW, viewportH);
        double start = contentY - scroll + (1 - pageFade) * 6;
        double end = switch (page) {
            case Appearance -> appearance(r, start, dt);
            case StartMenu -> startMenu(r, start, dt);
            case Atmosphere -> atmosphere(r, start, dt);
            case Stars -> stars(r, start, dt);
            case Lighting -> lighting(r, start, dt);
            case Weather -> weather(r, start, dt);
            case Effects -> effects(r, start, dt);
            case Scene -> scene(r, start, dt);
            case Players -> players(r, start, dt);
            case Bass -> bass(r, start, dt);
            case Typography -> typography(r, start, dt);
            case Widgets -> widgets(r, start, dt);
            case Music -> music(r, start, dt);
        };
        contentHeight = end - start;
        r.scissorEnd();
        for (int i = hits.size() - 1; i >= firstContentHit; i--) {
            Hit hit = hits.get(i);
            if (hit.y < contentY || hit.y + hit.h > contentY + viewportH) hits.remove(i);
        }
        if (contentHeight > viewportH) {
            double thumb = Math.max(28, viewportH * viewportH / contentHeight);
            r.roundedRect(px + pw - 13, contentY + (viewportH - thumb) * scroll / Math.max(1, contentHeight - viewportH), 3, thumb, 1.5, accent(145));
        }
        r.end();
    }

    private double appearance(GuiRenderer r, double y, double dt) {
        y = section(r, y, "SURFACE STYLE", "Choose the material used across menus and HUD.");
        EmberAppearance[] options = EmberAppearance.values();
        double gap = 8, cardW = (contentW - gap * (options.length - 1)) / options.length;
        for (int i = 0; i < options.length; i++) {
            EmberAppearance option = options[i]; double x = contentX + i * (cardW + gap);
            boolean selected = option == EmberAppearance.current();
            r.roundedRect(x, y, cardW, 84, 9, selected ? accent(55) : EmberUI.WELL);
            if (selected) r.roundedRect(x + 12, y + 11, cardW - 24, 3, 1.5, EmberPalette.accent());
            label(r, option.toString(), x + 12, y + 28, 0.83, EmberUI.TEXT, cardW - 20);
            label(r, switch (option) { case Solid -> "Clean & opaque"; case Frosted -> "Soft & blurred"; case LiquidGlass -> "Refractive glass"; }, x + 12, y + 55, 0.58, EmberUI.TEXT_DIM, cardW - 20);
            hits.add(new Hit(x, y, cardW, 84, () -> Config.get().appearance.set(option)));
        }
        y += 104;
        action(r, contentX, y, contentW, 34, "Use Liquid Glass defaults", () -> {
            var c = Config.get();
            c.appearance.reset(); c.glass.reset(); c.glassBlur.reset(); c.glassLiquid.reset(); c.glassTint.reset(); c.glassAccent.reset();
        }, false, dt);
        y += 46;
        y = section(r, y, "ACCENT COLOR", "Used for selections, switches and lyric highlights.");
        int count = EmberPalette.NAMES.length;
        double sw = Math.min(40, (contentW - 8 * (count - 1)) / count);
        for (int i = 0; i < count; i++) {
            double x = contentX + i * (sw + 8); Color c = EmberPalette.swatch(i);
            if (!Config.get().songAccent.get() && EmberPalette.selected() == i) r.roundedRect(x - 2, y - 2, sw + 4, 34, 8, EmberUI.TEXT);
            r.roundedRect(x, y, sw, 30, 6, c);
            int index = i; hits.add(new Hit(x, y, sw, 30, () -> EmberPalette.select(index)));
        }
        y += 52;
        y = toggle(r, y, "Match song artwork", "Use the cover's dominant color across the whole client.", Config.get().songAccent.get(),
            () -> Config.get().songAccent.set(!Config.get().songAccent.get()), dt);
        y += 12;
        y += 12;
        if (EmberAppearance.frosted()) {
            y = section(r, y, "GLASS DETAILS", "Fine-tune the surface against your game background.");
            Config c = Config.get();
            y = slider(r, y, "Opacity", c.glass.get(), percent(c.glass.get()), c.glass::set, dt);
            y = slider(r, y, "Blur", c.glassBlur.get(), percent(c.glassBlur.get()), c.glassBlur::set, dt);
            y = slider(r, y, "Refraction", c.glassLiquid.get(), percent(c.glassLiquid.get()), c.glassLiquid::set, dt);
            y = slider(r, y, "Lightness", c.glassTint.get(), percent(c.glassTint.get()), c.glassTint::set, dt);
            y = toggle(r, y, "Accent tint", "Blend your accent color into the glass.", c.glassAccent.get(), () -> c.glassAccent.set(!c.glassAccent.get()), dt);
            if (EmberAppearance.liquid()) y = slider(r, y, "Animation speed", c.liquidMotion.get() / 2, percent(c.liquidMotion.get()), v -> c.liquidMotion.set(v * 2), dt);
        }
        return y;
    }

    private double scene(GuiRenderer r, double y, double dt) {
        var c = Config.get();
        y = section(r, y, "BLOOM", "Soft light around bright world details. The HUD stays sharp.");
        y = toggle(r, y, "Bloom", "Glow from the world's brightest colors.", c.bloom.get(), () -> c.bloom.set(!c.bloom.get()), dt);
        if (c.bloom.get()) {
            y = slider(r, y, "Bloom strength", c.bloomStrength.get(), percent(c.bloomStrength.get()), c.bloomStrength::set, dt);
            y = slider(r, y, "Brightness threshold", (c.bloomThreshold.get() - .2) / .8, percent(c.bloomThreshold.get()), v -> c.bloomThreshold.set(.2 + v * .8), dt);
            y = slider(r, y, "Glow radius", c.bloomRadius.get(), percent(c.bloomRadius.get()), c.bloomRadius::set, dt);
        }
        y = section(r, y, "FLOATING GEOMETRY", "Rotating cubes, crystals and wireframe shapes in the world.");
        y = toggle(r, y, "Geometric particles", "Shapes fade smoothly at the edge of their volume.", c.geometricParticles.get(), () -> c.geometricParticles.set(!c.geometricParticles.get()), dt);
        y = slider(r, y, "Geometry density", c.geometricDensity.get(), percent(c.geometricDensity.get()), c.geometricDensity::set, dt);
        y = slider(r, y, "Geometry size", (c.geometricSize.get() - .1) / 1.4, String.format("%.2f", c.geometricSize.get()), v -> c.geometricSize.set(.1 + v * 1.4), dt);
        y = section(r, y, "LIGHT TRAILS", "Curved comets with a bright head and fading tail.");
        y = toggle(r, y, "Curved trails", "Ambient light streaks drift between the shapes.", c.curvedTrails.get(), () -> c.curvedTrails.set(!c.curvedTrails.get()), dt);
        y = slider(r, y, "Trail density", c.curvedTrailDensity.get(), percent(c.curvedTrailDensity.get()), c.curvedTrailDensity::set, dt);
        y = slider(r, y, "Particle brightness", c.sceneEffectBrightness.get(), percent(c.sceneEffectBrightness.get()), c.sceneEffectBrightness::set, dt);
        y = slider(r, y, "Animation speed", c.sceneEffectSpeed.get() / 2, percent(c.sceneEffectSpeed.get()), v -> c.sceneEffectSpeed.set(v * 2), dt);
        return y;
    }

    private double players(GuiRenderer r, double y, double dt) {
        var c = Config.get();
        y = section(r, y, "PLAYER COSMETICS", "Local visual effects that follow players and respect walls.");
        y = toggle(r, y, "Show on yourself", "Your cosmetics appear in third person.", c.cosmeticsSelf.get(), () -> c.cosmeticsSelf.set(!c.cosmeticsSelf.get()), dt);
        y = toggle(r, y, "Show on other players", "Decorate nearby visible players on your screen.", c.cosmeticsOthers.get(), () -> c.cosmeticsOthers.set(!c.cosmeticsOthers.get()), dt);
        y = slider(r, y, "Viewing distance", (c.cosmeticRange.get() - 8) / 56, String.format("%.0f blocks", c.cosmeticRange.get()), v -> c.cosmeticRange.set(8 + v * 56), dt);
        y = section(r, y, "STARLIGHT AURA", "Orbiting sparks and gently rising wisps.");
        y = toggle(r, y, "Player auras", "A soft animated aura around the player's body.", c.playerAuras.get(), () -> c.playerAuras.set(!c.playerAuras.get()), dt);
        y = slider(r, y, "Aura brightness", c.auraStrength.get(), percent(c.auraStrength.get()), c.auraStrength::set, dt);
        y = section(r, y, "HALOS & RIBBONS", "Bright head rings with accent-colored spirals around the body.");
        y = toggle(r, y, "Bright halos", "A floating luminous ring above each player's head.", c.playerHalos.get(), () -> c.playerHalos.set(!c.playerHalos.get()), dt);
        y = slider(r, y, "Halo radius", (c.haloSize.get() - .2) / .7, String.format("%.2f blocks", c.haloSize.get()), v -> c.haloSize.set(.2 + v * .7), dt);
        y = toggle(r, y, "Swirling rings & ribbons", "Animated orbiting rings and two rising ribbons.", c.playerRibbons.get(), () -> c.playerRibbons.set(!c.playerRibbons.get()), dt);
        y = slider(r, y, "Ribbon brightness", c.ribbonStrength.get(), percent(c.ribbonStrength.get()), c.ribbonStrength::set, dt);
        return y;
    }

    private double startMenu(GuiRenderer r, double y, double dt) {
        y = section(r, y, "START MENU", "A clock-first lock screen, or the classic Minecraft menu.");
        double menuW = contentW / 2;
        for (var style : StartMenuLayout.Style.values()) action(r, contentX + style.ordinal() * menuW, y, menuW - 5, 34,
            style.toString(), () -> Config.get().startMenu.set(style), Config.get().startMenu.get() == style, dt);
        y += 46;
        for (var wallpaper : StartMenuLayout.Wallpaper.values()) action(r, contentX + wallpaper.ordinal() * menuW, y, menuW - 5, 34,
            wallpaper.name() + " wallpaper", () -> Config.get().startWallpaper.set(wallpaper), Config.get().startWallpaper.get() == wallpaper, dt);
        y += 46;
        y = toggle(r, y, "24-hour menu clock", "Use a 24-hour clock on the startup screen.", Config.get().startClock24.get(), () -> Config.get().startClock24.set(!Config.get().startClock24.get()), dt);
        return y;
    }

    private double atmosphere(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "ATMOSPHERE", "Celestial light and stars follow your accent or song artwork.");
        y = toggle(r, y, "Atmospheric ambience", "A subtle aurora and drifting starlight behind the menu.", ambience.ambience.get(), () -> ambience.ambience.set(!ambience.ambience.get()), dt);
        y = slider(r, y, "Intensity", ambience.ambienceIntensity.get(), percent(ambience.ambienceIntensity.get()), ambience.ambienceIntensity::set, dt);
        y = slider(r, y, "Star density", ambience.ambienceDensity.get(), percent(ambience.ambienceDensity.get()), ambience.ambienceDensity::set, dt);
        y = slider(r, y, "Drift speed", ambience.ambienceSpeed.get() / 2, percent(ambience.ambienceSpeed.get()), v -> ambience.ambienceSpeed.set(v * 2), dt);
        y = toggle(r, y, "Screen ambience while playing", "Optional faint screen overlay. The 3D atmosphere below works independently.", ambience.ambienceHud.get(), () -> ambience.ambienceHud.set(!ambience.ambienceHud.get()), dt);
        y = section(r, y, "WORLD ATMOSPHERE", "A 3D sky, starlight and gentle haze in your current accent.");
        y = toggle(r, y, "World atmosphere", "Show the effects in the world, including when the menu is closed.", ambience.worldAtmosphere.get(), () -> ambience.worldAtmosphere.set(!ambience.worldAtmosphere.get()), dt);
        y = toggle(r, y, "Accent skybox", "A procedural sky that reacts to your accent and song artwork.", ambience.accentSky.get(), () -> ambience.accentSky.set(!ambience.accentSky.get()), dt);
        label(r, "Sky style", contentX + 2, y + 10, 0.72, EmberUI.TEXT, contentW / 3);
        double skyW = contentW * 0.66 / Config.CelestialSky.values().length;
        for (var style : Config.CelestialSky.values()) action(r, contentX + contentW * 0.34 + skyW * style.ordinal(), y, skyW - 5, 34,
            style.toString(), () -> ambience.celestialSky.set(style), ambience.celestialSky.get() == style, dt);
        y += 46;
        y = slider(r, y, "Sky intensity", ambience.skyIntensity.get(), percent(ambience.skyIntensity.get()), ambience.skyIntensity::set, dt);
        y = toggle(r, y, "Horizon glow", "A soft illuminated band behind distant mountains.", ambience.horizonGlow.get(), () -> ambience.horizonGlow.set(!ambience.horizonGlow.get()), dt);
        if (ambience.horizonGlow.get()) {
            y = slider(r, y, "Horizon brightness", ambience.horizonStrength.get(), percent(ambience.horizonStrength.get()), ambience.horizonStrength::set, dt);
            y = slider(r, y, "Horizon band width", (ambience.horizonWidth.get() - .025) / .275, String.format("%.0f°", Math.toDegrees(Math.atan(ambience.horizonWidth.get()))), v -> ambience.horizonWidth.set(.025 + v * .275), dt);
            y = toggle(r, y, "Slow Spotify pulses", "Enable React to Spotify in Bass reaction. Smooth pulses only affect the horizon band.", ambience.horizonBass.get(), () -> ambience.horizonBass.set(!ambience.horizonBass.get()), dt);
            y = slider(r, y, "Horizon pulse strength", ambience.horizonPulse.get(), percent(ambience.horizonPulse.get()), ambience.horizonPulse::set, dt);
        }
        return y;
    }

    private double stars(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "STARS & MOTES", "Control distant starlight and nearby floating particles separately.");
        y = toggle(r, y, "Sky stars", "Distant stars in the world sky.", ambience.worldStars.get(), () -> ambience.worldStars.set(!ambience.worldStars.get()), dt);
        y = toggle(r, y, "Floating motes", "Nearby drifting starlight with depth and terrain occlusion.", ambience.worldMotes.get(), () -> ambience.worldMotes.set(!ambience.worldMotes.get()), dt);
        y = slider(r, y, "Star brightness", ambience.starBrightness.get() / 2, percent(ambience.starBrightness.get()), v -> ambience.starBrightness.set(v * 2), dt);
        y = toggle(r, y, "Constellations", "Faint lines connect selected sky stars and slowly fade in and out.", ambience.constellations.get(), () -> ambience.constellations.set(!ambience.constellations.get()), dt);
        if (ambience.constellations.get()) {
            y = slider(r, y, "Constellation brightness", ambience.constellationStrength.get(), percent(ambience.constellationStrength.get()), ambience.constellationStrength::set, dt);
            y = slider(r, y, "Constellation groups", (ambience.constellationCount.get() - 1) / 7.0, ambience.constellationCount.get().toString(), v -> ambience.constellationCount.set(1 + (int) Math.round(v * 7)), dt);
        }
        y = toggle(r, y, "Shooting stars", "Occasional glowing streaks across the sky, behind terrain.", ambience.shootingStars.get(), () -> ambience.shootingStars.set(!ambience.shootingStars.get()), dt);
        if (ambience.shootingStars.get()) {
            y = slider(r, y, "Meteor frequency", (ambience.meteorFrequency.get() - 1) / 19, String.format("%.0f / min", ambience.meteorFrequency.get()), v -> ambience.meteorFrequency.set(1 + v * 19), dt);
            y = slider(r, y, "Meteor brightness", ambience.meteorBrightness.get(), percent(ambience.meteorBrightness.get()), ambience.meteorBrightness::set, dt);
            y = slider(r, y, "Meteor trail length", (ambience.meteorTrail.get() - .1) / .9, percent(ambience.meteorTrail.get()), v -> ambience.meteorTrail.set(.1 + v * .9), dt);
        }
        return y;
    }

    private double lighting(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "WORLD LIGHTING", "Balanced keeps terrain readable; Cinematic deepens the shadows.");
        double presetW = contentW / 3;
        action(r, contentX, y, presetW - 5, 34, "Vanilla", () -> lightingPreset(0), false, dt);
        action(r, contentX + presetW, y, presetW - 5, 34, "Balanced", () -> lightingPreset(1), false, dt);
        action(r, contentX + presetW * 2, y, presetW - 5, 34, "Cinematic", () -> lightingPreset(2), false, dt);
        y += 46;
        y = toggle(r, y, "Match world lighting", "Accent-colored daylight on blocks and entities, including during daytime.", ambience.worldLighting.get(), () -> ambience.worldLighting.set(!ambience.worldLighting.get()), dt);
        y = slider(r, y, "Vanilla light mix", ambience.vanillaLightMix.get(), percent(ambience.vanillaLightMix.get()), ambience.vanillaLightMix::set, dt);
        y = slider(r, y, "Daylight brightness", (ambience.daylightBrightness.get() - 0.2) / 1.4, percent(ambience.daylightBrightness.get()), v -> ambience.daylightBrightness.set(0.2 + v * 1.4), dt);
        y = slider(r, y, "World darkness", ambience.worldDarkness.get(), percent(ambience.worldDarkness.get()), ambience.worldDarkness::set, dt);
        y = slider(r, y, "Light color strength", ambience.worldLightTint.get(), percent(ambience.worldLightTint.get()), ambience.worldLightTint::set, dt);
        y = slider(r, y, "Foliage color balance", ambience.foliageBalance.get(), percent(ambience.foliageBalance.get()), ambience.foliageBalance::set, dt);
        y = section(r, y, "GROUND LIGHT", "Accent illumination on nearby terrain.");
        y = toggle(r, y, "Ground lighting", "Soft light on nearby ground, in the current accent color.", ambience.groundLight.get(), () -> ambience.groundLight.set(!ambience.groundLight.get()), dt);
        y = slider(r, y, "Light brightness", ambience.groundLightIntensity.get(), percent(ambience.groundLightIntensity.get()), ambience.groundLightIntensity::set, dt);
        y = slider(r, y, "Light radius", (ambience.groundLightRadius.get() - 1.5) / 5.5, String.format("%.1f blocks", ambience.groundLightRadius.get()), v -> ambience.groundLightRadius.set(1.5 + v * 5.5), dt);
        y = slider(r, y, "Light shimmer", ambience.lightShimmer.get(), percent(ambience.lightShimmer.get()), ambience.lightShimmer::set, dt);
        return y;
    }

    private double weather(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "RAIN STATUS", "Follows server weather or your selected local rain effect.");
        y = toggle(r, y, "Rain notifications", "Notify when rain starts, eases off, and stops.", ambience.rainNotifications.get(), () -> ambience.rainNotifications.set(!ambience.rainNotifications.get()), dt);
        if (ambience.rainNotifications.get())
            y = toggle(r, y, "Rain ending notification", "Notify as the rain starts fading out. No countdown prediction.", ambience.rainEndingNotification.get(), () -> ambience.rainEndingNotification.set(!ambience.rainEndingNotification.get()), dt);
        y = toggle(r, y, "Rain status icon", "A small status-bar badge, visible only while rain is active or fading.", ambience.rainStatusIcon.get(), () -> ambience.rainStatusIcon.set(!ambience.rainStatusIcon.get()), dt);
        y = section(r, y, "CUSTOM WEATHER", "Local visual weather, independent of server weather and biome.");
        y = toggle(r, y, "Custom weather", "Wind, roof shelter, splashes and surface impacts. No permanent world changes.", ambience.customWeather.get(), () -> ambience.customWeather.set(!ambience.customWeather.get()), dt);
        if (ambience.customWeather.get()) {
            double weatherW = contentW / 3;
            for (var kind : meteordevelopment.meteorclient.utils.render.WeatherSimulation.Kind.values()) {
                String name = switch (kind) { case Snow -> "Glowing snow"; case Leaves -> "Autumn leaves"; default -> kind.name(); };
                action(r, contentX + weatherW * (kind.ordinal() % 3), y + (kind.ordinal() / 3) * 42, weatherW - 5, 34,
                    name, () -> ambience.weatherStyle.set(kind), ambience.weatherStyle.get() == kind, dt);
            }
            y += 90;
            y = slider(r, y, "Weather density", ambience.weatherDensity.get(), percent(ambience.weatherDensity.get()), ambience.weatherDensity::set, dt);
            y = slider(r, y, "Weather radius", (ambience.weatherRadius.get() - 8) / 32, String.format("%.0f blocks", ambience.weatherRadius.get()), v -> ambience.weatherRadius.set(8 + v * 32), dt);
            y = slider(r, y, "Weather speed", (ambience.weatherSpeed.get() - .2) / 1.8, percent(ambience.weatherSpeed.get()), v -> ambience.weatherSpeed.set(.2 + v * 1.8), dt);
            y = slider(r, y, "Wind strength", ambience.weatherWind.get(), percent(ambience.weatherWind.get()), ambience.weatherWind::set, dt);
            y = slider(r, y, "Wind heading", ambience.weatherWindDirection.get() / 360, String.format("%.0f°", ambience.weatherWindDirection.get()), v -> ambience.weatherWindDirection.set(v * 360), dt);
            y = toggle(r, y, "Surface impacts", "Rain splashes and water ripples; snow and petals briefly settle.", ambience.weatherImpacts.get(), () -> ambience.weatherImpacts.set(!ambience.weatherImpacts.get()), dt);
            y = toggle(r, y, "Match weather to accent", "Off keeps natural rain, snow, autumn and petal colors.", ambience.weatherAccent.get(), () -> ambience.weatherAccent.set(!ambience.weatherAccent.get()), dt);
            if (ambience.weatherStyle.get() == meteordevelopment.meteorclient.utils.render.WeatherSimulation.Kind.Rain)
                y = toggle(r, y, "Rain sound", "Quieter under nearby roofs and silent deep underground.", ambience.weatherSound.get(), () -> ambience.weatherSound.set(!ambience.weatherSound.get()), dt);
        }
        y = section(r, y, "FOG & HAZE", "Set nearby clarity and distant haze independently of render distance.");
        y = toggle(r, y, "Accent fog", "Tint distant haze while keeping nearby blocks readable.", ambience.accentFog.get(), () -> ambience.accentFog.set(!ambience.accentFog.get()), dt);
        y = toggle(r, y, "Depth-based fog", "Keep haze independent of other mods' fog-distance overrides.", ambience.depthFog.get(), () -> ambience.depthFog.set(!ambience.depthFog.get()), dt);
        y = slider(r, y, "Fog density", ambience.fogAmount.get(), percent(ambience.fogAmount.get()), ambience.fogAmount::set, dt);
        y = slider(r, y, "Fog start", ambience.fogStart.get() / 512, String.format("%.0f blocks", ambience.fogStart.get()), v -> {
            ambience.fogStart.set(v * 512);
            if (ambience.fogEnd.get() < ambience.fogStart.get() + 8) ambience.fogEnd.set(ambience.fogStart.get() + 8);
        }, dt);
        y = slider(r, y, "Fog end", (ambience.fogEnd.get() - 16) / 1008, String.format("%.0f blocks", ambience.fogEnd.get()), v -> ambience.fogEnd.set(Math.max(ambience.fogStart.get() + 8, 16 + v * 1008)), dt);
        return y;
    }

    private double effects(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "MOVEMENT & INTERACTIONS", "Small accent effects that respond to your actions.");
        y = toggle(r, y, "Movement effects", "Footstep sparks, surface water wakes, and nearby leaf/petal movement.", ambience.movementEffects.get(), () -> ambience.movementEffects.set(!ambience.movementEffects.get()), dt);
        if (ambience.movementEffects.get()) {
            y = slider(r, y, "Movement intensity", ambience.movementAmount.get(), percent(ambience.movementAmount.get()), ambience.movementAmount::set, dt);
            y = slider(r, y, "Movement fade time", (ambience.movementLifetime.get() - .2) / 1.8, String.format("%.2fs", ambience.movementLifetime.get()), v -> ambience.movementLifetime.set(.2 + v * 1.8), dt);
            y = toggle(r, y, "Water wakes", "Small expanding ripples while moving at the water surface.", ambience.movementRipples.get(), () -> ambience.movementRipples.set(!ambience.movementRipples.get()), dt);
        }
        y = toggle(r, y, "Block interaction effects", "Soft rings and sparks on successful placements and breaks.", ambience.blockEffects.get(), () -> ambience.blockEffects.set(!ambience.blockEffects.get()), dt);
        if (ambience.blockEffects.get()) {
            y = toggle(r, y, "Placement effects", "Show an accent pulse when a block is placed.", ambience.blockPlaceEffects.get(), () -> ambience.blockPlaceEffects.set(!ambience.blockPlaceEffects.get()), dt);
            y = toggle(r, y, "Break effects", "Show sparks when a block is broken.", ambience.blockBreakEffects.get(), () -> ambience.blockBreakEffects.set(!ambience.blockBreakEffects.get()), dt);
            y = slider(r, y, "Block effect intensity", ambience.blockEffectAmount.get(), percent(ambience.blockEffectAmount.get()), ambience.blockEffectAmount::set, dt);
            y = slider(r, y, "Block effect size", (ambience.blockEffectSize.get() - .2) / 1.3, String.format("%.1f blocks", ambience.blockEffectSize.get()), v -> ambience.blockEffectSize.set(.2 + v * 1.3), dt);
            y = slider(r, y, "Block effect fade time", (ambience.blockEffectLifetime.get() - .2) / 1.8, String.format("%.2fs", ambience.blockEffectLifetime.get()), v -> ambience.blockEffectLifetime.set(.2 + v * 1.8), dt);
        }
        return y;
    }

    private double bass(GuiRenderer r, double y, double dt) {
        Config ambience = Config.get();
        y = section(r, y, "BASS REACTION", meteordevelopment.meteorclient.utils.misc.MusicReactive.status());
        y = toggle(r, y, "React to Spotify", "Spotify desktop audio only. Minecraft and other apps are excluded.", ambience.musicReactive.get(), () -> ambience.musicReactive.set(!ambience.musicReactive.get()), dt);
        if (ambience.musicReactive.get()) {
            double bassLevel = meteordevelopment.meteorclient.utils.misc.MusicReactive.level();
            label(r, "Bass level", contentX + 2, y + 4, 0.72, EmberUI.TEXT, contentW / 3);
            r.roundedRect(contentX + contentW * 0.34, y + 6, contentW * 0.66 - 5, 8, 4, EmberUI.WELL);
            if (bassLevel > 0.001) r.roundedRect(contentX + contentW * 0.34, y + 6, (contentW * 0.66 - 5) * bassLevel, 8, 4, EmberPalette.accent());
            y += 30;
            double modeW = contentW / 2;
            for (var mode : Config.BassMode.values()) action(r, contentX + modeW * mode.ordinal(), y, modeW - 5, 34,
                mode.toString(), () -> ambience.bassMode.set(mode), ambience.bassMode.get() == mode, dt);
            y += 46;
            y = slider(r, y, "Effect strength", ambience.bassStrength.get(), percent(ambience.bassStrength.get()), ambience.bassStrength::set, dt);
            y = slider(r, y, "Bass sensitivity", (ambience.bassSensitivity.get() - 0.1) / 3.9, String.format("%.1fx", ambience.bassSensitivity.get()), v -> ambience.bassSensitivity.set(0.1 + v * 3.9), dt);
            y = slider(r, y, "Fade smoothing", (ambience.bassSmoothing.get() - 0.08) / 1.42, String.format("%.2fs", ambience.bassSmoothing.get()), v -> ambience.bassSmoothing.set(0.08 + v * 1.42), dt);
            y = toggle(r, y, "React sky & fog", "Darken or brighten the accent sky and its matching horizon.", ambience.bassSky.get(), () -> ambience.bassSky.set(!ambience.bassSky.get()), dt);
            y = toggle(r, y, "React world lighting", "Apply bass to daylight. Requires Match world lighting.", ambience.bassLighting.get(), () -> ambience.bassLighting.set(!ambience.bassLighting.get()), dt);
            y = toggle(r, y, "React ground light", "Apply bass to colored light on nearby terrain.", ambience.bassGround.get(), () -> ambience.bassGround.set(!ambience.bassGround.get()), dt);
            y = toggle(r, y, "React starlight", "Gently pulse stars and motes on bass hits.", ambience.bassStars.get(), () -> ambience.bassStars.set(!ambience.bassStars.get()), dt);
        }
        return y;
    }

    private static void lightingPreset(int preset) {
        Config c = Config.get();
        c.worldLighting.set(preset != 0);
        c.vanillaLightMix.set(preset == 0 ? 1.0 : 0.0);
        c.worldDarkness.set(preset == 0 ? 0.0 : preset == 1 ? CelestialLighting.BALANCED_DARKNESS : 0.65);
        c.worldLightTint.set(preset == 0 ? 0.0 : preset == 1 ? CelestialLighting.BALANCED_TINT : 0.95);
        c.daylightBrightness.set(preset == 1 ? CelestialLighting.BALANCED_BRIGHTNESS : preset == 2 ? 0.9 : 1.0);
    }

    private double typography(GuiRenderer r, double y, double dt) {
        Config c = Config.get();
        y = toggle(r, y, "Custom font", "Apply your chosen font to the client interface.", c.customFont.get(), () -> c.customFont.set(!c.customFont.get()), dt);
        y += 12;
        String family = c.font.get() == null ? "Default" : c.font.get().info.family();
        y = section(r, y, "FONT LIBRARY", "Selected: " + family);
        for (FontFamily font : Fonts.FONT_FAMILIES) {
            if (y + 42 >= contentY && y < contentY + viewportH) {
                boolean selected = font.getName().equalsIgnoreCase(family);
                r.roundedRect(contentX, y, contentW, 40, 7, selected ? accent(40) : EmberUI.WELL);
                label(r, font.getName(), contentX + 12, y + 10, 0.88, EmberUI.TEXT, contentW - 95);
                if (selected) label(r, "Selected", contentX + contentW - 80, y + 13, 0.65, EmberPalette.accent(), 70);
                hits.add(new Hit(contentX, y, contentW, 40, () -> {
                    FontFace face = font.get(FontInfo.Type.Regular);
                    if (face != null) { c.font.set(face); c.customFont.set(true); }
                }));
            }
            y += 46;
        }
        return y;
    }

    private double widgets(GuiRenderer r, double y, double dt) {
        y = section(r, y, "HOTBAR", "A dark rounded bar with a quiet selected-slot highlight.");
        y = toggle(r, y, "Pill hotbar", "Use the rounded hotbar style. Off restores Minecraft's hotbar.", Config.get().pillHotbar.get(), () -> Config.get().pillHotbar.set(!Config.get().pillHotbar.get()), dt);
        y += 12;
        y = toggle(r, y, "HUD enabled", "Show your enabled widgets while playing.", Hud.get().active, () -> Hud.get().active = !Hud.get().active, dt);
        action(r, contentX, y, contentW, 36, "Open HUD layout editor", () -> mc.setScreen(new HudEditorScreen(theme)), true, dt); y += 54;
        y = section(r, y, "WIDGETS", "Switch each element on or off independently.");
        String[][] widgets = {{"Status bar", "ember-status-bar"}, {"Info pill", "ember-bubbles"}, {"Keybinds", "ember-keybinds"},
            {"Staff detector", "ember-staff"}, {"Region map", "ember-region-map"}, {"Armor", "ember-armor"},
            {"Target", "ember-target"}, {"Module list", "ember-module-list"}, {"Notifications", "ember-notifications"}, {"Music player", "spotify"}};
        for (String[] entry : widgets) {
            HudElement element = element(entry[1]);
            y = toggle(r, y, entry[0], "", element != null && element.isActive(), () -> { HudElement el = element(entry[1]); if (el != null) el.toggle(); }, dt);
        }
        y += 12;
        y = section(r, y, "LAYOUT", "Presets reposition widgets; reset restores the default HUD.");
        action(r, contentX, y, contentW, 36, "Apply compact glass layout", this::arrangeGlassHud, false, dt); y += 44;
        action(r, contentX, y, contentW, 36, confirmReset ? "Confirm reset of HUD widgets and positions" : "Reset HUD to Ember defaults", () -> {
            if (confirmReset) { Hud.get().resetToDefaultElements(); confirmReset = false; } else confirmReset = true;
        }, false, dt);
        return y + 44;
    }

    private double music(GuiRenderer r, double y, double dt) {
        Config c = Config.get();
        y = section(r, y, "PLAYBACK", "Detect music from Windows apps and compatible browser tabs.");
        y = status(r, y, "Player", MusicPlayback.status());
        MusicLyricsService.Result lyrics = MusicLyricsService.update();
        y = status(r, y, "Lyrics", !c.lyricsEnabled.get() ? "Lyric pill disabled" : lyrics.status().isEmpty() ? "Timed lyrics ready" : lyrics.status());
        y += 12;
        y = toggle(r, y, "Detect desktop music", "Spotify, YouTube Music and SoundCloud through Windows media controls.", c.desktopMusic.get(), () -> c.desktopMusic.set(!c.desktopMusic.get()), dt);
        y = section(r, y, "LYRIC SOURCES", "Spicy Lyrics first, then LRCLIB if a timed sync is unavailable.");
        y = toggle(r, y, "Spicy Lyrics", "Primary source for timed words, syllables and backing vocals.", c.spicyLyricsEnabled.get(), () -> c.spicyLyricsEnabled.set(!c.spicyLyricsEnabled.get()), dt);
        y = toggle(r, y, "LRCLIB", "Line-synced fallback. Also works alone without a key or login.", c.lyricsSearchFallback.get(), () -> c.lyricsSearchFallback.set(!c.lyricsSearchFallback.get()), dt);
        y = toggle(r, y, "Find Spotify track IDs", "Match title, artist and duration without a Spotify connection.", c.lyricsResolveSpotify.get(), () -> c.lyricsResolveSpotify.set(!c.lyricsResolveSpotify.get()), dt);
        y = section(r, y, "DISPLAY", "Compact lyrics with a fixed text size and syllable highlighting.");
        y = toggle(r, y, "Lyrics pill", "Show synced words beside the main status pill.", c.lyricsEnabled.get(), () -> c.lyricsEnabled.set(!c.lyricsEnabled.get()), dt);
        y = toggle(r, y, "Backing vocals", "Show background lyrics underneath the lead voice.", c.lyricsBackground.get(), () -> c.lyricsBackground.set(!c.lyricsBackground.get()), dt);
        y = toggle(r, y, "Romanization", "Use romanized lyrics from Spicy Lyrics when available.", c.lyricsRomanization.get(), () -> c.lyricsRomanization.set(!c.lyricsRomanization.get()), dt);
        y = toggle(r, y, "Always show lyric credits", "Otherwise, show provider names for five seconds per song or on hover.", c.lyricsCreditsAlways.get(), () -> c.lyricsCreditsAlways.set(!c.lyricsCreditsAlways.get()), dt);
        y = slider(r, y, "Maximum width", (c.lyricsMaxWidth.get() - 180) / 420.0, c.lyricsMaxWidth.get() + " px",
            v -> c.lyricsMaxWidth.set((int) Math.round((180 + 420 * v) / 10) * 10), dt);
        y = slider(r, y, "Text size", (c.lyricsTextSize.get() - 0.45) / 0.45, percent(c.lyricsTextSize.get()),
            v -> c.lyricsTextSize.set(Math.round((0.45 + 0.45 * v) * 100) / 100.0), dt);
        y = toggle(r, y, "Match song artwork", "Smoothly recolor Ember using the current album cover.", c.songAccent.get(), () -> c.songAccent.set(!c.songAccent.get()), dt);
        y = slider(r, y, "Timing offset", (c.lyricsOffset.get() + 3000) / 6000, String.format("%+.0f ms", c.lyricsOffset.get()), v -> c.lyricsOffset.set(Math.rint((v * 6000 - 3000) / 10) * 10), dt);
        label(r, "Positive advances lyrics. Negative delays them.", contentX + 2, y, 0.63, EmberUI.TEXT_DIM, contentW); y += 30;
        action(r, contentX, y, contentW, 32, "Reset timing offset", () -> c.lyricsOffset.set(0.0), false, dt); y += 52;
        y = section(r, y, "OPTIONAL SPOTIFY CONNECTION", "Exact track IDs enable Spicy Lyrics word and syllable sync.");
        y = field(r, y, "Spotify client ID", c.spotifyClientId, false);
        double half = (contentW - 8) / 2;
        action(r, contentX, y, half, 36, "Connect Spotify", SpotifyPlayback::connect, true, dt);
        action(r, contentX + half + 8, y, half, 36, "Disconnect", SpotifyPlayback::disconnect, false, dt); y += 46;
        action(r, contentX, y, contentW, 32, "Open Spotify app dashboard", () -> open("https://developer.spotify.com/dashboard"), false, dt); y += 42;
        label(r, "Redirect URI (must match your Spotify app):", contentX, y, 0.65, EmberUI.TEXT_DIM, contentW); y += 22;
        label(r, SpotifyPlayback.REDIRECT, contentX, y, 0.75, EmberUI.TEXT, contentW); y += 28;
        action(r, contentX, y, contentW, 30, "Copy redirect URI", () -> mc.keyboard.setClipboard(SpotifyPlayback.REDIRECT), false, dt); y += 50;
        y = section(r, y, "SPICY LYRICS ACCESS", "Your publishable key is needed even when track IDs are found automatically.");
        y = field(r, y, "Spicy Lyrics client key", c.spicyLyricsKey, true);
        label(r, "Enable 'No origin header' in the key's client-access settings.", contentX, y, 0.62, EmberUI.TEXT_DIM, contentW); y += 28;
        action(r, contentX, y, contentW, 34, "Open Spicy Lyrics key dashboard", () -> open("https://developers.spicylyrics.org/dashboard/applications"), false, dt); y += 54;
        y = section(r, y, "OPTIONAL SPICETIFY BRIDGE", "The Ember extension reconnects automatically when Minecraft starts.");
        y = toggle(r, y, "Automatic local bridge", "Remembered across restarts; accepts playback from Spicetify.", c.spotifyBridgeEnabled.get(),
            () -> c.spotifyBridgeEnabled.set(!c.spotifyBridgeEnabled.get()), dt);
        action(r, contentX, y, contentW, 34, "Enable local bridge", SpotifyPlayback::enableBridge, false, dt);
        return y + 42;
    }

    private double section(GuiRenderer r, double y, String title, String description) {
        label(r, title, contentX + 1, y, 0.68, EmberUI.TEXT, contentW);
        label(r, description, contentX + 1, y + 22, 0.64, EmberUI.TEXT_DIM, contentW);
        return y + 48;
    }
    private double status(GuiRenderer r, double y, String name, String status) {
        r.roundedRect(contentX, y, contentW, 48, 8, EmberUI.WELL);
        label(r, name, contentX + 12, y + 6, 0.66, EmberUI.TEXT_DIM, contentW - 24);
        label(r, status, contentX + 12, y + 24, 0.76, EmberUI.TEXT, contentW - 24);
        return y + 56;
    }
    private double toggle(GuiRenderer r, double y, String title, String description, boolean enabled, Runnable change, double dt) {
        r.roundedRect(contentX, y, contentW, ROW, 8, EmberUI.WELL);
        label(r, title, contentX + 12, description.isEmpty() ? y + 14 : y + 6, 0.84, EmberUI.TEXT, contentW - 76);
        if (!description.isEmpty()) label(r, description, contentX + 12, y + 27, 0.6, EmberUI.TEXT_DIM, contentW - 76);
        EmberUI.toggle(r, contentX + contentW - 48, y + 16, 34, 16, (float) anim("toggle-" + title, enabled ? 1 : 0, dt), 1);
        hits.add(new Hit(contentX, y, contentW, ROW, () -> { commitField(); change.run(); }));
        return y + ROW + 7;
    }
    private double slider(GuiRenderer r, double y, String title, double value, String shown, DoubleConsumer change, double dt) {
        double trackW = Math.min(210, contentW * 0.43), tx = contentX + contentW - trackW - 16, ty = y + 31;
        r.roundedRect(contentX, y, contentW, 52, 8, EmberUI.WELL);
        if (title.equals(dragging)) { value = Math.clamp((mx - tx) / trackW, 0, 1); change.accept(value); }
        label(r, title, contentX + 12, y + 16, 0.8, EmberUI.TEXT, contentW - trackW - 40);
        label(r, shown, tx, y + 4, 0.66, EmberUI.TEXT_DIM, trackW);
        r.roundedRect(tx, ty, trackW, 4, 2, new Color(61, 64, 73, 255));
        if (trackW * value > 4) r.roundedRect(tx, ty, trackW * value, 4, 2, EmberPalette.accent());
        r.roundedRect(tx + trackW * value - 5, ty - 3, 10, 10, 5, EmberUI.TEXT);
        hits.add(new Hit(tx - 7, y + 19, trackW + 14, 30, () -> { commitField(); dragging = title; }));
        return y + 59;
    }
    private double field(GuiRenderer r, double y, String title, Setting<String> setting, boolean secret) {
        fieldPositions.put(setting, y - contentY + scroll);
        label(r, title, contentX + 1, y, 0.76, EmberUI.TEXT, contentW); y += 25;
        boolean active = editing == setting;
        r.roundedRect(contentX, y, contentW, 36, 7, active ? accent(44) : EmberUI.WELL);
        String value = active ? editBuffer : setting.get();
        String shown = value.isEmpty() ? "Paste here" : secret ? "*".repeat(Math.min(24, value.length())) : value;
        if (active) shown += selectAll ? "  [selected]" : " |";
        label(r, shown, contentX + 11, y + 8, 0.78, value.isEmpty() ? EmberUI.TEXT_DIM : EmberUI.TEXT, contentW - 22);
        hits.add(new Hit(contentX, y, contentW, 36, () -> {
            if (editing != setting) { commitField(); editing = setting; editBuffer = setting.get(); selectAll = false; }
        }));
        return y + 48;
    }
    private void action(GuiRenderer r, double x, double y, double w, double h, String title, Runnable action, boolean primary, double dt) {
        double amount = anim("button-" + title, hovered(x, y, w, h) ? 1 : 0, dt);
        r.roundedRect(x, y, w, h, 7, primary ? accent((int) (85 + amount * 35)) : new Color(45, 47, 56, (int) (185 + amount * 50)));
        double pad = w < 40 ? 7 : 10;
        label(r, title, x + pad, y + (h - 17) / 2, 0.77, EmberUI.TEXT, w - 2 * pad);
        hits.add(new Hit(x, y, w, h, () -> { commitField(); dragging = null; action.run(); }));
    }

    /** Text is rendered within the same scissor as the controls it labels. */
    private void label(GuiRenderer r, String text, double x, double y, double scale, Color color, double maxW) {
        if (y + 26 < py || y > py + ph) return;
        r.post(() -> {
            var renderer = theme.textRenderer(); renderer.begin(theme.scale(scale));
            String shown = text;
            if (renderer.getWidth(shown) > maxW) {
                int low = 0, high = shown.codePointCount(0, shown.length());
                while (low < high) {
                    int mid = (low + high + 1) >>> 1;
                    String candidate = text.substring(0, text.offsetByCodePoints(0, mid)) + "\u2026";
                    if (renderer.getWidth(candidate) <= maxW) low = mid; else high = mid - 1;
                }
                shown = low == 0 ? "" : text.substring(0, text.offsetByCodePoints(0, low)) + "\u2026";
            }
            renderer.render(shown, x, y, color, false); renderer.end();
        });
    }
    private double anim(String key, double target, double dt) {
        float next = EmberAnim.approach(animations.getOrDefault(key, (float) target), (float) target, dt, 0.07);
        animations.put(key, next); return next;
    }
    private boolean hovered(double x, double y, double w, double h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private static String percent(double value) { return String.format("%.0f%%", value * 100); }
    private static Color accent(int alpha) { Color c = EmberPalette.accent(); return new Color(c.r, c.g, c.b, alpha); }
    private static void open(String url) { Util.getOperatingSystem().open(url); }
    private static HudElement element(String name) { for (HudElement el : Hud.get()) if (el.info.name.equals(name)) return el; return null; }
    private void arrangeGlassHud() {
        int w = getWindowWidth(), h = getWindowHeight();
        for (HudElement element : Hud.get()) switch (element.info.name) {
            case "ember-status-bar" -> element.setPos((int) (w * 0.12), 16);
            case "spotify" -> element.setPos(Math.max(16, w - Math.max(280, element.getWidth()) - 20), 16);
            case "ember-keybinds" -> element.setPos((int) (w * 0.18), (int) (h * 0.32));
            case "ember-target" -> element.setPos((w - element.getWidth()) / 2, (int) (h * 0.64));
            default -> { }
        }
    }
    private void commitField() {
        if (editing != null) editing.set(editBuffer.trim());
        editing = null; editBuffer = ""; selectAll = false;
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW_MOUSE_BUTTON_LEFT) return true;
        mx = click.x() * mc.getWindow().getScaleFactor(); my = click.y() * mc.getWindow().getScaleFactor();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) { hit.action.run(); return true; }
        }
        commitField(); if (!hovered(px, py, pw, ph)) close(); return true;
    }
    @Override public boolean mouseReleased(Click click) { dragging = null; return super.mouseReleased(click); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        double sx = x * mc.getWindow().getScaleFactor(), sy = y * mc.getWindow().getScaleFactor();
        if (sx >= px + 12 && sx < px + NAV && sy >= navTop && sy < navTop + navViewportH) {
            navScrollTarget = Math.clamp(navScrollTarget - vertical * 40, 0, Math.max(0, Page.values().length * 40 - navViewportH));
            return true;
        }
        if (sx >= contentX && sx < px + pw && sy >= contentY && sy < contentY + viewportH) {
            commitField(); dragging = null; scrollTarget = Math.clamp(scrollTarget - vertical * 44, 0, Math.max(0, contentHeight - viewportH));
        }
        return true;
    }
    @Override public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW_KEY_TAB && (input.modifiers() & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0) {
            int direction = (input.modifiers() & GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            selectPage(Page.values()[Math.floorMod(page.ordinal() + direction, Page.values().length)]);
            return true;
        }
        if (editing != null) {
            boolean ctrl = (input.modifiers() & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0;
            if (key == GLFW_KEY_ESCAPE) { editing = null; editBuffer = ""; selectAll = false; }
            else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) commitField();
            else if (ctrl && key == GLFW_KEY_A) selectAll = true;
            else if (ctrl && key == GLFW_KEY_V) {
                String pasted = mc.keyboard.getClipboard().replaceAll("\\s+", "");
                String next = (selectAll ? "" : editBuffer) + pasted;
                editBuffer = next.substring(0, Math.min(512, next.length())); selectAll = false;
            } else if (key == GLFW_KEY_BACKSPACE || key == GLFW_KEY_DELETE) {
                if (selectAll) editBuffer = "";
                else if (!editBuffer.isEmpty()) editBuffer = editBuffer.substring(0, editBuffer.offsetByCodePoints(editBuffer.length(), -1));
                selectAll = false;
            } else if (key == GLFW_KEY_TAB) {
                Setting<String> next = editing == Config.get().spotifyClientId ? Config.get().spicyLyricsKey : Config.get().spotifyClientId;
                commitField(); editing = next; editBuffer = next.get(); selectAll = true;
                scrollTarget = Math.max(0, fieldPositions.getOrDefault(editing, 0.0) - 24);
            }
            return true;
        }
        if (key == GLFW_KEY_ESCAPE) close();
        else if (key == GLFW_KEY_PAGE_DOWN) scrollTarget += viewportH * 0.8;
        else if (key == GLFW_KEY_PAGE_UP) scrollTarget -= viewportH * 0.8;
        else if (key == GLFW_KEY_HOME) scrollTarget = 0;
        else if (key == GLFW_KEY_END) scrollTarget = Math.max(0, contentHeight - viewportH);
        return true;
    }
    @Override public boolean charTyped(CharInput input) {
        if (editing != null && input.codepoint() >= 32 && editBuffer.length() < 512) {
            if (selectAll) editBuffer = "";
            editBuffer += new String(Character.toChars(input.codepoint())); selectAll = false;
        }
        return true;
    }
    @Override public void close() { commitField(); if (parent != null) mc.setScreen(parent); else super.close(); }
}
