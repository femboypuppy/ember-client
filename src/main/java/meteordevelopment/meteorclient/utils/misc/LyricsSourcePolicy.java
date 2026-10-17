package meteordevelopment.meteorclient.utils.misc;

import java.util.function.Supplier;

/** Only invoke enabled sources; the fallback cannot race a pending primary request. */
public final class LyricsSourcePolicy {
    public record Response(LyricsTimeline timeline, String status, boolean pending) {}
    private LyricsSourcePolicy() {}

    public static Response select(boolean spicyEnabled, boolean lrclibEnabled, Supplier<Response> spicy, Supplier<Response> lrclib,
                                  Response cachedFallback) {
        Response primary = spicyEnabled ? spicy.get() : new Response(null, "Spicy Lyrics is disabled", false);
        if (primary.timeline != null) return primary;
        if (primary.pending) return lrclibEnabled && cachedFallback != null && cachedFallback.timeline != null ? cachedFallback : primary;
        if (lrclibEnabled) return lrclib.get();
        return spicyEnabled ? primary : new Response(null, "Enable a source in Music & lyrics > Lyric sources", false);
    }
}
