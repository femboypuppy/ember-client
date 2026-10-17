package meteordevelopment.meteorclient.utils.misc;

/** Time-based bass envelope. Quiet audio and stale samples decay to zero without a synthetic beat. */
public final class BassEnvelope {
    private double value;
    public double update(double bass, double sensitivity, double release, double dt) {
        if (!Double.isFinite(bass)) bass = 0;
        double target = 1 - Math.exp(-Math.max(0, bass - 0.002) * Math.clamp(sensitivity, 0.1, 4) * 18);
        double seconds = target > value ? 0.035 : Math.clamp(release, 0.08, 1.5);
        value += (target - value) * (1 - Math.exp(-Math.clamp(dt, 0, 1) / seconds));
        if (value < 0.00001) value = 0;
        return value;
    }
    public void reset() { value = 0; }
    public static double exposure(double level, double strength, boolean glow) {
        return 1 + Math.clamp(level, 0, 1) * Math.clamp(strength, 0, 1) * (glow ? 0.65 : -0.6);
    }
}
