package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.EmberIcons;
import meteordevelopment.meteorclient.utils.render.EmberStrip;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.world.TickRate;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember V2: the info pill that sits in the bottom left - position and speed by default, with
 * tick rate and server available. Fully rounded ends, unlike the squarer status bar.
 */
public class EmberBubblesHud extends HudElement {
    public static final HudElementInfo<EmberBubblesHud> INFO = new HudElementInfo<>(Hud.GROUP, "ember-bubbles", "Ember V2 info pill: coordinates, speed, tick rate and server.", EmberBubblesHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the pill.")
        .defaultValue(1.0)
        .min(0.5)
        .max(3.0)
        .sliderRange(0.5, 3.0)
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

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("tps")
        .description("Show the server's ticks per second.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("server")
        .description("Show the server address.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Soft glow behind the pill.")
        .defaultValue(false)
        .build()
    );

    private double lastX, lastZ;
    private long lastSpeedNanos;
    private double speed;

    private final meteordevelopment.meteorclient.utils.render.EmberCounter counter =
        new meteordevelopment.meteorclient.utils.render.EmberCounter();

    public EmberBubblesHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double textScale = 0.85 * s;

        Color accent = EmberPalette.accent();

        List<EmberStrip.Segment> segments = new ArrayList<>(4);

        counter.tick();

        if (showCoords.get() && mc.player != null) {
            // Short time constant: coordinates roll rather than flicker, but stay close
            // enough to the real position to be worth reading while you move.
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.GLOBE,
                String.format("%.0fX %.0fY %.0fZ",
                    counter.get("x", mc.player.getX(), 0.07),
                    counter.get("y", mc.player.getY(), 0.07),
                    counter.get("z", mc.player.getZ(), 0.07)), accent));
        }

        if (showSpeed.get() && mc.player != null) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.SPEED,
                String.format("%.2f BPS", counter.get("bps", updateSpeed(), 0.12)), accent));
        }

        if (showTps.get()) {
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.TPS,
                String.format("%.1f TPS", counter.get("tps", TickRate.INSTANCE.getTickRate(), 0.3)), accent));
        }

        if (showServer.get()) {
            String address = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Local";
            segments.add(new EmberStrip.Segment(EmberIcons.Glyph.SERVER, address, accent));
        }

        if (segments.isEmpty()) {
            // Still needs a grabbable box in the editor, or it cannot be moved back.
            if (isInEditor()) segments.add(new EmberStrip.Segment(EmberIcons.Glyph.GLOBE, "0X 0Y 0Z", accent));
            else {
                setSize(0, 0);
                return;
            }
        }

        double h = EmberStrip.height(renderer, s, textScale);
        double w = EmberStrip.width(renderer, segments, s, textScale);
        double radius = h / 2;

        setSize(w, h);

        if (glow.get()) {
            renderer.softGlow(x, y, w, h, radius, 8 * s, new Color(accent.r, accent.g, accent.b, 130));
        }

        EmberStrip.draw(renderer, x, y, w, h, radius, segments, s, textScale);
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
