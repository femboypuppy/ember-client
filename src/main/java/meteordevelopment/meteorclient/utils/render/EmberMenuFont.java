package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontInfo;

import java.io.IOException;

/** A menu font independent of the HUD font and scale. Never redistributes system fonts. */
public final class EmberMenuFont {
    private static CustomTextRenderer renderer;
    private static FontFace activeFace;

    private EmberMenuFont() {}

    public static FontFace preferredFace() {
        for (String name : new String[] {"SF Pro Display", "SF Pro"}) {
            var family = Fonts.getFamily(name);
            if (family != null) {
                var regular = family.get(FontInfo.Type.Regular);
                if (regular != null) return regular;
            }
        }
        return Fonts.DEFAULT_FONT;
    }

    public static CustomTextRenderer renderer() {
        FontFace face = preferredFace();
        if (renderer != null && face == activeFace) return renderer;
        CustomTextRenderer next;
        try {
            next = new CustomTextRenderer(face);
        } catch (IOException e) {
            // Keep startup usable if a locally installed font disappears or becomes unreadable.
            // Cache this fallback too, rather than retrying the unreadable file every frame.
            try {
                next = new CustomTextRenderer(Fonts.DEFAULT_FONT);
            } catch (IOException fallback) {
                throw new IllegalStateException("Unable to load the bundled menu font", fallback);
            }
        }
        if (renderer != null) renderer.destroy();
        activeFace = face;
        return renderer = next;
    }
}
