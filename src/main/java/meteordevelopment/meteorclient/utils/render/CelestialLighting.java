package meteordevelopment.meteorclient.utils.render;

/** Shared, bounded lighting math. Changes sky illumination while leaving torch lighting independent. */
public final class CelestialLighting {
    public static final double BALANCED_DARKNESS = 0.30, BALANCED_TINT = 0.82, BALANCED_BRIGHTNESS = 1.20;
    public static final double SURFACE_GAIN = 0.55;
    private CelestialLighting() {}
    public static float skyFactor(float original, double darkness) {
        return original * (float) (1 - 0.72 * Math.clamp(darkness, 0, 1));
    }
    public static float gamma(float original, double darkness) {
        return Math.clamp(original, 0, 0.7f) * (float) (1 - 0.65 * Math.clamp(darkness, 0, 1));
    }
    public static float mixedGamma(float original, double darkness, double blend) {
        return original + (gamma(original, darkness) - original) * (float) Math.clamp(blend, 0, 1);
    }
    public static float illumination(float original, double darkness, double brightness, double blend, double reaction) {
        double custom = skyFactor(original, darkness) * Math.clamp(brightness, 0.2, 1.6) * Math.clamp(reaction, 0.4, 1.65);
        return (float) (original + (custom - original) * Math.clamp(blend, 0, 1));
    }
    public static float channel(float original, int accentChannel, int accentMaximum, float luminance, double strength) {
        // GUI colors are display RGB. Using those values directly as irradiance leaves
        // excessive green in blue accents, especially when multiplied by grass textures.
        // Decode their relative energy and keep a small neutral fill for material detail.
        float target = (float) (0.10 + 0.90 * relativeEnergy(accentChannel, accentMaximum)) * luminance;
        return original + (target - original) * (float) Math.clamp(strength, 0, 1);
    }
    public static float colorExposure(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, max = Math.max(r, Math.max(g, b));
        double energy = 0.2126 * relativeEnergy(r, max) + 0.7152 * relativeEnergy(g, max) + 0.0722 * relativeEnergy(b, max);
        // Deep blue must remain legible beside equally saturated green/yellow. Bound
        // compensation to retain headroom for torches and avoid a fullbright effect.
        return (float) Math.clamp(1 / Math.sqrt(0.10 + 0.90 * energy), 1, 1.4);
    }
    public static int surfaceColor(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, max = Math.max(r, Math.max(g, b));
        // Additive terrain light needs the chromatic component, without neutral fill.
        return (int) Math.round(relativeEnergy(r, max) * 255) << 16
            | (int) Math.round(relativeEnergy(g, max) * 255) << 8
            | (int) Math.round(relativeEnergy(b, max) * 255);
    }

    /** Reduce green biome dye before the normal lightmap is applied; preserve texture contrast. */
    public static int foliage(int original,double balance) {
        if(original==-1||balance<=0)return original;
        int r=original>>16&255,g=original>>8&255,b=original&255;
        if(g<=r*1.05||g<=b*1.05)return original;
        double amount=Math.clamp(balance,0,1),neutral=.2126*r+.7152*g+.0722*b;
        return original&0xFF000000|(int)Math.round(r+(neutral-r)*amount)<<16
            |(int)Math.round(g+(neutral-g)*amount)<<8|(int)Math.round(b+(neutral-b)*amount);
    }
    private static double relativeEnergy(int channel, int maximum) {
        return maximum <= 0 ? 0 : Math.pow(Math.clamp(channel / (double) maximum, 0, 1), 2.2);
    }
    public static float horizon(int accentChannel, double intensity) {
        // Same base as celestial_sky.frag at the horizon. No vanilla blue is mixed in.
        return (accentChannel / 255f * 0.27f + 0.018f) * (float) (0.35 + 0.65 * Math.clamp(intensity, 0, 1));
    }
}
