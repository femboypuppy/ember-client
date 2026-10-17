package meteordevelopment.meteorclient.utils.misc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.util.Util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Exact Spotify IDs through user-authorized PKCE, or the optional local Spicetify bridge. */
public final class SpotifyPlayback {
    public static final String REDIRECT = "http://127.0.0.1:18527/callback";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Ember-Spotify"); t.setDaemon(true); return t;
    });
    private static final Gson GSON = new Gson();
    private static final Path SESSION = MeteorClient.FOLDER.toPath().resolve("spotify-session.json");
    private static final Set<String> BRIDGE_ORIGINS = Set.of("https://xpui.app.spotify.com", "https://open.spotify.com");
    private static volatile Playback playback;
    private static volatile String status = "Connect Spotify in Lyrics settings";
    private static volatile String refreshToken = "", accessToken = "", sessionClient = "";
    private static volatile long expiresAt, nextPoll, bridgeUntil;
    private static volatile Pending pending;
    private static HttpServer server;
    private static boolean started;

    public record Playback(String trackId, String title, String artist, String artworkUrl, PlaybackClock clock, long receivedAtNanos) {
        public long positionMs() { return clock.positionAt(System.nanoTime()); }
    }
    private record Pending(String state, String verifier, String client, long createdAtNanos) {}

    private SpotifyPlayback() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        WORKER.execute(() -> {
            try {
                if (Files.isRegularFile(SESSION)) {
                    JsonObject saved = JsonParser.parseString(Files.readString(SESSION)).getAsJsonObject();
                    refreshToken = saved.get("refresh_token").getAsString();
                    sessionClient = saved.get("client_id").getAsString();
                }
            } catch (Exception ignored) { status = "Reconnect Spotify in Lyrics settings"; }
        });
        WORKER.scheduleWithFixedDelay(SpotifyPlayback::poll, 0, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (SpotifyPlayback.class) { if (server != null) server.stop(0); }
            WORKER.shutdownNow();
        }, "Ember-Spotify-Shutdown"));
    }

    public static Playback current() {
        start();
        Playback sample = playback;
        return sample != null && System.nanoTime() - sample.receivedAtNanos < 10_000_000_000L ? sample : null;
    }
    public static String status() { return status; }

    public static void connect() {
        start();
        String client = Config.get().spotifyClientId.get().trim();
        if (!client.matches("[A-Za-z0-9]{32}")) { status = "Enter your Spotify client ID first"; return; }
        WORKER.execute(() -> {
            try {
                ensureServer();
                String verifier = random(), state = random();
                pending = new Pending(state, verifier, client, System.nanoTime());
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=" + encode(client)
                    + "&redirect_uri=" + encode(REDIRECT) + "&scope=user-read-currently-playing&state=" + encode(state)
                    + "&code_challenge_method=S256&code_challenge=" + encode(challenge);
                status = "Finish connecting Spotify in your browser";
                Util.getOperatingSystem().open(url);
            } catch (Exception ignored) { status = "Spotify connection failed; check local port 18527"; }
        });
    }

    public static void enableBridge() {
        start();
        WORKER.execute(() -> {
            try { ensureServer(); status = "Waiting for the Ember Spicetify bridge"; }
            catch (IOException ignored) { status = "Local port 18527 is unavailable"; }
        });
    }

    public static void disconnect() {
        start();
        WORKER.execute(() -> {
            accessToken = refreshToken = sessionClient = "";
            playback = null; bridgeUntil = 0; pending = null;
            synchronized (SpotifyPlayback.class) { if (server != null) { server.stop(0); server = null; } }
            try { Files.deleteIfExists(SESSION); } catch (IOException ignored) { }
            status = "Spotify disconnected";
        });
    }

    private static void poll() {
        if (System.nanoTime() < bridgeUntil || System.currentTimeMillis() < nextPoll) return;
        if (Config.get() == null || (!Config.get().lyricsEnabled.get() && !Config.get().songAccent.get())) return;
        String client = Config.get().spotifyClientId.get().trim();
        if (refreshToken.isBlank() || !client.equals(sessionClient)) return;
        try {
            if (accessToken.isBlank() || System.currentTimeMillis() >= expiresAt) {
                token("grant_type=refresh_token&client_id=" + encode(client) + "&refresh_token=" + encode(refreshToken), client);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + accessToken).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) { playback = null; status = "Play a song in Spotify"; return; }
            if (response.statusCode() == 401) { accessToken = ""; playback = null; return; }
            if (response.statusCode() == 429) {
                nextPoll = System.currentTimeMillis() + retryMs(response);
                playback = null; status = "Spotify is rate limited; waiting to retry"; return;
            }
            if (response.statusCode() != 200) {
                nextPoll = System.currentTimeMillis() + 30_000;
                playback = null; status = "Spotify access unavailable; check your developer app"; return;
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("item") || !body.get("item").isJsonObject()) { playback = null; return; }
            JsonObject item = body.getAsJsonObject("item");
            if (!"track".equals(item.get("type").getAsString())) { playback = null; return; }
            String artist = item.getAsJsonArray("artists").isEmpty() ? "" : item.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
            accept(item.get("id").getAsString(), item.get("name").getAsString(), artist,
                body.get("progress_ms").getAsLong(), item.get("duration_ms").getAsLong(), body.get("is_playing").getAsBoolean(), artwork(item));
        } catch (AuthorizationException ignored) {
            playback = null;
            status = "Reconnect Spotify in Lyrics settings";
        } catch (Exception ignored) {
            nextPoll = System.currentTimeMillis() + 15_000;
            playback = null; status = "Spotify is offline; waiting to reconnect";
        }
    }

    private static synchronized void token(String form, String client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            accessToken = refreshToken = "";
            throw new AuthorizationException();
        }
        if (response.statusCode() != 200) throw new IOException("Spotify authorization failed");
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        accessToken = body.get("access_token").getAsString();
        expiresAt = System.currentTimeMillis() + Math.max(1, body.get("expires_in").getAsLong() - 30) * 1000;
        sessionClient = client;
        if (body.has("refresh_token")) refreshToken = body.get("refresh_token").getAsString();
        JsonObject saved = new JsonObject(); saved.addProperty("client_id", client); saved.addProperty("refresh_token", refreshToken);
        Files.createDirectories(SESSION.getParent());
        Path temp = SESSION.resolveSibling("spotify-session.json.tmp");
        Files.writeString(temp, GSON.toJson(saved));
        Files.move(temp, SESSION, StandardCopyOption.REPLACE_EXISTING);
        nextPoll = 0;
    }

    private static synchronized void ensureServer() throws IOException {
        if (server != null) return;
        HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", 18527), 0);
        next.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        next.createContext("/callback", SpotifyPlayback::callback);
        next.createContext("/playback", SpotifyPlayback::bridge);
        next.start(); server = next;
    }

    private static void callback(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> query = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String entry : raw.split("&")) {
                String[] pair = entry.split("=", 2);
                if (pair.length == 2) query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            Pending login;
            synchronized (SpotifyPlayback.class) {
                login = pending;
                if (!exchange.getRequestMethod().equals("GET") || login == null || System.nanoTime() - login.createdAtNanos > 300_000_000_000L
                    || !MessageDigest.isEqual(login.state.getBytes(StandardCharsets.UTF_8), query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) {
                    respond(exchange, 400, "This Spotify login has expired or is invalid. Return to Ember and connect again."); return;
                }
                pending = null;
            }
            if (query.containsKey("error") || !query.containsKey("code")) { status = "Spotify connection cancelled"; respond(exchange, 400, status); return; }
            try {
                token("grant_type=authorization_code&client_id=" + encode(login.client) + "&redirect_uri=" + encode(REDIRECT)
                    + "&code_verifier=" + encode(login.verifier) + "&code=" + encode(query.get("code")), login.client);
                status = "Spotify connected";
                respond(exchange, 200, "Spotify connected. You can close this page and return to Minecraft.");
            } catch (Exception ignored) { status = "Could not complete Spotify login"; respond(exchange, 502, status); }
        }
    }

    private static void bridge(HttpExchange exchange) throws IOException {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !BRIDGE_ORIGINS.contains(origin)) { respond(exchange, 403, "Origin not allowed"); return; }
            if (origin != null) exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
                respond(exchange, 200, ""); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { respond(exchange, 405, "POST required"); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(8193);
            if (bytes.length > 8192) { respond(exchange, 413, "Playback update too large"); return; }
            try {
                JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (value.get("id").getAsString().isEmpty()) { playback = null; status = "Play a song in Spotify"; }
                else accept(value.get("id").getAsString(), value.get("title").getAsString(), value.get("artist").getAsString(),
                    value.get("positionMs").getAsLong(), value.get("durationMs").getAsLong(), value.get("playing").getAsBoolean(),
                    value.has("artworkUrl") && value.get("artworkUrl").isJsonPrimitive() ? value.get("artworkUrl").getAsString() : "");
                bridgeUntil = System.nanoTime() + 3_000_000_000L;
                respond(exchange, 200, "OK");
            } catch (RuntimeException ignored) { respond(exchange, 400, "Invalid playback update"); }
        }
    }

    private static String artwork(JsonObject item) {
        try {
            String chosen = "";
            int closest = Integer.MAX_VALUE;
            for (var image : item.getAsJsonObject("album").getAsJsonArray("images")) {
                var object = image.getAsJsonObject();
                String url = ArtworkAccent.normalizeUrl(object.get("url").getAsString());
                int width = object.has("width") && !object.get("width").isJsonNull() ? object.get("width").getAsInt() : 300;
                int distance = Math.abs(width - 300);
                if (!url.isEmpty() && distance < closest) { chosen = url; closest = distance; }
            }
            return chosen;
        } catch (RuntimeException ignored) { return ""; }
    }

    private static synchronized void accept(String id, String title, String artist, long position, long duration, boolean playing, String artwork) {
        if (!id.matches("[A-Za-z0-9]{22}")) throw new IllegalArgumentException("Invalid Spotify track");
        long now = System.nanoTime();
        Playback previous = playback;
        PlaybackClock clock = previous != null && previous.trackId.equals(id)
            ? previous.clock.corrected(position, duration, playing, now)
            : new PlaybackClock(Math.max(0, position), Math.max(0, duration), playing, now);
        playback = new Playback(id, title, artist, ArtworkAccent.normalizeUrl(artwork), clock, now);
        status = playing ? "Spotify connected" : "Spotify paused";
    }
    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private static long retryMs(HttpResponse<?> response) {
        try { return Math.max(1000, (long) (Double.parseDouble(response.headers().firstValue("Retry-After").orElse("30")) * 1000)); }
        catch (NumberFormatException ignored) { return 30_000; }
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static final class AuthorizationException extends IOException {}
}
