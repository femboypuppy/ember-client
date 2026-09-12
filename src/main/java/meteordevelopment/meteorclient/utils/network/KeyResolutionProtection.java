package meteordevelopment.meteorclient.utils.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.mixininterface.IServerComponent;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Language;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stops servers from fingerprinting installed mods through key resolution.
 *
 * A server can put a translation or keybind placeholder (e.g. key.meteor-client.open-gui)
 * on a sign or item and make the client echo the resolved text back. A client that
 * resolves it has the mod. Text sent by a remote server is therefore resolved the way a
 * vanilla client would resolve it: mod-provided translations fall back to the packet's
 * fallback (or the raw key), and vanilla keybinds report their default key so custom
 * binds cannot be read either.
 *
 * Meteor upstream returned the raw key even when the probe supplied a fallback, which a
 * vanilla client never does, so its protection was itself a detection signal.
 */
public final class KeyResolutionProtection {
    private static volatile Set<String> vanillaKeys;
    private static volatile boolean disabled;

    private static volatile Map<String, String> modValues = Map.of();
    private static volatile Language modValuesFor;

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private KeyResolutionProtection() {
    }

    /** Only text decoded while connected to a remote server needs protecting. */
    public static boolean shouldTag() {
        if (disabled) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getNetworkHandler() != null && !mc.isInSingleplayer();
    }

    public static void markTree(Text text) {
        if (text == null) return;

        if (text.getContent() instanceof IServerComponent tagged) tagged.ember$markFromServer();

        if (text.getContent() instanceof TranslatableTextContent translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Text argText) markTree(argText);
            }
        }

        for (Text sibling : text.getSiblings()) markTree(sibling);
    }

    /**
     * @param resolved       what this client resolved the key to
     * @param vanillaDefault what a vanilla client returns for an unknown key: the fallback, or the key itself
     */
    public static String translation(String key, String resolved, String vanillaDefault) {
        if (!ready()) return resolved;

        String modValue = modValues().get(key);
        // Vanilla keys, resource-pack keys and unknown keys already resolve exactly as vanilla would.
        if (modValue == null) return resolved;
        // A resource pack overrode the mod's text; a vanilla client with that pack shows it too.
        if (!modValue.equals(resolved)) return resolved;

        report("translation", key);
        return vanillaDefault;
    }

    public static Text keybind(String name, Text resolved) {
        if (!ready()) return resolved;

        KeyBinding binding = findBinding(name);

        if (vanillaKeys.contains(name)) {
            // Report the default binding so rebinds can't be read back.
            return binding != null ? binding.getDefaultKey().getLocalizedText() : resolved;
        }

        if (binding != null || modValues().containsKey(name)) report("keybind", name);

        // A vanilla client has no binding for a mod keybind and resolves it as a translation.
        MutableText asTranslation = Text.translatable(name);
        markTree(asTranslation);
        return asTranslation;
    }

    private static boolean ready() {
        if (disabled) return false;
        if (vanillaKeys == null) loadVanillaKeys();
        return !disabled;
    }

    /**
     * This version keys bindings by id and exposes a static lookup, so no scan of
     * options.allKeys is needed - and it works before the options are populated.
     */
    private static KeyBinding findBinding(String name) {
        return KeyBinding.byId(name);
    }

    private static void report(String kind, String key) {
        if (reported.add(kind + ":" + key)) {
            MeteorClient.LOG.info("Blocked a server {} probe for '{}'", kind, key);
        }
    }

    // --- Key sets ---

    private static synchronized void loadVanillaKeys() {
        if (vanillaKeys != null || disabled) return;

        Set<String> keys = new HashSet<>();
        try {
            ClassLoader loader = MinecraftClient.class.getClassLoader();
            readKeys(loader, "assets/minecraft/lang/en_us.json", keys);

            // Renamed keys still resolve on a vanilla client.
            JsonObject deprecated = readJson(loader, "assets/minecraft/lang/deprecated.json");
            if (deprecated != null && deprecated.has("renamed") && deprecated.get("renamed").isJsonObject()) {
                keys.addAll(deprecated.getAsJsonObject("renamed").keySet());
            }
        } catch (Exception e) {
            MeteorClient.LOG.warn("Key resolution protection disabled: could not read vanilla language", e);
        }

        // Without the vanilla key set every mod override of a vanilla key would be blocked,
        // which is both broken and detectable, so refuse to run rather than guess.
        if (keys.isEmpty()) {
            disabled = true;
            return;
        }

        vanillaKeys = keys;
    }

    private static Map<String, String> modValues() {
        Language language = Language.getInstance();
        if (language == modValuesFor) return modValues;

        synchronized (KeyResolutionProtection.class) {
            if (language != modValuesFor) {
                modValues = loadModValues();
                modValuesFor = language;
            }
        }
        return modValues;
    }

    private static Map<String, String> loadModValues() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String selected = mc != null && mc.getLanguageManager() != null ? mc.getLanguageManager().getLanguage() : "en_us";

        Map<String, String> values = new HashMap<>();

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.getMetadata().getId().equals("minecraft")) continue;

            for (Path root : mod.getRootPaths()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;

                try (Stream<Path> namespaces = Files.list(assets)) {
                    for (Path namespace : namespaces.toList()) {
                        mergeLang(namespace.resolve("lang/en_us.json"), values);
                        if (!selected.equals("en_us")) mergeLang(namespace.resolve("lang/" + selected + ".json"), values);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        values.keySet().removeAll(vanillaKeys);
        return values;
    }

    private static void mergeLang(Path file, Map<String, String> into) {
        if (!Files.isRegularFile(file)) return;

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) return;

            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) into.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (Exception ignored) {
        }
    }

    private static void readKeys(ClassLoader loader, String resource, Set<String> into) throws Exception {
        JsonObject json = readJson(loader, resource);
        if (json != null) into.addAll(json.keySet());
    }

    private static JsonObject readJson(ClassLoader loader, String resource) throws Exception {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        }
    }
}
