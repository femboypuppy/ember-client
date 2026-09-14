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
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private record Bind(String name, String key) {}

    public EmberKeybindsHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double textScale = 0.85 * s;

        Color accent = EmberPalette.accent();
        Color bg = EmberStrip.background();
        Color bright = EmberPalette.textBright();

        List<Bind> binds = collect();

        if (binds.isEmpty()) {
            if (isInEditor()) binds.add(new Bind("KillAura", "R"));
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

            binds.add(new Bind(module.title, module.keybind.isSet() ? module.keybind.toString() : "-"));
        }

        binds.sort(Comparator.comparing(Bind::name));
        return binds;
    }
}
