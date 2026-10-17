package meteordevelopment.meteorclient.utils.render;

/** Shared, bounded lighting math. Changes sky illumination while leaving torch lighting independent. */
public final class CelestialLighting {
    private CelestialLighting() {}
    public static float skyFactor(float original, double darkness) {
        return original * (float) (1 - 0.8 * Math.clamp(darkness, 0, 1));
    }
    public static float gamma(float original, double darkness) {
        return Math.clamp(original, 0, 0.7f) * (float) (1 - 0.85 * Math.clamp(darkness, 0, 1));
    }
    public static float mixedGamma(float original, double darkness, double blend) {
        return original + (gamma(original, darkness) - original) * (float) Math.clamp(blend, 0, 1);
    }
    public static float illumination(float original, double darkness, double brightness, double blend, double reaction) {
        double custom = skyFactor(original, darkness) * Math.clamp(brightness, 0.2, 1.6) * Math.clamp(reaction, 0.4, 1.65);
        return (float) (original + (custom - original) * Math.clamp(blend, 0, 1));
    }
    public static float channel(float original, int accentChannel, int accentMaximum, float luminance, double strength) {
        float target = (0.08f + 0.92f * accentChannel / 255f) / (0.08f + 0.92f * accentMaximum / 255f) * luminance;
        return original + (target - original) * (float) Math.clamp(strength, 0, 1);
    }
    public static float horizon(int accentChannel, double intensity) {
        // Same base as celestial_sky.frag at the horizon. No vanilla blue is mixed in.
        return (accentChannel / 255f * 0.27f + 0.018f) * (float) (0.35 + 0.65 * Math.clamp(intensity, 0, 1));
    }
}
