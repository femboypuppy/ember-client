package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.systems.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fetch once per track off the render thread, with bounded caching and stale-response guards. */
public final class SpicyLyricsService {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Ember-Lyrics"); t.setDaemon(true); return t;
    });
    private record Versions(LyricsTimeline original, LyricsTimeline romanized) {
        LyricsTimeline select(boolean romanization) { return romanization ? romanized : original; }
    }
    private static final Map<String, Versions> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Versions> entry) { return size() > 32; }
    };
    private static volatile Result result = new Result("", null, "Connect Spotify in Lyrics settings");
    private static String wanted = "", keyUsed = "";
    private static boolean busy;
    private static boolean romanizationUsed;
    private static long retryAt, globallyLimitedUntil;
    public record Result(String trackId, LyricsTimeline timeline, String status, boolean pending) {
        public Result(String trackId, LyricsTimeline timeline, String status) { this(trackId, timeline, status, false); }
    }

    private SpicyLyricsService() {}

    public static synchronized Result update() {
        var playback = SpotifyPlayback.current();
        return update(playback == null ? "" : playback.trackId());
    }

    public static synchronized Result update(String id) {
        Config config = Config.get();
        if (config == null || !config.lyricsEnabled.get()) return new Result("", null, "");
        if (!config.spicyLyricsEnabled.get()) { wanted = ""; return new Result(id, null, "Spicy Lyrics is disabled"); }
        String key = config.spicyLyricsKey.get().trim();
        if (!key.startsWith("sl_pk_") || key.length() < 12) {
            wanted = "";
            return result = new Result("", null, "Add a Spicy Lyrics client key in Lyrics settings");
        }
        if (!id.matches("[A-Za-z0-9]{22}")) {
            wanted = "";
            return result = new Result("", null, "No matching Spotify track ID");
        }
        if (!key.equals(keyUsed)) { keyUsed = key; retryAt = 0; wanted = ""; }
        boolean romanization = config.lyricsRomanization.get();
        if (!id.equals(wanted) || romanizationUsed != romanization) {
            wanted = id; retryAt = 0;
            romanizationUsed = romanization;
            Versions versions = CACHE.get(id);
            LyricsTimeline cached = versions == null ? null : versions.select(romanization);
            result = new Result(id, cached, cached == null ? "Loading lyrics" : "");
        }
        long now = System.currentTimeMillis();
        if (result.timeline != null) return result;
        if (now < globallyLimitedUntil) return result = new Result(id, null, "Spicy Lyrics rate limited; waiting to retry");
        if (busy) return result = new Result(id, null, "Loading Spicy Lyrics", true);
        if (now < retryAt) return result;
        busy = true;
        result = new Result(id, null, "Loading Spicy Lyrics", true);
        WORKER.execute(() -> fetch(id, key));
        return result;
    }

    private static void fetch(String id, String key) {
        Versions versions = null;
        String status = "Lyrics unavailable; retrying shortly";
        long retry = 30_000;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spicylyrics.org/v1/lyrics/" + id))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").header("User-Agent", "EmberClient-Lyrics/1.0").GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, ignored -> MusicLyricsService.limitedBody());
            {
                int code = response.statusCode();
                if (code == 200) {
                    byte[] bytes = response.body();
                    var envelope = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!envelope.has("Body") || !envelope.getAsJsonObject("Body").has("id")
                        || !id.equals(envelope.getAsJsonObject("Body").get("id").getAsString())) {
                        throw new IllegalArgumentException("Lyrics belong to another track");
                    }
                    versions = new Versions(LyricsTimeline.parse(envelope), LyricsTimeline.parse(envelope, true));
                    status = versions.original.lines().isEmpty() && versions.original.background().isEmpty() ? "No timed lyrics for this song" : "";
                } else if (code == 401 || code == 403) {
                    status = "Check your Spicy Lyrics client key and native-client access"; retry = 300_000;
                } else if (code == 404) {
                    status = "No lyrics available for this song"; retry = 600_000;
                } else if (code == 429) {
                    status = "Lyrics rate limit reached; waiting to retry";
                    try { retry = Math.max(1000, (long) (Double.parseDouble(response.headers().firstValue("Retry-After")
                        .orElse(response.headers().firstValue("RateLimit-Reset").orElse("60"))) * 1000)); }
                    catch (NumberFormatException ignored) { retry = 60_000; }
                    synchronized (SpicyLyricsService.class) { globallyLimitedUntil = System.currentTimeMillis() + retry; }
                }
            }
        } catch (Exception ignored) {
            // Never log request headers, keys, or a provider's lyric payload.
        }
        synchronized (SpicyLyricsService.class) {
            busy = false;
            if (versions != null) CACHE.put(id, versions);
            if (id.equals(wanted) && key.equals(keyUsed)) {
                result = new Result(id, versions == null ? null : versions.select(romanizationUsed), status);
                retryAt = System.currentTimeMillis() + retry;
            }
        }
    }
}
