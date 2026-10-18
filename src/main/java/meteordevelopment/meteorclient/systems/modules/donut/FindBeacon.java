package meteordevelopment.meteorclient.systems.modules.donut;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Ember's find marker, shared by the sus chunk finders: a thin glowing beam rising from where the suspicious
 * blocks are, with sparks drifting up it, colored by how hot the find is (grey maybe, orange likely, white-hot
 * strong, dark red raided). A notch marks the height to dig to. Looking at one shows two short lines under the
 * crosshair instead of a card, so the screen stays clear.
 */
final class FindBeacon {
    static final Color RAIDED = new Color(170, 44, 36, 255);
    /** Gauge colors: one per reading, so each number is told apart at a glance. */
    static final Color BASE = new Color(255, 138, 50, 255), FALSE_POS = new Color(176, 186, 204, 255),
        GRIEFED = new Color(236, 74, 62, 255), GROWN = new Color(110, 200, 130, 255);

    private static final Color COLD = new Color(150, 142, 136, 255);
    private static final Color EMBER = new Color(255, 106, 43, 255);
    private static final Color HOT = new Color(255, 172, 72, 255);
    private static final Color WHITE_HOT = new Color(255, 240, 214, 255);
    private static final int SPARKS = 7;

    private FindBeacon() {
    }

    /** Grey through ember orange to white-hot, for a 0-100 score. */
    static Color heat(int percent) {
        double p = MathHelper.clamp(percent, 0, 100);
        if (p < 45) return mix(COLD, EMBER, p / 45);
        if (p < 70) return mix(EMBER, HOT, (p - 45) / 25);
        return mix(HOT, WHITE_HOT, (p - 70) / 30);
    }

    /** Top of the beam: well above the find, and above your eyes so it shows from wherever you are. */
    static double top(double findY, Vec3d cam) {
        return Math.min(findY + 320, Math.max(findY + 24, cam.y + 10));
    }

    /**
     * @param flicker for raided finds: the beam gutters like a dying fire
     * @param seed keeps each beam's sparks out of step with the others
     */
    static void draw(Renderer3D r, double x, double findY, double z, Vec3d cam, Color color, float hover, boolean flicker, int seed) {
        draw(r, x, findY, z, cam, color, hover, flicker, seed, 1);
    }

