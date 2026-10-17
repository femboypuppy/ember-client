package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember's menu background and loading scene: a dark forge with rising embers, meteor streaks at the
 * angle of the logo's own trails, and the logo floating inside a slowly turning ring of shards.
 *
 * Everything is a function of time, so drawing it twice in a frame (a screen's background and its
 * own render) costs nothing in consistency. Coordinates are framebuffer pixels.
 */
public final class EmberBackdrop {
    private static final Identifier LOGO = MeteorClient.identifier("textures/ember.png");

    private static final Color FORGE_TOP = new Color(11, 9, 12, 255);
    private static final Color FORGE_BOTTOM = new Color(36, 13, 11, 255);
    private static final Color EMBER = new Color(255, 106, 43, 255);
    private static final Color CRIMSON = new Color(196, 38, 33, 255);
    private static final Color MAROON = new Color(110, 22, 32, 255);
    private static final Color ASH = new Color(42, 41, 46, 255);
    private static final Color WHITE = new Color(255, 255, 255, 255);

    /** The logo's trails come in from the upper left: the meteor flies down and to the right. */
    private static final double STREAK_ANGLE = Math.toRadians(32);
    private static final int EMBERS = 78;
    private static final int STREAKS = 3;
    private static final int SHARDS = 6;

    private static final long START = System.nanoTime();

    public enum Layout {
        /** Menus: the forge only, with a faint logo watermark. */
        MENU,
        /** Loading screens: the logo in the middle, the wordmark, a status line and a progress bar. */
        LOADING
    }

    private EmberBackdrop() {
    }

    /** Whether the custom backgrounds are on. Before the config has loaded they are. */
    public static boolean enabled() {
        Config config = Config.get();
        return config == null || config.emberBackgrounds.get();
    }

    private static double time() {
        return (System.nanoTime() - START) / 1e9;
    }

    /**
     * One draw batch. Shapes are queued and drawn when the batch ends, but textures draw at once, so
     * anything that must sit in front of the logo goes in a later batch than the logo itself.
     */
    private static void batch(DrawContext context, java.util.function.Consumer<HudRenderer> draw) {
        HudRenderer r = HudRenderer.INSTANCE;
        Utils.unscaledProjection();
        r.begin(context);
        try {
            draw.accept(r);
        } finally {
            r.end();
            Utils.scaledProjection();
        }
    }

    private static final int BEHIND = 0, LOGO_PASS = 1, IN_FRONT = 2;

    /** Draws the forge, and for loading screens the logo scene. {@code progress} below 0 means unknown. */
    public static void render(DrawContext context, Layout layout, String status, float progress) {
        double w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        double t = time();
        double u = Math.min(w, h) / 1080.0;

        // The emblem: in the middle while loading, otherwise a big quiet watermark down the right.
        boolean loading = layout == Layout.LOADING;
        double size = loading ? 300 * u : 520 * u;
        double cx = loading ? w / 2 : w - size * 0.42, cy = loading ? h * 0.42 : h - size * 0.38;
        float alpha = loading ? 1 : 0.07f;

        batch(context, r -> {
            forge(r, w, h, t, u);
            emblem(r, cx, cy, size, t, alpha, BEHIND);
        });
        batch(context, r -> emblem(r, cx, cy, size, t, alpha, LOGO_PASS));
        batch(context, r -> {
            emblem(r, cx, cy, size, t, alpha, IN_FRONT);
            if (loading) {
                double markY = cy + size * 0.62;
                double markH = wordmark(r, cx, markY, u, 1);
                statusLine(r, cx, markY + markH + 26 * u, status, progress, t, u);
            }
            vignette(r, w, h, u);
        });
    }

    /**
     * The title screen's logo, in place of the Minecraft one: the emblem on the left, the wordmark on
     * the right, centred on the vanilla logo's spot so the buttons and splash text stay where they are.
     *
     * @param guiY top of the vanilla logo in GUI pixels
     */
    public static void renderTitleLogo(DrawContext context, int guiY, float alpha) {
        double scale = mc.getWindow().getScaleFactor();
        double w = mc.getWindow().getFramebufferWidth();
        double u = scale / 2.2;
        double t = time();

        double emblem = 104 * u;
        double markW = wordmarkWidth(HudRenderer.INSTANCE, u);
        double gap = 18 * u;
        double x = (w - (emblem + gap + markW)) / 2;
        double cy = (guiY + 22) * scale;
        double ex = x + emblem / 2;

        batch(context, r -> emblem(r, ex, cy, emblem, t, alpha, BEHIND));
        batch(context, r -> emblem(r, ex, cy, emblem, t, alpha, LOGO_PASS));
        batch(context, r -> {
            emblem(r, ex, cy, emblem, t, alpha, IN_FRONT);
            wordmarkLeft(r, x + emblem + gap, cy, u, alpha);
        });
    }

