package meteordevelopment.meteorclient.utils.misc;

import meteordevelopment.meteorclient.systems.config.Config;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Artwork requests and image decoding stay off the render thread, once per uncached cover. */
public final class SongAccentService {
    private static final class Network {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    }
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Ember-Album-Color"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, Integer> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) { return size() > 32; }
    };
    private static String wanted = "";
    private static int color = -1;
    private static boolean busy, ready;
    private static long retryAt;

    private SongAccentService() {}

    public static synchronized int update() {
        Config config = Config.get();
        if (config == null || !config.songAccent.get()) { wanted = ""; ready = false; color = -1; return -1; }
        var playback = SpotifyPlayback.current();
        String url = playback == null ? "" : ArtworkAccent.normalizeUrl(playback.artworkUrl());
        if (url.isEmpty()) { wanted = ""; ready = false; color = -1; return -1; }
        if (!url.equals(wanted)) {
            wanted = url; retryAt = 0; ready = false;
            Integer cached = CACHE.get(url);
            if (cached != null) { color = cached; ready = true; }
        }
        if (!ready && !busy && System.currentTimeMillis() >= retryAt) {
            busy = true; WORKER.execute(() -> fetch(url));
        }
        // Hold the previous artwork color during a track's initial download, then blend.
        return color;
    }

    private static void fetch(String url) {
        int extracted = -1;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<InputStream> response = Network.HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IllegalArgumentException("Artwork unavailable");
                byte[] bytes = body.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalArgumentException("Artwork too large");
                try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported artwork");
                    var reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        int w = reader.getWidth(0), h = reader.getHeight(0);
                        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) throw new IllegalArgumentException("Invalid artwork dimensions");
                        var params = reader.getDefaultReadParam();
                        int sample = Math.max(1, (Math.max(w, h) + 63) / 64);
                        params.setSourceSubsampling(sample, sample, 0, 0);
                        extracted = ArtworkAccent.extract(reader.read(0, params));
                    } finally { reader.dispose(); }
                }
            }
        } catch (Exception ignored) { /* Keep the manual accent when artwork is unavailable. */ }
        synchronized (SongAccentService.class) {
            busy = false;
            if (extracted >= 0) CACHE.put(url, extracted);
            if (url.equals(wanted)) {
                color = extracted; ready = extracted >= 0; retryAt = System.currentTimeMillis() + 60_000;
            }
        }
    }
}
