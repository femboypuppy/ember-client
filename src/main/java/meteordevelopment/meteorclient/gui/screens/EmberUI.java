package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

/**
 * The one place Ember's flat interface style is defined - near neutral black panels, soft
 * drop shadows, and colour reserved for controls and the active row.
 *
 * Deliberately no accent halo around panels. A glowing outline reads as heavy next to the
 * flat surfaces this style is built from, so depth comes from a shadow underneath instead.
 */
public final class EmberUI {
    /** Panel body. Matches the HUD strips so the menu and the widgets read as one client. */
    public static final Color BG = new Color(8, 8, 11, 244);
    /** Header strip and controls that sit on top of the body. */
    public static final Color RAISED = new Color(20, 20, 25, 255);
    /** Input wells and the off state of a switch. */
    public static final Color WELL = new Color(31, 31, 38, 255);
    public static final Color DIVIDER = new Color(38, 38, 46, 255);

    public static final Color TEXT = new Color(255, 255, 255, 255);
    public static final Color TEXT_DIM = new Color(142, 142, 154, 255);
    public static final Color TEXT_FAINT = new Color(96, 96, 108, 255);

    public static final double RADIUS = 10;
    public static final double ROW_RADIUS = 7;

    private EmberUI() {
    }

    public static Color accent() {
        return EmberPalette.accent();
    }

    public static Color accent(int alpha) {
        Color a = EmberPalette.accent();
        return new Color(a.r, a.g, a.b, Math.max(0, Math.min(255, alpha)));
    }

    public static Color alpha(Color c, float fade) {
        return new Color(c.r, c.g, c.b, (int) (c.a * fade));
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.r, c.g, c.b, Math.max(0, Math.min(255, a)));
    }

    /**
     * Deep dark shadow cast below a panel, giving depth without an accent outline. Three
     * passes: a wide soft halo, a mid body, and a tight dark core right under the edge.
     */
    public static void shadow(GuiRenderer r, double x, double y, double w, double h, float fade) {
        r.glow(x, y + 12, w, h, 34, new Color(0, 0, 0, (int) (185 * fade)), false);
        r.glow(x, y + 6, w, h, 18, new Color(0, 0, 0, (int) (160 * fade)), false);
        r.glow(x, y + 2, w, h, 8, new Color(0, 0, 0, (int) (130 * fade)), false);
    }

    /** Subtle lift under the cursor. Flat fill, no edge bar - the image has no such marker. */
    public static void hoverFill(GuiRenderer r, double x, double y, double w, double h, float amount) {
        if (amount <= 0.01f) return;
        r.roundedRect(x, y, w, h, ROW_RADIUS, new Color(255, 255, 255, (int) (14 * amount)));
    }

    /**
     * A pill switch with a knob that slides across as it turns on.
     * {@code amount} is the animated 0..1 state so the travel can be eased.
     */
    public static void toggle(GuiRenderer r, double x, double y, double w, double h, float amount, float fade) {
        Color off = alpha(WELL, fade);
        Color on = accent((int) (255 * fade));

        Color track = new Color(
            (int) (off.r + (on.r - off.r) * amount),
            (int) (off.g + (on.g - off.g) * amount),
            (int) (off.b + (on.b - off.b) * amount),
            (int) (255 * fade));

        r.roundedRect(x, y, w, h, h / 2, track);

        double pad = 2.5;
        double d = h - pad * 2;
        double travel = w - d - pad * 2;
        double kx = x + pad + travel * amount;

        r.quad(kx, y + pad, d, d, GuiRenderer.CIRCLE, new Color(255, 255, 255, (int) (250 * fade)));
    }

    /** A straight bar between two points, built from two triangles so it can be angled. */
    public static void bar(GuiRenderer r, double x1, double y1, double x2, double y2, double thickness, Color c) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001) return;

        // Normal to the line, scaled to half the thickness.
        double nx = -dy / len * thickness / 2;
        double ny = dx / len * thickness / 2;

        r.triangle(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, c);
        r.triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, c);
    }

    /** The tick marking the selected entry in a dropdown. */
    public static void check(GuiRenderer r, double x, double y, double size, Color c) {
        double t = Math.max(1.4, size * 0.16);
        bar(r, x + size * 0.16, y + size * 0.52, x + size * 0.40, y + size * 0.76, t, c);
        bar(r, x + size * 0.38, y + size * 0.76, x + size * 0.84, y + size * 0.24, t, c);
    }

    /** A chevron pointing in one of four directions, drawn from two angled bars. */
    public static void chevron(GuiRenderer r, double cx, double cy, double size, Direction dir, Color c) {
        double h = size / 2;
        double t = Math.max(1.3, size * 0.2);

        switch (dir) {
            case RIGHT -> {
                bar(r, cx - h * 0.5, cy - h, cx + h * 0.5, cy, t, c);
                bar(r, cx + h * 0.5, cy, cx - h * 0.5, cy + h, t, c);
            }
            case LEFT -> {
                bar(r, cx + h * 0.5, cy - h, cx - h * 0.5, cy, t, c);
                bar(r, cx - h * 0.5, cy, cx + h * 0.5, cy + h, t, c);
            }
            case DOWN -> {
                bar(r, cx - h, cy - h * 0.5, cx, cy + h * 0.5, t, c);
                bar(r, cx, cy + h * 0.5, cx + h, cy - h * 0.5, t, c);
            }
            case UP -> {
                bar(r, cx - h, cy + h * 0.5, cx, cy - h * 0.5, t, c);
                bar(r, cx, cy - h * 0.5, cx + h, cy + h * 0.5, t, c);
            }
        }
    }

    /** The close cross on a panel header. */
    public static void cross(GuiRenderer r, double cx, double cy, double size, Color c) {
        double h = size / 2;
        double t = Math.max(1.3, size * 0.16);
        bar(r, cx - h, cy - h, cx + h, cy + h, t, c);
        bar(r, cx + h, cy - h, cx - h, cy + h, t, c);
    }

    public enum Direction { UP, DOWN, LEFT, RIGHT }
}
