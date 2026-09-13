package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember V2: the top centre strip - real world clock, client name with a status dot, and
 * signal bars whose filled count reflects your ping.
 */
public class EmberClockHud extends HudElement {
    public static final HudElementInfo<EmberClockHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-clock", "Ember V2 top strip: clock, client name and connection bars.", EmberClockHud::new);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** Ping thresholds for one, two, three and four bars. */
    private static final int[] PING_STEPS = {400, 200, 100, 50};

    private static final Color GOOD = new Color(93, 217, 127, 255);
    private static final Color FAIR = new Color(231, 164, 80, 255);
    private static final Color POOR = new Color(222, 86, 86, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the strip.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> showClock = sgGeneral.add(new BoolSetting.Builder()
        .name("clock")
        .description("Show the real world time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showName = sgGeneral.add(new BoolSetting.Builder()
        .name("client-name")
        .description("Show the client name with a status dot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBars = sgGeneral.add(new BoolSetting.Builder()
        .name("connection-bars")
        .description("Show signal bars for your ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the strip.")
        .defaultValue(false)
        .build()
    );

    public EmberClockHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double textScale = 0.85 * s;
        double textH = renderer.textHeight(true, textScale);

        double h = textH + 11 * s;
        double radius = h / 2;
        double pad = 10 * s;
        double gap = 8 * s;

        Color panel = EmberPalette.panel();
        Color bg = new Color(panel.r, panel.g, panel.b, 230);
        Color bright = EmberPalette.textBright();
        Color accent = EmberPalette.accent();

        String time = LocalTime.now().format(CLOCK);
        String name = "Ember";

        int ping = ping();
        int bars = barsFor(ping);
        Color signal = bars >= 3 ? GOOD : bars == 2 ? FAIR : POOR;

        double dot = 5 * s;
        double barW = 2.5 * s, barGap = 2 * s;
        double barsW = 4 * barW + 3 * barGap;

        // Measure before drawing so the HUD editor gets a true box.
        double w = pad;
        if (showClock.get()) w += renderer.textWidth(time, true, textScale);
        if (showName.get()) {
            if (showClock.get()) w += gap;
            w += dot + 5 * s + renderer.textWidth(name, true, textScale);
        }
        if (showBars.get()) {
            if (showClock.get() || showName.get()) w += gap;
            w += barsW;
        }
        w += pad;

        setSize(w, h);

        if (glow.get()) renderer.softGlow(x, y, w, h, radius, 8 * s, new Color(accent.r, accent.g, accent.b, 110));
        renderer.roundedQuad(x, y, w, h, radius, bg);

        double cx = x + pad;
        double ty = y + (h - textH) / 2;

        if (showClock.get()) {
            renderer.text(time, cx, ty, bright, true, textScale);
            cx += renderer.textWidth(time, true, textScale) + gap;
        }

        if (showName.get()) {
            renderer.roundedQuad(cx, y + (h - dot) / 2, dot, dot, dot / 2, accent);
            cx += dot + 5 * s;
            renderer.text(name, cx, ty, bright, true, textScale);
            cx += renderer.textWidth(name, true, textScale) + gap;
        }

        if (showBars.get()) {
            // Four bars of rising height; unfilled ones stay as dim stubs.
            for (int i = 0; i < 4; i++) {
                double bh = (4 + i * 2.5) * s;
                double bx = cx + i * (barW + barGap);
                double by = y + h - (h - textH) / 2 - bh;

                boolean on = i < bars;
                Color c = on ? signal : new Color(signal.r, signal.g, signal.b, 70);
                renderer.roundedQuad(bx, by, barW, bh, barW / 2, c);
            }
        }
    }

    private int ping() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    /** Singleplayer and unknown pings show full bars rather than a false warning. */
    private int barsFor(int ping) {
        if (ping <= 0) return 4;
        for (int i = 0; i < PING_STEPS.length; i++) {
            if (ping > PING_STEPS[i]) return i + 1;
        }
        return 4;
    }
}
