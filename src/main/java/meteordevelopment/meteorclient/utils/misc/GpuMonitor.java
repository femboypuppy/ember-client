package meteordevelopment.meteorclient.utils.misc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU utilisation, which the JVM cannot report on its own - there is no management bean for
 * the graphics card the way there is for the CPU and heap.
 *
 * The number is read from nvidia-smi on a daemon thread, so the game loop never waits on a
 * process. That means it only works on NVIDIA hardware with the driver tools installed; on
 * anything else the probe fails once and the monitor stays off, reporting -1 so callers can
 * simply drop the segment rather than show a wrong figure.
 */
public final class GpuMonitor {
    private static final long INTERVAL_MS = 2000;

    private static final AtomicInteger usage = new AtomicInteger(-1);
    private static volatile boolean started;
    private static volatile boolean unavailable;
    private static volatile long lastRequested;

    private GpuMonitor() {
    }

    /**
     * Latest utilisation percentage, or -1 when unknown. Calling this is what keeps the
     * monitor alive: the thread stops polling once nothing has asked for a while, so a
     * disabled segment costs nothing.
     */
    public static int usage() {
        lastRequested = System.currentTimeMillis();
        if (unavailable) return -1;
        if (!started) start();
        return usage.get();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;

        Thread thread = new Thread(GpuMonitor::loop, "Ember GPU monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        while (!unavailable) {
            // Stop working when the segment is switched off or the widget is hidden.
            if (System.currentTimeMillis() - lastRequested > INTERVAL_MS * 3) {
                started = false;
                usage.set(-1);
                return;
            }

            int value = probe();
            if (value < 0) {
                unavailable = true;
                usage.set(-1);
                return;
            }

            usage.set(value);

            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One nvidia-smi call. Returns -1 for any failure, which permanently disables polling. */
    private static int probe() {
        Process process = null;

        try {
            process = new ProcessBuilder(
                "nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits")
                .redirectErrorStream(true)
                .start();

            String line;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }

            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) return -1;
            if (process.exitValue() != 0 || line == null) return -1;

            return Math.max(0, Math.min(100, Integer.parseInt(line.trim())));
        } catch (Exception e) {
            return -1;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
