package meteordevelopment.meteorclient.utils.render;

/** Resolves vanilla rain flags and plugin-driven intensity packets without mixing the two. */
public final class ServerRainState {
    public record Sample(boolean requested, float strength, boolean easing) {}
    private enum Source { WORLD, FLAG, LEVEL }
    private Source source = Source.WORLD;
    private boolean requested, lastWet, allowFade;
    private float level;
    private int stoppedTicks, fallingTicks;

    public void start() {
        source = Source.FLAG;
        requested = true;
        stoppedTicks = fallingTicks = 0;
    }

    public void stop() {
        // Duplicate clear-weather packets must not restart a fade indefinitely.
        if (source != Source.FLAG || requested) {
            allowFade = lastWet || source == Source.FLAG && requested || source == Source.LEVEL && level > .001f;
            stoppedTicks = 0;
        }
        source = Source.FLAG;
        requested = false;
        fallingTicks = 0;
    }

    public void level(float value) {
        if (!Float.isFinite(value)) return;
        value = Math.clamp(value, 0, 1);
        if (source == Source.LEVEL) {
            if (value < level - .001f) fallingTicks = 40;
            else if (value > level + .001f) fallingTicks = 0;
        } else fallingTicks = 0;
        source = Source.LEVEL;
        level = value;
        stoppedTicks = 0;
    }

    /** Called once per client tick. The native fade remains authoritative after start/stop flags. */
    public Sample sample(boolean worldRequested, float worldStrength) {
        float strength = Float.isFinite(worldStrength) ? Math.clamp(worldStrength, 0, 1) : 0;
        if (source == Source.LEVEL) {
            boolean wet = level > .001f;
            boolean easing = wet && fallingTicks > 0;
            if (fallingTicks > 0) fallingTicks--;
            return remember(wet, level, easing);
        }
        if (source == Source.FLAG) {
            // Vanilla fades by .01 per tick. Allow six seconds, then discard a stale
            // gradient repeatedly reset to one by duplicate server clear packets.
            if (!requested) {
                stoppedTicks = Math.min(121, stoppedTicks + 1);
                if (!allowFade || stoppedTicks > 120) strength = 0;
            }
            return remember(requested, strength, !requested && strength > .001f);
        }
        return remember(worldRequested, strength, !worldRequested && strength > .001f);
    }

    private Sample remember(boolean requested, float strength, boolean easing) {
        lastWet = requested || strength > .001f;
        return new Sample(requested, strength, easing);
    }

    public void reset() {
        source = Source.WORLD;
        requested = false;
        lastWet = allowFade = false;
        level = 0;
        stoppedTicks = fallingTicks = 0;
    }
}
