package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.systems.config.Config;

import java.io.InputStream;
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
    private static final Map<String, LyricsTimeline> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, LyricsTimeline> entry) { return size() > 32; }
    };
    private static volatile Result result = new Result("", null, "Connect Spotify in Lyrics settings");
    private static String wanted = "", keyUsed = "";
    private static boolean busy;
    private static long retryAt, globallyLimitedUntil;
    public record Result(String trackId, LyricsTimeline timeline, String status) {}

    private SpicyLyricsService() {}

    public static synchronized Result update() {
        Config config = Config.get();
        if (config == null || !config.lyricsEnabled.get()) return new Result("", null, "");
        String key = config.spicyLyricsKey.get().trim();
        if (!key.startsWith("sl_pk_") || key.length() < 12) {
            wanted = "";
            return result = new Result("", null, "Add a Spicy Lyrics client key in Lyrics settings");
        }
        SpotifyPlayback.Playback playback = SpotifyPlayback.current();
        if (playback == null) {
            wanted = "";
            return result = new Result("", null, SpotifyPlayback.status());
        }
        String id = playback.trackId();
        if (!key.equals(keyUsed)) { keyUsed = key; retryAt = 0; wanted = ""; }
        if (!id.equals(wanted)) {
            wanted = id; retryAt = 0;
            LyricsTimeline cached = CACHE.get(id);
            result = new Result(id, cached, cached == null ? "Loading lyrics" : "");
        }
        long now = System.currentTimeMillis();
        if (result.timeline != null || busy || now < retryAt || now < globallyLimitedUntil) return result;
        busy = true;
        WORKER.execute(() -> fetch(id, key));
        return result;
    }

    private static void fetch(String id, String key) {
        LyricsTimeline timeline = null;
        String status = "Lyrics unavailable; retrying shortly";
        long retry = 30_000;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spicylyrics.org/v1/lyrics/" + id))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").header("User-Agent", "EmberClient-Lyrics/1.0").GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                int code = response.statusCode();
                if (code == 200) {
                    byte[] bytes = input.readNBytes(2_000_001);
                    if (bytes.length > 2_000_000) throw new IllegalArgumentException("Lyrics response too large");
                    var envelope = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!envelope.has("Body") || !envelope.getAsJsonObject("Body").has("id")
                        || !id.equals(envelope.getAsJsonObject("Body").get("id").getAsString())) {
                        throw new IllegalArgumentException("Lyrics belong to another track");
                    }
                    timeline = LyricsTimeline.parse(envelope);
                    status = timeline.lines().isEmpty() ? "No timed lyrics for this song" : "";
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
            if (timeline != null) CACHE.put(id, timeline);
            if (id.equals(wanted) && key.equals(keyUsed)) {
                result = new Result(id, timeline, status);
                retryAt = System.currentTimeMillis() + retry;
            }
        }
    }
}
