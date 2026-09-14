package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.screens.HudEditorScreen;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.Click;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * Ember's own client settings screen: theme, font and widgets in one place, styled like the
 * rest of the client rather than falling back to Meteor's stock setting screens.
 */
public class EmberClientSettingsScreen extends WidgetScreen {
    // Refreshed from EmberPalette every frame, so this screen follows the selected theme.
    private static Color PANEL_BG, HEADER_BG, DIVIDER, ROW_BG, LIST_BG, TEXT_WHITE, TEXT_DIM, ON_ACCENT;

    static {
        syncPalette();
    }

    private static final double PW = 560;
    /** Starting height only; the panel is sized to whatever the left column actually needs. */
    private static final double PH_MIN = 300;
    private static final double PR = 10;
    private static final double ROW_H = 32;
    private static final double HEADER_H = 44;
    private static final double LIST_W = 210;

    /** A clickable area recorded while drawing, so click handling never re-derives the layout. */
    private record Hit(double x, double y, double w, double h, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<Object, Float> anims = new HashMap<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();

    private double px, py, mx, my;
    private float fade;
    private int fontScroll;

    /**
     * The panel grows to fit its rows instead of assuming a height. Rows have been added to
     * this screen repeatedly, and a fixed height simply let the later ones spill out the
     * bottom with no sign anything was wrong.
     */
    private double panelH = 430;
    private double measuredLeftH;

    /** Recorded while laying out, so section headings cannot drift away from their rows. */
    private double widgetsLabelY = -1;

    /** True between pressing on the glass slider and releasing, so it can be dragged. */
    private boolean draggingSlider;

    public EmberClientSettingsScreen(GuiTheme theme) {
        // WidgetScreen already records the screen this was opened from as `parent`.
        super(theme, "Client Settings");
    }

    /**
     * Shared with the module menu, so this screen is flat black and follows the appearance
     * setting like everything else. It used to fill from the accent-tinted palette, which left
     * it looking purple next to a menu that had already moved to black.
     */
    private static void syncPalette() {
        PANEL_BG = EmberUI.bg();
        HEADER_BG = EmberUI.raised();
        DIVIDER = EmberUI.DIVIDER;
        ROW_BG = EmberUI.WELL;
        LIST_BG = new Color(EmberUI.WELL.r, EmberUI.WELL.g, EmberUI.WELL.b, 190);
        TEXT_WHITE = EmberUI.TEXT;
        TEXT_DIM = EmberUI.TEXT_DIM;
        // Dark text for use on top of accent-coloured fills.
        ON_ACCENT = new Color(10, 10, 13, 255);
    }

    @Override
    public void initWidgets() {
        clear();
    }

    /** This branch hands onRenderBefore no mouse position, so track the cursor here. */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double s = mc.getWindow().getScaleFactor();
        mx = mouseX * s;
        my = mouseY * s;
        super.mouseMoved(mouseX, mouseY);
    }
    private Color accent() {
        return EmberPalette.accent();
    }

    private Color accentAlpha(int a) {
        Color c = accent();
        return new Color(c.r, c.g, c.b, Math.max(0, Math.min(255, a)));
    }

    private float anim(Object key, float target, float dt) {
        float cur = EmberAnim.approach(anims.getOrDefault(key, target), target, dt, 0.065);
        anims.put(key, cur);
        return cur;
    }

