package meteordevelopment.meteorclient.utils.misc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Optional Windows integration check for the shipped Spotify process capture and isolation. */
public final class DesktopAudioChecks {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            System.out.println("Desktop audio integration check requires Windows; DSP envelope checks run on every platform."); return;
        }
        var factory = MusicReactive.class.getDeclaredMethod("executable"); factory.setAccessible(true);
        Path exe = (Path) factory.invoke(null);
        var selfTest = new ProcessBuilder(exe.toString(), "--self-test").inheritIO().start();
        if (!selfTest.waitFor(20, TimeUnit.SECONDS)) { selfTest.destroyForcibly(); throw new AssertionError("Audio DSP self-test timed out"); }
        if (selfTest.exitValue() != 0) throw new AssertionError("Audio DSP self-test failed");
        var probe = new ProcessBuilder(exe.toString(), "--probe").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!probe.waitFor(15, TimeUnit.SECONDS)) { probe.destroyForcibly(); throw new AssertionError("Loopback probe did not stop"); }
        if (probe.exitValue() != 0) throw new AssertionError("Loopback probe failed");
        int count = 0, audible = 0;
        for (String line : new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
            var signal = SpotifyAudioSignal.parse(line);
            if (signal == null) throw new AssertionError("Unexpected Spotify audio sample");
            if (line.startsWith("LEVEL ")) { count++; if (signal.rms() > 0.0005) audible++; }
        }
        // Spotify can be closed on a development machine. The self-capture isolation
        // check always exercises the same Windows process-loopback path without audio.
        var isolation = new ProcessBuilder(exe.toString(), "--probe-self").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (!isolation.waitFor(15, TimeUnit.SECONDS)) { isolation.destroyForcibly(); throw new AssertionError("Isolation probe did not stop"); }
        if (isolation.exitValue() != 0) throw new AssertionError("Process audio isolation failed");
        int isolated = 0;
        for (String line : new String(isolation.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
            var signal = SpotifyAudioSignal.parse(line);
            if (signal == null) throw new AssertionError("Invalid isolation output");
            if (line.startsWith("LEVEL ")) {
                isolated++;
                if (signal.rms() > 0.000001) throw new AssertionError("A silent target process captured another app's audio");
            }
        }
        if (isolated < 5) throw new AssertionError("The isolated process did not supply measurements");
        if (!exe.equals(factory.invoke(null))) throw new AssertionError("The same source must reuse the cached helper");
        var lifetime = new ProcessBuilder(exe.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            lifetime.getOutputStream().close();
            if (!lifetime.waitFor(5, TimeUnit.SECONDS) || lifetime.exitValue() != 0) throw new AssertionError("Capture must stop when its owning process closes input");
        } finally { lifetime.destroyForcibly(); }
        System.out.println("Spotify audio passed: production helper build/cache, DSP, " + count + " Spotify samples (" + audible
            + " audible), " + isolated + " silent isolated-process samples, bounded probes and parent-exit cleanup.");
    }
}
