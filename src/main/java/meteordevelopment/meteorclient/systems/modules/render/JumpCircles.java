package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.CelestialGeometry;
import meteordevelopment.meteorclient.utils.render.CelestialMesh;
import meteordevelopment.meteorclient.utils.render.CelestialRenderer;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

/** Ground-pinned, depth-tested pulses with feathered surfaces and delayed trailing rings. */
public class JumpCircles extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgShape = settings.createGroup("Shape");
    private final Setting<CelestialGeometry.CircleStyle> style = sgGeneral.add(new EnumSetting.Builder<CelestialGeometry.CircleStyle>()
        .name("style").description("Liquid ripples, orbiting stars, or a rotating celestial dial.")
        .defaultValue(CelestialGeometry.CircleStyle.Orbit).build());
    private final Setting<Boolean> onJump = sgGeneral.add(new BoolSetting.Builder()
        .name("on-jump").description("Release a pulse from the ground where you take off.").defaultValue(true).build());
    private final Setting<Boolean> onLand = sgGeneral.add(new BoolSetting.Builder()
        .name("on-land").description("Release a pulse when your feet touch the ground.").defaultValue(true).build());
    private final Setting<Double> duration = sgGeneral.add(new DoubleSetting.Builder()
        .name("duration").description("Seconds for each pulse to unfold and dissolve.").defaultValue(1.15).range(0.2, 5).sliderRange(0.3, 3).build());
    private final Setting<Integer> maxCircles = sgGeneral.add(new IntSetting.Builder()
        .name("max-circles").description("Maximum stored pulses. Geometry also has a fixed per-frame budget.").defaultValue(16).range(1, 24).sliderRange(1, 24).build());
    private final Setting<Boolean> followAccent = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-accent").description("Use Ember's accent, including the current song's artwork color.").defaultValue(true).build());
    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Pulse color when accent matching is off.").defaultValue(new SettingColor(167, 139, 224, 235))
        .visible(() -> !followAccent.get()).build());
    private final Setting<Double> radius = sgShape.add(new DoubleSetting.Builder()
        .name("radius").description("Final radius of the pulse in blocks.").defaultValue(1.4).range(0.2, 10).sliderRange(0.3, 4).build());
    private final Setting<Double> rise = sgShape.add(new DoubleSetting.Builder()
        .name("rise").description("How far the pulse floats above the ground over its lifetime.").defaultValue(0.13).range(0, 3).sliderRange(0, 1).build());
    private final Setting<Integer> segments = sgShape.add(new IntSetting.Builder()
        .name("segments").description("Curve detail. Mesh-based ribbons keep their width at every resolution.").defaultValue(72).range(32, 128).sliderRange(32, 128).build());
    private final Setting<Integer> rings = sgShape.add(new IntSetting.Builder()
        .name("rings").description("Delayed pulses. Every trailing ring gets its complete fade-out.").defaultValue(2).range(1, 4).sliderRange(1, 4).build());
    private final Setting<Double> thickness = sgShape.add(new DoubleSetting.Builder()
        .name("thickness").description("Width of the luminous ribbon in blocks.").defaultValue(0.032).range(0.012, 0.15).sliderRange(0.012, 0.10).build());
    private final Setting<Double> glow = sgShape.add(new DoubleSetting.Builder()
        .name("glow").description("Soft fringe around the pulse and its orbiting highlights.").defaultValue(0.85).range(0, 1.5).sliderRange(0, 1.5).build());
    private final Setting<Double> rotation = sgShape.add(new DoubleSetting.Builder()
        .name("rotation-speed").description("Orbital rotation speed. Zero leaves the pattern stationary.").defaultValue(0.65).range(0, 2).sliderRange(0, 2).build());

    private record Circle(double x, double y, double z, long born, double seed) {}
    private final List<Circle> circles = new ArrayList<>();
    private final CelestialMesh mesh = new CelestialMesh(90000);
    private boolean wasOnGround;
    private Object world;
    private double groundX, groundY, groundZ;

    public JumpCircles() {
        super(Categories.Render, "jump-circles", "Celestial custom circles with soft ribbons, orbiting stars and accent matching.", "custom-circles", "custom circles", "circles");
    }
    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }
    private void reset() { circles.clear(); world = null; wasOnGround = false; }

    @EventHandler private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) { reset(); return; }
        boolean ground = mc.player.isOnGround();
        if (world != mc.world) {
            circles.clear(); world = mc.world; wasOnGround = ground;
            groundX = mc.player.getX(); groundY = mc.player.getY(); groundZ = mc.player.getZ();
            return;
        }
        if (wasOnGround && !ground && mc.player.getVelocity().y > 0.05 && onJump.get()) spawn(groundX, groundY, groundZ);
        else if (!wasOnGround && ground && onLand.get()) spawn(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (ground) { groundX = mc.player.getX(); groundY = mc.player.getY(); groundZ = mc.player.getZ(); }
        wasOnGround = ground;
    }

    private void spawn(double x, double y, double z) {
        long born = System.nanoTime();
        circles.add(new Circle(x, y, z, born, (born % 1_000_003) / 1_000_003.0));
        while (circles.size() > maxCircles.get()) circles.removeFirst();
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (mc.world != world) { circles.clear(); return; }
        long now = System.nanoTime();
        double life = duration.get(), total = life + (rings.get() - 1) * 0.11;
        circles.removeIf(c -> (now - c.born) / 1e9 >= total);
        if (circles.isEmpty()) return;
        var tint = followAccent.get() ? EmberPalette.accent() : color.get();
        int rgb = (tint.r << 16) | (tint.g << 8) | tint.b;
        // Newest effects get priority if the geometry budget is reached.
        int budget = 90000;
        for (int i = circles.size() - 1; i >= 0 && budget > 3000; i--) {
            var circle = circles.get(i);
            if (mc.player != null && mc.player.squaredDistanceTo(circle.x, circle.y, circle.z) > 4096) continue;
            double elapsed = (now - circle.born) / 1e9;
            mesh.clear();
            for (int ring = 0; ring < rings.get(); ring++) {
                if (!mesh.hasRoom(6000) || mesh.size >= budget - 6000) break;
                double age = (elapsed - ring * 0.11) / life;
                CelestialGeometry.circle(mesh, style.get(), age, radius.get() * (1 - ring * 0.10), rise.get(),
                    elapsed * rotation.get() * 2 + circle.seed * 4, thickness.get(), glow.get(), segments.get(), rgb,
                    tint.a / 255.0 * (1 - ring * 0.25), circle.seed);
            }
            CelestialRenderer.draw(mesh, event.depthRenderer, circle.x, circle.y, circle.z, 0, 0);
            budget -= mesh.size;
        }
    }
}
