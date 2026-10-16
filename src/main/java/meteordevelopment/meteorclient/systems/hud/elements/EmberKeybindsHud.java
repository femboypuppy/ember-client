package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.EmberAnim;
import meteordevelopment.meteorclient.utils.render.EmberAppearance;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ember V2: a compact panel listing every module you have bound to a key, with the module on
 * the left and its key on the right in the accent colour.
 */
public class EmberKeybindsHud extends HudElement {
    public static final HudElementInfo<EmberKeybindsHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-keybinds", "Ember V2 panel listing your bound modules and their keys.", EmberKeybindsHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the panel.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> showHeader = sgGeneral.add(new BoolSetting.Builder()
        .name("header")
        .description("Show the panel title.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> boundOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("bound-only")
        .description("Only list modules that actually have a key set.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> activeOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled-only")
        .description("Only list modules that are currently on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the panel.")
        .defaultValue(false)
        .build()
    );

    /** One listed row: the module's display name and the key that toggles it. */
    private record Bind(String name, String key, boolean active) {}
    private static final class RowMotion {
        Bind bind;
        float amount;
        boolean present;
        RowMotion(Bind bind) { this.bind = bind; }
    }
    private final Map<String, RowMotion> glassRows = new LinkedHashMap<>();
    private final EmberAnim.Clock glassClock = new EmberAnim.Clock();
    private double glassWidth;

    public EmberKeybindsHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (EmberAppearance.liquid()) {
            renderLiquid(renderer);
            return;
        }
        double s = scale.get();
        double textScale = 0.85 * s;

        Color accent = EmberPalette.accent();
        Color bg = EmberStrip.background();
        Color bright = EmberPalette.textBright();

        List<Bind> binds = collect();

        if (binds.isEmpty()) {
            if (isInEditor()) binds.add(new Bind("KillAura", "R", true));
            else {
                setSize(0, 0);
                return;
            }
        }

        double pad = 9 * s;
        double rowH = renderer.textHeight(true, textScale) + 7 * s;
        double headerH = showHeader.get() ? rowH + 2 * s : 0;
        double iconSize = 9 * s;
        double columnGap = 18 * s;

        // Measure the widest row so the keys can be flush right.
        double widest = 0;
        for (Bind bind : binds) {
            double w = renderer.textWidth(bind.name(), true, textScale) + columnGap
                + renderer.textWidth(bind.key(), true, textScale);
            widest = Math.max(widest, w);
        }

        if (showHeader.get()) {
            widest = Math.max(widest, iconSize + 5 * s + renderer.textWidth("Keybinds", true, textScale));
        }

        double w = pad * 2 + widest;
        double h = pad * 2 + headerH + rowH * binds.size();
        double radius = 6 * s;

        setSize(w, h);

        if (glow.get()) {
            renderer.softGlow(x, y, w, h, radius, 8 * s, new Color(accent.r, accent.g, accent.b, 120));
        }

        renderer.dropShadow(x, y, w, h, radius, 20 * s);
        EmberStrip.panel(renderer, x, y, w, h, radius, 1);

        double cy = y + pad;

        if (showHeader.get()) {
            double th = renderer.textHeight(true, textScale);
            EmberIcons.draw(renderer, EmberIcons.Glyph.KEY, x + pad, cy + (th - iconSize) / 2, iconSize, accent, bg);
            renderer.text("Keybinds", x + pad + iconSize + 5 * s, cy, bright, true, textScale);
            cy += headerH;
        }