    @Override
    protected void onRenderBefore(DrawContext graphics, float delta) {
        syncPalette();
        float dt = clock.tick();

        hits.clear();

        fade = Math.min(1f, fade + dt / 0.22f);
        float f = 1f - (1f - fade) * (1f - fade) * (1f - fade);

        // Sized from what the previous frame actually laid out, clamped to the screen. The
        // correction lands during the open fade, so it is never visible as a resize.
        if (measuredLeftH > 0) {
            panelH = Math.max(PH_MIN, Math.min(getWindowHeight() - 40, HEADER_H + measuredLeftH + 18));
        }

        px = (getWindowWidth() - PW) / 2;
        py = (getWindowHeight() - panelH) / 2;

        GuiRenderer r = new GuiRenderer();
        r.theme = theme;
        r.begin(graphics);

        // Dim backdrop in its own batch so it does not darken the panel's glow.
        r.scissorStart(0, 0, getWindowWidth(), getWindowHeight());
        r.quad(0, 0, getWindowWidth(), getWindowHeight(), new Color(0, 0, 0, (int) (150 * f)));
        r.scissorEnd();

        // Shadow, not an accent halo - the menu dropped those and this should match.
        EmberUI.shadow(r, px, py, PW, panelH, f);
        EmberUI.rim(r, px, py, PW, panelH, EmberUI.RADIUS, f);
        r.roundedRect(px, py, PW, panelH, EmberUI.RADIUS, PANEL_BG);
        EmberUI.gloss(r, px, py, PW, panelH, EmberUI.RADIUS, f);

        // Header
        r.roundedRect(px, py, PW, HEADER_H + PR, PR, HEADER_BG);
        r.quad(px, py + HEADER_H, PW, PR, HEADER_BG);
        r.quad(px, py + HEADER_H, PW, 1, DIVIDER);

        double cbX = px + PW - 34, cbY = py + 13;
        boolean closeHover = mx >= cbX - 6 && mx < cbX + 20 && my >= cbY - 5 && my < cbY + 21;
        float ca = anim("close", closeHover ? 1f : 0f, dt);
        if (ca > 0.01f) r.roundedRect(cbX - 6, cbY - 5, 26, 26, 6, new Color(222, 86, 86, (int) (60 * ca)));
        hits.add(new Hit(cbX - 6, cbY - 5, 26, 26, this::close));

        drawLeftColumn(r, dt);
        drawFontList(r, dt);

        r.end();

        drawText(graphics, closeHover);
    }

    // --- Left column: theme, font toggle, widgets ---

    private void drawLeftColumn(GuiRenderer r, float dt) {
        double x = px + 18;
        double w = PW - 36 - LIST_W - 14;
        double y = py + HEADER_H + 16;

        y += 18; // section label
        y = themeRow(r, dt, x, y, w);
        y += 8;

        y = appearanceRow(r, dt, x, y, w);

        // Only meaningful while frosted, so it stays out of the way otherwise.
        if (meteordevelopment.meteorclient.utils.render.EmberAppearance.frosted()) {
            y = sliderRow(r, dt, x, y, w, "Glass", Config.get().glass.get(),
                v -> Config.get().glass.set(v));
        }

        // The master switch for the HUD. Without this the only ways to reach it are Meteor's
        // own HUD tab, the .toggle command or a keybind, none of which live in Ember's UI.
        y = toggleRow(r, dt, x, y, w, "HUD enabled", Hud.get().active,
            () -> Hud.get().active = !Hud.get().active);

        boolean custom = Config.get().customFont.get();
        y = toggleRow(r, dt, x, y, w, "Custom font", custom,
            () -> Config.get().customFont.set(!Config.get().customFont.get()));
        y += 16;

        // Recorded rather than recomputed: the heading used to be placed by an arithmetic
        // expression that assumed how many rows sat above it, so every row added since landed
        // it on top of one of them.
        widgetsLabelY = y;
        y += 18;

        y = widgetRow(r, dt, x, y, w, "Status bar", "ember-status-bar");
        y = widgetRow(r, dt, x, y, w, "Info pill", "ember-bubbles");
        y = widgetRow(r, dt, x, y, w, "Keybinds", "ember-keybinds");
        y = widgetRow(r, dt, x, y, w, "Armor", "ember-armor");
        y = widgetRow(r, dt, x, y, w, "Target", "ember-target");
        y = widgetRow(r, dt, x, y, w, "Module list", "ember-module-list");
        y = widgetRow(r, dt, x, y, w, "Notifications", "ember-notifications");
        y = widgetRow(r, dt, x, y, w, "Music widget", "spotify");
        y += 10;

        // Action rows
        float ha = anim("hudedit", hovered(x, y, w, ROW_H) ? 1f : 0f, dt);
        r.roundedRect(x, y, w, ROW_H, 7, new Color(
            (int) MathHelper.lerp(ha * 0.35f, ROW_BG.r, accent().r),
            (int) MathHelper.lerp(ha * 0.35f, ROW_BG.g, accent().g),
            (int) MathHelper.lerp(ha * 0.35f, ROW_BG.b, accent().b), 235));
        hits.add(new Hit(x, y, w, ROW_H, () -> mc.setScreen(new HudEditorScreen(theme))));
        texts.add(new Label("Edit HUD positions", x + 12, y + (ROW_H - 0) / 2, TEXT_WHITE, 0.92));

        y += ROW_H + 8;

        // Wipes every widget back to Ember's layout, which is the only way to clear leftovers
        // from a config saved before it - saved elements always win over new defaults.
        float hr = anim("hudreset", hovered(x, y, w, ROW_H) ? 1f : 0f, dt);
        r.roundedRect(x, y, w, ROW_H, 7, new Color(
            (int) MathHelper.lerp(hr * 0.35f, ROW_BG.r, 222),
            (int) MathHelper.lerp(hr * 0.35f, ROW_BG.g, 86),
            (int) MathHelper.lerp(hr * 0.35f, ROW_BG.b, 86), 235));
        hits.add(new Hit(x, y, w, ROW_H, () -> Hud.get().resetToDefaultElements()));
        texts.add(new Label("Reset HUD to Ember defaults", x + 12, y + (ROW_H - 0) / 2, TEXT_WHITE, 0.92));

        // What the panel has to be tall enough to hold, measured from below the header.
        measuredLeftH = (y + ROW_H) - (py + HEADER_H);
    }