    // The forge

    private static void forge(HudRenderer r, double w, double h, double t, double u) {
        r.quad(0, 0, w, h, FORGE_TOP, FORGE_TOP, FORGE_BOTTOM, FORGE_BOTTOM);

        // Heat rising off the bottom edge, breathing slowly.
        double breath = 0.5 + 0.5 * Math.sin(t * 0.7);
        r.softGlow(w * 0.15, h * 1.02, w * 0.7, 2, 1, h * (0.45 + 0.05 * breath), fade(CRIMSON, 0.22f + 0.08f * (float) breath));
        r.softGlow(w * 0.35, h * 1.02, w * 0.3, 2, 1, h * 0.3, fade(EMBER, 0.12f));

        for (int i = 0; i < STREAKS; i++) streak(r, w, h, t, u, i);
        for (int i = 0; i < EMBERS; i++) ember(r, w, h, t, u, i);
    }

    /** Deterministic noise in [0, 1) from an index, so each ember keeps its own path every frame. */
    private static double hash(int i, int salt) {
        long x = i * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static void ember(HudRenderer r, double w, double h, double t, double u, int i) {
        double speed = (28 + 70 * hash(i, 1)) * u;
        double life = (h + 80 * u) / speed;
        double phase = ((t + hash(i, 2) * life) % life) / life;

        double sway = Math.sin(t * (0.6 + hash(i, 3)) + i) * 24 * u * (0.4 + phase);
        double x = hash(i, 4) * w + sway + phase * 40 * u * (hash(i, 5) - 0.3);
        double y = h + 20 * u - phase * (h + 80 * u);

        // Embers flare up just after leaving the coals and cool to nothing near the top.
        float heat = (float) (Math.min(1, phase * 6) * (1 - phase) * (1 - phase));
        // Never smaller than a couple of pixels, or small windows lose them entirely.
        double size = (2 + 4.5 * hash(i, 6)) * Math.max(u, 0.8) * (0.6 + 0.4 * heat);
        Color color = mix(CRIMSON, EMBER, (float) hash(i, 7));
        float a = heat * (0.55f + 0.45f * (float) hash(i, 8));
        if (a <= 0.02f) return;

        if (size > 4.5 * u) r.softGlow(x - size, y - size, size * 2, size * 2, size, size * 3, fade(color, a * 0.35f));
        diamond(r, x, y, size, fade(color, a));
    }

    private static void streak(HudRenderer r, double w, double h, double t, double u, int i) {
        double period = 5.5 + 2.5 * hash(i, 11);
        double phase = ((t + hash(i, 12) * period) % period) / period;
        // Each streak is visible for the first fifth of its period, then the sky is quiet again.
        double p = phase / 0.2;
        if (p >= 1) return;

        int cycle = (int) Math.floor((t + hash(i, 12) * period) / period);
        double sx = (hash(i * 31 + cycle, 13) * 1.2 - 0.3) * w;
        double sy = (hash(i * 31 + cycle, 14) * 0.5 - 0.15) * h;
        double travel = Math.hypot(w, h) * 0.55;
        double dx = Math.cos(STREAK_ANGLE), dy = Math.sin(STREAK_ANGLE);
        double hx = sx + dx * travel * p, hy = sy + dy * travel * p;
        double len = (180 + 140 * hash(i, 15)) * u;
        double thick = (1.6 + 1.4 * hash(i, 16)) * u;
        float a = (float) Math.sin(Math.PI * p);

        // A tapered tail in steps: bright and wide at the head, thinning and cooling behind it.
        int steps = 8;
        for (int k = 0; k < steps; k++) {
            double f0 = (double) k / steps, f1 = (double) (k + 1) / steps;
            float heat = (float) Math.pow(1 - f0, 1.6);
            line(r, hx - dx * len * f0, hy - dy * len * f0, hx - dx * len * f1, hy - dy * len * f1,
                thick * 2 * (1 - f0), fade(mix(CRIMSON, EMBER, heat), a * heat));
        }
        r.softGlow(hx - thick * 2, hy - thick * 2, thick * 4, thick * 4, thick * 2, thick * 7, fade(lighten(EMBER), a * 0.55f));
    }

    private static void vignette(HudRenderer r, double w, double h, double u) {
        Color dark = new Color(0, 0, 0, 150), clear = new Color(0, 0, 0, 0);
        double band = Math.min(w, h) * 0.22;
        r.quad(0, 0, w, band, dark, dark, clear, clear);
        r.quad(0, h - band, w, band, clear, clear, dark, dark);
        r.quad(0, 0, band, h, dark, clear, clear, dark);
        r.quad(w - band, 0, band, h, clear, dark, dark, clear);
    }

    // The emblem

    /**
     * The logo floating in its ring of shards, with a heat glow breathing behind it, drawn in three
     * passes: what is behind the rock, the rock, and what passes in front of it.
     */
    private static void emblem(HudRenderer r, double cx, double cy, double size, double t, float alpha, int pass) {
        double breath = 0.5 + 0.5 * Math.sin(t * 1.7);
        cy += Math.sin(t * 1.1) * size * 0.018;
        double ringR = size * 0.66;

        switch (pass) {
            case BEHIND -> {
                // Heat behind the rock, strongest on its cracked side.
                double glow = size * (0.32 + 0.04 * breath);
                r.softGlow(cx - glow * 0.3, cy - glow * 0.2, glow * 0.9, glow * 0.9, glow * 0.45, size * 0.45, fade(EMBER, alpha * (0.26f + 0.1f * (float) breath)));
                r.softGlow(cx - glow * 0.6, cy - glow * 0.6, glow * 1.2, glow * 1.2, glow * 0.6, size * 0.3, fade(CRIMSON, alpha * 0.22f));
                ring(r, cx, cy, ringR, t, size, alpha, false);
                shards(r, cx, cy, ringR, t, size, alpha, false);
            }
            case LOGO_PASS -> {
                double s = size * (1 + 0.012 * breath);
                r.texture(LOGO, cx - s / 2, cy - s / 2, s, s, fade(WHITE, alpha));
            }
            default -> {
                shards(r, cx, cy, ringR, t, size, alpha, true);
                ring(r, cx, cy, ringR, t, size, alpha, true);
            }
        }
    }

    /**
     * A segmented ring, tilted into an ellipse so it reads as a hoop around the rock: the far half
     * is drawn before the logo, the near half after it.
     */
    private static void ring(HudRenderer r, double cx, double cy, double radius, double t, double size, float alpha, boolean front) {
        int segments = 54;
        double spin = t * 0.22;
        double tilt = 0.34;
        double thick = Math.max(1, size * 0.006);
        for (int i = 0; i < segments; i++) {
            // Every fourth segment is a gap, so the ring looks forged in pieces.
            if (i % 4 == 3) continue;
            double a0 = spin + i * Math.PI * 2 / segments, a1 = a0 + Math.PI * 2 / segments * 0.82;
            boolean near = Math.sin((a0 + a1) / 2) > 0;
            if (near != front) continue;

            double x0 = cx + Math.cos(a0) * radius, y0 = cy + Math.sin(a0) * radius * tilt;
            double x1 = cx + Math.cos(a1) * radius, y1 = cy + Math.sin(a1) * radius * tilt;
            float depth = (float) (0.55 + 0.45 * Math.sin((a0 + a1) / 2));
            Color c = mix(MAROON, EMBER, depth);
            line(r, x0, y0, x1, y1, thick, fade(c, alpha * (0.35f + 0.5f * depth)));
        }
    }

    /** Diamond shards like the logo's sparks, orbiting on the ring's tilted path. */
    private static void shards(HudRenderer r, double cx, double cy, double radius, double t, double size, float alpha, boolean front) {
        for (int i = 0; i < SHARDS; i++) {
            double angle = t * (0.35 + 0.08 * i) + i * Math.PI * 2 / SHARDS;
            double depth = Math.sin(angle);
            if ((depth > 0) != front) continue;

            double orbit = radius * (0.92 + 0.12 * hash(i, 21));
            double x = cx + Math.cos(angle) * orbit, y = cy + Math.sin(angle) * orbit * 0.34 - size * 0.04 * Math.sin(t + i);
            double s = size * (0.016 + 0.012 * hash(i, 22)) * (0.75 + 0.25 * depth);
            Color c = i % 3 == 0 ? ASH : (i % 3 == 1 ? EMBER : CRIMSON);
            float a = alpha * (float) (0.45 + 0.55 * (0.5 + 0.5 * depth));
            if (c != ASH) r.softGlow(x - s, y - s, s * 2, s * 2, s, s * 2.5, fade(c, a * 0.4f));
            diamond(r, x, y, s, fade(c, a));
        }
    }

    // Text

    private static final String MARK = "EMBER";
    private static final String SUB = "CLIENT";

    private static double tracked(HudRenderer r, String text, double scale, double tracking) {
        double width = 0;
        for (int i = 0; i < text.length(); i++) width += r.textWidth(String.valueOf(text.charAt(i)), scale) + (i < text.length() - 1 ? tracking : 0);
        return width;
    }

    private static void drawTracked(HudRenderer r, String text, double x, double y, double scale, double tracking, Color color) {
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            r.text(c, x, y, color, false, scale);
            x += r.textWidth(c, scale) + tracking;
        }
    }

