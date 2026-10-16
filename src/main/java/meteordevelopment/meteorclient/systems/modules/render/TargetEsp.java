package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Marks the entity you are fighting with an animated ring: whoever you last hit, or whoever is under your crosshair.
 * The mark fades in, follows the target smoothly, flashes when your hit lands, and fades out at the last spot when
 * the fight ends.
 */
public class TargetEsp extends Module {
    public enum Style {
        Halo,
        Orbit,
        Spiral,
        Pulse
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Style> style = sgLook.add(new EnumSetting.Builder<Style>()
        .name("style")
        .description("Halo: a spinning ring with sparks above the head. Orbit: two tilted rings. Spiral: a rising helix. Pulse: rings rippling from the feet.")
        .defaultValue(Style.Halo)
        .build()
    );

    private final Setting<SettingColor> color = sgLook.add(new ColorSetting.Builder()
        .name("color")
        .description("Main colour.")
        .defaultValue(new SettingColor(255, 110, 40, 230))
        .build()
    );

    private final Setting<SettingColor> secondColor = sgLook.add(new ColorSetting.Builder()
        .name("second-color")
        .description("The colour the mark fades into around the ring.")
        .defaultValue(new SettingColor(255, 210, 90, 230))
        .build()
    );

    private final Setting<Boolean> hitFlash = sgLook.add(new BoolSetting.Builder()
        .name("hit-flash")
        .description("Flash the mark when the target takes damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> flashColor = sgLook.add(new ColorSetting.Builder()
        .name("flash-color")
        .description("Colour of the hit flash.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(hitFlash::get)
        .build()
    );

    private final Setting<Double> size = sgLook.add(new DoubleSetting.Builder()
        .name("size")
        .description("Size of the mark.")
        .defaultValue(1.0)
        .range(0.5, 2.0)
        .sliderRange(0.5, 2.0)
        .build()
    );

    private final Setting<Double> speed = sgLook.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Animation speed.")
        .defaultValue(1.0)
        .range(0.2, 3.0)
        .sliderRange(0.2, 3.0)
        .build()
    );

    private final Setting<Boolean> crosshair = sgGeneral.add(new BoolSetting.Builder()
        .name("crosshair")
        .description("Also mark whoever is under your crosshair, not only who you hit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only")
        .description("Only mark players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> memory = sgGeneral.add(new DoubleSetting.Builder()
        .name("memory")
        .description("Seconds the mark stays after your last hit or after you look away.")
        .defaultValue(4.0)
        .range(0.5, 15.0)
        .sliderRange(0.5, 15.0)
        .build()
    );

    private static final float FADE_IN = 0.2f;
    private static final float FADE_OUT = 0.35f;

    private LivingEntity target;
    private long activeAt;
    private long shownAt;
    private Vec3d lastPos;
    private double lastWidth = 0.6, lastHeight = 1.8;
    private long fadingSince;
    private Vec3d fadePos;
    private double fadeWidth, fadeHeight;

    private final Color work = new Color();
    private final Color work2 = new Color();

    public TargetEsp() {
        super(Categories.Render, "target-esp", "Marks your combat target with an animated ring.");
    }

    @Override
    public void onDeactivate() {
        target = null;
        fadePos = null;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (event.entity instanceof LivingEntity living && valid(living)) select(living);
    }

    private boolean valid(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player || !living.isAlive() || living.isRemoved()) return false;
        return !playersOnly.get() || entity instanceof PlayerEntity;
    }

    private void select(LivingEntity living) {
        long now = System.currentTimeMillis();
        if (living != target) {
            if (target != null && lastPos != null) startFade();
            target = living;
            shownAt = now;
        }
        activeAt = now;
    }

    private void startFade() {
        fadingSince = System.currentTimeMillis();
        fadePos = lastPos;
        fadeWidth = lastWidth;
        fadeHeight = lastHeight;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        if (crosshair.get() && valid(mc.targetedEntity)) select((LivingEntity) mc.targetedEntity);

        if (target != null && (!valid(target) || now - activeAt > memory.get() * 1000)) {
            startFade();
            target = null;
        }

        float time = (now % 1_000_000L) / 1000f * speed.get().floatValue();

        if (fadePos != null) {
            float t = (now - fadingSince) / 1000f / FADE_OUT;
            if (t >= 1) fadePos = null;
            else draw(event.renderer, fadePos, fadeWidth, fadeHeight, time, 1 - t, 0);
        }

        if (target != null) {
            Vec3d pos = target.getLerpedPos(event.tickDelta);
            lastPos = pos;
            lastWidth = target.getWidth();
            lastHeight = target.getHeight();
            float in = Math.min(1f, (now - shownAt) / 1000f / FADE_IN);
            float flash = hitFlash.get() && target.hurtTime > 0 ? target.hurtTime / 10f : 0;
            draw(event.renderer, pos, lastWidth, lastHeight, time, in, flash);
        }
    }

    private void draw(Renderer3D r, Vec3d pos, double width, double height, float time, float alpha, float flash) {
        double s = size.get();
        double radius = Math.max(0.45, width * 0.85) * s;

        switch (style.get()) {
            case Halo -> {
                double y = pos.y + height + 0.2 * s + Math.sin(time * 2.2) * 0.04;
                ring(r, pos.x, y, pos.z, radius * 0.6, radius * 0.6 + 0.06 * s, time * 1.6, alpha, flash);
                for (int i = 0; i < 3; i++) {
                    double a = time * 3.0 + i * Math.PI * 2 / 3;
                    spark(r, pos.x + Math.cos(a) * radius * 0.6, y, pos.z + Math.sin(a) * radius * 0.6, 0.05 * s, i / 3f, alpha, flash);
                }
            }
            case Orbit -> {
                double cy = pos.y + height * 0.5;
                tiltedRing(r, pos.x, cy, pos.z, radius, Math.toRadians(25), time * 1.4, alpha, flash);
                tiltedRing(r, pos.x, cy, pos.z, radius * 0.92, Math.toRadians(-25), -time * 1.1 + 1.3, alpha, flash);
            }
            case Spiral -> {
                int points = 48;
                for (int i = 0; i < points - 1; i++) {
                    float f0 = (float) i / points, f1 = (float) (i + 1) / points;
                    double a0 = f0 * Math.PI * 4 + time * 2.4, a1 = f1 * Math.PI * 4 + time * 2.4;
                    // A bright band climbs up the helix.
                    float head = (float) ((time * 0.6) % 1.0);
                    float glow = 0.25f + 0.75f * (float) Math.max(0, 1 - Math.abs(f0 - head) * 4);
                    blend(f0, alpha * glow, flash, work);
                    r.line(pos.x + Math.cos(a0) * radius, pos.y + f0 * height, pos.z + Math.sin(a0) * radius,
                        pos.x + Math.cos(a1) * radius, pos.y + f1 * height, pos.z + Math.sin(a1) * radius, work);
                }
            }
            case Pulse -> {
                double y = pos.y + 0.02;
                ring(r, pos.x, y, pos.z, radius * 0.5, radius * 0.5 + 0.05 * s, time, alpha, flash);
                for (int i = 0; i < 3; i++) {
                    float phase = (float) ((time * 0.8 + i / 3.0) % 1.0);
                    double rr = radius * (0.5 + phase * 1.1);
                    ring(r, pos.x, y, pos.z, rr, rr + 0.04 * s, time, alpha * (1 - phase) * 0.9f, flash);
                }
            }
        }
    }

    /** Flat ring band, coloured around its circumference from the main colour into the second. */
    private void ring(Renderer3D r, double x, double y, double z, double inner, double outer, double spin, float alpha, float flash) {
        int segments = 48;
        for (int i = 0; i < segments; i++) {
            double a0 = spin + i * Math.PI * 2 / segments, a1 = spin + (i + 1) * Math.PI * 2 / segments;
            float f = (float) Math.abs(Math.sin((double) i / segments * Math.PI));
            blend(f, alpha, flash, work);
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            r.quad(x + c0 * inner, y, z + s0 * inner, x + c0 * outer, y, z + s0 * outer,
                x + c1 * outer, y, z + s1 * outer, x + c1 * inner, y, z + s1 * inner, work);
        }
    }

    /** A ring tipped by {@code tilt}, spinning around the vertical axis; drawn as a doubled line for weight. */
    private void tiltedRing(Renderer3D r, double x, double y, double z, double radius, double tilt, double spin, float alpha, float flash) {
        int segments = 56;
        double ct = Math.cos(tilt), st = Math.sin(tilt), cs = Math.cos(spin), ss = Math.sin(spin);
        double px = 0, py = 0, pz = 0;
        for (int i = 0; i <= segments; i++) {
            double a = i * Math.PI * 2 / segments;
            double lx = Math.cos(a) * radius, lz = Math.sin(a) * radius;
            double ly = lz * st;
            lz *= ct;
            double wx = lx * cs - lz * ss, wz = lx * ss + lz * cs;
            if (i > 0) {
                float f = (float) Math.abs(Math.sin((double) i / segments * Math.PI));
                blend(f, alpha, flash, work);
                r.line(x + px, y + py, z + pz, x + wx, y + ly, z + wz, work);
                r.line(x + px, y + py + 0.015, z + pz, x + wx, y + ly + 0.015, z + wz, work);
            }
            px = wx;
            py = ly;
            pz = wz;
        }
    }

    /** A small diamond lying flat. */
    private void spark(Renderer3D r, double x, double y, double z, double half, float f, float alpha, float flash) {
        blend(f, alpha, flash, work);
        r.quad(x, y, z - half, x + half, y, z, x, y, z + half, x - half, y, z, work);
    }

    private void blend(float f, float alpha, float flash, Color out) {
        SettingColor a = color.get(), b = secondColor.get();
        int red = lerp(a.r, b.r, f), green = lerp(a.g, b.g, f), blue = lerp(a.b, b.b, f), al = lerp(a.a, b.a, f);
        if (flash > 0) {
            SettingColor h = flashColor.get();
            red = lerp(red, h.r, flash);
            green = lerp(green, h.g, flash);
            blue = lerp(blue, h.b, flash);
        }
        out.set(red, green, blue, Math.round(al * Math.max(0, Math.min(1, alpha))));
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }
}
