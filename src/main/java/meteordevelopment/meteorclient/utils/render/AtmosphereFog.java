package meteordevelopment.meteorclient.utils.render;

/** Fog distances are world units, never multiplied by the user's chunk render distance. */
public final class AtmosphereFog {
    public record Range(float start, float end, float opacity) {}
    public static Range range(double start, double end, double opacity) {
        float near = (float) Math.clamp(start, 0, 512);
        return new Range(near, (float) Math.max(near + 8, Math.clamp(end, 16, 1024)), (float) Math.clamp(opacity, 0, 1));
    }
    private AtmosphereFog() {}
}
