package meteordevelopment.meteorclient.systems.modules.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Optional;

/**
 * Recolours the enchantment glint. The glint pipeline adds the texture on top of the item, so instead of touching
 * shaders this swaps the two glint textures for copies recoloured from the vanilla brightness map, and re-uploads
 * them whenever the colour changes (which is every frame for rainbow colours).
 */
public class Glint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Glint colour. Enable rainbow in the colour picker for a cycling glint.")
        .defaultValue(new SettingColor(255, 110, 40))
        .build()
    );

    private final Setting<Double> brightness = sgGeneral.add(new DoubleSetting.Builder()
        .name("brightness")
        .description("How strong the glint is.")
        .defaultValue(1.5)
        .range(0.1, 4.0)
        .sliderRange(0.1, 4.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("How fast the glint scrolls, on top of the accessibility glint speed.")
        .defaultValue(1.0)
        .range(0.0, 6.0)
        .sliderRange(0.0, 6.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the glint pattern. Lower values give finer, denser streaks.")
        .defaultValue(1.0)
        .range(0.2, 4.0)
        .sliderRange(0.2, 4.0)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> items = sgGeneral.add(new BoolSetting.Builder()
        .name("items")
        .description("Recolour the glint on items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> armor = sgGeneral.add(new BoolSetting.Builder()
        .name("armor")
        .description("Recolour the glint on worn armour and elytra.")
        .defaultValue(true)
        .build()
    );

    private final Tinted itemGlint = new Tinted(ItemRenderer.ITEM_ENCHANTMENT_GLINT);
    private final Tinted armorGlint = new Tinted(ItemRenderer.ENTITY_ENCHANTMENT_GLINT);

    public Glint() {
        super(Categories.Render, "glint", "Custom enchantment glint colour, speed and size.");
    }

    /** Texture to bind instead of the vanilla glint, or null to keep vanilla. Render thread only. */
    public AbstractTexture override(Identifier id) {
        if (!isActive()) return null;
        if (items.get() && itemGlint.id.equals(id)) return itemGlint.get();
        if (armor.get() && armorGlint.id.equals(id)) return armorGlint.get();
        return null;
    }

    public float speed() {
        return speed.get().floatValue();
    }

    public float scale() {
        return scale.get().floatValue();
    }

    private final class Tinted {
        final Identifier id;
        NativeImage base;
        NativeImageBackedTexture texture;
        int appliedColor;
        double appliedBrightness = -1;
        boolean failed;

        Tinted(Identifier id) {
            this.id = id;
        }

        AbstractTexture get() {
            if (failed) return null;

            if (texture == null) {
                base = load(id);
                if (base == null) {
                    failed = true;
                    return null;
                }

                NativeImage copy = new NativeImage(base.getWidth(), base.getHeight(), false);
                copy.copyFrom(base);
                // The vanilla glint is blurred (linear) and tiles while it scrolls.
                texture = new NativeImageBackedTexture(() -> "Ember glint " + id.getPath(), copy) {{
                    sampler = RenderSystem.getSamplerCache().getRepeated(FilterMode.LINEAR);
                }};
                appliedBrightness = -1;
            }

            int packed = color.get().getPacked();
            if (packed != appliedColor || brightness.get() != appliedBrightness) {
                recolor(packed);
                appliedColor = packed;
                appliedBrightness = brightness.get();
            }

            return texture;
        }

        void recolor(int packed) {
            NativeImage out = texture.getImage();
            if (out == null) return;

            SettingColor c = color.get();
            float bright = brightness.get().floatValue();

            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) {
                    int argb = base.getColorArgb(x, y);
                    int a = argb >>> 24;
                    // The vanilla glint is a purple brightness map; its strongest channel is the streak intensity.
                    float level = Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF)) / 255f * bright;

                    int r = Math.min(255, Math.round(c.r * level));
                    int g = Math.min(255, Math.round(c.g * level));
                    int b = Math.min(255, Math.round(c.b * level));
                    out.setColorArgb(x, y, a << 24 | r << 16 | g << 8 | b);
                }
            }

            texture.upload();
        }
    }

    private NativeImage load(Identifier id) {
        try {
            Optional<Resource> resource = mc.getResourceManager().getResource(id);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().getInputStream()) {
                return NativeImage.read(in);
            }
        } catch (Exception e) {
            MeteorClient.LOG.warn("Could not load glint texture {}", id, e);
            return null;
        }
    }
}
