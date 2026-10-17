package meteordevelopment.meteorclient.utils.misc;

import java.util.function.Supplier;

/** Only invoke enabled sources; the fallback cannot race a pending primary request. */
public final class LyricsSourcePolicy {
    public record Response(LyricsTimeline timeline, String status, boolean pending) {}
    private LyricsSourcePolicy() {}

    /** Keep the loaded primary for one recording; provider retries cannot downgrade its timing. */
    public static final class Session {
        private String track = "", cacheKey = "";
        private long duration;
        private boolean romanized;
        private Response primary;

        public String begin(String key, long durationMs, boolean romanization) {
            boolean changed = !track.equals(key) || duration > 0 && durationMs > 0 && Math.abs(duration - durationMs) > 3000;
            if (changed || romanized != romanization) primary = null;
            if (changed || duration == 0 && durationMs > 0) {
                track = key; duration = durationMs;
                cacheKey = key + '\u0001' + durationMs;
            }
            romanized = romanization;
            return cacheKey;
        }
        public void reset() { track = cacheKey = ""; duration = 0; primary = null; }
        public Response select(boolean spicyEnabled, boolean lrclibEnabled, Supplier<Response> spicy, Supplier<Response> lrclib,
                               Response cachedFallback) {
            if (!spicyEnabled) primary = null;
            if (primary != null) return primary;
            return LyricsSourcePolicy.select(spicyEnabled, lrclibEnabled, () -> {
                Response response = spicy.get();
                if (response.timeline != null) primary = response;
                return response;
            }, lrclib, cachedFallback);
        }
    }

    public static Response select(boolean spicyEnabled, boolean lrclibEnabled, Supplier<Response> spicy, Supplier<Response> lrclib,
                                  Response cachedFallback) {
        Response primary = spicyEnabled ? spicy.get() : new Response(null, "Spicy Lyrics is disabled", false);
        if (primary.timeline != null) return primary;
        if (primary.pending) return lrclibEnabled && cachedFallback != null && cachedFallback.timeline != null ? cachedFallback : primary;
        if (lrclibEnabled) return lrclib.get();
        return spicyEnabled ? primary : new Response(null, "Enable a source in Music & lyrics > Lyric sources", false);
    }
}
