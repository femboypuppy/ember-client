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
     * Essentially pure black. The reference bars read as black with colour only in the icons,
     * so the palette's tinted panel colour is deliberately not used here. Frosted thins it
     * right down and lifts it slightly, so the scene reads through the glass.
     */
    public static Color background() {
        // Frosted stays neutral grey. A blue-leaning tint that is invisible at full opacity
        // turns into a clear purple cast once the scene shows through it.
        return EmberAppearance.frosted()
            ? new Color(10, 10, 10, EmberAppearance.panelAlpha())
            : new Color(7, 7, 9, EmberAppearance.panelAlpha());
    }

    /**
     * The glass treatment: a white film over the fill and a lit top edge. Drawn after the
     * background, and only when frosted - on a solid panel it would just look washed out.
     *
     * @param opacity 0..1, so a fading widget's glass fades with it
     */
    public static void gloss(HudRenderer r, double x, double y, double w, double h, double radius, double opacity) {
        if (!EmberAppearance.frosted() || opacity <= 0.01) return;

        r.roundedQuad(x, y, w, h, radius, new Color(255, 255, 255, (int) (16 * opacity)));

        // A single bright line along the top is what actually reads as glass; without it the
        // panel just looks half transparent.
        double inset = Math.min(radius, w / 2);
        r.quad(x + inset, y, w - inset * 2, Math.max(0.6, h * 0.012),
            new Color(255, 255, 255, (int) (46 * opacity)));
    }

    /** Background plus glass, the pairing every Ember panel wants. */
    public static void panel(HudRenderer r, double x, double y, double w, double h, double radius, double opacity) {
        Color bg = background();
        r.roundedQuad(x, y, w, h, radius, new Color(bg.r, bg.g, bg.b, (int) (bg.a * opacity)));
        gloss(r, x, y, w, h, radius, opacity);
    }

    public static double height(HudRenderer r, double s, double textScale) {
        return r.textHeight(true, textScale) + 14 * s;
    }

    public static double width(HudRenderer r, List<Segment> segments, double s, double textScale) {
        if (segments.isEmpty()) return 0;

        double w = 14 * s * 2;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            if (seg.icon() != EmberIcons.Glyph.NONE) w += 11 * s + 6 * s;
            w += r.textWidth(seg.text(), true, textScale);

            if (i < segments.size() - 1) {
                w += 8 * s * 2 + r.textWidth(SEPARATOR, true, textScale);
            }
        }

        return w;
    }

    public static void draw(HudRenderer r, double x, double y, double w, double h, double radius,
                            List<Segment> segments, double s, double textScale) {
        Color bg = background();
        Color bright = new Color(255, 255, 255, 255);
        Color dim = new Color(110, 110, 122, 255);
        Color accent = EmberPalette.accent();

        r.dropShadow(x, y, w, h, radius, 22 * s);
        panel(r, x, y, w, h, radius, 1);

        double iconSize = 11 * s;
        double textH = r.textHeight(true, textScale);
        double ty = y + (h - textH) / 2;
        double cx = x + 14 * s;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            if (seg.icon() != EmberIcons.Glyph.NONE) {
                // Icons carry the accent; the labels stay white, as in the reference.
                Color ic = seg.iconColor() != null ? seg.iconColor() : accent;
                EmberIcons.draw(r, seg.icon(), cx, y + (h - iconSize) / 2, iconSize, ic, bg);
                cx += iconSize + 6 * s;
            }

            r.text(seg.text(), cx, ty, bright, true, textScale);
            cx += r.textWidth(seg.text(), true, textScale);

            if (i < segments.size() - 1) {
                cx += 8 * s;
                r.text(SEPARATOR, cx, ty, dim, true, textScale);
                cx += r.textWidth(SEPARATOR, true, textScale) + 8 * s;
            }
        }
    }
}
