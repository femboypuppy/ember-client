package meteordevelopment.meteorclient.utils.misc;

/** Immutable media timestamp; monotonic interpolation freezes on pause and resets on seek. */
public record PlaybackClock(long positionMs, long durationMs, boolean playing, long sampledAtNanos) {
    public long positionAt(long nowNanos) {
        long elapsed = playing ? Math.max(0, (nowNanos - sampledAtNanos) / 1_000_000) : 0;
        long position = Math.max(0, positionMs + elapsed);
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
}
