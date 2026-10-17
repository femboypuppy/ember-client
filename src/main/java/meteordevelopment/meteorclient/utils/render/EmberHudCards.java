package meteordevelopment.meteorclient.utils.render;

import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;

import java.util.ArrayList;
import java.util.List;

/** Shared glass, spacing and bounded typography for Ember's alert cards. */
public final class EmberHudCards {
    private EmberHudCards() {}

    public static Color alpha(Color color, double opacity) {
        return new Color(color.r, color.g, color.b, (int) Math.round(255 * Math.clamp(opacity, 0, 1)));
    }

    public static Color ink(Color color, double opacity) {
        return new Color((color.r * 3 + 255 * 2) / 5, (color.g * 3 + 255 * 2) / 5,
            (color.b * 3 + 255 * 2) / 5, (int) Math.round(255 * Math.clamp(opacity, 0, 1)));
    }

    public static void surface(HudRenderer r, double x, double y, double w, double h, double s, double opacity, boolean glow) {
        if (w <= 0 || h <= 0 || opacity <= 0.01) return;
        Color accent = EmberPalette.accent();
        double radius = 12 * s;
        if (glow) r.softGlow(x, y, w, h, radius, 9 * s, alpha(accent, 0.16 * opacity));
        r.dropShadow(x, y, w, h, radius, 18 * s, (float) (opacity * 0.85));
        EmberStrip.panel(r, x, y, w, h, radius, opacity);
        // A short reflected edge carries the accent without enclosing the card in neon.
        double edge = Math.min(w - radius * 2, 42 * s);
        if (edge > 0) r.quad(x + radius, y + 0.5 * s, edge, Math.max(0.6, s * 0.65),
            alpha(accent, opacity * 0.44), alpha(accent, 0), alpha(accent, 0), alpha(accent, opacity * 0.44));
    }

    public static void icon(HudRenderer r, EmberIcons.Glyph glyph, double x, double y, double size, Color color, double opacity) {
        r.roundedQuad(x, y, size, size, size * 0.32, alpha(color, 0.12 * opacity));
        double inset = size * 0.25;
        EmberIcons.draw(r, glyph, x + inset, y + inset, size - inset * 2, ink(color, opacity), EmberStrip.background());
    }

    public static double badgeWidth(HudRenderer r, String text, double scale, double s) {
        return r.textWidth(text, false, scale) + 12 * s;
    }

    public static void badge(HudRenderer r, String text, double x, double y, double scale, double s, Color color, double opacity) {
        double h = r.textHeight(false, scale) + 6 * s, w = badgeWidth(r, text, scale, s);
        r.roundedQuad(x, y, w, h, h / 2, alpha(color, 0.12 * opacity));
        r.text(text, x + 6 * s, y + 3 * s, ink(color, opacity), false, scale);
    }

    /** Ellipsize by code point, so CJK and supplementary characters stay intact. */
    public static String fit(HudRenderer r, String text, double scale, double width) {
        if (text == null || text.isEmpty() || width <= 0) return "";
        if (r.textWidth(text, false, scale) <= width) return text;
        String dots = "\u2026";
        if (r.textWidth(dots, false, scale) > width) return "";
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String candidate = text.substring(0, text.offsetByCodePoints(0, mid)).stripTrailing() + dots;
            if (r.textWidth(candidate, false, scale) <= width) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)).stripTrailing() + dots;
    }

    public static List<String> wrap(HudRenderer r, String text, double scale, double width) {
        List<String> lines = new ArrayList<>(2);
        if (text == null || text.isBlank() || width <= 0) return lines;
        String remaining = text.replaceAll("\\s+", " ").trim();
        if (r.textWidth(remaining, false, scale) <= width) return List.of(remaining);
        String prefix = fit(r, remaining, scale, width);
        if (prefix.isEmpty()) return lines;
        int cut = prefix.endsWith("\u2026") ? prefix.length() - 1 : prefix.length();
        int space = remaining.lastIndexOf(' ', cut);
        if (space > cut / 2) cut = space;
        if (cut <= 0) return List.of(prefix);
        lines.add(remaining.substring(0, cut).stripTrailing());
        remaining = remaining.substring(cut).stripLeading();
        if (!remaining.isEmpty()) lines.add(fit(r, remaining, scale, width));
        return lines;
    }
}
