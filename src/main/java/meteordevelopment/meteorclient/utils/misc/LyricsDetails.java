package meteordevelopment.meteorclient.utils.misc;

import java.util.Objects;

/** Track-scoped five-second introduction and a reversible, time-based details reveal. */
public final class LyricsDetails {
    private String track;
    private long started, lastPosition;
    private double reveal;

    public double reveal() { return reveal; }
    public double elapsedSeconds(long now) { return Math.max(0, (now - started) / 1e9); }

    public double update(String key, long positionMs, long now, double dt, boolean hovered, boolean always, boolean animate) {
        // A repeat (or a seek to the beginning) also gets a fresh introduction. Ordinary
        // lyric boundaries, pauses, timing offsets and provider responses never restart it.
        if (!Objects.equals(track, key) || lastPosition > 5000 && positionMs <= 1000) {
            track = key;
            started = now;
        }
        lastPosition = positionMs;
        double target = always || hovered || elapsedSeconds(now) < 5 ? 1 : 0;
        reveal = animate ? target + (reveal - target) * Math.exp(-Math.clamp(dt, 0, 0.1) / 0.10) : target;
        if (Math.abs(reveal - target) < 0.001) reveal = target;
        return reveal;
    }
}
