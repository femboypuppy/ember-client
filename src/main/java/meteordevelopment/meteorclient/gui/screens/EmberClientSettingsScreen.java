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
import meteordevelopment.meteorclient.utils.misc.SpicyLyricsService;
import meteordevelopment.meteorclient.utils.misc.SpotifyPlayback;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberAppearance;
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
        Typography("Typography", "Choose a readable font for Ember"),
        Widgets("HUD widgets", "Choose what appears in your game"),
        Music("Spotify & lyrics", "Connections, vocals and synchronization");
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
    private Page page;
    private double px, py, pw, ph, mx, my, contentX, contentY, contentW, viewportH;
    private double scroll, scrollTarget, contentHeight, pageFade = 1;
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
    }

    @Override protected void onRenderBefore(DrawContext graphics, float delta) {
        double dt = clock.tick();
        openFade = Math.min(1, openFade + dt / 0.18); pageFade = Math.min(1, pageFade + dt / 0.16);
        pw = Math.min(760, getWindowWidth() - 32); ph = Math.min(600, getWindowHeight() - 32);
        px = (getWindowWidth() - pw) / 2.0; py = (getWindowHeight() - ph) / 2.0;
        contentX = px + NAV + 24; contentY = py + 104; contentW = pw - NAV - 48; viewportH = ph - 132;
        scrollTarget = Math.clamp(scrollTarget, 0, Math.max(0, contentHeight - viewportH));
        scroll = EmberAnim.approach(scroll, scrollTarget, dt, 0.065);
        hits.clear(); fieldPositions.clear();
        GuiRenderer r = new GuiRenderer(); r.theme = theme; r.begin(graphics);
        r.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (145 * openFade)));
        EmberUI.shadow(r, px, py, pw, ph, (float) openFade);
        EmberUI.rim(r, px, py, pw, ph, 14, (float) openFade);
        r.roundedRect(px, py, pw, ph, 14, EmberUI.bg());
        EmberUI.gloss(r, px, py, pw, ph, 14, (float) openFade);
        r.roundedRect(px + 9, py + 9, NAV - 10, ph - 18, 9, EmberUI.raised());
        label(r, "EMBER", px + 24, py + 26, 1.04, EmberUI.TEXT, NAV - 40);
        label(r, "CLIENT SETTINGS", px + 24, py + 51, 0.58, EmberUI.TEXT_DIM, NAV - 40);
        double ny = py + 100;
        for (Page option : Page.values()) {
            boolean selected = page == option;
            double hover = anim("nav-" + option, selected || hovered(px + 17, ny, NAV - 26, 42) ? 1 : 0, dt);
            if (hover > 0.01) r.roundedRect(px + 17, ny, NAV - 26, 42, 7, accent((int) ((selected ? 44 : 20) * hover)));
            if (selected) r.roundedRect(px + 17, ny + 12, 3, 18, 1.5, EmberPalette.accent());
            label(r, option.title, px + 29, ny + 12, 0.81, selected ? EmberUI.TEXT : EmberUI.TEXT_DIM, NAV - 45);
            hits.add(new Hit(px + 17, ny, NAV - 26, 42, () -> selectPage(option))); ny += 48;
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
        y = section(r, y, "PLAYBACK", "Spotify and lyrics have separate connection settings.");
        y = status(r, y, "Spotify", SpotifyPlayback.status());
        SpicyLyricsService.Result lyrics = SpicyLyricsService.update();
        y = status(r, y, "Lyrics", !c.lyricsEnabled.get() ? "Lyric pill disabled" : lyrics.status().isEmpty() ? "Timed lyrics ready" : lyrics.status());
        y += 12;
        y = section(r, y, "DISPLAY", "Compact lyrics with a fixed text size and syllable highlighting.");
        y = toggle(r, y, "Lyrics pill", "Show synced words beside the main status pill.", c.lyricsEnabled.get(), () -> c.lyricsEnabled.set(!c.lyricsEnabled.get()), dt);
        y = toggle(r, y, "Backing vocals", "Show background lyrics underneath the lead voice.", c.lyricsBackground.get(), () -> c.lyricsBackground.set(!c.lyricsBackground.get()), dt);
        y = slider(r, y, "Maximum width", (c.lyricsMaxWidth.get() - 180) / 420.0, c.lyricsMaxWidth.get() + " px",
            v -> c.lyricsMaxWidth.set((int) Math.round((180 + 420 * v) / 10) * 10), dt);
        y = slider(r, y, "Text size", (c.lyricsTextSize.get() - 0.45) / 0.45, percent(c.lyricsTextSize.get()),
            v -> c.lyricsTextSize.set(Math.round((0.45 + 0.45 * v) * 100) / 100.0), dt);
        y = toggle(r, y, "Match song artwork", "Smoothly recolor Ember using the current album cover.", c.songAccent.get(), () -> c.songAccent.set(!c.songAccent.get()), dt);
        y = slider(r, y, "Timing offset", (c.lyricsOffset.get() + 3000) / 6000, String.format("%+.0f ms", c.lyricsOffset.get()), v -> c.lyricsOffset.set(Math.rint((v * 6000 - 3000) / 10) * 10), dt);
        label(r, "Positive advances lyrics. Negative delays them.", contentX + 2, y, 0.63, EmberUI.TEXT_DIM, contentW); y += 30;
        action(r, contentX, y, contentW, 32, "Reset timing offset", () -> c.lyricsOffset.set(0.0), false, dt); y += 52;
        y = section(r, y, "SPOTIFY CONNECTION", "Paste your app client ID, then connect in the browser.");
        y = field(r, y, "Spotify client ID", c.spotifyClientId, false);
        double half = (contentW - 8) / 2;
        action(r, contentX, y, half, 36, "Connect Spotify", SpotifyPlayback::connect, true, dt);
        action(r, contentX + half + 8, y, half, 36, "Disconnect", SpotifyPlayback::disconnect, false, dt); y += 46;
        action(r, contentX, y, contentW, 32, "Open Spotify app dashboard", () -> open("https://developer.spotify.com/dashboard"), false, dt); y += 42;
        label(r, "Redirect URI (must match your Spotify app):", contentX, y, 0.65, EmberUI.TEXT_DIM, contentW); y += 22;
        label(r, SpotifyPlayback.REDIRECT, contentX, y, 0.75, EmberUI.TEXT, contentW); y += 28;
        action(r, contentX, y, contentW, 30, "Copy redirect URI", () -> mc.keyboard.setClipboard(SpotifyPlayback.REDIRECT), false, dt); y += 50;
        y = section(r, y, "LYRICS PROVIDER", "Use a publishable sl_pk_ key with native-client access enabled.");
        y = field(r, y, "Spicy Lyrics client key", c.spicyLyricsKey, true);
        label(r, "Enable 'No origin header' in the key's client-access settings.", contentX, y, 0.62, EmberUI.TEXT_DIM, contentW); y += 28;
        action(r, contentX, y, contentW, 34, "Open Spicy Lyrics key dashboard", () -> open("https://developers.spicylyrics.org/dashboard/applications"), false, dt); y += 54;
        y = section(r, y, "OPTIONAL SPICETIFY BRIDGE", "Use the Ember extension for local playback updates.");
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
        if (sx >= contentX && sx < px + pw && sy >= contentY && sy < contentY + viewportH) {
            commitField(); dragging = null; scrollTarget = Math.clamp(scrollTarget - vertical * 44, 0, Math.max(0, contentHeight - viewportH));
        }
        return true;
    }
    @Override public boolean keyPressed(KeyInput input) {
        int key = input.key();
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
