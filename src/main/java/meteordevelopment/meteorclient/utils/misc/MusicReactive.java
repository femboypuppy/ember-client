package meteordevelopment.meteorclient.utils.misc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.orbit.EventHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Opt-in output-only audio analysis; capture and compilation never run on the render thread. */
public final class MusicReactive {
    private static final BassEnvelope ENVELOPE = new BassEnvelope();
    private static volatile Thread worker;
    private static volatile Process process;
    private static volatile boolean requested;
    private static volatile String status = "Off";
    private record Sample(double bass, long received) {}
    private static volatile Sample sample = new Sample(0, 0);
    private static long previous;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private MusicReactive() {}

    @PostInit public static void init() {
        MeteorClient.EVENT_BUS.subscribe(MusicReactive.class);
        Runtime.getRuntime().addShutdownHook(new Thread(MusicReactive::stop, "Ember-Audio-Shutdown"));
    }
    @EventHandler private static void tick(TickEvent.Post event) {
        var c = Config.get();
        boolean want = c != null && c.ambience.get() && c.musicReactive.get();
        requested = want;
        if (!want) { stop(); return; }
        if (!WINDOWS) { status = "Desktop audio is supported on Windows"; return; }
        if (worker == null) start();
    }
    private static synchronized void start() {
        if (worker != null) return;
        worker = new Thread(MusicReactive::run, "Ember-Bass-Analyzer"); worker.setDaemon(true); worker.start();
    }
    private static void stop() {
        requested = false;
        Process p = process; if (p != null) p.destroyForcibly();
        Thread t = worker; if (t != null) t.interrupt();
        sample = new Sample(0, 0); status = "Off";
    }
    public static String status() { return status; }
    public static double level() {
        var c = Config.get();
        long now = System.nanoTime();
        double dt = previous == 0 ? 0 : (now - previous) / 1e9; previous = now;
        if (c == null || !c.ambience.get() || !c.musicReactive.get()) { ENVELOPE.reset(); return 0; }
        var current = sample;
        return ENVELOPE.update(now - current.received < 350_000_000L ? current.bass : 0,
            c.bassSensitivity.get(), c.bassSmoothing.get(), dt);
    }
    public static double exposure(boolean enabled) {
        var c = Config.get();
        return enabled && c != null ? BassEnvelope.exposure(level(), c.bassStrength.get(), c.bassMode.get() == Config.BassMode.Glow) : 1;
    }
    private static void run() {
        try {
            status = "Starting desktop audio";
            Path executable = executable();
            while (requested && !Thread.currentThread().isInterrupted()) {
                Process p = new ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                process = p;
                if (!requested) { p.destroyForcibly(); break; }
                status = "Waiting for desktop audio";
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (requested && (line = reader.readLine()) != null) {
                        String[] parts = line.split(" ", 2);
                        try {
                            double bass = Double.parseDouble(parts[0]);
                            if (Double.isFinite(bass) && bass >= 0 && bass <= 1) {
                                sample = new Sample(bass, System.nanoTime()); status = "Listening to desktop output";
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                } finally { p.destroyForcibly(); process = null; }
                if (requested) { status = "Waiting for an output device"; Thread.sleep(3000); }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (requested) {
                status = "Desktop audio unavailable";
                MeteorClient.LOG.warn("Bass analysis unavailable: {}", e.toString());
            }
            // Do not retry compilation every tick. A later toggle can retry.
            try { while (requested) Thread.sleep(1000); } catch (InterruptedException ignored) {}
        } finally { sample = new Sample(0, 0); if (!requested) status = "Off"; worker = null; }
    }
    private static Path executable() throws Exception {
        byte[] source;
        try (var input = MusicReactive.class.getResourceAsStream("/assets/meteor-client/audio/EmberAudio.cs")) {
            if (input == null) throw new IllegalStateException("Audio helper resource missing");
            source = input.readAllBytes();
        }
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source)).substring(0, 16);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "ember-audio"); Files.createDirectories(directory);
        Path exe = directory.resolve("EmberAudio-" + hash + ".exe");
        if (Files.isRegularFile(exe)) return exe;
        Path cs = directory.resolve("EmberAudio-" + hash + ".cs"); Files.write(cs, source);
        Path csc = Path.of(System.getenv("WINDIR"), "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe");
        if (!Files.isRegularFile(csc)) csc = Path.of(System.getenv("WINDIR"), "Microsoft.NET", "Framework", "v4.0.30319", "csc.exe");
        Path log = directory.resolve("compile.log");
        Path staging = Files.createTempDirectory(directory, "compile-");
        Path output = staging.resolve("EmberAudio.exe");
        Process compiler = new ProcessBuilder(csc.toString(), "/nologo", "/target:exe", "/out:" + output, cs.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process = compiler;
        try {
            if (!compiler.waitFor(30, TimeUnit.SECONDS) || compiler.exitValue() != 0 || !Files.isRegularFile(output)) {
                throw new IllegalStateException("Could not compile audio helper");
            }
            Files.move(output, exe, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            compiler.destroyForcibly(); process = null;
            // A cancelled compiler must never leave a partially written cached executable.
            compiler.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(output); Files.deleteIfExists(staging);
        }
        return exe;
    }
}
