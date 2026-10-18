package meteordevelopment.meteorclient.systems.profiles;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ProfileTransfer {
    private ProfileTransfer() {}

    public static void writeTag(Path target, NbtCompound tag) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".snapshot-", ".tmp");
        try {
            NbtIo.write(tag, temporary);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] export(Profile profile) throws IOException {
        File folder = profile.getSafeFile();
        if (folder == null) throw new IOException("Invalid config name");
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        File[] contents = folder.listFiles();
        if (contents != null)
            for (File file : contents) {
                boolean selected =
                        switch (file.getName()) {
                            case "appearance.nbt", "theme.nbt" -> profile.appearance.get();
                            case "modules.nbt" -> profile.modules.get();
                            case "hud.nbt" -> profile.hud.get();
                            case "macros.nbt" -> profile.macros.get();
                            default -> profile.waypoints.get();
                        };
                if (file.isFile() && selected && ProfileArchive.allowed(file.getName())) {
                    total += file.length();
                    if (total > ProfileArchive.LIMIT) throw new IOException("Config is too large");
                    files.put(file.getName(), Files.readAllBytes(file.toPath()));
                }
            }
        return ProfileArchive.encode(profile.name.get(), files);
    }

    /** Replace a snapshot's selected sections without retaining stale, deselected data. */
    public static void saveSnapshot(Profile profile) throws IOException {
        Path root = Profiles.FOLDER.toPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".save-");
        File target = profile.getSafeFile();
        if (target == null) throw new IOException("Invalid config name");
        try {
            if (profile.appearance.get()) {
                writeTag(
                        staging.resolve("appearance.nbt"),
                        meteordevelopment.meteorclient.systems.config.Config.get()
                                .appearanceToTag());
                writeTag(
                        staging.resolve("theme.nbt"),
                        meteordevelopment.meteorclient.gui.GuiThemes.get().toTag());
            }
            if (profile.modules.get())
                meteordevelopment.meteorclient.systems.modules.Modules.get().save(staging.toFile());
            if (profile.hud.get())
                meteordevelopment.meteorclient.systems.hud.Hud.get().save(staging.toFile());
            if (profile.macros.get())
                meteordevelopment.meteorclient.systems.macros.Macros.get().save(staging.toFile());
            if (profile.waypoints.get())
                meteordevelopment.meteorclient.systems.waypoints.Waypoints.get()
                        .save(staging.toFile());
            if (profile.modules.get() && !Files.isRegularFile(staging.resolve("modules.nbt"))
                    || profile.hud.get() && !Files.isRegularFile(staging.resolve("hud.nbt"))
                    || profile.macros.get() && !Files.isRegularFile(staging.resolve("macros.nbt")))
                throw new IOException("A config section could not be saved");
            // Keep a recoverable copy when replacing a saved setup.
            if (target.exists()) {
                Path backup = Files.createTempDirectory(root, ".previous-");
                Files.delete(backup);
                Files.move(target.toPath(), backup);
                try {
                    Files.move(staging, target.toPath());
                } catch (IOException e) {
                    Files.move(backup, target.toPath());
                    throw e;
                }
            } else Files.move(staging, target.toPath());
        } finally {
            if (staging.getParent().equals(root) && Files.isDirectory(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
            }
        }
    }

    public static Profile importBytes(byte[] bytes) throws IOException {
        var bundle = ProfileArchive.decode(bytes);
        Path root = Profiles.FOLDER.toPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".import-");
        try {
            for (var file : bundle.files().entrySet()) {
                Path path = staging.resolve(file.getKey());
                Files.write(path, file.getValue());
                try (var input = new DataInputStream(Files.newInputStream(path))) {
                    if (NbtIo.read(input, NbtSizeTracker.of(ProfileArchive.LIMIT)) == null)
                        throw new IOException("Empty config section: " + file.getKey());
                }
            }
            if (Profiles.describe(staging.toFile()) == null)
                throw new IOException("No supported config sections");
            Path target = root.resolve(bundle.name());
            for (int suffix = 2;
                    Files.exists(target)
                            || Profiles.get().get(target.getFileName().toString()) != null;
                    suffix++) target = root.resolve(bundle.name() + " " + suffix);
            Files.move(staging, target);
            Profile profile = Profiles.describe(target.toFile());
            Profiles.get().registerExisting(profile);
            return profile;
        } catch (RuntimeException e) {
            throw new IOException("Invalid config data", e);
        } finally {
            // Only clean the staging directory that this import created, inside the profile root.
            if (staging.getParent().equals(root) && Files.isDirectory(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
            }
        }
    }
}
