package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.systems.config.Config;

import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;

/** Exact Spicy syncs first, with cached metadata search for any desktop music player. */
public final class MusicLyricsService {
    public record Result(String trackKey, LyricsTimeline timeline, String status) {}
    private record Cached(LyricsTimeline timeline, String status, long retryAt) {}
    private static final class Network {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Ember-Lyric-Search"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 32; }
    };
    private static long nextSearchAt;
    private static boolean busy;

    private MusicLyricsService() {}

    public static Result update() { return update(MusicPlayback.current()); }

    public static synchronized Result update(MusicPlayback.Playback playback) {
        var config = Config.get();
        if (config == null || !config.lyricsEnabled.get()) { SpotifyTrackResolver.stop(); return new Result("", null, ""); }
        if (playback == null) { SpotifyTrackResolver.stop(); return new Result("", null, MusicPlayback.status()); }
        String requestKey = playback.key() + "\u0001" + playback.clock().durationMs();
        long now = System.currentTimeMillis();
        if (!config.spicyLyricsEnabled.get() || !config.lyricsResolveSpotify.get()) SpotifyTrackResolver.stop();
        Cached cached = CACHE.get(requestKey);
        var readyFallback = cached != null && cached.timeline != null && now < cached.retryAt
            ? new LyricsSourcePolicy.Response(cached.timeline, cached.status, false) : null;
        var selected = LyricsSourcePolicy.select(config.spicyLyricsEnabled.get(), config.lyricsSearchFallback.get(),
            () -> primary(playback), () -> fallback(playback, requestKey, now), readyFallback);
        return new Result(playback.key(), selected.timeline(), selected.status());
    }

    private static LyricsSourcePolicy.Response primary(MusicPlayback.Playback playback) {
        var config = Config.get();
        String key = config.spicyLyricsKey.get().trim();
        if (!key.startsWith("sl_pk_") || key.length() < 12) return new LyricsSourcePolicy.Response(null, "Add a Spicy Lyrics client key", false);
        String id = playback.spotifyId();
        if (id.isEmpty() && config.lyricsResolveSpotify.get()) {
            var lookup = SpotifyTrackResolver.update(playback);
            if (lookup.pending() || lookup.id().isEmpty()) return new LyricsSourcePolicy.Response(null, lookup.status(), lookup.pending());
            id = lookup.id();
        }
        if (id.isEmpty()) return new LyricsSourcePolicy.Response(null, "Connect Spotify or enable Find Spotify track IDs", false);
        var spicy = SpicyLyricsService.update(id);
        LyricsTimeline timeline = spicy.trackId().equals(id) ? spicy.timeline() : null;
        if (timeline != null && timeline.lines().isEmpty() && timeline.background().isEmpty()) timeline = null;
        return new LyricsSourcePolicy.Response(timeline, timeline != null ? "Spicy Lyrics ready" : spicy.status(), spicy.pending());
    }

    private static LyricsSourcePolicy.Response fallback(MusicPlayback.Playback playback, String requestKey, long now) {
        Cached cached = CACHE.get(requestKey);
        if (cached != null && now < cached.retryAt) return new LyricsSourcePolicy.Response(cached.timeline, cached.status, false);
        if (!busy && now >= nextSearchAt) {
            var query = LrclibLyrics.query(playback.title(), playback.artist(), playback.clock().durationMs());
            if (query.artist().isBlank()) {
                var missing = new Cached(null, "The player hasn't supplied an artist for this song", now + 10_000);
                CACHE.put(requestKey, missing);
                return new LyricsSourcePolicy.Response(null, missing.status, false);
            }
            busy = true;
            WORKER.execute(() -> search(requestKey, query));
        }
        return new LyricsSourcePolicy.Response(null, now < nextSearchAt ? "LRCLIB is waiting to retry" : "Finding lyrics with LRCLIB", true);
    }

    private static void search(String key, LrclibLyrics.Query query) {
        LyricsTimeline timeline = null;
        String status = "No matching timed lyrics for this recording";
        long retry = 600_000, throttle = 350;
        try {
            String url = "https://lrclib.net/api/search?track_name=" + encode(query.title()) + "&artist_name=" + encode(query.artist());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .header("User-Agent", "EmberClient-Lyrics/1.2.2-dev (https://github.com/femboypuppy/ember-client)").GET().build();
            HttpResponse<byte[]> response = Network.HTTP.send(request, ignored -> limitedBody());
            if (response.statusCode() == 200) {
                byte[] bytes = response.body();
                var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!json.isJsonArray()) throw new IllegalArgumentException("Invalid search response");
                timeline = LrclibLyrics.select(json.getAsJsonArray(), query);
                if (timeline != null) { status = "LRCLIB ready (line sync)"; retry = Long.MAX_VALUE; }
            } else if (response.statusCode() == 429) {
                try { throttle = Math.clamp((long) (Double.parseDouble(response.headers().firstValue("Retry-After").orElse("60")) * 1000), 1000, 86_400_000); }
                catch (NumberFormatException ignored) { throttle = 60_000; }
                retry = throttle; status = "Lyrics search rate limited; waiting to retry";
            } else { retry = 30_000; status = "Lyrics search unavailable; retrying shortly"; }
        } catch (Exception ignored) { retry = 30_000; status = "Lyrics search offline; retrying shortly"; }
        synchronized (MusicLyricsService.class) {
            CACHE.put(key, new Cached(timeline, status, retry == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + retry));
            nextSearchAt = System.currentTimeMillis() + throttle;
            busy = false;
        }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }

    // Unlike an InputStream body, completing this subscriber keeps the request's timeout
    // active until all bytes arrive. A stalled server cannot occupy the search worker forever.
    static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long received;
            private boolean failed;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription next) { subscription = next; delegate.onSubscribe(next); }
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) received += buffer.remaining();
                if (received > 2_000_000) {
                    failed = true; subscription.cancel();
                    delegate.onError(new IllegalArgumentException("Search response too large"));
                } else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!failed) delegate.onError(error); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }
}
