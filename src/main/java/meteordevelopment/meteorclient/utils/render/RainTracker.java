package meteordevelopment.meteorclient.utils.render;

/** Observed rain transitions; the server's stop flag precedes the visible fade-out. */
public final class RainTracker {
    public enum Change {
        None,
        Started,
        Ending,
        Stopped
    }

    private boolean initialized, raining, ending;

    public Change update(boolean requested, float strength) {
        boolean wet = requested || Float.isFinite(strength) && strength > .001f;
        boolean easing = wet && !requested;
        Change change = Change.None;
        if (initialized) {
            if (!raining && wet) change = Change.Started;
            else if (raining && !wet) change = Change.Stopped;
            else if (easing && !ending) change = Change.Ending;
        }
        initialized = true;
        raining = wet;
        ending = easing;
        return change;
    }

    public boolean raining() {
        return raining;
    }

    public boolean ending() {
        return ending;
    }

    public void reset() {
        initialized = raining = ending = false;
    }
}
