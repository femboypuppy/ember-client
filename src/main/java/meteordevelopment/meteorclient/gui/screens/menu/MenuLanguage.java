package meteordevelopment.meteorclient.gui.screens.menu;

import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Menu-only language selection; changing it does not reload the world's resource packs. */
public final class MenuLanguage {
    private static final Map<String, String> WORDS = new HashMap<>();
    private static String loaded = "";
    private MenuLanguage() {}
    public static String locale() {
        String language = ConfigManager.get().language;
        if (language.equals("system")) language = MinecraftClient.getInstance().options.language;
        return language.startsWith("fr") ? "fr_fr" : "en_us";
    }
    public static void reload() {
        loaded = locale(); WORDS.clear();
        var resource = MinecraftClient.getInstance().getResourceManager().getResource(Identifier.of("ember", "menu_lang/" + loaded + ".json"));
        if (resource.isPresent()) try (var reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e -> WORDS.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception ignored) { /* English source labels are a complete fallback. */ }
    }
    public static String text(String source) { if (!loaded.equals(locale())) reload(); return WORDS.getOrDefault(source, source); }
}
