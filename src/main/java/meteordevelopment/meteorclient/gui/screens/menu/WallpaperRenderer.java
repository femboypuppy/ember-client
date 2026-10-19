package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/** Resource-managed offline wallpapers, small prefiltered blur copies, and shared parallax coordinates. */
public final class WallpaperRenderer {
    private static final MenuConfig.Wallpaper[] WALLPAPERS = MenuConfig.Wallpaper.values();
    private static final String[] ART;
    private static final int[] ART_OF = new int[WALLPAPERS.length];
    private static final Identifier[] SHARP, BLUR, THUMB;
    static {
        // Wallpapers that share artwork (Live/Nebula) share one texture set and one blend weight.
        List<String> art = new ArrayList<>();
        for (var wallpaper : WALLPAPERS) {
            if (!art.contains(wallpaper.file)) art.add(wallpaper.file);
            ART_OF[wallpaper.ordinal()] = art.indexOf(wallpaper.file);
        }
        ART = art.toArray(String[]::new);
        SHARP = new Identifier[ART.length]; BLUR = new Identifier[ART.length]; THUMB = new Identifier[ART.length];
        for (int i = 0; i < ART.length; i++) {
            SHARP[i] = Identifier.of("ember", "textures/menu/" + ART[i] + ".png");
            BLUR[i] = Identifier.of("ember", "textures/menu/" + ART[i] + "_blur.png");
            THUMB[i] = Identifier.of("ember", "textures/menu/" + ART[i] + "_thumb.png");
        }
    }
    private static final double ART_W = 2560, ART_H = 1440;
    // Every weight eases toward the selection at the same rate, so they always sum to 1 and rapid
    // selections blend continuously instead of jumping.
    private final double[] weight = new double[ART.length];
    private double live, panX, panY, time, width, height, coverX, coverY, coverW, coverH;

    public WallpaperRenderer() {
        var wallpaper = ConfigManager.get().wallpaper;
        weight[ART_OF[wallpaper.ordinal()]] = 1;
        live = wallpaper.live ? 1 : 0;
    }
    public void update(double w, double h, double mouseX, double mouseY, double dt) {
        var c = ConfigManager.get(); width = w; height = h;
        int selected = ART_OF[c.wallpaper.ordinal()];
        for (int i = 0; i < ART.length; i++) weight[i] = MenuAnimation.smooth(weight[i], i == selected ? 1 : 0, dt * c.animationSpeed, c.motion() ? .16 : .055);
        live = MenuAnimation.smooth(live, c.wallpaper.live ? 1 : 0, dt * c.animationSpeed, .2);
        if (c.motion()) time += dt * c.animationSpeed;
        double tx = c.motion() && c.parallax ? (mouseX / w - .5) * 14 : 0, ty = c.motion() && c.parallax ? (mouseY / h - .5) * 10 : 0;
        if (c.motion()) { panX = MenuAnimation.smooth(panX, tx, dt, .35); panY = MenuAnimation.smooth(panY, ty, dt, .35); } else { panX = panY = 0; }
        double zoom = 1.06 + (c.motion() ? Math.sin(time * .12) * .012 * live : 0);
        double scale = Math.max(w / ART_W, h / ART_H) * zoom; coverW = ART_W * scale; coverH = ART_H * scale;
        coverX = (w - coverW) * .5 + panX; coverY = (h - coverH) * .5 + panY;
        if (c.motion()) { coverX += Math.sin(time * .09) * 7 * live; coverY += Math.cos(time * .07) * 4 * live; }
    }
    public void draw(MenuCanvas c, double backgroundBlur) {
        layers(c, SHARP, 0, 0, width, height, 0, 1);
        if (ConfigManager.get().blur && backgroundBlur > .001) layers(c, BLUR, 0, 0, width, height, 0, backgroundBlur * ConfigManager.get().blurIntensity);
        c.gradient(0, 0, width, height * .45, 0x45000000, 0x00000000);
        c.gradient(0, height * .45, width, height * .55, 0x00000000, 0x99000000);
        // Soft vignette without a framebuffer, custom pipeline or per-frame image allocation.
        for (int i = 0; i < 16; i++) { double strip = width * .018; int color = MenuTheme.alpha(0x14000000, Math.pow(1 - i / 16.0, 2)); c.rect(i * strip, 0, strip, height, color); c.rect(width - (i + 1) * strip, 0, strip, height, color); }
    }
    /**
     * Composites every artwork with a nonzero weight. Layer j is drawn at weight_j / (sum of weights up to j),
     * which reproduces the exact weighted average of opaque images for any number of overlapping fades.
     */
    private void layers(MenuCanvas c, Identifier[] set, double x, double y, double w, double h, double radius, double alpha) {
        double sum = 0;
        for (int i = 0; i < ART.length; i++) {
            if (weight[i] < .001) continue;
            sum += weight[i];
            double layer = weight[i] / sum;
            c.cover(set[i], x, y, w, h, radius, coverX, coverY, coverW, coverH, alpha * (layer > .999 ? 1 : layer));
        }
    }
    /** Blurred wallpaper behind a rounded glass panel, aligned with the full-screen wallpaper. */
    public void sample(MenuCanvas c, double x, double y, double w, double h, double radius, double alpha) {
        if (alpha > .001) layers(c, BLUR, x, y, w, h, radius, alpha);
    }
    public static void thumbnail(MenuCanvas c, MenuConfig.Wallpaper wallpaper, double x, double y, double w, double h, double radius) {
        // Aspect-fill the 16:9 thumbnail into the tile, cropping the overflow evenly.
        double cw = Math.max(w, h * ART_W / ART_H), ch = cw * ART_H / ART_W;
        c.cover(THUMB[ART_OF[wallpaper.ordinal()]], x, y, w, h, radius, x + (w - cw) * .5, y + (h - ch) * .5, cw, ch, 1);
    }
}