    /**
     * A 0..1 slider. Clicking the track jumps to that value and keeps following the cursor
     * until the button comes up, so it can be dragged as well as tapped.
     */
    private double sliderRow(GuiRenderer r, float dt, double x, double y, double w, String label,
                             double value, java.util.function.DoubleConsumer onChange) {
        r.roundedRect(x, y, w, ROW_H, 7, ROW_BG);

        double trackW = 150, trackH = 4;
        double tx = x + w - 12 - trackW;
        double ty = y + (ROW_H - trackH) / 2;

        boolean over = hovered(tx - 8, y, trackW + 16, ROW_H);
        float a = anim("glass-slider", over || draggingSlider ? 1f : 0f, dt);

        // Follow the cursor while held, which is what makes it feel like a slider rather
        // than a row of steps.
        if (draggingSlider) {
            double next = Math.max(0, Math.min(1, (mx - tx) / trackW));
            if (Math.abs(next - value) > 0.001) {
                onChange.accept(next);
                value = next;
            }
        }

        r.roundedRect(tx, ty, trackW, trackH, trackH / 2, new Color(44, 44, 53, 255));
        double filled = trackW * value;
        if (filled > trackH) r.roundedRect(tx, ty, filled, trackH, trackH / 2, accent());

        double knob = 11 + 2 * a;
        r.roundedRect(tx + filled - knob / 2, y + (ROW_H - knob) / 2, knob, knob, knob / 2, TEXT_WHITE);

        hits.add(new Hit(tx - 8, y, trackW + 16, ROW_H, () -> draggingSlider = true));

        texts.add(new Label(label, x + 12, y + ROW_H / 2, TEXT_WHITE, 0.92));
        texts.add(new Label(String.format("%.0f%%", value * 100), x + w - 12 - trackW - 46,
            y + ROW_H / 2, TEXT_DIM, 0.84));

        return y + ROW_H + 8;
    }

    /** Solid or frosted, as two pills on one row. */
    private double appearanceRow(GuiRenderer r, float dt, double x, double y, double w) {
        r.roundedRect(x, y, w, ROW_H, 7, ROW_BG);

        var options = meteordevelopment.meteorclient.utils.render.EmberAppearance.values();
        var current = meteordevelopment.meteorclient.utils.render.EmberAppearance.current();

        double bw = 78, gap = 6;
        double total = options.length * bw + (options.length - 1) * gap;
        double sx = x + w - 12 - total;

        for (int i = 0; i < options.length; i++) {
            var option = options[i];
            double bx = sx + i * (bw + gap);
            boolean sel = current == option;
            float a = anim("appearance" + i, sel || hovered(bx, y + 5, bw, ROW_H - 10) ? 1f : 0f, dt);

            Color fill = sel ? accentAlpha(225) : new Color(
                (int) MathHelper.lerp(a * 0.4f, ROW_BG.r, accent().r),
                (int) MathHelper.lerp(a * 0.4f, ROW_BG.g, accent().g),
                (int) MathHelper.lerp(a * 0.4f, ROW_BG.b, accent().b), 235);

            r.roundedRect(bx, y + 5, bw, ROW_H - 10, 6, fill);
            hits.add(new Hit(bx, y + 5, bw, ROW_H - 10, () -> Config.get().appearance.set(option)));
            texts.add(new Label(option.name(), bx + 9, y + ROW_H / 2, sel ? ON_ACCENT : TEXT_WHITE, 0.84));
        }

        texts.add(new Label("Appearance", x + 12, y + ROW_H / 2, TEXT_WHITE, 0.92));
        return y + ROW_H + 8;
    }

