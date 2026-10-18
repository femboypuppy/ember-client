package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Small procedural glyphs for the Ember HUD. The brand mark uses the uploaded artwork,
 * deferred until the HUD's font and color batches finish; other glyphs use primitives.
 *
 * Every glyph is drawn inside a square of side {@code size} with its top left at (x, y).
 * Glyphs that need a hole take the panel colour behind them as {@code bg} and punch the
 * detail out with it, since there is no stencil to cut with.
 */
public final class EmberIcons {
    public enum Glyph {
        /** The uploaded Ember meteor mark. */
        BRAND,
        /** Stacked server racks. */
        SERVER,
        /** Analogue clock face. */
        CLOCK,
        /** Rising signal bars, for latency. */
        PING,
        /** An ascending trend, for framerate. */
        FPS,
        /** A processor die with pins. */
        CPU,
        /** A graphics card. */
        GPU,
        /** A memory stick. */
        RAM,
        /** Head and shoulders. */
        USER,
        /** A crosshaired globe, for coordinates. */
        GLOBE,
        /** Motion lines, for speed. */
        SPEED,
        /** A heartbeat trace, for tick rate. */
        TPS,
        /** A key, for keybinds. */
        KEY,
        /** A checkmark, for enabled modules and success notifications. */
        CHECK,
        /** A cross mark, for disabled modules and errors. */
        CROSS,
        /** An info mark, for general client notifications. */
        INFO,
        /** An exclamation mark, for warnings and alerts. */
        WARNING,
        /** A notification bell. */
        BELL,
        /** A cloud with falling raindrops. */
        RAIN,
        /** Draws nothing and takes no room. */
        NONE
    }

    private EmberIcons() {
    }

    public static void draw(HudRenderer r, Glyph glyph, double x, double y, double size, Color color, Color bg) {
        switch (glyph) {
            case BRAND -> brand(r, x, y, size, color);
            case SERVER -> server(r, x, y, size, color, bg);
            case CLOCK -> clock(r, x, y, size, color, bg);
            case PING -> ping(r, x, y, size, color);
            case FPS -> fps(r, x, y, size, color);
            case CPU -> cpu(r, x, y, size, color, bg);
            case GPU -> gpu(r, x, y, size, color, bg);
            case RAM -> ram(r, x, y, size, color, bg);
            case USER -> user(r, x, y, size, color);
            case GLOBE -> globe(r, x, y, size, color, bg);
            case SPEED -> speed(r, x, y, size, color);
            case TPS -> tps(r, x, y, size, color);
            case KEY -> key(r, x, y, size, color, bg);
            case CHECK -> check(r, x, y, size, color);
            case CROSS -> cross(r, x, y, size, color);
            case INFO -> info(r, x, y, size, color, bg);
            case WARNING -> warning(r, x, y, size, color, bg);
            case BELL -> bell(r, x, y, size, color);
            case RAIN -> rain(r, x, y, size, color);
            case NONE -> {
            }
        }
    }

    /** A filled circle, which the primitives only offer as a fully rounded quad. */
    private static void circle(HudRenderer r, double x, double y, double d, Color color) {
        r.roundedQuad(x, y, d, d, d / 2, color);
    }

    private static void brand(HudRenderer r, double x, double y, double s, Color c) {
        EmberLogo.draw(r, x, y, s, c.a / 255.0);
    }

    private static void server(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        r.roundedQuad(x, y + s * 0.12, s, s * 0.32, s * 0.10, c);
        r.roundedQuad(x, y + s * 0.56, s, s * 0.32, s * 0.10, c);
        // Status lamps punched out of each rack.
        circle(r, x + s * 0.12, y + s * 0.22, s * 0.12, bg);
        circle(r, x + s * 0.12, y + s * 0.66, s * 0.12, bg);
    }

    private static void clock(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        circle(r, x, y, s, c);
        // Hands meeting at the centre: one up, one to the right.
        r.quad(x + s * 0.45, y + s * 0.24, s * 0.11, s * 0.28, bg);
        r.quad(x + s * 0.45, y + s * 0.45, s * 0.30, s * 0.11, bg);
    }

    private static void ping(HudRenderer r, double x, double y, double s, Color c) {
        double bw = s * 0.19, gap = s * 0.08;
        for (int i = 0; i < 4; i++) {
            double bh = s * (0.28 + i * 0.24);
            r.roundedQuad(x + i * (bw + gap), y + s - bh, bw, bh, bw * 0.35, c);
        }
    }

    private static void fps(HudRenderer r, double x, double y, double s, Color c) {
        // Four dots climbing to the right - a trend line, distinct from PING's solid bars.
        double d = s * 0.24;
        for (int i = 0; i < 4; i++) {
            circle(r, x + i * (s - d) / 3, y + s - d - i * (s - d) / 3, d, c);
        }
    }

    private static void cpu(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        r.roundedQuad(x + s * 0.16, y + s * 0.16, s * 0.68, s * 0.68, s * 0.14, c);
        r.roundedQuad(x + s * 0.32, y + s * 0.32, s * 0.36, s * 0.36, s * 0.08, bg);
        r.roundedQuad(x + s * 0.42, y + s * 0.42, s * 0.16, s * 0.16, s * 0.04, c);
        // Pins on all four sides.
        for (int i = 0; i < 2; i++) {
            double o = s * (0.34 + i * 0.24);
            r.quad(o + x - s * 0.02, y, s * 0.10, s * 0.16, c);
            r.quad(o + x - s * 0.02, y + s * 0.84, s * 0.10, s * 0.16, c);
            r.quad(x, y + o - s * 0.02, s * 0.16, s * 0.10, c);
            r.quad(x + s * 0.84, y + o - s * 0.02, s * 0.16, s * 0.10, c);
        }
    }

