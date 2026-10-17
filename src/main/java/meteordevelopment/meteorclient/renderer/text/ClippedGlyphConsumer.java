package meteordevelopment.meteorclient.renderer.text;

import net.minecraft.client.render.VertexConsumer;

/** Clips native font quads and their UVs together, without altering the HUD's scissor state. */
public final class ClippedGlyphConsumer implements VertexConsumer {
    private final float[] xs = new float[4], ys = new float[4], zs = new float[4], us = new float[4], vs = new float[4];
    private final int[] colors = new int[4], lights = new int[4];
    private VertexConsumer target;
    private double left, top, right, bottom;
    private int index;

    public void bounds(double left, double top, double right, double bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public VertexConsumer target(VertexConsumer target) { this.target = target; return this; }
    @Override public VertexConsumer vertex(float x, float y, float z) { xs[index] = x; ys[index] = y; zs[index] = z; return this; }
    @Override public VertexConsumer color(int r, int g, int b, int a) { return color(a << 24 | r << 16 | g << 8 | b); }
    @Override public VertexConsumer color(int argb) { colors[index] = argb; return this; }
    @Override public VertexConsumer texture(float u, float v) { us[index] = u; vs[index] = v; return this; }
    @Override public VertexConsumer overlay(int u, int v) { return this; }
    @Override public VertexConsumer normal(float x, float y, float z) { return this; }
    @Override public VertexConsumer lineWidth(float width) { return this; }
    // Native text supplies light last for every vertex, including shadow and effect quads.
    @Override public VertexConsumer light(int u, int v) {
        lights[index] = u & 0xFFFF | v << 16;
        if (++index == 4) { emit(); index = 0; }
        return this;
    }

    private void emit() {
        double minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        if (Math.min(maxX, right) <= Math.max(minX, left) || Math.min(maxY, bottom) <= Math.max(minY, top)) return;
        double dx1 = xs[1] - xs[0], dy1 = ys[1] - ys[0], dx3 = xs[3] - xs[0], dy3 = ys[3] - ys[0];
        double determinant = dx1 * dy3 - dx3 * dy1;
        if (Math.abs(determinant) < 1e-12) return;
        for (int i = 0; i < 4; i++) {
            float x = (float) Math.clamp(xs[i], left, right), y = (float) Math.clamp(ys[i], top, bottom);
            double dx = x - xs[0], dy = y - ys[0];
            double a = (dx * dy3 - dy * dx3) / determinant, b = (dx1 * dy - dy1 * dx) / determinant;
            float u = (float) (us[0] + a * (us[1] - us[0]) + b * (us[3] - us[0]));
            float v = (float) (vs[0] + a * (vs[1] - vs[0]) + b * (vs[3] - vs[0]));
            target.vertex(x, y, zs[i]).color(colors[i]).texture(u, v).light(lights[i]);
        }
    }
}