    private double themeRow(GuiRenderer r, float dt, double x, double y, double w) {
        r.roundedRect(x, y, w, ROW_H, 7, ROW_BG);

        // Swatches, so the theme is picked by eye rather than by cycling a name.
        int count = EmberPalette.NAMES.length;
        double sw = 20, gap = 6;
        double total = count * sw + (count - 1) * gap;
        double sx = x + w - 12 - total;

        for (int i = 0; i < count; i++) {
            double bx = sx + i * (sw + gap);
            boolean sel = i == EmberPalette.selected();
            boolean hover = hovered(bx, y + 6, sw, ROW_H - 12);
            float a = anim("sw" + i, sel || hover ? 1f : 0f, dt);

            Color c = EmberPalette.swatch(i);
            if (a > 0.01f) r.glow(bx, y + 6, sw, ROW_H - 12, 8, new Color(c.r, c.g, c.b, (int) (150 * a)), false);
            r.roundedRect(bx, y + 6, sw, ROW_H - 12, 5, new Color(c.r, c.g, c.b, sel ? 255 : (int) (170 + 60 * a)));

            int index = i;
            hits.add(new Hit(bx, y + 6, sw, ROW_H - 12, () -> EmberPalette.select(index)));
        }

        texts.add(new Label("Theme", x + 12, y + ROW_H / 2, TEXT_WHITE, 0.92));
        return y + ROW_H + 8;
    }

    private double toggleRow(GuiRenderer r, float dt, double x, double y, double w, String label, boolean on, Runnable toggle) {
        boolean hover = hovered(x, y, w, ROW_H);
        float ha = anim("tg" + label, hover ? 1f : 0f, dt);
        float oa = anim("on" + label, on ? 1f : 0f, dt);

        r.roundedRect(x, y, w, ROW_H, 7, new Color(
            (int) MathHelper.lerp(ha * 0.25f, ROW_BG.r, accent().r),
            (int) MathHelper.lerp(ha * 0.25f, ROW_BG.g, accent().g),
            (int) MathHelper.lerp(ha * 0.25f, ROW_BG.b, accent().b), 235));

        // Pill switch
        double tw = 34, th = 16;
        double tx = x + w - 12 - tw, ty = y + (ROW_H - th) / 2;
        Color off = EmberPalette.toggleOff();
        r.roundedRect(tx, ty, tw, th, th / 2, new Color(
            (int) MathHelper.lerp(oa, off.r, accent().r),
            (int) MathHelper.lerp(oa, off.g, accent().g),
            (int) MathHelper.lerp(oa, off.b, accent().b), 255));
        r.roundedRect(tx + 2 + (tw - th - 2) * oa, ty + 2, th - 4, th - 4, (th - 4) / 2, TEXT_WHITE);

        hits.add(new Hit(x, y, w, ROW_H, toggle));
        texts.add(new Label(label, x + 12, y + ROW_H / 2, TEXT_WHITE, 0.92));
        return y + ROW_H + 6;
    }

    private double widgetRow(GuiRenderer r, float dt, double x, double y, double w, String label, String elementName) {
        HudElement element = element(elementName);
        boolean on = element != null && element.isActive();

        return toggleRow(r, dt, x, y, w, label, on, () -> {
            HudElement el = element(elementName);
            if (el != null) el.toggle();
        });
    }

    private static HudElement element(String name) {
        for (HudElement el : Hud.get()) {
            if (el.info.name.equals(name)) return el;
        }
        return null;
    }

    // --- Right column: font list ---

