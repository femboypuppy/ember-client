package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Keyless, best-effort metadata matching. Never treats an unverified candidate as an exact ID. */
public final class SpotifyTrackResolver {
    public record Lookup(String id, String status, boolean pending) {}
    private record Cached(Lookup result, long retryAt) {}
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 32; }
    };
    private static final class Network {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Ember-Track-Match"); thread.setDaemon(true); return thread;
    });
    private static final Pattern NEXT_DATA = Pattern.compile("<script\\b(?=[^>]*\\bid=[\"']__NEXT_DATA__[\"'])[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static volatile String wanted = "";
    private static boolean busy;
    private static long nextLookupAt;
    private SpotifyTrackResolver() {}

    public static void stop() { wanted = ""; }

    public static synchronized Lookup update(MusicPlayback.Playback playback) {
        var query = LrclibLyrics.query(playback.title(), playback.artist(), playback.clock().durationMs());
        if (query.title().isBlank() || query.artist().isBlank() || query.durationMs() <= 0)
            return new Lookup("", "Spotify ID matching needs the song title, artist and duration", false);
        if (query.title().length() > 500 || query.artist().length() > 500 || playback.album().length() > 500)
            return new Lookup("", "Song metadata is too long to match safely", false);
        String key = query.title() + '\u0001' + query.artist() + '\u0001' + playback.album() + '\u0001' + query.durationMs();
        wanted = key;
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null && now < cached.retryAt) return cached.result;
        if (now < nextLookupAt) return new Lookup("", "Spotify ID matching is waiting to retry", false);
        if (!busy) {
            busy = true;
            WORKER.execute(() -> resolve(key, query, playback.album()));
        }
        return new Lookup("", "Finding a Spotify ID from song metadata", true);
    }

    private static void resolve(String key, LrclibLyrics.Query query, String album) {
        Lookup result = new Lookup("", "No verified Spotify ID for this recording", false);
        long retry = 600_000, throttle = 1200;
        long deadline = System.nanoTime() + 8_000_000_000L;
        try {
            if (!key.equals(wanted)) return;
            JsonObject row = new JsonObject();
            row.addProperty("artist_name", query.artist()); row.addProperty("track_name", query.title()); row.addProperty("release_name", album);
            JsonArray body = new JsonArray(); body.add(row);
            if (!album.isBlank()) {
                // Desktop album labels often include edition suffixes absent from the catalog.
                // Batch a title/artist-only candidate search; duration verification still applies.
                JsonObject withoutAlbum = row.deepCopy(); withoutAlbum.addProperty("release_name", ""); body.add(withoutAlbum);
            }
            var response = request("https://labs.api.listenbrainz.org/spotify-id-from-metadata/json", body.toString(), deadline);
            for (String id : candidates(JsonParser.parseString(response), query)) {
                if (!key.equals(wanted)) return;
                String html = request("https://open.spotify.com/embed/track/" + id, null, deadline);
                String verified = verifiedId(html, id, query);
                if (!verified.isEmpty()) {
                    result = new Lookup(verified, "Matched Spotify track metadata", false);
                    retry = Long.MAX_VALUE;
                    break;
                }
            }
        } catch (RateLimited limited) {
            throttle = limited.delay; retry = throttle;
            result = new Lookup("", "Spotify ID matching rate limited; waiting to retry", false);
        } catch (Exception ignored) {
            retry = 30_000;
            result = new Lookup("", "Spotify ID matching unavailable; using enabled sources", false);
        } finally {
            synchronized (SpotifyTrackResolver.class) {
                if (key.equals(wanted)) CACHE.put(key, new Cached(result,
                    retry == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + retry));
                nextLookupAt = System.currentTimeMillis() + throttle;
                busy = false;
            }
        }
    }

    private static String request(String url, String body, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.net.http.HttpTimeoutException("Track matching time budget reached");
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(Math.min(remaining, 4_000_000_000L)))
            .header("User-Agent", "EmberClient/1.3.2 (https://github.com/femboypuppy/ember-client)");
        if (body == null) builder.GET().header("Accept", "text/html");
        else builder.header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response = Network.HTTP.send(builder.build(), ignored -> MusicLyricsService.limitedBody());
        if (response.statusCode() == 429) {
            long delay = 60_000;
            try { delay = Math.clamp((long) (Double.parseDouble(response.headers().firstValue("Retry-After").orElse("60")) * 1000), 1000, 3_600_000); }
            catch (NumberFormatException ignored) {}
            throw new RateLimited(delay);
        }
        if (response.statusCode() != 200) throw new IllegalStateException("Track metadata unavailable");
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    static List<String> candidates(JsonElement response, LrclibLyrics.Query query) {
        if (!response.isJsonArray()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (var item : response.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            var row = item.getAsJsonObject();
            if (!LrclibLyrics.matches(query.title(), query.artist(), 0, text(row, "track_name"), text(row, "artist_name"), 0)) continue;
            if (!row.has("spotify_track_ids") || !row.get("spotify_track_ids").isJsonArray()) continue;
            for (var value : row.getAsJsonArray("spotify_track_ids")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                String id = value.getAsString();
                if (id.matches("[A-Za-z0-9]{22}")) ids.add(id);
                if (ids.size() >= 4) return List.copyOf(ids);
            }
        }
        return List.copyOf(ids);
    }

    static String verifiedId(String html, String expectedId, LrclibLyrics.Query query) {
        if (!expectedId.matches("[A-Za-z0-9]{22}") || query.durationMs() <= 0 || html.length() > 2_000_000) return "";
        var match = NEXT_DATA.matcher(html);
        if (!match.find()) return "";
        try {
            JsonObject entity = JsonParser.parseString(match.group(1)).getAsJsonObject().getAsJsonObject("props")
                .getAsJsonObject("pageProps").getAsJsonObject("state").getAsJsonObject("data").getAsJsonObject("entity");
            if (!text(entity, "type").equals("track") || !text(entity, "id").equals(expectedId)) return "";
            if (entity.has("isMusicVideo") && entity.get("isMusicVideo").getAsBoolean()) return "";
            List<String> artists = new ArrayList<>();
            for (var artist : entity.getAsJsonArray("artists")) if (artist.isJsonObject()) artists.add(text(artist.getAsJsonObject(), "name"));
            long duration = entity.get("duration").getAsLong();
            if (duration <= 0 || duration > 86_400_000) return "";
            return LrclibLyrics.matches(query.title(), query.artist(), query.durationMs(), text(entity, "name"),
                String.join(", ", artists), duration) ? expectedId : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    private static String text(JsonObject row, String key) {
        return row.has(key) && row.get(key).isJsonPrimitive() ? row.get(key).getAsString() : "";
    }
    private static final class RateLimited extends Exception {
        final long delay;
        RateLimited(long delay) { this.delay = delay; }
    }
}
