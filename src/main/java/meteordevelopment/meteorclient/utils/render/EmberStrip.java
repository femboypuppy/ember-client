package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.List;

/**
 * A horizontal pill of icon-and-label segments separated by a dim middle dot - the shape the
 * Ember V2 status bar and the info pill are both built from.
 *
 * Measuring and drawing share one layout pass so the element reports a box to the HUD editor
 * that matches what is actually painted.
 */
public final class EmberStrip {
    /** One entry: a glyph, its label, and the colour the glyph is tinted. */
    public record Segment(EmberIcons.Glyph icon, String text, Color iconColor) {
        public Segment(EmberIcons.Glyph icon, String text) {
            this(icon, text, null);
        }
    }

    private static final String SEPARATOR = "·";

    private EmberStrip() {
    }

    /**
     * Near neutral black, faintly tinted by the accent. The palette's own panel colour is too
     * saturated for this layout, which reads as black with colour only in the details.
     */
    public static Color background() {
        Color p = EmberPalette.panel();
        return new Color((int) (p.r * 0.45), (int) (p.g * 0.45), (int) (p.b * 0.45), 232);
    }

    public static double height(HudRenderer r, double s, double textScale) {
        return r.textHeight(true, textScale) + 11 * s;
    }

    public static double width(HudRenderer r, List<Segment> segments, double s, double textScale) {
        if (segments.isEmpty()) return 0;

        double w = 11 * s * 2;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            if (seg.icon() != EmberIcons.Glyph.NONE) w += 9 * s + 5 * s;
            w += r.textWidth(seg.text(), true, textScale);

            if (i < segments.size() - 1) {
                w += 7 * s * 2 + r.textWidth(SEPARATOR, true, textScale);
            }
        }

        return w;
    }

    public static void draw(HudRenderer r, double x, double y, double w, double h, double radius,
                            List<Segment> segments, double s, double textScale) {
        Color bg = background();
        Color bright = EmberPalette.textBright();
        Color dim = EmberPalette.textDim();

        r.roundedQuad(x, y, w, h, radius, bg);

        double iconSize = 9 * s;
        double textH = r.textHeight(true, textScale);
        double ty = y + (h - textH) / 2;
        double cx = x + 11 * s;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            if (seg.icon() != EmberIcons.Glyph.NONE) {
                Color ic = seg.iconColor() != null ? seg.iconColor() : dim;
                EmberIcons.draw(r, seg.icon(), cx, y + (h - iconSize) / 2, iconSize, ic, bg);
                cx += iconSize + 5 * s;
            }

            r.text(seg.text(), cx, ty, bright, true, textScale);
            cx += r.textWidth(seg.text(), true, textScale);

            if (i < segments.size() - 1) {
                cx += 7 * s;
                r.text(SEPARATOR, cx, ty, dim, true, textScale);
                cx += r.textWidth(SEPARATOR, true, textScale) + 7 * s;
            }
        }
    }
}