    private void drawFontList(GuiRenderer r, float dt) {
        double x = px + PW - 18 - LIST_W;
        double y = py + HEADER_H + 16 + 18;
        double h = py + panelH - 18 - y;

        r.roundedRect(x, y, LIST_W, h, 8, LIST_BG);

        List<FontFamily> families = Fonts.FONT_FAMILIES;
        String current = currentFamily();

        double rowH = 26;
        int visible = (int) (h / rowH);
        fontScroll = Math.max(0, Math.min(fontScroll, Math.max(0, families.size() - visible)));

        r.scissorStart(x, y, LIST_W, h);

        for (int i = 0; i < visible && i + fontScroll < families.size(); i++) {
            FontFamily family = families.get(i + fontScroll);
            double ry = y + i * rowH;

            boolean sel = family.getName().equalsIgnoreCase(current);
            boolean hover = hovered(x + 4, ry + 2, LIST_W - 8, rowH - 4);
            float a = anim("f" + family.getName(), hover ? 1f : 0f, dt);

            if (sel) r.roundedRect(x + 4, ry + 2, LIST_W - 8, rowH - 4, 6, accentAlpha(210));
            else if (a > 0.01f) r.roundedRect(x + 4, ry + 2, LIST_W - 8, rowH - 4, 6, accentAlpha((int) (60 * a)));

            hits.add(new Hit(x + 4, ry + 2, LIST_W - 8, rowH - 4, () -> selectFont(family)));
            texts.add(new Label(family.getName(), x + 14, ry + rowH / 2, sel ? ON_ACCENT : TEXT_WHITE, 0.88));
        }

        r.scissorEnd();
    }

    private static String currentFamily() {
        FontFace face = Config.get().font.get();
        return face != null ? face.info.family() : "";
    }

    private void selectFont(FontFamily family) {
        FontFace face = family.get(FontInfo.Type.Regular);
        if (face == null) return;

        Config.get().font.set(face);
        Fonts.load(face);
    }

    // --- Text ---

    /** Text is queued while drawing and flushed afterwards, so it never sits under a panel. */
    private record Label(String text, double x, double centerY, Color color, double scale) {}

    private final List<Label> texts = new ArrayList<>();

    private void drawText(DrawContext graphics, boolean closeHover) {
        theme.textRenderer().begin(theme.scale(1.1));
        theme.textRenderer().render("Client Settings", px + 20, py + (HEADER_H - theme.textHeight()) / 2, TEXT_WHITE, false);
        theme.textRenderer().end();

        theme.textRenderer().begin(theme.scale(1.0));
        theme.textRenderer().render("X", px + PW - 34 + 4, py + 13 + 4,
            closeHover ? new Color(255, 140, 140, 255) : TEXT_DIM, false);
        theme.textRenderer().end();

        // Section labels
        theme.textRenderer().begin(theme.scale(0.82));
        double lx = px + 18;
        theme.textRenderer().render("APPEARANCE", lx, py + HEADER_H + 16, TEXT_DIM, false);
        if (widgetsLabelY > 0) theme.textRenderer().render("WIDGETS", lx, widgetsLabelY, TEXT_DIM, false);
        theme.textRenderer().render("FONT", px + PW - 18 - LIST_W, py + HEADER_H + 16, TEXT_DIM, false);
        theme.textRenderer().end();

        for (Label label : texts) {
            theme.textRenderer().begin(theme.scale(label.scale()));
            theme.textRenderer().render(label.text(), label.x(), label.centerY() - theme.textHeight() / 2, label.color(), false);
            theme.textRenderer().end();
        }
        texts.clear();
    }

    private boolean hovered(double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // --- Input ---

    @Override
    public boolean mouseReleased(Click click) {
        draggingSlider = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double s = mc.getWindow().getScaleFactor();
        double cx = click.x() * s, cy = click.y() * s;

        // Hits are recorded newest last, so walk backwards to let the topmost one win.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(cx, cy)) {
                hit.action().run();
                return true;
            }
        }

        // A click outside the panel closes it, like the other Ember screens.
        if (cx < px || cx >= px + PW || cy < py || cy >= py + panelH) {
            close();
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        double s = mc.getWindow().getScaleFactor();
        double cx = mouseX * s;

        if (cx >= px + PW - 18 - LIST_W) {
            fontScroll -= (int) v;
            fontScroll = Math.max(0, fontScroll);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput event) {
        if (event.key() == GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void close() {
        // Go back to the ClickGUI this was opened from instead of closing to the game.
        if (parent != null) mc.setScreen(parent);
        else super.close();
    }
}
