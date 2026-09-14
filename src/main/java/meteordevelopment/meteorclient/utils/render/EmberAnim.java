package meteordevelopment.meteorclient.utils.render;

/**
 * Animation helpers driven by real time. Game tick deltas are tiny per frame and stop
 * entirely while singleplayer is paused behind a screen, which made UI animations crawl.
 */
public final class EmberAnim {
    private EmberAnim() {
    }

    /**
     * Eases toward the target, independent of frame rate.
     *
     * @param tau time constant in seconds; the value is ~95% of the way after 3 * tau
     */
    public static float approach(float current, float target, double dtSeconds, double tau) {
        if (tau <= 0) return target;

        float next = current + (target - current) * (float) (1 - Math.exp(-dtSeconds / tau));
        return Math.abs(next - target) < 0.002f ? target : next;
    }

    /** Same easing for doubles, for things like scroll offsets that are not 0..1 amounts. */
    public static double approach(double current, double target, double dtSeconds, double tau) {
        if (tau <= 0) return target;

        double next = current + (target - current) * (1 - Math.exp(-dtSeconds / tau));
        return Math.abs(next - target) < 0.01 ? target : next;
    }

    /**
     * Overshoots the target and settles back. Applied to an already-eased 0..1 amount it
     * gives a switch knob a little spring, which is most of what makes a control feel
     * physical rather than merely animated.
     */
    public static double backOut(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        double u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** Cubic ease-out: fast departure, soft landing. */
    public static double easeOut(double t) {
        double u = 1 - t;
        return 1 - u * u * u;
    }

    /** Seconds between frames, capped so a hitch doesn't jump an animation to its end. */
    public static final class Clock {
        private long last = -1;

        public float tick() {
            long now = System.nanoTime();
            float dt = last < 0 ? 0f : (now - last) / 1_000_000_000f;
            last = now;
            return Math.min(dt, 0.1f);
        }
    }
}