    private static void gpu(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        // A wide board with two fans punched out of it.
        r.roundedQuad(x, y + s * 0.18, s, s * 0.62, s * 0.12, c);
        circle(r, x + s * 0.10, y + s * 0.30, s * 0.34, bg);
        circle(r, x + s * 0.54, y + s * 0.30, s * 0.34, bg);
        r.quad(x + s * 0.16, y + s * 0.80, s * 0.12, s * 0.16, c);
        r.quad(x + s * 0.68, y + s * 0.80, s * 0.12, s * 0.16, c);
    }

    private static void ram(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        r.roundedQuad(x, y + s * 0.20, s, s * 0.58, s * 0.10, c);
        for (int i = 0; i < 3; i++) {
            r.quad(x + s * (0.20 + i * 0.24), y + s * 0.32, s * 0.11, s * 0.34, bg);
        }
        // Contact legs along the bottom edge.
        r.quad(x + s * 0.16, y + s * 0.78, s * 0.16, s * 0.14, c);
        r.quad(x + s * 0.66, y + s * 0.78, s * 0.16, s * 0.14, c);
    }

    private static void user(HudRenderer r, double x, double y, double s, Color c) {
        circle(r, x + s * 0.28, y + s * 0.04, s * 0.44, c);
        r.roundedQuad(x + s * 0.10, y + s * 0.58, s * 0.80, s * 0.46, s * 0.23, c);
    }

    private static void globe(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        circle(r, x, y, s, c);
        r.quad(x, y + s * 0.44, s, s * 0.12, bg);
        r.quad(x + s * 0.44, y, s * 0.12, s, bg);
    }

    private static void speed(HudRenderer r, double x, double y, double s, Color c) {
        r.roundedQuad(x, y + s * 0.18, s, s * 0.14, s * 0.07, c);
        r.roundedQuad(x + s * 0.26, y + s * 0.44, s * 0.74, s * 0.14, s * 0.07, c);
        r.roundedQuad(x + s * 0.52, y + s * 0.70, s * 0.48, s * 0.14, s * 0.07, c);
    }

    private static void tps(HudRenderer r, double x, double y, double s, Color c) {
        r.quad(x, y + s * 0.46, s * 0.30, s * 0.12, c);
        r.quad(x + s * 0.30, y + s * 0.18, s * 0.13, s * 0.64, c);
        r.quad(x + s * 0.43, y + s * 0.46, s * 0.57, s * 0.12, c);
    }

    private static void key(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        circle(r, x, y + s * 0.18, s * 0.64, c);
        circle(r, x + s * 0.18, y + s * 0.36, s * 0.28, bg);
        r.quad(x + s * 0.56, y + s * 0.42, s * 0.44, s * 0.14, c);
        r.quad(x + s * 0.84, y + s * 0.42, s * 0.13, s * 0.30, c);
    }

    private static void stroke(HudRenderer r, double x1, double y1, double x2, double y2, double thickness, Color c) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dist = Math.hypot(dx, dy);
        int steps = Math.max(2, (int) Math.ceil(dist / Math.max(0.5, thickness * 0.32)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            circle(r, x1 + dx * t - thickness / 2, y1 + dy * t - thickness / 2, thickness, c);
        }
    }

    private static void check(HudRenderer r, double x, double y, double s, Color c) {
        double t = s * 0.17;
        stroke(r, x + s * 0.18, y + s * 0.54, x + s * 0.41, y + s * 0.76, t, c);
        stroke(r, x + s * 0.41, y + s * 0.76, x + s * 0.84, y + s * 0.26, t, c);
    }

    private static void cross(HudRenderer r, double x, double y, double s, Color c) {
        double t = s * 0.17;
        stroke(r, x + s * 0.22, y + s * 0.22, x + s * 0.78, y + s * 0.78, t, c);
        stroke(r, x + s * 0.78, y + s * 0.22, x + s * 0.22, y + s * 0.78, t, c);
    }

    private static void info(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        circle(r, x + s * 0.41, y + s * 0.12, s * 0.18, c);
        r.roundedQuad(x + s * 0.41, y + s * 0.38, s * 0.18, s * 0.50, s * 0.09, c);
    }

    private static void warning(HudRenderer r, double x, double y, double s, Color c, Color bg) {
        r.roundedQuad(x + s * 0.41, y + s * 0.12, s * 0.18, s * 0.50, s * 0.09, c);
        circle(r, x + s * 0.41, y + s * 0.70, s * 0.18, c);
    }

    private static void bell(HudRenderer r, double x, double y, double s, Color c) {
        circle(r, x + s * 0.20, y + s * 0.10, s * 0.60, c);
        r.roundedQuad(x + s * 0.20, y + s * 0.36, s * 0.60, s * 0.36, s * 0.08, c);
        r.roundedQuad(x + s * 0.08, y + s * 0.62, s * 0.84, s * 0.14, s * 0.07, c);
        circle(r, x + s * 0.40, y + s * 0.80, s * 0.20, c);
    }

    private static void rain(HudRenderer r, double x, double y, double s, Color c) {
        circle(r, x + s * .12, y + s * .22, s * .38, c);
        circle(r, x + s * .34, y + s * .08, s * .48, c);
        circle(r, x + s * .63, y + s * .24, s * .29, c);
        r.roundedQuad(x + s * .15, y + s * .36, s * .72, s * .23, s * .1, c);
        for (int i = 0; i < 3; i++) {
            double dx = x + (.29 + i * .22) * s;
            stroke(r, dx, y + s * .69, dx - s * .07, y + s * .89, s * .065, c);
        }
    }
}
