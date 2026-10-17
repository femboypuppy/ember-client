package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dir;

/** Bounded, shared visual treatment for block and storage ESP. */
public final class EspStyle {
    public enum Texture { None, Etched, Scanlines }
    public final Setting<Double> roundness;
    public final Setting<Texture> texture;
    public final Setting<Double> textureStrength;
    public final Setting<Boolean> accent;
    public final Setting<Integer> detailBudget;
    public final Setting<Double> detailRange;
    private int detailed;

    public EspStyle(Settings settings) {
        this(settings, () -> true, () -> true);
    }

    public EspStyle(Settings settings, java.util.function.BooleanSupplier geometryEnabled, java.util.function.BooleanSupplier textureEnabled) {
        var group = settings.createGroup("Ember Surface");
        roundness = group.add(new DoubleSetting.Builder().name("corner-roundness").description("Rounded 3D corners. Zero restores square boxes.")
            .defaultValue(0.10).range(0, 0.2).sliderRange(0, 0.2).visible(geometryEnabled::getAsBoolean).build());
        texture = group.add(new EnumSetting.Builder<Texture>().name("surface-texture").description("Subtle etched or horizontal detail on filled boxes.")
            .defaultValue(Texture.Etched).visible(textureEnabled::getAsBoolean).build());
        textureStrength = group.add(new DoubleSetting.Builder().name("texture-strength").description("Opacity of the surface detail.")
            .defaultValue(0.22).range(0, 1).sliderRange(0, 1).visible(() -> textureEnabled.getAsBoolean() && texture.get() != Texture.None).build());
        accent = group.add(new BoolSetting.Builder().name("use-ember-accent").description("Use the current Ember accent while preserving each configured opacity.")
            .defaultValue(false).build());
        detailRange = group.add(new DoubleSetting.Builder().name("detail-range").description("Distance for rounded geometry and surface detail; farther boxes keep a lightweight outline.")
            .defaultValue(48).range(8, 128).sliderRange(8, 96).visible(geometryEnabled::getAsBoolean).build());
        detailBudget = group.add(new IntSetting.Builder().name("detailed-box-limit").description("Maximum detailed boxes per frame, keeping large finds inexpensive.")
            .defaultValue(64).range(8, 256).sliderRange(8, 128).visible(geometryEnabled::getAsBoolean).build());
    }

    public void beginFrame() { detailed = 0; RoundedBoxMesh.prepare(roundness.get()); }
    public Color tint(Color original) {
        if (!accent.get()) return original;
        Color c = EmberPalette.accent(); return new Color(c.r, c.g, c.b, original.a);
    }

    public void box(Renderer3D r, double x, double y, double z, double x2, double y2, double z2, Color side, Color line, ShapeMode mode, int exclude) {
        side = tint(side); line = tint(line);
        double radius = roundness.get();
        boolean detail = detailed < detailBudget.get()
            && PlayerUtils.squaredDistanceTo((x + x2) / 2, (y + y2) / 2, (z + z2) / 2) <= detailRange.get() * detailRange.get();
        if (!detail) { r.box(x, y, z, x2, y2, z2, side, line, mode, exclude); return; }
        double w = x2 - x, h = y2 - y, d = z2 - z;
        int joined = ((exclude & Dir.WEST) != 0 ? RoundedBoxMesh.WEST : 0) | ((exclude & Dir.EAST) != 0 ? RoundedBoxMesh.EAST : 0)
            | ((exclude & Dir.DOWN) != 0 ? RoundedBoxMesh.DOWN : 0) | ((exclude & Dir.UP) != 0 ? RoundedBoxMesh.UP : 0)
            | ((exclude & Dir.NORTH) != 0 ? RoundedBoxMesh.NORTH : 0) | ((exclude & Dir.SOUTH) != 0 ? RoundedBoxMesh.SOUTH : 0);
        var mesh = RoundedBoxMesh.peek(radius, joined);
        if (mesh == null) { r.box(x, y, z, x2, y2, z2, side, line, mode, exclude); return; }
        detailed++;
        if (mode.sides() && side.a > 0) for (var face : mesh.faces) {
            double light = 0.65 + Math.max(0, face.ny()) * 0.27 + Math.abs(face.nz()) * 0.08;
            Color c = new Color((int) (side.r * light), (int) (side.g * light), (int) (side.b * light), side.a);
            double[] v = face.vertices();
            r.quad(x + v[0] * w, y + v[1] * h, z + v[2] * d, x + v[3] * w, y + v[4] * h, z + v[5] * d,
                x + v[6] * w, y + v[7] * h, z + v[8] * d, x + v[9] * w, y + v[10] * h, z + v[11] * d, c);
        }
        if (mode.lines()) for (double[] edge : mesh.edges)
            r.line(x + edge[0] * w, y + edge[1] * h, z + edge[2] * d, x + edge[3] * w, y + edge[4] * h, z + edge[5] * d, line);
        if (mode.sides() && texture.get() != Texture.None && textureStrength.get() > 0) {
            Color c = new Color(line.r, line.g, line.b, (int) (line.a * textureStrength.get() * 0.27));
            double inset = radius + 0.025;
            for (int i = 1; i < 5; i++) {
                double t = inset + (1 - 2 * inset) * i / 5;
                if ((exclude & Dir.NORTH) == 0) r.line(x + inset * w, y + t * h, z - 0.001, x2 - inset * w, y + t * h, z - 0.001, c);
                if ((exclude & Dir.SOUTH) == 0) r.line(x + inset * w, y + t * h, z2 + 0.001, x2 - inset * w, y + t * h, z2 + 0.001, c);
                if ((exclude & Dir.WEST) == 0) r.line(x - 0.001, y + t * h, z + inset * d, x - 0.001, y + t * h, z2 - inset * d, c);
                if ((exclude & Dir.EAST) == 0) r.line(x2 + 0.001, y + t * h, z + inset * d, x2 + 0.001, y + t * h, z2 - inset * d, c);
                if (texture.get() == Texture.Etched && (exclude & Dir.UP) == 0) {
                    r.line(x + t * w, y2 + 0.001, z + inset * d, x + t * w, y2 + 0.001, z2 - inset * d, c);
                    r.line(x + inset * w, y2 + 0.001, z + t * d, x2 - inset * w, y2 + 0.001, z + t * d, c);
                }
            }
        }
    }
}