    /** @return the wordmark's height, so what goes under it never overlaps */
    private static double wordmark(HudRenderer r, double cx, double y, double u, float alpha) {
        double big = 3.2 * u, small = 1.25 * u;
        double bigW = tracked(r, MARK, big, 14 * u), smallW = tracked(r, SUB, small, 18 * u);
        double bigH = r.textHeight(false, big), smallH = r.textHeight(false, small);
        r.softGlow(cx - bigW / 2, y + bigH * 0.25, bigW, bigH * 0.5, bigH * 0.25, bigH * 0.9, fade(EMBER, alpha * 0.16f));
        drawTracked(r, MARK, cx - bigW / 2, y, big, 14 * u, fade(WHITE, alpha));
        drawTracked(r, SUB, cx - smallW / 2, y + bigH + 6 * u, small, 18 * u, fade(EMBER, alpha * 0.95f));
        return bigH + 6 * u + smallH;
    }

    private static double wordmarkWidth(HudRenderer r, double u) {
        return Math.max(tracked(r, MARK, 2.6 * u, 10 * u), tracked(r, SUB, 1.05 * u, 14 * u));
    }

    private static void wordmarkLeft(HudRenderer r, double x, double cy, double u, float alpha) {
        double big = 2.6 * u, small = 1.05 * u;
        double bigH = r.textHeight(false, big), smallH = r.textHeight(false, small);
        double top = cy - (bigH + 4 * u + smallH) / 2;
        drawTracked(r, MARK, x, top, big, 10 * u, fade(WHITE, alpha));
        drawTracked(r, SUB, x + 2 * u, top + bigH + 4 * u, small, 14 * u, fade(EMBER, alpha));
    }

