package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.renderer.text.ClippedGlyphConsumer;
import net.minecraft.client.render.VertexConsumer;

/** Records the production native-font consumer without needing a game or GPU context. */
public final class GlyphClipChecks {
    public static void run() {
        var clip = new ClippedGlyphConsumer();
        var output = new RecordingConsumer();
        clip.target(output);
        clip.bounds(3, 2, 7, 8);
        quad(clip, 0, 0, 10, 10);
        require(output.count == 4, "A clipped Unicode glyph must remain a complete quad");
        require(output.x[0] == 3 && output.y[0] == 2 && output.x[2] == 7 && output.y[2] == 8,
            "Unicode text must obey both horizontal and vertical lyric bounds");
        require(Math.abs(output.u[0] - 0.3) < 0.00001 && Math.abs(output.v[2] - 0.8) < 0.00001,
            "Partial syllable fills must crop UVs rather than squeeze or drop the character");
        require(output.color[0] == 0xE0AABBCC && output.light[0] == 0x00F000F0,
            "Native glyph colors and full-bright light data must survive clipping");
        int before = output.count;
        quad(clip, 11, 0, 12, 10);
        require(output.count == before, "Offscreen glyphs must emit no geometry");
        clip.bounds(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        quad(clip, 2, 1, 3, 4);
        require(output.count == 8 && output.x[4] == 2 && output.y[4] == 1, "Small high punctuation must not vanish at the lyric's left edge");
        clip.bounds(2.25, 0, 2.75, 10);
        quad(clip, 2, 1, 3, 4);
        require(output.count == 12 && output.x[8] == 2.25f && output.x[10] == 2.75f,
            "Apostrophes and dashes must support sub-glyph highlighting");
    }

    private static void quad(VertexConsumer consumer, float left, float top, float right, float bottom) {
        vertex(consumer, left, top, 0, 0); vertex(consumer, left, bottom, 0, 1);
        vertex(consumer, right, bottom, 1, 1); vertex(consumer, right, top, 1, 0);
    }
    private static void vertex(VertexConsumer consumer, float x, float y, float u, float v) {
        consumer.vertex(x, y, 0).color(0xE0AABBCC).texture(u, v).light(0x00F000F0);
    }
    private static class RecordingConsumer implements VertexConsumer {
        final float[] x = new float[16], y = new float[16], u = new float[16], v = new float[16];
        final int[] color = new int[16], light = new int[16];
        int count;
        public VertexConsumer vertex(float x, float y, float z) { this.x[count] = x; this.y[count] = y; return this; }
        public VertexConsumer color(int r, int g, int b, int a) { return color(a << 24 | r << 16 | g << 8 | b); }
        public VertexConsumer color(int color) { this.color[count] = color; return this; }
        public VertexConsumer texture(float u, float v) { this.u[count] = u; this.v[count] = v; return this; }
        public VertexConsumer overlay(int u, int v) { return this; }
        public VertexConsumer normal(float x, float y, float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
        public VertexConsumer light(int u, int v) { light[count++] = u | v << 16; return this; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
