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
import meteordevelopment.meteorclient.utils.world.TickRate;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember V2: a row of dark rounded bubbles, each with a coloured icon block and a value -
 * ticks per second, position, speed and the server you are on.
 */
public class EmberBubblesHud extends HudElement {
    public static final HudElementInfo<EmberBubblesHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-bubbles", "Ember V2 info bubbles: TPS, coordinates, speed and server.", EmberBubblesHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the bubbles.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
        .build()
    );

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("tps")
        .description("Show the server's ticks per second.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("coordinates")
        .description("Show your position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("speed")
        .description("Show your horizontal speed in blocks per second.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("server")
        .description("Show the server address.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind each bubble.")
        .defaultValue(false)
        .build()
    );

    /** One bubble: a coloured square, a value, and a smaller unit after it. */
    private record Bubble(Color icon, String value, String unit) {}

    private double lastX, lastZ;
    private long lastSpeedNanos;
    private double speed;

    public EmberBubblesHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double valueScale = 0.85 * s;
        double unitScale = 0.62 * s;

        double textH = renderer.textHeight(true, valueScale);
        double h = textH + 11 * s;
        double radius = h / 2;
        double pad = 8 * s;
        double iconSize = 9 * s;
        double gap = 6 * s;
        double between = 6 * s;

        Color panel = EmberPalette.panel();
        Color bg = new Color(panel.r, panel.g, panel.b, 230);
        Color value = EmberPalette.textBright();
        Color unit = EmberPalette.textDim();
        Color accent = EmberPalette.accent();

        List<Bubble> bubbles = new ArrayList<>(4);

        if (showTps.get()) {
            bubbles.add(new Bubble(accent, String.format("%.1f", TickRate.INSTANCE.getTickRate()), "TPS"));
        }

        if (showCoords.get() && mc.player != null) {
            bubbles.add(new Bubble(accent,
                String.format("%.0f %.0f %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ()), "XYZ"));
        }

        if (showSpeed.get() && mc.player != null) {
            bubbles.add(new Bubble(accent, String.format("%.2f", updateSpeed()), "BPS"));
        }

        if (showServer.get()) {
            String address = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Singleplayer";
            bubbles.add(new Bubble(accent, address, ""));
        }

        if (bubbles.isEmpty()) {
            if (isInEditor()) bubbles.add(new Bubble(accent, "20.0", "TPS"));
            else {
                setSize(0, 0);
                return;
            }
        }

        // Measure first so the element reports its true size to the HUD editor.
        double[] widths = new double[bubbles.size()];
        double total = 0;

        for (int i = 0; i < bubbles.size(); i++) {
            Bubble b = bubbles.get(i);
            double w = pad + iconSize + gap + renderer.textWidth(b.value(), true, valueScale) + pad;
            if (!b.unit().isEmpty()) w += 3 * s + renderer.textWidth(b.unit(), true, unitScale);

            widths[i] = w;
            total += w;
            if (i < bubbles.size() - 1) total += between;
        }

        setSize(total, h);

        double bx = x;
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble b = bubbles.get(i);
            double w = widths[i];

            if (glow.get()) renderer.softGlow(bx, y, w, h, radius, 6 * s, new Color(accent.r, accent.g, accent.b, 110));
            renderer.roundedQuad(bx, y, w, h, radius, bg);

            renderer.roundedQuad(bx + pad, y + (h - iconSize) / 2, iconSize, iconSize, 2.5 * s, b.icon());

            double tx = bx + pad + iconSize + gap;
            double ty = y + (h - textH) / 2;
            renderer.text(b.value(), tx, ty, value, true, valueScale);

            if (!b.unit().isEmpty()) {
                double vw = renderer.textWidth(b.value(), true, valueScale);
                double unitH = renderer.textHeight(true, unitScale);
                // Sits on the value's baseline rather than centred, so it reads as a suffix.
                renderer.text(b.unit(), tx + vw + 3 * s, ty + (textH - unitH), unit, true, unitScale);
            }

            bx += w + between;
        }
    }

    /** Blocks per second from real elapsed time, so it does not drift with framerate. */
    private double updateSpeed() {
        if (mc.player == null) return 0;

        long now = System.nanoTime();
        double dt = lastSpeedNanos == 0 ? 0 : (now - lastSpeedNanos) / 1_000_000_000.0;
        lastSpeedNanos = now;

        if (dt > 0 && dt < 1) {
            double dx = mc.player.getX() - lastX;
            double dz = mc.player.getZ() - lastZ;
            double sample = Math.sqrt(dx * dx + dz * dz) / dt;
            // Smoothed, or it flickers every frame.
            speed += (sample - speed) * Math.min(1, dt * 6);
        }

        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
        return speed;
    }
}
