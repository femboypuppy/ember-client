package meteordevelopment.meteorclient.systems.profiles;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** One portable file; decoding never writes to disk or applies settings. */
public final class ProfileArchive {
    public static final int LIMIT = 8 * 1024 * 1024;

    public record Bundle(String name, Map<String, byte[]> files) {}

    private ProfileArchive() {}

    public static byte[] encode(String name, Map<String, byte[]> files) throws IOException {
        if (files.isEmpty()) throw new IOException("This config has no saved sections");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("ember-profile.txt"));
            zip.write(("ember-profile:1\n" + cleanName(name)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            int size = 0;
            for (var entry : files.entrySet()) {
                if (!allowed(entry.getKey())
                        || files.size() > 128
                        || (size += entry.getValue().length) > LIMIT)
                    throw new IOException("Config contains unsupported files or is too large");
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        if (bytes.size() > LIMIT) throw new IOException("Compressed config is too large");
        return bytes.toByteArray();
    }

    public static Bundle decode(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("Config is too large");
        String name = null;
        int size = 0, count = 0;
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String key = entry.getName();
                if (++count > 129
                        || entry.isDirectory()
                        || (!key.equals("ember-profile.txt") && !allowed(key)))
                    throw new IOException("Unsupported config archive entry");
                byte[] value = zip.readNBytes(LIMIT - size + 1);
                size += value.length;
                if (size > LIMIT) throw new IOException("Expanded config is too large");
                if (key.equals("ember-profile.txt")) {
                    String manifest = new String(value, StandardCharsets.UTF_8);
                    if (name != null
                            || !manifest.startsWith("ember-profile:1\n")
                            || value.length > 256)
                        throw new IOException("Unsupported config format");
                    name = cleanName(manifest.substring("ember-profile:1\n".length()));
                } else if (files.putIfAbsent(key, value) != null)
                    throw new IOException("Duplicate config section");
            }
        }
        if (name == null || files.isEmpty())
            throw new IOException("Select an Ember .ember config file");
        return new Bundle(name, files);
    }

    public static String cleanName(String value) {
        String name = value.replaceAll("[^\\p{L}\\p{N} _-]", "").strip();
        if (name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])")) name = "Config " + name;
        return name.isBlank() ? "Imported config" : name.substring(0, Math.min(32, name.length()));
    }

    static boolean allowed(String name) {
        return name.matches("[\\p{L}\\p{N} _.,()\\[\\]:-]+\\.nbt")
                && !name.contains(":")
                && !Set.of(
                                "accounts.nbt",
                                "proxies.nbt",
                                "friends.nbt",
                                "profiles.nbt",
                                "config.nbt")
                        .contains(name);
    }
}
