package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.HatGeometry;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;

/** Local head cosmetic. The feature renderer supplies the actual animated head transform. */
public final class Hats extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHologram = settings.createGroup("Hologram");
    private final SettingGroup sgMotion = settings.createGroup("Motion");
    private final Setting<HatGeometry.Style> style = sgGeneral.add(new EnumSetting.Builder<HatGeometry.Style>()
        .name("style").description("Holographic cone, floating Halo, jeweled Crown, bent Witch hat, or Top Hat.")
        .defaultValue(HatGeometry.Style.Hologram).build());
    private final Setting<Boolean> followAccent = sgGeneral.add(new BoolSetting.Builder()
        .name("follow-accent").description("Match the accent used by Wings and Ember's interface.").defaultValue(true).build());
    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Hat color when accent matching is off.").defaultValue(new SettingColor(167, 139, 224, 255))
        .visible(() -> !followAccent.get()).build());
    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size").description("Hat scale around the top of your head.").defaultValue(1).range(.65, 1.7).sliderRange(.65, 1.7).build());
    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height").description("Gap above your head. Raise this to clear a helmet.").defaultValue(.045).range(0, .5).sliderRange(0, .5).build());
    private final Setting<Boolean> hideWithHelmet = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-with-helmet").description("Hide the cosmetic while wearing something in the head slot.").defaultValue(false).build());
    private final Setting<Double> opacity = sgHologram.add(new DoubleSetting.Builder()
        .name("opacity").description("Strength of the transparent surfaces and luminous outlines.").defaultValue(.85).range(0, 1).sliderRange(0, 1).build());
    private final Setting<Double> glow = sgHologram.add(new DoubleSetting.Builder()
        .name("glow").description("Soft geometry glow around the edges.").defaultValue(.75).range(0, 1.5).sliderRange(0, 1.5).build());
    private final Setting<Boolean> scanlines = sgHologram.add(new BoolSetting.Builder()
        .name("scanlines").description("Fine luminous bands sweep through the hat.").defaultValue(true).build());
    private final Setting<Double> iridescence = sgHologram.add(new DoubleSetting.Builder()
        .name("color-shift").description("Pearlescent color shifting. Zero keeps the chosen accent color.").defaultValue(.3).range(0, 1).sliderRange(0, 1).build());
    private final Setting<Double> speed = sgMotion.add(new DoubleSetting.Builder()
        .name("animation-speed").description("Scan, shimmer and orbit speed. Zero freezes all animation.").defaultValue(.8).range(0, 2).sliderRange(0, 2).build());
    private final Setting<Double> bob = sgMotion.add(new DoubleSetting.Builder()
        .name("float").description("Gentle floating movement above the head, in blocks.").defaultValue(.015).range(0, .1).sliderRange(0, .1).build());
    private ClientWorld world;
    private double time, previousTime;

    public Hats() {
        super(Categories.Render, "hats", "Holographic hats, halos and crowns that follow your head. Visible locally in third person.", "hat", "holographic-hat", "cosmetic-hat", "halo", "crown");
    }
    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }
    private void reset() { world = null; previousTime = time = 0; }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (world != mc.world) { reset(); world = mc.world; }
        previousTime = time;
        if (mc.world != null) time += .05 * speed.get();
    }

    public boolean hideWithHelmet() { return hideWithHelmet.get(); }

    /** Immutable submission parameters: deferred commands never depend on later setting/model changes. */
    public record Appearance(HatGeometry.Style style, double time, double size, double height, double opacity,
                             double glow, boolean scanlines, double iridescence, int rgb) {}

    public Appearance appearance(float tickDelta) {
        var tint = followAccent.get() ? EmberPalette.accent() : color.get();
        double t = previousTime + (time - previousTime) * Math.clamp(tickDelta, 0, 1);
        return new Appearance(style.get(), t, size.get(), height.get() + bob.get() * (.5 + .5 * Math.sin(t * 1.4)),
            opacity.get() * tint.a / 255.0, glow.get(), scanlines.get(), iridescence.get(), tint.r << 16 | tint.g << 8 | tint.b);
    }
}
