package meteordevelopment.meteorclient.gui.screens.menu;

/** Time-based easing and an analytic damped spring, independent of the render FPS. */
public final class MenuAnimation {
    private MenuAnimation() {}
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static double cubic(double t) { t = Math.clamp(t, 0, 1); return 1 - Math.pow(1 - t, 3); }
    public static double smooth(double value, double target, double dt, double seconds) {
        return lerp(value, target, -Math.expm1(-Math.max(0, dt) / Math.max(.001, seconds)));
    }
    public static final class Clock {
        private long previous;
        public double tick() {
            long now = System.nanoTime(); double dt = previous == 0 ? 0 : (now - previous) * 1e-9;
            previous = now; return Math.clamp(dt, 0, .1);
        }
        public void reset() { previous = 0; }
    }
    public static final class Spring {
        public double value, velocity;
        public Spring(double initial) { value = initial; }
        public void snap(double target) { value = target; velocity = 0; }
        public void kick(double impulse) { velocity += impulse; }
        public void update(double target, double dt, double frequency, double damping) {
            if (dt <= 0) return;
            // Exact underdamped solution; damping stays below one, no Euler instability.
            double omega = frequency * Math.PI * 2, zeta = Math.clamp(damping, .05, .99);
            double wd = omega * Math.sqrt(1 - zeta * zeta), offset = value - target;
            double c = Math.cos(wd * dt), s = Math.sin(wd * dt), decay = Math.exp(-zeta * omega * dt);
            double b = (velocity + zeta * omega * offset) / wd;
            value = target + decay * (offset * c + b * s);
            velocity = decay * ((-zeta * omega * offset + wd * b) * c + (-zeta * omega * b - wd * offset) * s);
        }
    }
}
