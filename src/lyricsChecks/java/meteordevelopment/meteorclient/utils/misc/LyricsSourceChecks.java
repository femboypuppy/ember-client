package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class LyricsSourceChecks {
    static void run() {
        var timeline = new LyricsTimeline("Line", List.of(new LyricsTimeline.Line("test", 0, 1, List.of())), List.of(), List.of());
        var ready = new LyricsSourcePolicy.Response(timeline, "primary", false);
        var fallback = new LyricsSourcePolicy.Response(timeline, "fallback", false);
        var pending = new LyricsSourcePolicy.Response(null, "loading", true);
        var missing = new LyricsSourcePolicy.Response(null, "unavailable", false);
        AtomicInteger primaryCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
        Supplier<LyricsSourcePolicy.Response> primary = () -> { primaryCalls.incrementAndGet(); return ready; };
        Supplier<LyricsSourcePolicy.Response> secondary = () -> { fallbackCalls.incrementAndGet(); return fallback; };
        require(LyricsSourcePolicy.select(false, false, primary, secondary, fallback).timeline() == null, "Both disabled means no lyrics, even if cached");
        require(primaryCalls.get() == 0 && fallbackCalls.get() == 0, "Disabled providers must not be called");
        require(LyricsSourcePolicy.select(false, true, primary, secondary, null) == fallback, "LRCLIB must work alone");
        require(primaryCalls.get() == 0 && fallbackCalls.get() == 1, "LRCLIB-only must not start Spicy or the ID resolver");
        require(LyricsSourcePolicy.select(true, true, primary, secondary, fallback) == ready, "Ready Spicy must always take priority");
        require(fallbackCalls.get() == 1, "A successful primary must not launch fallback requests");
        require(LyricsSourcePolicy.select(true, true, () -> pending, secondary, null) == pending, "First load must wait for the primary");
        require(fallbackCalls.get() == 1, "A pending primary must not race a new fallback request");
        require(LyricsSourcePolicy.select(true, true, () -> pending, secondary, fallback) == fallback, "A primary retry must not blank an existing fallback");
        require(LyricsSourcePolicy.select(true, false, () -> pending, secondary, fallback) == pending, "Turning LRCLIB off must also hide its cached lyrics");
        require(LyricsSourcePolicy.select(true, true, () -> missing, secondary, null) == fallback, "Unavailable primary must use the enabled fallback");
        require(LyricsSourcePolicy.select(true, false, () -> missing, secondary, fallback) == missing, "Spicy-only must retain its own unavailable status");

        var query = new LrclibLyrics.Query("Synthetic Song", "Example Artist", 180000);
        String id = "0123456789ABCDEFGHIJKL";
        var candidates = SpotifyTrackResolver.candidates(JsonParser.parseString("""
            [{"track_name":"Other Song","artist_name":"Example Artist","spotify_track_ids":["wrong"]},
             {"track_name":"Synthetic Song","artist_name":"Example Artist","spotify_track_ids":["bad/id", "%s", "%s"]}]
            """.formatted(id, id)), query);
        require(candidates.equals(List.of(id)), "Candidates must match metadata, be valid base62 IDs and be deduplicated");
        String fixture = """
            <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":
            {"type":"track","id":"%s","name":"Synthetic Song","artists":[{"name":"Example Artist"}],"duration":180000,"isMusicVideo":false}
            }}}}}</script>
            """.formatted(id);
        require(SpotifyTrackResolver.verifiedId(fixture, id, query).equals(id), "Public metadata must verify the ID, recording and duration");
        require(SpotifyTrackResolver.verifiedId(fixture.replace("180000", "195000"), id, query).isEmpty(), "Different recording duration must be rejected");
        require(SpotifyTrackResolver.verifiedId(fixture.replace("Synthetic Song", "Synthetic Song (Live)"), id, query).isEmpty(), "Live versions cannot replace the requested recording");
        require(SpotifyTrackResolver.verifiedId(fixture.replace("Example Artist", "Someone Else"), id, query).isEmpty(), "Wrong artists must be rejected");
        require(SpotifyTrackResolver.verifiedId(fixture.replace("false", "true"), id, query).isEmpty(), "Music video timing must not be treated as the audio recording");
        require(SpotifyTrackResolver.verifiedId(fixture, "ZZ23456789ABCDEFGHIJKL", query).isEmpty(), "Metadata for another ID cannot validate the candidate");
        require(SpotifyTrackResolver.verifiedId("<html>changed schema</html>", id, query).isEmpty(), "Missing public metadata must safely fall back");
        require(SpotifyTrackResolver.verifiedId(fixture.replace("{\"props\"", "{broken"), id, query).isEmpty(), "Malformed metadata must safely fall back");
        System.out.println("Lyric sources passed: independent toggles, primary priority, retry continuity and verified Spotify metadata matching.");
    }
    static void liveResolver() {
        var playback = new MusicPlayback.Playback("smoke-test", "", "Blinding Lights", "The Weeknd", "", "Test", "",
            new PlaybackClock(0, 200040, false, System.nanoTime()));
        long start = System.nanoTime();
        SpotifyTrackResolver.Lookup result;
        do {
            result = SpotifyTrackResolver.update(playback);
            if (!result.pending()) break;
            require(System.nanoTime() - start < 12_000_000_000L, "Live ID matching must finish within its time budget");
            try { Thread.sleep(50); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        } while (true);
        require(!result.id().isEmpty(), "Public metadata lookup failed: " + result.status());
        require(SpotifyTrackResolver.update(playback) == result, "A successful match must come straight from cache");
        System.out.printf("Live metadata resolver passed: verified Spotify ID %s in %.2f seconds, then served from cache.%n",
            result.id(), (System.nanoTime() - start) / 1_000_000_000.0);
        SpotifyTrackResolver.stop();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
