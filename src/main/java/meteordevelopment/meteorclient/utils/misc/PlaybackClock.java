package meteordevelopment.meteorclient.utils.misc;

/** Immutable media timestamp; monotonic interpolation freezes on pause and resets on seek. */
public record PlaybackClock(long positionMs, long durationMs, boolean playing, long sampledAtNanos, long correctionMs) {
    public PlaybackClock(long positionMs, long durationMs, boolean playing, long sampledAtNanos) {
        this(positionMs, durationMs, playing, sampledAtNanos, 0);
    }
    /** Absorb small polling jitter while applying pauses and real seeks immediately. */
    public PlaybackClock corrected(long reportedMs, long duration, boolean isPlaying, long nowNanos) {
        long reported = Math.max(0, reportedMs);
        long predicted = positionAt(nowNanos);
        long drift = reported - predicted;
        if (playing && isPlaying && nowNanos - sampledAtNanos < 3_000_000_000L && Math.abs(drift) < 400) {
            // Slew toward the report over subsequent frames. Applying even a small negative
            // correction immediately can replay a word/line boundary on every Spotify poll.
            return new PlaybackClock(predicted, Math.max(0, duration), true, nowNanos, drift);
        }
        return new PlaybackClock(reported, Math.max(0, duration), isPlaying, nowNanos);
    }

    public long positionAt(long nowNanos) {
        long elapsed = playing ? Math.max(0, (nowNanos - sampledAtNanos) / 1_000_000) : 0;
        long correction = playing ? Math.round(correctionMs * (1 - Math.exp(-elapsed / 600.0))) : 0;
        long position = Math.max(0, positionMs + elapsed + correction);
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
}
