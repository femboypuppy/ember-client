/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */
package meteordevelopment.meteorclient.utils.render;

/** A short, interruptible glide in hotbar-relative pixels, independent of the frame rate. */
public final class HotbarSelectionMotion {
    private static final double DURATION = 160_000_000.0;
    private boolean initialized;
    private double from, target;
    private long started;

    public double position(double next, long now, boolean animate) {
        if (!initialized || !animate) {
            initialized = true;
            from = target = next;
            started = now;
            return next;
        }
        double current = sample(now);
        if (next != target) {
            from = current;
            target = next;
            started = now;
        }
        return current;
    }

    private double sample(long now) {
        double t = Math.clamp((now - started) / DURATION, 0, 1);
        return from + (target - from) * (1 - Math.pow(1 - t, 3));
    }

    public void reset() { initialized = false; }
}
