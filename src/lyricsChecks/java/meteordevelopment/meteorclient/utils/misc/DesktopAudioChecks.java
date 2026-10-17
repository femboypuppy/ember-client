package meteordevelopment.meteorclient.utils.misc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Optional Windows integration check. Uses the shipped Java launcher and output-only helper for two seconds. */
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
        int count = 0;
        for (String line : new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
            String[] levels = line.split(" ");
            if (levels.length != 2) throw new AssertionError("Unexpected level sample");
            for (String level : levels) {
                double value = Double.parseDouble(level);
                if (!Double.isFinite(value) || value < 0 || value > 1) throw new AssertionError("Invalid audio level");
            }
            count++;
        }
        if (count < 5) throw new AssertionError("The output device did not supply level measurements");
        if (!exe.equals(factory.invoke(null))) throw new AssertionError("The same source must reuse the cached helper");
        var lifetime = new ProcessBuilder(exe.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            lifetime.getOutputStream().close();
            if (!lifetime.waitFor(5, TimeUnit.SECONDS) || lifetime.exitValue() != 0) throw new AssertionError("Capture must stop when its owning process closes input");
        } finally { lifetime.destroyForcibly(); }
        System.out.println("Desktop audio passed: production helper extraction/build/cache, native bass DSP, " + count + " valid loopback measurements, bounded probe lifetime and parent-exit cleanup.");
    }
}
