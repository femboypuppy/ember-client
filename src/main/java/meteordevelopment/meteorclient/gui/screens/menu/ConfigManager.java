package meteordevelopment.meteorclient.gui.screens.menu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;

/** One config instance, validated on load; debounced atomic writes happen off the render thread. */
public final class ConfigManager {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ember/menu.json");
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Ember menu config"); t.setDaemon(true); return t;
    });
    private static final MenuConfig CONFIG = load(PATH);
    private static ScheduledFuture<?> pending;
    private static volatile String latestSnapshot;
    private static boolean initialized;
    private static final Object WRITE_LOCK = new Object();
    private ConfigManager() {}
    public static MenuConfig get() { return CONFIG; }
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String snapshot = latestSnapshot;
            if (snapshot != null) write(PATH, snapshot);
        }, "Ember menu preferences shutdown"));
    }

    public static MenuConfig load(Path path) {
        if (Files.isRegularFile(path)) try {
            MenuConfig config = JSON.fromJson(Files.readString(path), MenuConfig.class);
            if (config == null) throw new IOException("Empty menu config");
            config.validate(); return config;
        } catch (Exception error) {
            LoggerFactory.getLogger("Ember menu").warn("Could not load menu preferences; preserving the file and using defaults", error);
        }
        return new MenuConfig();
    }
    public static synchronized void changed() {
        CONFIG.validate();
        // Serialize a snapshot now; the worker never reads mutable UI fields.
        String snapshot = JSON.toJson(CONFIG);
        latestSnapshot = snapshot;
        if (pending != null) pending.cancel(false);
        pending = IO.schedule(() -> write(PATH, snapshot), 200, TimeUnit.MILLISECONDS);
    }
    public static synchronized void flush() {
        if (pending != null) pending.cancel(false);
        String snapshot = JSON.toJson(CONFIG);
        latestSnapshot = snapshot;
        IO.execute(() -> write(PATH, snapshot));
    }
    public static void write(Path path, String snapshot) {
        synchronized (WRITE_LOCK) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, snapshot);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { LoggerFactory.getLogger("Ember menu").error("Could not save menu preferences", error); }
        }
    }
}
