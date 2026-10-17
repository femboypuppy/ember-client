package meteordevelopment.meteorclient.utils.misc;

/** Immutable media timestamp; monotonic interpolation freezes on pause and resets on seek. */
public record PlaybackClock(long positionMs, long durationMs, boolean playing, long sampledAtNanos) {
    /** Absorb small polling jitter while applying pauses and real seeks immediately. */
    public PlaybackClock corrected(long reportedMs, long duration, boolean isPlaying, long nowNanos) {
        long reported = Math.max(0, reportedMs);
        long predicted = positionAt(nowNanos);
        long drift = reported - predicted;
        if (playing && isPlaying && nowNanos - sampledAtNanos < 3_000_000_000L && Math.abs(drift) < 400) {
            reported = predicted + Math.round(drift * 0.18);
        }
        return new PlaybackClock(reported, Math.max(0, duration), isPlaying, nowNanos);
    }

    public long positionAt(long nowNanos) {
        long elapsed = playing ? Math.max(0, (nowNanos - sampledAtNanos) / 1_000_000) : 0;
        long position = Math.max(0, positionMs + elapsed);
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
}