        for (Bind bind : binds) {
            renderer.text(bind.name(), x + pad, cy, bright, true, textScale);

            double kw = renderer.textWidth(bind.key(), true, textScale);
            renderer.text(bind.key(), x + w - pad - kw, cy, accent, true, textScale);

            cy += rowH;
        }
    }

    private List<Bind> collect() {
        List<Bind> binds = new ArrayList<>();
        Modules modules = Modules.get();
        if (modules == null) return binds;

        for (Module module : modules.getAll()) {
            if (boundOnly.get() && !module.keybind.isSet()) continue;
            if (activeOnly.get() && !module.isActive()) continue;

            binds.add(new Bind(module.title, module.keybind.isSet() ? module.keybind.toString() : "-", module.isActive()));
        }

        binds.sort(Comparator.comparing(Bind::name));
        return binds;
    }

    private void renderLiquid(HudRenderer renderer) {
        double dt = glassClock.tick(), motion = EmberAppearance.motion();
        double tau = motion == 0 ? 0 : 0.07 / motion;
        List<Bind> binds = collect();
        if (binds.isEmpty() && isInEditor()) {
            binds.add(new Bind("Click GUI", "RShift", true));
            binds.add(new Bind("Freecam", "V", false));
        }
        for (RowMotion row : glassRows.values()) row.present = false;
        for (Bind bind : binds) {
            RowMotion row = glassRows.computeIfAbsent(bind.name, key -> new RowMotion(bind));
            row.bind = bind;
            row.present = true;
        }
        for (RowMotion row : glassRows.values()) row.amount = EmberAnim.approach(row.amount, row.present ? 1f : 0f, dt, tau);
        glassRows.values().removeIf(row -> !row.present && row.amount <= 0.002f);
        if (glassRows.isEmpty()) { setSize(0, 0); return; }

        double s = scale.get(), ts = 0.78 * s, pad = 9 * s;
        double textH = renderer.textHeight(false, ts), rowH = textH + 8 * s;
        double headerH = showHeader.get() ? rowH + 4 * s : 0;
        double targetW = 100 * s, bodyH = 0;
        float opacity = 0;
        List<RowMotion> rows = new ArrayList<>(glassRows.values());
        rows.sort(Comparator.comparing(row -> row.bind.name));
        for (RowMotion row : rows) {
            targetW = Math.max(targetW, renderer.textWidth(row.bind.name, false, ts) + renderer.textWidth(row.bind.key, false, ts * 0.9) + 36 * s);
            bodyH += rowH * row.amount;
            opacity = Math.max(opacity, row.amount);
        }
        targetW += pad * 2;
        glassWidth = glassWidth == 0 ? targetW : EmberAnim.approach(glassWidth, targetW, dt, tau);
        double w = Math.max(targetW, glassWidth), h = pad * 2 + headerH + bodyH;
        setSize(w, h);
        renderer.dropShadow(x, y, w, h, 7 * s, 12 * s, opacity);
        EmberStrip.panel(renderer, x, y, w, h, 7 * s, opacity);
        Color accent = EmberPalette.accent();
        double cy = y + pad;
        if (showHeader.get()) {
            EmberIcons.draw(renderer, EmberIcons.Glyph.KEY, x + pad, cy + (textH - 9 * s) / 2, 9 * s, accent, EmberStrip.background());
            renderer.text("Keybinds", x + pad + 14 * s, cy, new Color(245, 245, 250, (int) (255 * opacity)), false, ts);
            renderer.quad(x + pad, cy + headerH - 4 * s, w - pad * 2, Math.max(0.6, s * 0.5), new Color(255, 255, 255, (int) (22 * opacity)));
            cy += headerH;
        }
        for (RowMotion row : rows) {
            float a = row.amount;
            double ry = cy + (1 - a) * 5 * s;
            Color text = row.bind.active ? new Color(242, 243, 248, (int) (255 * a)) : new Color(167, 171, 183, (int) (255 * a));
            double kw = renderer.textWidth(row.bind.key, false, ts * 0.9);
            double chipW = kw + 9 * s, chipH = textH + 3 * s;
            double chipX = x + w - pad - chipW;
            renderer.roundedQuad(chipX, ry - s, chipW, chipH, 3 * s, new Color(accent.r, accent.g, accent.b, (int) ((row.bind.active ? 35 : 14) * a)));
            renderer.text(row.bind.name, x + pad, ry, text, false, ts);
            renderer.text(row.bind.key, chipX + 4.5 * s, ry + s, new Color(accent.r, accent.g, accent.b, (int) (255 * a)), false, ts * 0.9);
            cy += rowH * a;
        }
    }
}
