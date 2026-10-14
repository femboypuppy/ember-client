package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Resolves and caches 64x64 player skin textures for staff members, including vanished
 * moderators who are not present in the Tab list (via Minotar / MC-Heads skin lookup).
 */
public final class StaffFaceSkins {
    public record Skin(Identifier texture, boolean pending) {}

    private static final Map<String, Identifier> FETCHED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private StaffFaceSkins() {}

    public static Skin get(UUID id, String name) {
        if (mc != null) {
            ClientPlayNetworkHandler handler = mc.getNetworkHandler();
            if (handler != null) {
                PlayerListEntry entry = id != null ? handler.getPlayerListEntry(id) : null;
                if (entry == null && name != null && !name.isBlank()) {
                    for (PlayerListEntry e : handler.getPlayerList()) {
                        if (e.getProfile() != null && e.getProfile().name() != null
                            && e.getProfile().name().equalsIgnoreCase(name.trim())) {
                            entry = e;
                            break;
                        }
                    }
                }

                if (entry != null && entry.getSkinTextures() != null && entry.getSkinTextures().body() != null) {
                    return new Skin(entry.getSkinTextures().body().texturePath(), false);
                }
            }
        }

        if (name != null && !name.isBlank()) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            Identifier cached = FETCHED.get(key);
            if (cached != null) return new Skin(cached, false);

            preload(name);
        }

        UUID fallbackId = id != null
            ? id
            : UUID.nameUUIDFromBytes(("OfflinePlayer:" + (name == null ? "Steve" : name.trim())).getBytes(StandardCharsets.UTF_8));
        return new Skin(DefaultSkinHelper.getSkinTextures(fallbackId).body().texturePath(), true);
    }

    public static Identifier getOrFetch(UUID id, String name) {
        return get(id, name).texture();
    }

    public static void preload(String name) {
        if (name == null || name.isBlank()) return;

        String trimmed = name.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);

        if (FETCHED.containsKey(key) || !LOADING.add(key)) return;

        MeteorExecutor.execute(() -> {
            try {
                String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
                NativeImage image = downloadSkin("https://minotar.net/skin/" + encoded);
                if (image == null) {
                    image = downloadSkin("https://mc-heads.net/skin/" + encoded);
                }

                if (image == null) {
                    LOADING.remove(key);
                    return;
                }

                final NativeImage finalImage = image;
                if (mc != null) {
                    mc.execute(() -> {
                        try {
                            Identifier texId = MeteorClient.identifier("staff_skin/" + Integer.toHexString(key.hashCode()));
                            mc.getTextureManager().registerTexture(texId, new NativeImageBackedTexture(null, finalImage));
                            FETCHED.put(key, texId);
                        } catch (Throwable ignored) {
                            try {
                                finalImage.close();
                            } catch (Throwable ignoredClose) {}
                        } finally {
                            LOADING.remove(key);
                        }
                    });
                } else {
                    finalImage.close();
                    LOADING.remove(key);
                }
            } catch (Throwable ignored) {
                LOADING.remove(key);
            }
        });
    }

    private static NativeImage downloadSkin(String url) {
        try (InputStream in = Http.get(url).sendInputStream()) {
            if (in == null) return null;
            return NativeImage.read(in);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