    /** @param strength 0 to 1: weak finds draw thinner and fainter so the strong ones stand out */
    static void draw(Renderer3D r, double x, double findY, double z, Vec3d cam, Color color, float hover, boolean flicker, int seed, double strength) {
        double dx = x - cam.x, dy = findY - cam.y, dz = z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Thin up close, thicker far away, so it reads at any distance without filling the view.
        double weak = MathHelper.clamp(strength, 0, 1) * (1 - hover) + hover;
        double w = MathHelper.clamp(0.025 + dist * 0.0012, 0.025, 0.5) * (1 + 0.6 * hover) * (0.6 + 0.4 * weak);
        double top = top(findY, cam);
        double time = System.nanoTime() / 1e9;

        double life = 0.45 + 0.55 * weak;
        if (flicker) life *= 0.55 + 0.45 * Math.abs(Math.sin(time * 7.3 + seed) * Math.sin(time * 2.1 + seed * 0.7));

        // The beam, brightest at the find and fading upward.
        double span = top - findY;
        int[] alphas = {(int) ((150 + 70 * hover) * life), (int) ((95 + 50 * hover) * life), (int) (45 * life)};
        for (int i = 0; i < 3; i++) {
            double y0 = findY + span * i / 3, y1 = findY + span * (i + 1) / 3;
            r.box(x - w, y0, z - w, x + w, y1, z + w, alpha(color, alphas[i]), alpha(color, 0), ShapeMode.Sides, 0);
        }

        // The notch: a small diamond at the height to dig to.
        double n = w * (5 + 2.5 * hover) + 0.15;
        Color ring = alpha(color, (int) ((170 + 80 * hover) * life));
        double[][] pts = {{x + n, z}, {x, z + n}, {x - n, z}, {x, z - n}};
        for (int i = 0; i < 4; i++) {
            double[] a = pts[i], b = pts[(i + 1) % 4];
            r.line(a[0], findY, a[1], b[0], findY, b[1], ring);
        }
        r.line(x, findY - n, z, x, findY + n, z, ring);

        // Sparks drifting up from the find and burning out on the way.
        double rise = Math.min(span, 40);
        for (int i = 0; i < SPARKS; i++) {
            double phase = fract(time * 0.22 + hash(seed, i));
            double y = findY + phase * rise;
            double sway = Math.sin(time * 1.7 + i * 2.3 + seed) * w * 3;
            double s = w * 1.5 * (1 - phase * 0.5);
            int a = (int) (230 * Math.pow(1 - phase, 1.4) * life);
            if (a <= 4) continue;
            double sx = x + sway, sz = z + Math.cos(time * 1.3 + i * 1.9 + seed) * w * 3;
            r.box(sx - s, y - s, sz - s, sx + s, y + s, sz + s, alpha(i % 3 == 0 ? WHITE_HOT : color, a), alpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    /** Degrees between the view direction and the closest point of a beam. */
    static double angleTo(Vec3d cam, Vec3d look, double x, double findY, double z) {
        double dx = x - cam.x, dz = z - cam.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        // Where the view ray is when it is as far out as the beam, held to the beam's length.
        double y = lookHorizontal < 1e-4 ? findY : cam.y + look.y * horizontal / lookHorizontal;
        y = MathHelper.clamp(y, findY, top(findY, cam));
        double dy = y - cam.y;
        double dist = Math.sqrt(horizontal * horizontal + dy * dy);
        if (dist < 0.5) return 0;
        return Math.toDegrees(Math.acos(MathHelper.clamp((look.x * dx + look.y * dy + look.z * dz) / dist, -1, 1)));
    }

    /** "dig down 58", "dig up 12" or "at your height", from your feet to the find. */
    static String dig(double findY) {
        int dy = (int) Math.round(mc.player.getY() - findY);
        if (Math.abs(dy) <= 2) return "at your height";
        return dy > 0 ? "dig down " + dy : "go up " + -dy;
    }

    /**
     * Two short lines under the crosshair: the verdict in the beam's color, then the main reason.
     *
     * @param a fade, 0 to 1
     */
    static void info(HudRenderer r, Color color, String verdict, String headline, String reason, float a, double scale) {
        if (a <= 0.01f) return;
        double s = scale * 1.3;
        double big = 0.92 * s, small = 0.8 * s;
        double bigH = r.textHeight(true, big), smallH = r.textHeight(false, small);
        String rest = "  ·  " + headline;
        double line1 = r.textWidth(verdict, true, big) + r.textWidth(rest, true, big);
        double line2 = reason == null ? 0 : r.textWidth(reason, false, small);
        double pad = 8 * s, gap = 4 * s;
        double w = Math.max(line1, line2) + pad * 2 + 4 * s;
        double h = pad * 2 + bigH + (reason == null ? 0 : gap + smallH);

        int fbW = mc.getWindow().getFramebufferWidth(), fbH = mc.getWindow().getFramebufferHeight();
        double rise = (1 - a) * 6 * s;
        // Below the gauges around the crosshair.
        double x = (fbW - w) / 2, y = fbH / 2.0 + (RING + 14) * s + rise;

        r.roundedQuad(x, y, w, h, 6 * s, new Color(10, 10, 12, (int) (165 * a)));
        r.roundedQuad(x + 3 * s, y + 5 * s, 2.5 * s, h - 10 * s, 1.25 * s, alpha(color, (int) (255 * a)));

        Color bright = EmberPalette.textBright();
        double tx = x + pad + 4 * s;
        r.text(verdict, tx, y + pad, alpha(color, (int) (255 * a)), true, big);
        r.text(rest, tx + r.textWidth(verdict, true, big), y + pad, alpha(bright, (int) (bright.a * a)), true, big);
        if (reason != null) {
            r.text(reason, tx, y + pad + bigH + gap, alpha(bright, (int) (bright.a * 0.85f * a)), false, small);
        }
    }

    // Both sus chunk finders draw beacons; only the one looked at most directly may show its info, or the two
    // readouts would land on top of each other.
    private static Object owner;
    private static double ownerAngle;
    private static long ownerAt;

    /** Render thread, each frame: the beacon this module is looked at, by how many degrees. */
    static void offer(Object module, double angle) {
        long now = System.nanoTime();
        if (owner == module || owner == null || now - ownerAt > 100_000_000L || angle < ownerAngle) {
            owner = module;
            ownerAngle = angle;
            ownerAt = now;
        }
    }

    /** Render thread, each frame: this module isn't looked at. */
    static void withdraw(Object module) {
        if (owner == module) owner = null;
    }

    static boolean shows(Object module) {
        return owner == module;
    }

    /** One of the three readings around the crosshair. */
    record Gauge(String label, int percent, Color color) {
    }

    private static final double RING = 30, GAUGE_SPAN = 64;
    /** Left, right, top: where each gauge sits around the crosshair, in screen degrees (0 = right, y down). */
    private static final double[] GAUGE_AT = {180, 0, 270};
    private static final int GAUGE_DOTS = 13;

    /**
     * Three dotted arcs around the crosshair, each filled to its percentage, with the number beside it in big text,
     * so the scores read at a glance: left, right, then top.
     */
    static void gauges(HudRenderer r, Gauge[] gauges, float a, double scale) {
        if (a <= 0.01f) return;
        double s = scale * 1.3;
        int fbW = mc.getWindow().getFramebufferWidth(), fbH = mc.getWindow().getFramebufferHeight();
        double cx = fbW / 2.0, cy = fbH / 2.0;
        double ring = RING * s * (0.85 + 0.15 * a);
        double dot = 3.4 * s;
        double big = 1.0 * s, small = 0.62 * s;
        double bigH = r.textHeight(true, big), smallH = r.textHeight(false, small);
        Color bright = EmberPalette.textBright();

        for (int g = 0; g < gauges.length && g < GAUGE_AT.length; g++) {
            Gauge gauge = gauges[g];
            double filled = MathHelper.clamp(gauge.percent, 0, 100) / 100.0 * GAUGE_DOTS * a;
            for (int i = 0; i < GAUGE_DOTS; i++) {
                double t = GAUGE_DOTS == 1 ? 0.5 : i / (GAUGE_DOTS - 1.0);
                // Left and right fill from the bottom up, the top one from left to right.
                double deg = g == 2 ? GAUGE_AT[g] - GAUGE_SPAN / 2 + t * GAUGE_SPAN
                    : g == 0 ? GAUGE_AT[g] - GAUGE_SPAN / 2 + (1 - t) * GAUGE_SPAN : GAUGE_AT[g] + GAUGE_SPAN / 2 - (1 - t) * GAUGE_SPAN;
                double rad = Math.toRadians(deg);
                double x = cx + Math.cos(rad) * ring, y = cy + Math.sin(rad) * ring;
                double lit = MathHelper.clamp(filled - i, 0, 1);
                Color c = lit > 0 ? alpha(gauge.color, (int) ((90 + 165 * lit) * a)) : new Color(255, 255, 255, (int) (40 * a));
                r.roundedQuad(x - dot / 2, y - dot / 2, dot, dot, dot / 2, c);
            }

            String number = gauge.percent + "%";
            double nw = r.textWidth(number, true, big), lw = r.textWidth(gauge.label, false, small);
            double blockW = Math.max(nw, lw), blockH = bigH + smallH;
            double bx, by;
            if (g == 0) {
                bx = cx - ring - 9 * s - blockW;
                by = cy - blockH / 2;
            } else if (g == 1) {
                bx = cx + ring + 9 * s;
                by = cy - blockH / 2;
            } else {
                bx = cx - blockW / 2;
                by = cy - ring - 8 * s - blockH;
            }
            double nx = g == 0 ? bx + blockW - nw : g == 1 ? bx : bx + (blockW - nw) / 2;
            double lx = g == 0 ? bx + blockW - lw : g == 1 ? bx : bx + (blockW - lw) / 2;
            r.text(number, nx, by, alpha(gauge.color, (int) (255 * a)), true, big);
            r.text(gauge.label, lx, by + bigH, alpha(bright, (int) (bright.a * 0.8f * a)), false, small);
        }
    }

    static Color alpha(Color c, int a) {
        return new Color(c.r, c.g, c.b, MathHelper.clamp(a, 0, 255));
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.r + (b.r - a.r) * t), (int) (a.g + (b.g - a.g) * t), (int) (a.b + (b.b - a.b) * t), 255);
    }

    private static double fract(double v) {
        return v - Math.floor(v);
    }

    private static double hash(int seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) / (double) (1L << 53);
    }
}
