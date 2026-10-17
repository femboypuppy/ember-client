"""Run production bridge and artwork lifecycle code with lightweight game collaborators.

Uses real loopback HTTP and files. No Spotify account, tokens or game launch needed.
On Windows, also compile the actual embedded media helper against stock WinRT APIs.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "meteordevelopment/meteorclient/utils/misc/"
java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
java = str(java_home / "bin/java") if java_home else shutil.which("java")
gson = next((Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson").rglob("gson-*.jar"))

STUBS = {
    "meteordevelopment/meteorclient/systems/config/Config.java": """
package meteordevelopment.meteorclient.systems.config;
public class Config {
    static final Config INSTANCE = new Config();
    public static Config get() { return INSTANCE; }
    public static class Setting<T> {
        T value; Setting(T value) { this.value = value; }
        public T get() { return value; } public void set(T value) { this.value = value; }
    }
    public final Setting<Boolean> spotifyBridgeEnabled = new Setting<>(true),
        lyricsEnabled = new Setting<>(true), songAccent = new Setting<>(false);
    public final Setting<String> spotifyClientId = new Setting<>("");
}
""",
    "meteordevelopment/meteorclient/MeteorClient.java": """
package meteordevelopment.meteorclient;
import net.minecraft.util.Identifier;
public class MeteorClient {
    public static final java.io.File FOLDER = new java.io.File(System.getProperty("test.folder"));
    public static Identifier identifier(String name) { return new Identifier(); }
    public static final Log LOG = new Log();
    public static class Log { public void warn(String message, Object value) {} }
    public static final Client mc = new Client();
    public static class Client { public Manager getTextureManager() { return new Manager(); } }
    public static class Manager { public void registerTexture(Identifier id, Object texture) {} }
}
""",
    "net/minecraft/util/Identifier.java": "package net.minecraft.util; public class Identifier {}",
    "net/minecraft/util/Util.java": """
package net.minecraft.util;
public class Util {
    public static Util getOperatingSystem() { return new Util(); }
    public void open(String uri) {}
}
""",
    "net/minecraft/client/texture/NativeImage.java": """
package net.minecraft.client.texture;
public class NativeImage implements AutoCloseable {
    public static int opened, closed;
    public static NativeImage read(byte[] bytes) throws java.io.IOException {
        if (bytes[0] == 0) throw new java.io.IOException("Simulated corrupt image");
        opened++; return new NativeImage();
    }
    public void close() { closed++; }
}
""",
    "net/minecraft/client/texture/NativeImageBackedTexture.java": """
package net.minecraft.client.texture;
public class NativeImageBackedTexture implements AutoCloseable {
    final NativeImage image;
    public NativeImageBackedTexture(java.util.function.Supplier<String> name, NativeImage image) { this.image = image; }
    public void close() { image.close(); }
}
""",
    PACKAGE + "SongAccentService.java": """
