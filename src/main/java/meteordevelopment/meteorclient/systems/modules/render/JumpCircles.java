package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Leaves an expanding ring on the ground wherever you jump from, and optionally where you
 * land. Rings are fixed in world space once spawned, so they stay put as you move away.
 */
public class JumpCircles extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgShape = settings.createGroup("Shape");

    private final Setting<Boolean> onJump = sgGeneral.add(new BoolSetting.Builder()
        .name("on-jump")
        .description("Leave a ring where you jump from.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onLand = sgGeneral.add(new BoolSetting.Builder()
        .name("on-land")
        .description("Leave a ring where you land.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> duration = sgGeneral.add(new DoubleSetting.Builder()
        .name("duration")
        .description("Seconds a ring takes to expand and fade.")
        .defaultValue(0.8)
        .min(0.1)
        .max(5)
        .sliderRange(0.1, 3)
        .build()
    );

    private final Setting<Integer> maxCircles = sgGeneral.add(new IntSetting.Builder()
        .name("max-circles")
        .description("How many rings can be on screen at once.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> followAccent = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-accent")
        .description("Use the Ember accent colour instead of the one below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Ring colour.")
        .defaultValue(new SettingColor(160, 120, 255, 255))
        .visible(() -> !followAccent.get())
        .build()
    );

    private final Setting<Double> radius = sgShape.add(new DoubleSetting.Builder()
        .name("radius")
        .description("How wide a ring grows, in blocks.")
        .defaultValue(1.4)
        .min(0.1)
        .max(10)
        .sliderRange(0.2, 4)
        .build()
    );

    private final Setting<Double> rise = sgShape.add(new DoubleSetting.Builder()
        .name("rise")
        .description("How far a ring drifts upwards as it expands.")
        .defaultValue(0.35)
        .min(0)
        .max(5)
        .sliderRange(0, 2)
        .build()
    );

    private final Setting<Integer> segments = sgShape.add(new IntSetting.Builder()
        .name("segments")
        .description("Line segments per ring. Higher is smoother and costs more.")
        .defaultValue(40)
        .min(6)
        .sliderRange(8, 96)
        .build()
    );

    private final Setting<Integer> rings = sgShape.add(new IntSetting.Builder()
        .name("rings")
        .description("Concentric rings drawn per jump, each trailing the one before.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );

    /** One ring, pinned to where it was spawned. */
    private static final class Circle {
        final double x, y, z;
        final long born;

        Circle(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.born = System.currentTimeMillis();
        }
    }

    private final List<Circle> circles = new ArrayList<>();
    private boolean wasOnGround;

    public JumpCircles() {
        super(Categories.Render, "jump-circles", "Leaves an expanding ring where you jump and land.");
    }

    @Override
    public void onDeactivate() {
        circles.clear();
        wasOnGround = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        boolean onGround = mc.player.isOnGround();

        // Leaving the ground while moving upwards is a jump; anything else is a fall or a
        // step off an edge, which should not spawn a ring.
        if (wasOnGround && !onGround && mc.player.getVelocity().y > 0.05 && onJump.get()) spawn();
        else if (!wasOnGround && onGround && onLand.get()) spawn();

        wasOnGround = onGround;
    }

    private void spawn() {
        circles.add(new Circle(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        while (circles.size() > maxCircles.get()) circles.removeFirst();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (circles.isEmpty()) return;

        long now = System.currentTimeMillis();
        double life = duration.get() * 1000;
        Color base = followAccent.get() ? EmberPalette.accent() : color.get();

        for (Iterator<Circle> it = circles.iterator(); it.hasNext(); ) {
            Circle circle = it.next();
            double age = (now - circle.born) / life;

            if (age >= 1) {
                it.remove();
                continue;
            }

            for (int ring = 0; ring < rings.get(); ring++) {
                // Each extra ring lags a little, so they read as one pulse rather than
                // several rings that all happen to start together.
                double t = age - ring * 0.12;
                if (t <= 0) continue;

                draw(event, circle, t, base);
            }
        }
    }

    private void draw(Render3DEvent event, Circle circle, double t, Color base) {
        // Eased out, so the ring leaps outwards and then settles rather than creeping.
        double eased = 1 - Math.pow(1 - Math.min(1, t), 3);

        double r = radius.get() * eased;
        double y = circle.y + rise.get() * eased;

        int alpha = (int) (base.a * (1 - Math.min(1, t)));
        if (alpha <= 2 || r <= 0.001) return;

        Color c = new Color(base.r, base.g, base.b, alpha);

        int count = segments.get();
        double step = Math.PI * 2 / count;

        double px = circle.x + r, pz = circle.z;

        for (int i = 1; i <= count; i++) {
            double angle = i * step;
            double nx = circle.x + Math.cos(angle) * r;
            double nz = circle.z + Math.sin(angle) * r;

            event.renderer.line(px, y, pz, nx, y, nz, c);

            px = nx;
            pz = nz;
        }
    }
}
