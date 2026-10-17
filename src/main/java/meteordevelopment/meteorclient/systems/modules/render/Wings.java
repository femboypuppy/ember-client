package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.CelestialGeometry;
import meteordevelopment.meteorclient.utils.render.CelestialMesh;
import meteordevelopment.meteorclient.utils.render.CelestialRenderer;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.MathHelper;

/** Local cosmetic, attached to the body's interpolated pose rather than the camera. */
public final class Wings extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMotion = settings.createGroup("Motion");
    private final Setting<CelestialGeometry.WingStyle> style = sgGeneral.add(new EnumSetting.Builder<CelestialGeometry.WingStyle>()
        .name("style").description("Astral starlight, pearlescent Seraph feathers, or floating Prism blades.")
        .defaultValue(CelestialGeometry.WingStyle.Astral).build());
    private final Setting<Boolean> followAccent = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-accent").description("Follow Ember's accent, including the current song artwork color.").defaultValue(true).build());
    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Wing tint when accent matching is off.").defaultValue(new SettingColor(167, 139, 224, 255))
        .visible(() -> !followAccent.get()).build());
    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Scale the wings around the shoulder attachment.").defaultValue(1.0).range(0.45, 1.8).sliderRange(0.5, 1.5).build());
    private final Setting<Double> opacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("opacity").description("Feather and crystal translucency.").defaultValue(0.85).range(0.1, 1).sliderRange(0.1, 1).build());
    private final Setting<Double> glow = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow").description("Soft light around each feather, with no post-processing required.").defaultValue(0.75).range(0, 1.5).sliderRange(0, 1.5).build());
    private final Setting<Integer> feathers = sgGeneral.add(new IntSetting.Builder()
        .name("feathers").description("Feathers per wing. Prism uses seven separate blades.").defaultValue(14).range(8, 22).sliderRange(8, 22)
        .visible(() -> style.get() != CelestialGeometry.WingStyle.Prism).build());
    private final Setting<Boolean> firstPerson = sgGeneral.add(new BoolSetting.Builder()
        .name("first-person").description("Also draw your wings in first person. Usually best viewed with F5.").defaultValue(false).build());
    private final Setting<Double> speed = sgMotion.add(new DoubleSetting.Builder()
        .name("animation-speed").description("Flutter and twinkle speed. Zero holds a still pose.").defaultValue(0.8).range(0, 2).sliderRange(0, 2).build());
    private final Setting<Double> flutter = sgMotion.add(new DoubleSetting.Builder()
        .name("flutter").description("Subtle movement through the feather tips.").defaultValue(0.7).range(0, 1.5).sliderRange(0, 1.5).build());
    private final Setting<Double> spread = sgMotion.add(new DoubleSetting.Builder()
        .name("spread").description("How widely the wings rest when standing.").defaultValue(0.93).range(0.15, 1).sliderRange(0.15, 1).build());
    private final Setting<Boolean> reactive = sgMotion.add(new BoolSetting.Builder()
        .name("react-to-movement").description("Fold smoothly when sneaking or gliding, and flare when sprinting.").defaultValue(true).build());

    private final CelestialMesh mesh = new CelestialMesh(18000);
    private double time, open, fade;
    private float yaw;
    private boolean initialized;

    public Wings() { super(Categories.Render, "wings", "Animated celestial wings with feathers, starlight and accent matching.", "cosmetic-wings", "angel-wings"); }
    @Override public void onActivate() { time = fade = 0; initialized = false; }

    @EventHandler private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || mc.player.isSpectator() || mc.player.isInvisible()) return;
        double dt = Math.clamp(event.frameTime, 0, 0.1);
        time += dt * speed.get();
        boolean glide = mc.player.isGliding(), crouch = mc.player.isSneaking();
        double target = spread.get();
        if (reactive.get()) target *= glide ? 0.42 : crouch ? 0.70 : mc.player.isSprinting() ? 1.07 : 1;
        if (!initialized) { yaw = mc.player.bodyYaw; open = target; initialized = true; }
        yaw += MathHelper.wrapDegrees(mc.player.bodyYaw - yaw) * (float) (1 - Math.exp(-dt / 0.075));
        open = CelestialGeometry.approach(open, target, dt, 0.19);
        fade = CelestialGeometry.approach(fade, 1, dt, 0.18);
        if (!firstPerson.get() && mc.options.getPerspective().isFirstPerson() && mc.getCameraEntity() == mc.player) return;
        var tint = followAccent.get() ? EmberPalette.accent() : color.get();
        int rgb = (tint.r << 16) | (tint.g << 8) | tint.b;
        mesh.clear();
        CelestialGeometry.wings(mesh, style.get(), time, size.get(), Math.clamp(open, 0, 1), flutter.get(), glow.get(), feathers.get(), rgb, opacity.get() * fade * tint.a / 255.0);
        var pos = mc.player.getLerpedPos(event.tickDelta);
        double pitch = glide ? Math.toRadians(90 + mc.player.getPitch()) : crouch ? 0.20 : 0;
        CelestialRenderer.draw(mesh, event.depthRenderer, pos.x, pos.y + (glide ? -0.82 : crouch ? -0.22 : 0), pos.z, Math.toRadians(yaw), pitch);
    }
}