package meteordevelopment.meteorclient.utils.misc;
public class SongAccentService { static int extract(byte[] bytes) { return 0xabcdef; } }
""",
}

HARNESS = r'''
package meteordevelopment.meteorclient.utils.misc;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Base64;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.texture.NativeImage;

public class MusicConnectionChecks {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void field(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    static Object get(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    static HttpClient http = HttpClient.newHttpClient();
    static int post(String body, String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18527/playback"))
            .header("Origin", origin).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    static String sample(String title, int flag, String artKey) {
        return String.join("\u0001", title, "Artist", "1200", "180000", "Playing", "" + flag,
            "Spotify.exe", "Album", Base64.getEncoder().encodeToString(artKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        SpotifyPlayback.start(); // No enableBridge/button call.
        for (int i = 0; i < 60 && get(SpotifyPlayback.class, "server") == null; i++) Thread.sleep(50);
        require(get(SpotifyPlayback.class, "server") != null, "Bridge must open automatically");
        String body = """
            {"id":"0123456789ABCDEFGHIJKL","title":"Song A","artist":"Artist",
             "positionMs":1200,"durationMs":180000,"playing":true,"artworkUrl":"https://i.scdn.co/image/abc"}
            """;
        require(post(body, "https://xpui.app.spotify.com") == 200, "Existing extension must reconnect without clicking");
        require(SpotifyPlayback.current().trackId().equals("0123456789ABCDEFGHIJKL"), "Bridge supplies an exact ID");
        require(post(body.replace("https://i.scdn.co/image/abc", ""), "https://xpui.app.spotify.com") == 200,
            "Transient missing artwork is accepted");
        require(SpotifyPlayback.current().artworkUrl().endsWith("/abc"), "Same-track empty artwork must preserve its URL");
        require(post(body, "https://example.com") == 403, "Reject unrelated web origins");
        require(post(body.replace("0123456789ABCDEFGHIJKL", "bad"), "https://xpui.app.spotify.com") == 400, "Reject invalid IDs");
        Config.get().spotifyBridgeEnabled.set(false);
        require(post(body, "https://xpui.app.spotify.com") == 403, "Explicit disable must be respected");
        SpotifyPlayback.disconnect();
        for (int i = 0; i < 60 && get(SpotifyPlayback.class, "server") != null; i++) Thread.sleep(50);
        SpotifyPlayback.enableBridge();
        for (int i = 0; i < 60 && get(SpotifyPlayback.class, "server") == null; i++) Thread.sleep(50);
        require(post(body, "https://xpui.app.spotify.com") == 200, "Bridge can reconnect after an explicit disconnect");
        field(MediaInfo.class, "worker", Thread.currentThread()); // Do not start a real media reader in synthetic checks.
        Path art = Path.of(System.getProperty("test.folder"), "art.img");
        field(MediaInfo.class, "artPath", art);
        var handle = MediaInfo.class.getDeclaredMethod("handleLine", String.class); handle.setAccessible(true);
        String a = "Spotify.exe\u0001Song A\u0001Artist\u0001Album", b = a.replace("Song A", "Song B");
        byte[] bytes = new byte[32]; bytes[0] = 1; Files.write(art, bytes);
        handle.invoke(null, sample("Song A", 1, a)); MediaInfo.uploadPendingArt();
        require(MediaInfo.hasArt(), "Upload matching artwork");
        handle.invoke(null, sample("Song B", 1, a)); MediaInfo.uploadPendingArt();
        require(!MediaInfo.hasArt(), "Track changes must hide the old song's art");
        Files.write(art, new byte[32]);
        handle.invoke(null, sample("Song B", 2, b));
        require(!MediaInfo.hasArt(), "Failed decoding must not reuse old art");
        Files.write(art, bytes);
        handle.invoke(null, sample("Song B", 2, b)); MediaInfo.uploadPendingArt();
        require(MediaInfo.hasArt(), "Retry the same revision after a failed decode");
        handle.invoke(null, sample("Song A", 3, a));
        handle.invoke(null, sample("Song B", 4, b)); // Replace a queued image before the next frame.
        require(NativeImage.closed >= 2, "Superseded images must close");
        handle.invoke(null, sample("Song A", 4, b)); MediaInfo.uploadPendingArt();
        require(!MediaInfo.hasArt(), "Discard pending art if the song changed before GPU upload");
        Files.writeString(Path.of(System.getProperty("test.folder"), "EmberMedia.cs"), (String) get(MediaInfo.class, "SOURCE"));
        System.out.println("Music connection passed: automatic listener, extension reconnect, disable, ID validation, late covers, retry and pending-image ownership.");
        System.exit(0);
    }
}
'''

with tempfile.TemporaryDirectory(prefix="ember-music-checks-") as folder:
    folder = Path(folder)
    for name, content in STUBS.items():
        path = folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    for name in ("SpotifyPlayback", "MediaInfo", "PlaybackClock", "DesktopMediaSession", "ArtworkAccent"):
        shutil.copyfile(ROOT / "src/main/java" / PACKAGE / (name + ".java"), folder / PACKAGE / (name + ".java"))
    (folder / PACKAGE / "MusicConnectionChecks.java").write_text(HARNESS, encoding="utf-8")
    sources = [str(path) for path in folder.rglob("*.java")]
    subprocess.run([javac, "-cp", str(gson), "-d", str(folder), *sources], check=True)
    # A second fresh JVM confirms the extension works after a Minecraft restart.
    for _ in range(2):
        subprocess.run([java, "-Dtest.folder=" + str(folder), "-cp", str(folder) + os.pathsep + str(gson),
                        "meteordevelopment.meteorclient.utils.misc.MusicConnectionChecks"], check=True, timeout=20)
    if os.name == "nt":
        windir = Path(os.environ["WINDIR"])
        runtime = next((windir / "Microsoft.NET/assembly/GAC_MSIL/System.Runtime").rglob("System.Runtime.dll"))
        metadata = windir / "System32/WinMetadata"
        subprocess.run([str(windir / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"), "/nologo", "/target:exe",
                        "/out:" + str(folder / "EmberMedia.exe"), "/reference:" + str(runtime),
                        *["/reference:" + str(metadata / (name + ".winmd")) for name in
                          ("Windows.Media", "Windows.Storage", "Windows.Foundation")], str(folder / "EmberMedia.cs")], check=True)
        print("Windows media helper compiled against real WinRT APIs.")
