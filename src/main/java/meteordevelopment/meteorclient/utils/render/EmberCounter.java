package meteordevelopment.meteorclient.utils.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Readouts that travel to their new value instead of snapping to it, so a framerate or a
 * memory figure counts up rather than flickering between numbers.
 *
 * Each tracked value is keyed by name. The first reading lands immediately - counting up from
 * zero when a widget first appears would be noise, not animation.
 */
public final class EmberCounter {
    private final Map<String, Double> values = new HashMap<>();
    private final EmberAnim.Clock clock = new EmberAnim.Clock();
    private float dt;

    /** Call once per frame before reading any values. */
    public void tick() {
        dt = clock.tick();
    }

    /**
     * @param tau seconds the value takes to close most of the gap; smaller is snappier
     */
    public double get(String key, double target, double tau) {
        Double current = values.get(key);

        if (current == null) {
            values.put(key, target);
            return target;
        }

        // A large jump is a teleport or a world change, not a value counting - going there
        // directly avoids a long meaningless scroll through numbers in between.
        double next = Math.abs(target - current) > 4096
            ? target
            : EmberAnim.approach(current, target, dt, tau);

        values.put(key, next);
        return next;
    }
}