    private static void statusLine(HudRenderer r, double cx, double y, String status, float progress, double t, double u) {
        double scale = 1.05 * u;
        if (status != null && !status.isEmpty()) {
            double tw = r.textWidth(status, scale);
            r.text(status, cx - tw / 2, y, fade(WHITE, 0.82f), false, scale);
        }

        double barW = 300 * u, barH = 4 * u, bx = cx - barW / 2, by = y + r.textHeight(false, scale) + 16 * u;
        r.roundedQuad(bx, by, barW, barH, barH / 2, new Color(255, 255, 255, 26));
        if (progress >= 0) {
            double fw = barW * MathHelper.clamp(progress, 0, 1);
            if (fw >= barH) {
                r.roundedQuad(bx, by, fw, barH, barH / 2, EMBER);
                r.softGlow(bx + fw - barH, by, barH, barH, barH / 2, 10 * u, fade(EMBER, 0.5f));
            }
        } else {
            // Unknown progress: a glowing ember slides along the track and back.
            double p = 0.5 - 0.5 * Math.cos(t * 2.2);
            double seg = barW * 0.28;
            double sx = bx + (barW - seg) * p;
            r.roundedQuad(sx, by, seg, barH, barH / 2, EMBER);
            r.softGlow(sx, by, seg, barH, barH / 2, 10 * u, fade(EMBER, 0.45f));
        }
    }

    // Shapes

    private static void diamond(HudRenderer r, double x, double y, double s, Color c) {
        r.triangle(x, y - s, x + s * 0.72, y, x, y + s, c);
        r.triangle(x, y - s, x, y + s, x - s * 0.72, y, c);
    }

    /** A line with thickness, as two triangles. */
    private static void line(HudRenderer r, double x0, double y0, double x1, double y1, double thick, Color c) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 0.01) return;
        double nx = -dy / len * thick / 2, ny = dx / len * thick / 2;
        r.triangle(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, c);
        r.triangle(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, c);
    }

    private static Color mix(Color a, Color b, float f) {
        f = MathHelper.clamp(f, 0, 1);
        return new Color((int) (a.r + (b.r - a.r) * f), (int) (a.g + (b.g - a.g) * f), (int) (a.b + (b.b - a.b) * f), (int) (a.a + (b.a - a.a) * f));
    }

    private static Color lighten(Color c) {
        return new Color((c.r + 255) / 2, (c.g + 255) / 2, (c.b + 255) / 2, c.a);
    }

    private static Color fade(Color c, float a) {
        return new Color(c.r, c.g, c.b, MathHelper.clamp((int) (c.a * a), 0, 255));
    }
}
